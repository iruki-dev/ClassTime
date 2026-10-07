package dev.iruki.classtime.ai

import android.content.Context
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.RecordingDao
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptError
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.util.AppLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 대기열의 작업 하나를 **한 걸음** 나아가게 한다. 걸음마다 결과를 DB 에 저장하므로
 * 어디서 멈춰도(앱 종료, 네트워크 끊김, 한도) 그다음 걸음부터 이어진다.
 *
 * 걸음: 조각 나누기 → 조각마다 받아 적기 → 부분마다 교정 → 끝.
 */
class TranscriptionEngine(
    private val context: Context,
    private val transcripts: TranscriptDao,
    private val recordings: RecordingDao,
    private val settings: AiSettings,
    private val audio: AudioSource = AudioChunks(context),
    private val groq: SpeechToText = GroqClient(),
    private val nvidia: ChatModel = NvidiaClient(),
    private val ledger: QuotaLedger = QuotaLedger(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome {
        /** 한 걸음 나아갔다(또는 끝났다). 다음 걸음으로. */
        data object Progressed : Outcome
        /** 연결이 없다. 연결되면 다시. */
        data object Offline : Outcome
    }

    suspend fun step(job: Transcript, isStopped: () -> Boolean = { false }): Outcome = withContext(Dispatchers.IO) {
        val recording = recordings.getById(job.recordingId)
        if (recording == null) {
            transcripts.delete(job.recordingId)
            return@withContext Outcome.Progressed
        }
        try {
            when {
                job.plan.isBlank() -> prepare(job, recording, isStopped)
                job.chunksDone < TranscriptJson.plan(job.plan).size -> transcribeNext(job, recording)
                job.stateEnum == TranscriptState.CORRECTING -> correctNext(job, recording)
                else -> afterTranscribing(job)
            }
            Outcome.Progressed
        } catch (e: AiException) {
            handle(job, e)
        } catch (e: IOException) {
            if (isStopped()) return@withContext Outcome.Progressed
            AppLog.w(TAG, "녹음 파일을 읽지 못했습니다 id=${job.recordingId}", e)
            fail(job, TranscriptError.FILE)
            Outcome.Progressed
        }
    }

    // --- 1. 조각 나누기 ---

    private suspend fun prepare(job: Transcript, recording: Recording, isStopped: () -> Boolean) {
        save(job.copy(state = TranscriptState.PREPARING.name))
        val envelope = audio.envelope(recording.uri, isStopped)
        val plan = plannerFor(recording).plan(envelope)
        if (plan.isEmpty()) {
            fail(job, TranscriptError.NO_SPEECH)
            return
        }
        save(
            job.copy(
                state = TranscriptState.TRANSCRIBING.name,
                plan = TranscriptJson.plan(plan),
                chunksDone = 0,
                segments = "",
                speechSeconds = (plan.sumOf { it.last - it.first } / 1000).toInt(),
            )
        )
    }

    /**
     * 조각 최대 길이를 파일의 비트레이트에 맞춘다. 이 앱의 녹음(96kbps)은 13분이면 9MB 남짓이지만,
     * 폴더에서 들여온 고음질 파일은 같은 길이로도 무료 한도(25MB)를 넘을 수 있다.
     */
    private fun plannerFor(recording: Recording): ChunkPlanner {
        val bytesPerMs = if (recording.durationMs > 0 && recording.sizeBytes > 0) {
            recording.sizeBytes.toDouble() / recording.durationMs
        } else 96_000 / 8 / 1000.0
        val fitMs = (MAX_CHUNK_BYTES / bytesPerMs).toLong()
        val maxMs = minOf(13 * 60_000L, fitMs)
        val targetMs = minOf(10 * 60_000L, maxMs * 10 / 13)
        return ChunkPlanner(targetMs = targetMs, maxMs = maxMs, searchMs = targetMs * 3 / 10)
    }

    // --- 2. 받아 적기 ---

    private suspend fun transcribeNext(job: Transcript, recording: Recording) {
        val key = settings.groqKey()
        if (key == null) {
            fail(job, TranscriptError.GROQ_KEY)
            return
        }
        val plan = TranscriptJson.plan(job.plan)
        val range = plan[job.chunksDone]
        val seconds = ((range.last - range.first + 999) / 1000).toInt()

        val now = clock()
        val wait = ledger.waitMs(settings.usage(), now, seconds)
        if (wait > 0) {
            save(job.copy(waitUntil = now + wait, error = TranscriptError.QUOTA.name))
            return
        }

        val file = File(chunkDir(job.recordingId), "${job.chunksDone}.m4a")
        if (!file.exists() || file.length() == 0L) audio.cut(recording.uri, range, file)
        if (file.length() > GroqClient.MAX_FILE_BYTES) throw AiException(AiException.Kind.TOO_LARGE, "chunk")

        val previous = TranscriptJson.segments(job.segments)
        save(job.copy(state = TranscriptState.TRANSCRIBING.name, waitUntil = 0, error = ""))
        val raw = groq.transcribe(key, file, whisperPrompt(recording, previous))
        settings.recordUse(QuotaLedger.Use(clock(), maxOf(seconds, QuotaLedger.MIN_BILLED_SECONDS)), ledger)

        val cleaned = WhisperFilter.clean(raw).map {
            Segment(it.startMs + range.first, it.endMs + range.first, it.text)
        }
        file.delete()
        val latest = transcripts.get(job.recordingId) ?: return // 그사이 취소됨
        save(
            latest.copy(
                segments = TranscriptJson.segments(previous + cleaned),
                chunksDone = job.chunksDone + 1,
                attempts = 0,
                error = "",
            )
        )
    }

    /**
     * Whisper 프롬프트: 과목 정보 + 앞 조각의 끝부분. 받아 적는 말투와 용어 표기를 이어 준다.
     * 224토큰 제한이 있어 한국어 기준 150자 안팎으로 자른다.
     */
    internal fun whisperPrompt(recording: Recording, previous: List<Segment>): String {
        val head = buildString {
            append(recording.subject).append(" 강의")
            if (recording.professor.isNotBlank()) append(", ").append(recording.professor)
            append(". ")
        }
        val tail = previous.takeLast(6).joinToString(" ") { it.text }.takeLast(150 - head.length.coerceAtMost(60))
        return (head + tail).trim()
    }

    // --- 3. 교정 ---

    private suspend fun afterTranscribing(job: Transcript) {
        val segments = TranscriptJson.segments(job.segments)
        if (segments.isEmpty()) {
            fail(job, TranscriptError.NO_SPEECH)
            chunkDir(job.recordingId).deleteRecursively()
            return
        }
        chunkDir(job.recordingId).deleteRecursively()
        val config = settings.config.value
        if (job.correct && config.nvidiaKeyHint != null && !config.nvidiaRejected) {
            save(
                job.copy(
                    state = TranscriptState.CORRECTING.name,
                    sectionsDone = 0,
                    sectionsTotal = TranscriptCorrector.sections(segments).size,
                    paragraphs = "",
                    attempts = 0,
                    error = "",
                )
            )
        } else {
            finish(job, Paragraphs.fromSegments(segments), model = "", error = null)
        }
    }

    private suspend fun correctNext(job: Transcript, recording: Recording) {
        val segments = TranscriptJson.segments(job.segments)
        val sections = TranscriptCorrector.sections(segments)
        val done = TranscriptJson.paragraphs(job.paragraphs)
        if (job.sectionsDone >= sections.size) {
            finish(job, done, job.model, error = job.errorEnum?.takeIf { it == TranscriptError.CORRECTION })
            return
        }
        val key = settings.nvidiaKey()
        val section = sections[job.sectionsDone]
        if (key == null) {
            finishWithRaw(job, sections, done)
            return
        }

        val prompt = TranscriptCorrector.userPrompt(
            recording.subject, recording.professor, done.lastOrNull()?.text.orEmpty(), section,
        )
        var used = job.model
        var result: List<Paragraph>? = null
        for (model in modelsToTry(job)) {
            try {
                save(job.copy(waitUntil = 0, error = if (job.error == TranscriptError.CORRECTION.name) job.error else ""))
                val answer = nvidia.chat(key, model, TranscriptCorrector.SYSTEM, prompt)
                used = model
                result = TranscriptCorrector.parse(answer, section)
                break
            } catch (e: AiException) {
                if (e.kind == AiException.Kind.MODEL_GONE) continue
                throw e
            }
        }

        val latest = transcripts.get(job.recordingId) ?: return
        if (result == null && latest.attempts < 1) {
            // 형식이 깨졌거나 요약해 버렸다. 한 번만 더.
            save(latest.copy(attempts = latest.attempts + 1, model = used))
            return
        }
        val paragraphs = result ?: Paragraphs.fromSegments(section)
        save(
            latest.copy(
                paragraphs = TranscriptJson.paragraphs(done + paragraphs),
                sectionsDone = job.sectionsDone + 1,
                attempts = 0,
                model = used,
                error = if (result == null) TranscriptError.CORRECTION.name else latest.error,
            )
        )
    }

    private fun modelsToTry(job: Transcript): List<String> =
        (listOf(job.model) + settings.modelOrder()).filter { it.isNotBlank() }.distinct()

    /** 교정을 더 할 수 없을 때: 남은 부분은 원문 문단으로 채워 끝낸다. */
    private suspend fun finishWithRaw(job: Transcript, sections: List<List<Segment>>, done: List<Paragraph>) {
        val rest = sections.drop(job.sectionsDone).flatten()
        finish(job, done + Paragraphs.fromSegments(rest), job.model, TranscriptError.CORRECTION)
    }

    // --- 실패 처리 ---

    private suspend fun handle(job: Transcript, e: AiException): Outcome {
        val latest = transcripts.get(job.recordingId) ?: return Outcome.Progressed
        val correcting = latest.stateEnum == TranscriptState.CORRECTING
        AppLog.w(TAG, "AI 호출 실패 id=${job.recordingId} ${e.kind} ${e.message}")
        when (e.kind) {
            AiException.Kind.NETWORK -> {
                // 화면이 ‘연결되면 이어서’를 보여 줄 수 있게 남긴다. 다음 걸음이 성공하면 지워진다.
                save(latest.copy(error = TranscriptError.NETWORK.name))
                return Outcome.Offline
            }
            AiException.Kind.AUTH -> {
                settings.markRejected(groq = !correcting)
                if (correcting) {
                    val sections = TranscriptCorrector.sections(TranscriptJson.segments(latest.segments))
                    finishWithRaw(latest, sections, TranscriptJson.paragraphs(latest.paragraphs))
                } else {
                    fail(latest, TranscriptError.GROQ_KEY)
                }
            }
            AiException.Kind.RATE_LIMIT -> {
                val wait = e.retryAfterMs.takeIf { it > 0 } ?: (60_000L shl latest.attempts.coerceAtMost(4))
                save(latest.copy(waitUntil = clock() + wait, error = TranscriptError.QUOTA.name, attempts = latest.attempts + 1))
            }
            AiException.Kind.SERVER, AiException.Kind.BAD_REQUEST, AiException.Kind.MODEL_GONE -> {
                val attempts = latest.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    if (correcting) {
                        val sections = TranscriptCorrector.sections(TranscriptJson.segments(latest.segments))
                        // 이 부분만 원문으로 두고 다음 부분으로.
                        val done = TranscriptJson.paragraphs(latest.paragraphs)
                        save(
                            latest.copy(
                                paragraphs = TranscriptJson.paragraphs(done + Paragraphs.fromSegments(sections[latest.sectionsDone])),
                                sectionsDone = latest.sectionsDone + 1,
                                attempts = 0,
                                error = TranscriptError.CORRECTION.name,
                            )
                        )
                    } else {
                        fail(latest, TranscriptError.SERVER)
                    }
                } else {
                    val backoff = 30_000L shl (attempts - 1)
                    save(latest.copy(attempts = attempts, waitUntil = clock() + backoff, error = TranscriptError.SERVER.name))
                }
            }
            AiException.Kind.TOO_LARGE -> fail(latest, TranscriptError.FILE)
        }
        return Outcome.Progressed
    }

    private suspend fun fail(job: Transcript, error: TranscriptError) {
        val latest = transcripts.get(job.recordingId) ?: return
        save(latest.copy(state = TranscriptState.FAILED.name, error = error.name, waitUntil = 0))
        chunkDir(job.recordingId).deleteRecursively()
    }

    private suspend fun finish(job: Transcript, paragraphs: List<Paragraph>, model: String, error: TranscriptError?) {
        val latest = transcripts.get(job.recordingId) ?: return
        save(
            latest.copy(
                state = TranscriptState.DONE.name,
                paragraphs = TranscriptJson.paragraphs(paragraphs),
                model = model,
                error = error?.name.orEmpty(),
                waitUntil = 0,
                attempts = 0,
            )
        )
    }

    private suspend fun save(t: Transcript) {
        // 사용자가 그사이 취소(행 삭제)했으면 되살리지 않는다.
        if (transcripts.get(t.recordingId) == null) return
        transcripts.upsert(t.copy(updatedAt = clock()))
    }

    private fun chunkDir(recordingId: Long) = File(context.cacheDir, "transcribe/$recordingId")

    companion object {
        private const val TAG = "Transcription"
        private const val MAX_ATTEMPTS = 4
        /** 무료 한도 25MB 에 여유를 둔 조각 크기. */
        private const val MAX_CHUNK_BYTES = 20.0 * 1024 * 1024
    }
}
