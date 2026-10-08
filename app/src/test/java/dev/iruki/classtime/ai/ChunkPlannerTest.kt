package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkPlannerTest {

    private val planner = ChunkPlanner()
    private val w = AudioProfile.WINDOW_MS

    /** [seconds] 동안 말소리(-20dB)와 짧은 숨(0.5초, -60dB)이 번갈아 나오는 크기 곡선. */
    private fun speechLevel(seconds: Int): FloatArray =
        FloatArray(seconds * 10) { if (it % 80 in 75..79) -60f else -20f }

    private fun min(m: Int) = m * 60_000L

    @Test
    fun nearbySpans_arePackedIntoOneChunk_withTheirGapsLeftOut() {
        val level = speechLevel(40 * 60)
        val spans = listOf(0L..min(3), min(10)..min(14), min(30)..min(31))
        val plan = planner.plan(spans, level)

        assertThat(plan).hasSize(1)
        assertThat(plan[0].pieces).isEqualTo(spans)
        assertThat(plan[0].durationMs).isEqualTo(min(8))
    }

    @Test
    fun longSpeech_isCutAtQuietMoments_withinLimits() {
        val level = speechLevel(75 * 60)
        val plan = planner.plan(listOf(0L..min(75)), level)

        assertThat(plan.size).isAtLeast(6)
        // 이어 붙이면 빈틈도 겹침도 없다.
        plan.zipWithNext { a, b -> assertThat(b.pieces.first().first).isEqualTo(a.pieces.last().last) }
        plan.forEach { c ->
            assertThat(c.durationMs).isAtMost(min(13))
            assertThat(c.durationMs).isAtLeast(ChunkPlanner.MIN_BILLED_MS)
        }
        // 자른 곳은 숨 쉬는 자리(-60dB)다.
        plan.dropLast(1).forEach { c -> assertThat(level[(c.pieces.last().last / w).toInt()]).isEqualTo(-60f) }
    }

    @Test
    fun chunkThatWouldOverflow_isFilledToTarget_thenContinues() {
        val level = speechLevel(30 * 60)
        val spans = listOf(0L..min(5), min(6)..min(20))
        val plan = planner.plan(spans, level)

        assertThat(plan).hasSize(2)
        // 첫 조각: 5분 + 둘째 구간 앞부분(목표 10분 근처까지).
        assertThat(plan[0].pieces).hasSize(2)
        assertThat(plan[0].durationMs).isIn(com.google.common.collect.Range.closed(min(7) + 30_000, min(10)))
        assertThat(plan[1].pieces.single().first).isEqualTo(plan[0].pieces.last().last)
        assertThat(plan[1].pieces.single().last).isEqualTo(min(20))
    }

    @Test
    fun tinyTail_isMergedIntoPreviousChunk() {
        val level = speechLevel(13 * 60 + 5)
        val plan = planner.plan(listOf(0L..(13 * 60 + 5) * 1000L), level)
        plan.forEach { assertThat(it.durationMs).isAtLeast(ChunkPlanner.MIN_BILLED_MS) }
    }

    @Test
    fun nothingToSend_whenNoSpeech() {
        assertThat(planner.plan(emptyList(), FloatArray(1200) { -65f })).isEmpty()
    }

    @Test
    fun chunkTimeline_mapsBackToTheRecording() {
        val c = Chunk(listOf(10_000L..20_000L, 50_000L..55_000L))
        assertThat(c.durationMs).isEqualTo(15_000L)
        assertThat(c.toSource(0)).isEqualTo(10_000L)
        assertThat(c.toSource(9_000)).isEqualTo(19_000L)
        // 이음매: 끝은 앞 구간, 시작은 뒤 구간으로.
        assertThat(c.toSource(10_000, end = true)).isEqualTo(20_000L)
        assertThat(c.toSource(10_000)).isEqualTo(50_000L)
        assertThat(c.toSource(14_000)).isEqualTo(54_000L)
        // Whisper 가 끝을 조금 넘겨 적어도 녹음 밖으로 나가지 않는다.
        assertThat(c.toSource(16_000, end = true)).isEqualTo(55_000L)
        assertThat(c.sourcePieces(8_000L..12_000L)).containsExactly(18_000L..20_000L, 50_000L..52_000L).inOrder()
    }
}
