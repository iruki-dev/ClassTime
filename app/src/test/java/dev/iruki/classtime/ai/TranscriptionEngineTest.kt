package dev.iruki.classtime.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptError
import dev.iruki.classtime.data.TranscriptState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 대기열 한 건이 걸음마다 어떻게 나아가는지. 네트워크·오디오는 가짜. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptionEngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: AiSettings
    private var now = 1_700_000_000_000L

    private val secrets = object : Secrets {
        val map = mutableMapOf<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    }

    /** 파일을 열 때 던질 예외(들여온 파일 흉내). */
    private var audioError: Exception? = null

    /** 25분 강의: 말소리 + 숨(크기 곡선, 목소리 주기성). 10분 남짓 조각 세 개로 나뉜다. */
    private val audio = object : AudioSource {
        override fun profile(uri: String, isStopped: () -> Boolean): AudioProfile {
            audioError?.let { throw it }
            val n = 25 * 60 * 10
            return AudioProfile(
                FloatArray(n) { if (it % 40 >= 34) -60f else -20f },
                FloatArray(n) { if (it % 40 >= 34) 0f else 0.6f },
            )
        }
        override fun cut(uri: String, pieces: List<LongRange>, out: File) {
            cuts += pieces
            out.parentFile?.mkdirs(); out.writeBytes(ByteArray(10))
        }
    }
    private val cuts = mutableListOf<List<LongRange>>()

    private val sent = mutableListOf<String>()
    private var groqError: AiException? = null
    /** 있으면 다음 응답으로 쓴다(조별활동에서 30초 창이 무너진 흉내 등). */
    private val replies = ArrayDeque<List<RawSegment>>()
    private val groq = object : SpeechToText {
        override fun transcribe(key: String, file: File, prompt: String, language: String): List<RawSegment> {
            groqError?.let { groqError = null; throw it }
            sent += prompt
            replies.removeFirstOrNull()?.let { return it }
            // 조각 전체를 덮는 강의(5초마다 한 문장). 무음 환각 하나.
            val length = cuts.last().sumOf { it.last - it.first }
            return listOf(
                RawSegment(1_000, 4_000, "조각 ${sent.size} 첫 문장", -0.2, 0.01, 1.4),
                RawSegment(4_000, 6_000, "시청해 주셔서 감사합니다", -1.3, 0.8, 1.2),
            ) + (6_000 until length step 5_000).map {
                RawSegment(it, minOf(length, it + 5_000), "조각 ${sent.size} 이어지는 말 $it", -0.2, 0.01, 1.4)
            }
        }
    }

    private lateinit var engine: TranscriptionEngine

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = AiSettings(context, secrets)
        settings.setGroqKey("gsk_test1234")
        engine = TranscriptionEngine(
            context, db.transcriptDao(), db.recordingDao(), settings,
            audio = audio, groq = groq, clock = { now },
        )
        db.recordingDao().insert(
            Recording(id = 1, subject = "자료구조", professor = "김교수", fileName = "a.m4a", uri = "content://x/1",
                relativePath = "Music/ClassTime", startedAt = 0, durationMs = 25 * 60_000L, sizeBytes = 18_000_000)
        )
        Unit
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job() = db.transcriptDao().get(1)!!

    private suspend fun runToEnd(maxSteps: Int = 50) {
        repeat(maxSteps) {
            val j = db.transcriptDao().get(1) ?: return
            if (!j.stateEnum.active) return
            if (j.waitUntil > now) now = j.waitUntil
            engine.step(j)
        }
    }

    @Test
    fun fullRun_splitsTranscribes_andDropsHallucinations() = runBlocking {
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now))
        runToEnd()

        val t = job()
        assertThat(t.stateEnum).isEqualTo(TranscriptState.DONE)
        assertThat(t.error).isEmpty()
        val plan = TranscriptJson.plan(t.plan)
        assertThat(plan.size).isAtLeast(2)
        assertThat(sent).hasSize(plan.size)
        // 과목 정보가 프롬프트에 실리고, 둘째 조각부터는 앞 조각 끝이 이어진다.
        assertThat(sent[0]).startsWith("자료구조 강의, 김교수.")
        assertThat(sent[1]).contains("조각 1 이어지는 말")

        val segments = TranscriptJson.segments(t.segments)
        assertThat(segments.none { it.text.contains("시청해") }).isTrue()
        // 둘째 조각의 시간은 녹음 전체 기준으로 옮겨졌다.
        assertThat(segments.first { it.text == "조각 2 첫 문장" }.startMs).isEqualTo(plan[1].toSource(1_000))
        assertThat(cuts).isEqualTo(plan.map { it.pieces })

        val paragraphs = TranscriptJson.paragraphs(t.paragraphs)
        assertThat(paragraphs.first().text).contains("조각 1 첫 문장")
        assertThat(paragraphs.first().startMs).isEqualTo(segments.first().startMs)
        // 한도 기록이 남는다(조각마다).
        assertThat(settings.usage()).hasSize(plan.size)
    }

    @Test
    fun collapsedWindow_isTranscribedAgain_andReplacedWhenBetter() = runBlocking {
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now))
        engine.step(job()) // 나누기
        val planned = TranscriptJson.plan(job().plan).size
        // 조각 1: 20–50초가 “네.” 하나로 무너지고 나머지는 정상.
        val length = TranscriptJson.plan(job().plan)[0].durationMs
        replies += listOf(
            RawSegment(15_000, 20_000, "오늘은 히스토그램을 그려 보겠습니다", -0.3, 0.05, 1.4),
            RawSegment(20_000, 50_000, "네.", -1.19, 0.68, 0.4),
            RawSegment(50_000, 60_000, "막대 모양을 맞춰 주세요 그래야 비교가 돼요", -0.3, 0.05, 1.4),
        ) + (0L until 15_000L step 5_000).map { RawSegment(it, it + 5_000, "출석 부르는 중 $it", -0.3, 0.05, 1.4) } +
            (60_000 until length step 5_000).map { RawSegment(it, minOf(length, it + 5_000), "설명 이어서 $it", -0.3, 0.05, 1.4) }
        replies += listOf( // 다시 받아 적기(앞 조각)
            RawSegment(1_000, 15_000, "기초 통계량을 먼저 계산하고", -0.3, 0.1, 1.3),
            RawSegment(15_000, 16_500, "그다음에", -0.3, 0.1, 1.3),
        )
        replies += listOf( // 다시 받아 적기(뒤 조각)
            RawSegment(0, 16_500, "히스토그램을 만들어 달라고 해야 할 것 같아요", -0.3, 0.1, 1.3),
        )
        engine.step(job()) // 조각 1 → 놓친 창(30초 안팎으로 둘)을 바로 뒤에 끼운다

        val plan = TranscriptJson.plan(job().plan)
        assertThat(plan).hasSize(planned + 2)
        assertThat(plan[1].retry && plan[2].retry).isTrue()
        assertThat(plan[1].pieces.single()).isEqualTo(18_000L..34_500L)
        assertThat(plan[2].pieces.single()).isEqualTo(34_500L..51_000L)

        // 조각 1, 다시 받아 적기 둘 순서로 보낸다. 다시 받아 적을 때는 그 앞까지만 프롬프트에.
        runToEnd()
        assertThat(job().stateEnum).isEqualTo(TranscriptState.DONE)
        assertThat(cuts[1]).isEqualTo(plan[1].pieces)
        val texts = TranscriptJson.segments(job().segments).map { it.text }
        val at = texts.indexOf("오늘은 히스토그램을 그려 보겠습니다")
        assertThat(texts.subList(at, at + 5)).containsExactly(
            "오늘은 히스토그램을 그려 보겠습니다",
            "기초 통계량을 먼저 계산하고",
            "그다음에",
            "히스토그램을 만들어 달라고 해야 할 것 같아요",
            "막대 모양을 맞춰 주세요 그래야 비교가 돼요",
        ).inOrder()
        assertThat(sent[1]).doesNotContain("막대")
        // 다시 받아 적은 것도 한도에 센다.
        assertThat(settings.usage()).hasSize(planned + 2)
    }

    @Test
    fun rateLimit_waitsAndResumesWithoutResendingDoneChunks() = runBlocking {
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now, correct = false))
        engine.step(job()) // 나누기
        engine.step(job()) // 조각 1
        groqError = AiException(AiException.Kind.RATE_LIMIT, "429", retryAfterMs = 90_000)
        engine.step(job())

        val waiting = job()
        assertThat(waiting.chunksDone).isEqualTo(1)
        assertThat(waiting.errorEnum).isEqualTo(TranscriptError.QUOTA)
        assertThat(waiting.waitUntil).isEqualTo(now + 90_000)

        runToEnd()
        assertThat(job().stateEnum).isEqualTo(TranscriptState.DONE)
        assertThat(sent).hasSize(TranscriptJson.plan(job().plan).size)
    }

    @Test
    fun rejectedGroqKey_failsAndFlagsSettings() = runBlocking {
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now, correct = false))
        engine.step(job())
        groqError = AiException(AiException.Kind.AUTH, "401")
        engine.step(job())
        assertThat(job().stateEnum).isEqualTo(TranscriptState.FAILED)
        assertThat(job().errorEnum).isEqualTo(TranscriptError.GROQ_KEY)
        assertThat(settings.config.value.groqRejected).isTrue()
    }

    @Test
    fun legacyCorrectingJob_finishesWithRawParagraphs() = runBlocking {
        // 예전 버전(LLM 교정)에서 ‘다듬는 중’으로 남은 작업: 받아 적은 원문으로 끝낸다.
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now))
        engine.step(job())
        repeat(TranscriptJson.plan(job().plan).size) { engine.step(job()) }
        db.transcriptDao().upsert(job().copy(state = TranscriptState.CORRECTING.name, sectionsTotal = 3))
        runToEnd()
        assertThat(job().stateEnum).isEqualTo(TranscriptState.DONE)
        assertThat(TranscriptJson.paragraphs(job().paragraphs)).isNotEmpty()
    }

    @Test
    fun unreadableImportedFile_failsWithPermissionReasonAndDetail() = runBlocking {
        audioError = AudioAccessException(
            AudioAccessException.Reason.PERMISSION, "permission",
            SecurityException("Permission Denial: reading MediaProvider"),
        )
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now))
        engine.step(job())
        assertThat(job().stateEnum).isEqualTo(TranscriptState.FAILED)
        assertThat(job().errorEnum).isEqualTo(TranscriptError.PERMISSION)
        assertThat(job().errorDetail).contains("SecurityException")
        // 권한을 주고 다시 시도하면 처음부터 이어진다.
        audioError = null
        TranscriptionQueueRetry.reset(db, now)
        runToEnd()
        assertThat(job().stateEnum).isEqualTo(TranscriptState.DONE)
    }

    @Test
    fun unexpectedCodecError_failsInsteadOfCrashingTheQueue() = runBlocking {
        audioError = IllegalStateException("codec died")
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now))
        engine.step(job())
        assertThat(job().stateEnum).isEqualTo(TranscriptState.FAILED)
        assertThat(job().errorEnum).isEqualTo(TranscriptError.UNKNOWN)
        assertThat(job().errorDetail).contains("codec died")
    }

    @Test
    fun cancelledMidway_isNotRevived() = runBlocking {
        db.transcriptDao().upsert(Transcript(recordingId = 1, queuedAt = now, correct = false))
        engine.step(job())
        val snapshot = job()
        db.transcriptDao().delete(1)
        engine.step(snapshot)
        assertThat(db.transcriptDao().get(1)).isNull()
    }
}

/** [TranscriptionQueue.retry] 와 같은 되살리기(WorkManager 없이). */
private object TranscriptionQueueRetry {
    suspend fun reset(db: AppDatabase, now: Long) {
        val t = db.transcriptDao().get(1)!!
        db.transcriptDao().upsert(t.copy(state = TranscriptState.QUEUED.name, error = "", attempts = 0, waitUntil = 0, queuedAt = now))
    }
}
