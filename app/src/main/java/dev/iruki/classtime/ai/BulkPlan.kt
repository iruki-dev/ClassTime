package dev.iruki.classtime.ai

import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState

/**
 * 설정의 ‘전체 텍스트 변환·요약’이 맡길 것. 순수 계산.
 *
 * - [transcribe]: 텍스트가 없거나 변환에 실패한 녹음. 요약을 쓸 수 있으면 변환 뒤 요약까지 한다.
 * - [summarize]: 텍스트는 있는데 요약이 없거나 실패한 녹음.
 * 진행 중인 것, 녹음 중인 것, 무음·1분 미만 녹음은 건드리지 않는다.
 */
data class BulkPlan(val transcribe: List<Long>, val summarize: List<Long>) {
    val isEmpty get() = transcribe.isEmpty() && summarize.isEmpty()

    companion object {
        fun of(
            recordings: List<Recording>,
            transcripts: Map<Long, Transcript>,
            summaries: Map<Long, Summary>,
            summaryEnabled: Boolean,
        ): BulkPlan {
            val transcribe = mutableListOf<Long>()
            val summarize = mutableListOf<Long>()
            for (r in recordings.sortedBy { it.startedAt }) {
                if (r.ongoing) continue
                val t = transcripts[r.id]
                when {
                    t == null || t.stateEnum == TranscriptState.FAILED -> {
                        if (!r.isSilent && r.durationMs >= TranscriptionQueue.MIN_AUTO_MS) transcribe += r.id
                    }
                    t.stateEnum == TranscriptState.DONE && summaryEnabled -> {
                        val s = summaries[r.id]
                        if (s == null || s.stateEnum == SummaryState.FAILED) summarize += r.id
                    }
                }
            }
            return BulkPlan(transcribe, summarize)
        }
    }
}
