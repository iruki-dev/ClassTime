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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 대기열의 작업 하나를 **한 걸음** 나아가게 한다. 걸음마다 결과를 DB 에 저장하므로
 * 어디서 멈춰도(앱 종료, 네트워크 끊김, 한도) 그다음 걸음부터 이어진다.
 *
 * 걸음: 조각 나누기 → 조각마다 받아 적기(놓친 곳은 한 번 더) → 문단으로 묶어 끝.
 */
class TranscriptionEngine(
    private val context: Context,
    private val transcripts: TranscriptDao,
    private val recordings: RecordingDao,
    private val settings: AiSettings,
    private val audio: AudioSource = AudioChunks(context),
    private val groq: SpeechToText = GroqClient(),
    private val ledger: QuotaLedger = QuotaLedger(),
    private val files: TextFiles = TranscriptFiles(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val detector = SpeechDetector()

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
                job.chunksDone < TranscriptJson.plan(job.plan).size -> transcribeNext(job, recording, isStopped)
                else -> finishTranscribing(job, recording)
            }
            Outcome.Progressed
        } catch (e: AiException) {
            handle(job, e)
        } catch (e: AudioAccessException) {
            if (isStopped()) return@withContext Outcome.Progressed
            AppLog.w(TAG, "녹음 파일을 읽지 못했습니다 id=${job.recordingId} ${e.reason}", e)
            val error = when (e.reason) {
                AudioAccessException.Reason.PERMISSION -> TranscriptError.PERMISSION
                AudioAccessException.Reason.MISSING -> TranscriptError.FILE
                AudioAccessException.Reason.FORMAT -> TranscriptError.FORMAT
            }
            fail(job, error, e.detail())
            Outcome.Progressed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 기기마다 다른 코덱 오류(IllegalStateException 등)까지. 처리기가 같은 자리에서 계속 죽지 않게
            // 실패로 남기고, 무엇 때문인지 화면에 보이도록 짧게 적어 둔다.
            if (isStopped()) return@withContext Outcome.Progressed
            AppLog.w(TAG, "텍스트 변환 중 예외 id=${job.recordingId}", e)
            fail(job, if (e is IOException) TranscriptError.FILE else TranscriptError.UNKNOWN, e.detail())
            Outcome.Progressed
        }
    }

    // --- 1. 조각 나누기 ---

    private suspend fun prepare(job: Transcript, recording: Recording, isStopped: () -> Boolean) {
        save(job.copy(state = TranscriptState.PREPARING.name, error = ""))
        val profile = audio.profile(recording.uri, isStopped)
        val active = detector.active(profile)
        saveEvidence(job.recordingId, SpeechEvidence(active))
        val plan = plannerFor(recording).plan(detector.spans(active), profile.level)
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
                error = "",
                speechSeconds = (plan.sumOf { it.durationMs } / 1000).toInt(),
            )
        )
    }

    /**
     * 조각 최대 길이를 보낼 파일의 비트레이트에 맞춘다. AAC 는 그대로 옮기므로 원본 비트레이트,
     * 그 밖의 형식은 64kbps AAC 로 바꿔 보낸다. 이 앱의 녹음(96kbps)은 13분이면 9MB 남짓이다.
     */
    private fun plannerFor(recording: Recording): ChunkPlanner {
        val aac = recording.fileName.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp4", "aac")
        val bytesPerMs = if (aac && recording.durationMs > 0 && recording.sizeBytes > 0) {
            recording.sizeBytes.toDouble() / recording.durationMs
        } else if (aac) 96_000 / 8 / 1000.0 else 64_000 / 8 / 1000.0
        val fitMs = (MAX_CHUNK_BYTES / bytesPerMs).toLong()
        val maxMs = minOf(13 * 60_000L, fitMs)
        val targetMs = minOf(10 * 60_000L, maxMs * 10 / 13)
        return ChunkPlanner(targetMs = targetMs, maxMs = maxMs, searchMs = targetMs * 3 / 10)
    }

    // --- 2. 받아 적기 ---

    private suspend fun transcribeNext(job: Transcript, recording: Recording, isStopped: () -> Boolean) {
        val key = settings.groqKey()
        if (key == null) {
            fail(job, TranscriptError.GROQ_KEY)
            return
        }
        val plan = TranscriptJson.plan(job.plan)
        val chunk = plan[job.chunksDone]
        val seconds = ((chunk.durationMs + 999) / 1000).toInt()

        val now = clock()
        val wait = ledger.waitMs(settings.usage(), now, seconds)
        if (wait > 0) {
            save(job.copy(waitUntil = now + wait, error = TranscriptError.QUOTA.name))
            return
        }

        val evidence = evidence(job.recordingId, recording, isStopped).slice(chunk)
        val file = File(chunkDir(job.recordingId), "${job.chunksDone}.m4a")
        if (!file.exists() || file.length() == 0L) audio.cut(recording.uri, chunk.pieces, file)
        if (file.length() > GroqClient.MAX_FILE_BYTES) throw AiException(AiException.Kind.TOO_LARGE, "chunk")

        val previous = TranscriptJson.segments(job.segments)
        save(job.copy(state = TranscriptState.TRANSCRIBING.name, waitUntil = 0, error = ""))
        val before = if (chunk.retry) previous.filter { it.endMs <= chunk.pieces.first().first } else previous
        val raw = groq.transcribe(key, file, whisperPrompt(recording, before))
        settings.recordUse(QuotaLedger.Use(clock(), maxOf(seconds, QuotaLedger.MIN_BILLED_SECONDS)), ledger)

        val cleaned = WhisperFilter.clean(raw, evidence).map {
            Segment(chunk.toSource(it.startMs), chunk.toSource(it.endMs, end = true), it.text)
        }
        // Whisper 가 말소리를 통째로 놓친 30초 창은 그 부분만 바로 뒤에 한 번 더 받아 적는다.
        val retries = if (chunk.retry) emptyList() else {
            WhisperFilter.missedWindows(raw, evidence, chunk.durationMs)
                .map { Chunk(chunk.sourcePieces(it), retry = true) }
        }
        file.delete()
        val latest = transcripts.get(job.recordingId) ?: return // 그사이 취소됨
        val newPlan = plan.take(job.chunksDone + 1) + retries + plan.drop(job.chunksDone + 1)
        save(
            latest.copy(
                plan = TranscriptJson.plan(newPlan),
                segments = TranscriptJson.segments(if (chunk.retry) replaceMissed(previous, cleaned, chunk) else previous + cleaned),
                chunksDone = job.chunksDone + 1,
                speechSeconds = latest.speechSeconds + (retries.sumOf { it.durationMs } / 1000).toInt(),
                attempts = 0,
                error = "",
            )
        )
    }

    /**
     * 다시 받아 적은 결과가 처음보다 많이 알아들었으면(글자 수) 그 구간의 문장을 바꾼다.
     * 아니면 처음 것을 그대로 둔다.
     */
    internal fun replaceMissed(previous: List<Segment>, fresh: List<Segment>, chunk: Chunk): List<Segment> {
        fun inside(s: Segment) = chunk.pieces.any { (s.startMs + s.endMs) / 2 in it }
        val old = previous.filter(::inside)
        if (fresh.sumOf { it.text.length } <= old.sumOf { it.text.length }) return previous
        return (previous.filterNot(::inside) + fresh).sortedBy { it.startMs }
    }

    /** 말소리 근거. 나누기 때 저장해 두고, 캐시가 지워졌으면 다시 잰다. */
    private fun evidence(recordingId: Long, recording: Recording, isStopped: () -> Boolean): SpeechEvidence {
        val f = File(chunkDir(recordingId), EVIDENCE)
        if (f.exists() && f.length() > 0) return SpeechEvidence.fromBytes(f.readBytes())
        val e = SpeechEvidence(detector.active(audio.profile(recording.uri, isStopped)))
        saveEvidence(recordingId, e)
        return e
    }

    private fun saveEvidence(recordingId: Long, e: SpeechEvidence) {
        val dir = chunkDir(recordingId).apply { mkdirs() }
        File(dir, EVIDENCE).writeBytes(e.toBytes())
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
        // 길고 성긴 문장은 넘기지 않는다: 엉뚱한 문장이 프롬프트로 넘어가면 다음 조각이 통째로 무너진다.
        val tail = previous.filterNot { WhisperFilter.isSparse(it.startMs, it.endMs, it.text) }
            .takeLast(6).joinToString(" ") { it.text }.takeLast(150 - head.length.coerceAtMost(60))
        return (head + tail).trim()
    }

    // --- 3. 끝 ---

    /** 모든 조각을 받아 적었다: 문단으로 묶어 끝낸다. 예전 버전의 ‘교정 중’ 작업도 여기로 온다. */
    private suspend fun finishTranscribing(job: Transcript, recording: Recording) {
        chunkDir(job.recordingId).deleteRecursively()
        val segments = TranscriptJson.segments(job.segments)
        if (segments.isEmpty()) {
            fail(job, TranscriptError.NO_SPEECH)
            return
        }
        val latest = transcripts.get(job.recordingId) ?: return
        val paragraphs = Paragraphs.fromSegments(segments)
        // 녹음 옆(Documents/ClassTime/<과목>)에 텍스트 파일로도 남긴다. 못 써도 변환은 끝난 것으로.
        runCatching { files.write(recording, paragraphs) }
            .onFailure { AppLog.w(TAG, "텍스트 파일을 쓰지 못했습니다 id=${job.recordingId}", it) }
        save(
            latest.copy(
                state = TranscriptState.DONE.name,
                paragraphs = TranscriptJson.paragraphs(paragraphs),
                model = "",
                error = "",
                waitUntil = 0,
                attempts = 0,
            )
        )
    }

    // --- 실패 처리 ---

    private suspend fun handle(job: Transcript, e: AiException): Outcome {
        val latest = transcripts.get(job.recordingId) ?: return Outcome.Progressed
        AppLog.w(TAG, "AI 호출 실패 id=${job.recordingId} ${e.kind} ${e.message}")
        when (e.kind) {
            AiException.Kind.NETWORK -> {
                // 화면이 ‘연결되면 이어서’를 보여 줄 수 있게 남긴다. 다음 걸음이 성공하면 지워진다.
                save(latest.copy(error = TranscriptError.NETWORK.name))
                return Outcome.Offline
            }
            AiException.Kind.AUTH -> {
                settings.markRejected()
                fail(latest, TranscriptError.GROQ_KEY)
            }
            AiException.Kind.RATE_LIMIT -> {
                val wait = e.retryAfterMs.takeIf { it > 0 } ?: (60_000L shl latest.attempts.coerceAtMost(4))
                save(latest.copy(waitUntil = clock() + wait, error = TranscriptError.QUOTA.name, attempts = latest.attempts + 1))
            }
            AiException.Kind.SERVER, AiException.Kind.BAD_REQUEST, AiException.Kind.MODEL_GONE -> {
                val attempts = latest.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    fail(latest, TranscriptError.SERVER, e.detail())
                } else {
                    val backoff = 30_000L shl (attempts - 1)
                    save(latest.copy(attempts = attempts, waitUntil = clock() + backoff, error = TranscriptError.SERVER.name))
                }
            }
            AiException.Kind.TOO_LARGE -> fail(latest, TranscriptError.FILE, e.detail())
        }
        return Outcome.Progressed
    }

    private suspend fun fail(job: Transcript, error: TranscriptError, detail: String = "") {
        val latest = transcripts.get(job.recordingId) ?: return
        save(latest.copy(state = TranscriptState.FAILED.name, error = TranscriptError.encode(error, detail), waitUntil = 0))
        chunkDir(job.recordingId).deleteRecursively()
    }

    private suspend fun save(t: Transcript) {
        // 사용자가 그사이 취소(행 삭제)했으면 되살리지 않는다.
        if (transcripts.get(t.recordingId) == null) return
        transcripts.upsert(t.copy(updatedAt = clock()))
    }

    private fun chunkDir(recordingId: Long) = File(context.cacheDir, "transcribe/$recordingId")

    /** 화면에 보일 짧은 원인. 예외 이름 + 메시지 앞부분. */
    private fun Throwable.detail(): String {
        val root = generateSequence(this) { it.cause }.last()
        val name = root.javaClass.simpleName.ifBlank { "Error" }
        return (name + (root.message?.let { ": $it" } ?: "")).take(160)
    }

    companion object {
        private const val TAG = "Transcription"
        private const val MAX_ATTEMPTS = 4
        private const val EVIDENCE = "speech.bin"
        /** 무료 한도 25MB 에 여유를 둔 조각 크기. */
        private const val MAX_CHUNK_BYTES = 20.0 * 1024 * 1024
    }
}
