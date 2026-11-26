package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import org.junit.Test

class BulkPlanTest {

    private fun rec(id: Long, durationMs: Long = 60 * 60_000L, ongoing: Boolean = false, peak: Int = 3000) = Recording(
        id = id, subject = "자료구조", professor = "", fileName = "r$id.m4a", uri = "content://x/$id",
        relativePath = "Music/ClassTime/자료구조", startedAt = id, durationMs = durationMs, sizeBytes = 1,
        ongoing = ongoing, peakAmplitude = peak,
    )

    private fun t(id: Long, state: TranscriptState) = Transcript(recordingId = id, queuedAt = 0, state = state.name)
    private fun s(id: Long, state: SummaryState) = Summary(recordingId = id, model = "m", queuedAt = 0, state = state.name)

    @Test
    fun findsWhatIsMissing_andSkipsWhatIsBusyOrPointless() {
        val recs = listOf(
            rec(1), // 텍스트 없음 → 변환(+요약)
            rec(2), // 변환 실패 → 다시 변환
            rec(3), // 변환 중 → 건드리지 않음
            rec(4), // 텍스트 있고 요약 없음 → 요약
            rec(5), // 요약 실패 → 다시 요약
            rec(6), // 다 있음
            rec(7, ongoing = true), // 녹음 중
            rec(8, durationMs = 30_000), // 1분 미만
            rec(9, peak = 0), // 무음
        )
        val ts = mapOf(
            2L to t(2, TranscriptState.FAILED), 3L to t(3, TranscriptState.TRANSCRIBING),
            4L to t(4, TranscriptState.DONE), 5L to t(5, TranscriptState.DONE), 6L to t(6, TranscriptState.DONE),
        )
        val ss = mapOf(5L to s(5, SummaryState.FAILED), 6L to s(6, SummaryState.DONE))

        val plan = BulkPlan.of(recs, ts, ss, summaryEnabled = true)
        assertThat(plan.transcribe).containsExactly(1L, 2L).inOrder()
        assertThat(plan.summarize).containsExactly(4L, 5L).inOrder()

        // 요약을 쓰지 않으면 변환만.
        val textOnly = BulkPlan.of(recs, ts, ss, summaryEnabled = false)
        assertThat(textOnly.summarize).isEmpty()
        assertThat(BulkPlan.of(listOf(rec(6)), ts, ss, true).isEmpty).isTrue()
    }
}
