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

    @Test
    fun keepsDurations_andEstimatesCostFromLength() {
        val recs = listOf(rec(1, durationMs = 90 * 60_000L), rec(2, durationMs = 60 * 60_000L))
        val plan = BulkPlan.of(recs, mapOf(2L to t(2, TranscriptState.DONE)), emptyMap(), summaryEnabled = true)

        assertThat(plan.transcribe).containsExactly(1L)
        assertThat(plan.summarize).containsExactly(2L)
        assertThat(plan.transcribeMs).isEqualTo(90 * 60_000L)
        // 요약만 셀 때보다 변환할 것까지 셀 때 더 비싸고, 길이에 비례해 는다.
        val only = plan.estimate(SummaryModel.OPUS, summarizeTranscribed = false)
        val both = plan.estimate(SummaryModel.OPUS, summarizeTranscribed = true)
        assertThat(only).isEqualTo(SummaryModel.OPUS.estimate(60 * 60_000L))
        assertThat(both).isWithin(1e-9).of(only + SummaryModel.OPUS.estimate(90 * 60_000L))
        // 강의 1시간 Opus 는 모델 고르기 안내(약 $0.08)와 어긋나지 않는다.
        assertThat(only).isWithin(0.02).of(0.08)
        assertThat(SummaryModel.HAIKU.estimate(60 * 60_000L)).isLessThan(0.01)
    }
}
