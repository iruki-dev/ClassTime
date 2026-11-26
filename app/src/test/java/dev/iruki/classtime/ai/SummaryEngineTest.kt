package dev.iruki.classtime.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.SummaryError
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 요약 한 건이 배치로 나가고, 확인하고, 저장되기까지. Claude 는 가짜. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SummaryEngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: AiSettings
    private lateinit var engine: SummaryEngine
    private lateinit var queue: SummaryQueue
    private val files = FakeTextFiles()
    private var now = 1_700_000_000_000L

    private val secrets = object : Secrets {
        val map = mutableMapOf<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    }

    private val summaryJson = """
        {"title":"힙 정렬","overview":"힙과 힙 정렬을 다룸.",
         "notices":[{"kind":"exam","text":"다음 주 쪽지시험","refs":[1]}],
         "sections":[{"heading":"힙","refs":[0],"points":[{"text":"완전 이진 트리","refs":[0]}]}],
         "terms":[]}
    """.trimIndent()

    private inner class FakeApi : SummaryApi {
        val submitted = mutableListOf<List<SummaryRequest>>()
        var ended = false
        var endedCalls = 0
        var submitError: SummaryApiException? = null
        var outcomes: (List<SummaryRequest>) -> Map<String, SummaryOutcome> = { reqs ->
            reqs.associate { it.customId to SummaryOutcome.Success(summaryJson, 12_000, 3_000) }
        }
        override fun verify(key: String) = Unit
        override fun submit(key: String, requests: List<SummaryRequest>): String {
            submitError?.let { throw it }
            submitted += requests
            return "batch_${submitted.size}"
        }
        override fun ended(key: String, batchId: String): Boolean { endedCalls++; return ended }
        override fun results(key: String, batchId: String) = outcomes(submitted.last())
    }

    private val api = FakeApi()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = AiSettings(context, secrets)
        settings.setClaudeKey("sk-ant-test1234")
        engine = SummaryEngine(db.transcriptDao(), db.summaryDao(), db.recordingDao(), settings, files, api) { now }
        queue = SummaryQueue(context, db.summaryDao(), db.transcriptDao(), db.recordingDao(), settings, files) { now }
        for (id in 1L..2L) {
            db.recordingDao().insert(
                Recording(id = id, subject = "자료구조", professor = "", fileName = "r$id.m4a", uri = "content://x/$id",
                    relativePath = "Music/ClassTime/자료구조", startedAt = 0, durationMs = 60_000, sizeBytes = 1)
            )
            db.transcriptDao().upsert(
                Transcript(
                    recordingId = id, queuedAt = 0, state = TranscriptState.DONE.name, updatedAt = 42,
                    paragraphs = TranscriptJson.paragraphs(listOf(Paragraph(0, "힙은 완전 이진 트리"), Paragraph(90_000, "다음 주 쪽지시험"))),
                )
            )
        }
        Unit
    }

    @After
    fun tearDown() = db.close()

    private suspend fun summary(id: Long = 1) = db.summaryDao().get(id)!!

    @Test
    fun queuedSummaries_goOutAsOneBatch_thenArePolledUntilDone() = runBlocking {
        assertThat(queue.enqueue(1)).isTrue()
        assertThat(queue.enqueue(2, SummaryModel.HAIKU)).isTrue()

        val first = engine.run()
        assertThat(api.submitted.single().map { it.customId }).containsExactly("rec-1", "rec-2")
        assertThat(api.submitted.single().map { it.model }).containsExactly("claude-opus-5-5", "claude-haiku-5-5")
        assertThat(api.submitted.single().first().user).contains("[1] 다음 주 쪽지시험")
        assertThat(summary().stateEnum).isEqualTo(SummaryState.SUBMITTED)
        assertThat(summary().transcriptAt).isEqualTo(42)
        assertThat(first.nextAt).isEqualTo(now + SummaryEngine.interval(0))

        // 확인할 때가 안 됐으면 묻지도 않는다.
        engine.run()
        assertThat(api.endedCalls).isEqualTo(0)

        // 아직 진행 중이면 간격을 늘려 다시.
        now += 60_000
        val waiting = engine.run()
        assertThat(api.endedCalls).isEqualTo(1)
        assertThat(summary().checks).isEqualTo(1)
        assertThat(waiting.nextAt).isEqualTo(now + SummaryEngine.interval(1))

        // 끝나면 결과를 저장하고, 녹음 옆에 요약 파일도 남긴다.
        now += SummaryEngine.interval(1)
        api.ended = true
        val done = engine.run()
        assertThat(done.finished).containsExactly(1L, 2L)
        assertThat(done.nextAt).isNull()
        val s = summary()
        assertThat(s.stateEnum).isEqualTo(SummaryState.DONE)
        assertThat(s.inputTokens).isEqualTo(12_000)
        assertThat(SummaryJson.parse(s.content).notices.single().text).isEqualTo("다음 주 쪽지시험")
        assertThat(files[1L, TextKind.SUMMARY]).contains("- 다음 주 쪽지시험 [01:30]")
        assertThat(api.submitted).hasSize(1)
    }

    @Test
    fun refusedTruncatedAndExpired_failWithReasons() = runBlocking {
        api.outcomes = { mapOf("rec-1" to SummaryOutcome.Refused("cyber"), "rec-2" to SummaryOutcome.Truncated(10, 32_000)) }
        queue.enqueue(1); queue.enqueue(2)
        engine.run()
        now += 60_000
        api.ended = true
        engine.run()
        assertThat(summary(1).errorEnum).isEqualTo(SummaryError.REFUSED)
        assertThat(summary(2).errorEnum).isEqualTo(SummaryError.TOO_LONG)
        assertThat(summary(2).outputTokens).isEqualTo(32_000)
        assertThat(files.files).isEmpty()
    }

    @Test
    fun rejectedKey_failsAll_andFlagsSettings() = runBlocking {
        api.submitError = SummaryApiException(SummaryApiException.Kind.AUTH, "401")
        queue.enqueue(1)
        val pass = engine.run()
        assertThat(summary().errorEnum).isEqualTo(SummaryError.CLAUDE_KEY)
        assertThat(pass.finished).containsExactly(1L)
        assertThat(settings.config.value.claudeRejected).isTrue()
    }

    @Test
    fun serverTrouble_keepsItQueued_andTriesAgainLater() = runBlocking {
        api.submitError = SummaryApiException(SummaryApiException.Kind.SERVER, "529")
        queue.enqueue(1)
        val pass = engine.run()
        assertThat(summary().stateEnum).isEqualTo(SummaryState.QUEUED)
        assertThat(pass.nextAt).isGreaterThan(now)

        api.submitError = SummaryApiException(SummaryApiException.Kind.NETWORK, "io")
        assertThat(engine.run().offline).isTrue()
    }

    @Test
    fun onlyFinishedTextCanBeSummarized_andAutoFollowsSettings() = runBlocking {
        db.transcriptDao().upsert(db.transcriptDao().get(2)!!.copy(state = TranscriptState.TRANSCRIBING.name))
        assertThat(queue.enqueue(2)).isFalse()

        // 자동 요약: 외부 전송 안내를 확인하지 않았으면 하지 않는다.
        queue.onTranscribed(1)
        assertThat(db.summaryDao().get(1)).isNull()
        settings.summaryConsented = true
        settings.setAutoSummarize(false)
        queue.onTranscribed(1)
        assertThat(db.summaryDao().get(1)).isNull()
        settings.setAutoSummarize(true)
        queue.onTranscribed(1)
        assertThat(summary().stateEnum).isEqualTo(SummaryState.QUEUED)
    }

    @Test
    fun removedWhileWaiting_isNotRevived() = runBlocking {
        queue.enqueue(1)
        engine.run()
        queue.remove(1)
        now += 60_000
        api.ended = true
        engine.run()
        assertThat(db.summaryDao().get(1)).isNull()
        assertThat(files.files).isEmpty()
    }

    @Test
    fun summaryQueuedBeforeText_waitsForTranscription_thenGoesOut() = runBlocking {
        db.transcriptDao().upsert(db.transcriptDao().get(2)!!.copy(state = TranscriptState.TRANSCRIBING.name))
        queue.enqueueAfterText(2)
        val waiting = engine.run()
        assertThat(api.submitted).isEmpty()
        assertThat(waiting.nextAt).isNull() // 변환이 끝나면 깨운다(돌며 기다리지 않는다).
        assertThat(summary(2).stateEnum).isEqualTo(SummaryState.QUEUED)

        db.transcriptDao().upsert(db.transcriptDao().get(2)!!.copy(state = TranscriptState.DONE.name))
        queue.onTranscribed(2, ok = true)
        engine.run()
        assertThat(api.submitted.single().single().customId).isEqualTo("rec-2")
    }

    @Test
    fun summaryWaitingForText_failsWhenTranscriptionFails() = runBlocking {
        db.transcriptDao().upsert(db.transcriptDao().get(2)!!.copy(state = TranscriptState.TRANSCRIBING.name))
        queue.enqueueAfterText(2)
        db.transcriptDao().upsert(db.transcriptDao().get(2)!!.copy(state = TranscriptState.FAILED.name))
        queue.onTranscribed(2, ok = false)
        engine.run()
        assertThat(summary(2).errorEnum).isEqualTo(SummaryError.NO_TEXT)
    }

    @Test
    fun usage_isRecordedPerMonth_andSurvivesDeletingTheSummary() = runBlocking {
        queue.enqueue(1)
        engine.run()
        now += 60_000
        api.ended = true
        engine.run()
        queue.remove(1)
        val month = settings.summaryMonths().single()
        assertThat(month.count).isEqualTo(1)
        assertThat(month.inputTokens).isEqualTo(12_000)
        assertThat(month.dollars).isWithin(1e-9).of(SummaryModel.OPUS.batchCost(12_000, 3_000))
    }

    @Test
    fun withoutKey_failsWithKeyReason() = runBlocking {
        queue.enqueue(1)
        settings.setClaudeKey(null)
        engine.run()
        assertThat(summary().errorEnum).isEqualTo(SummaryError.CLAUDE_KEY)
        assertThat(api.submitted).isEmpty()
    }
}
