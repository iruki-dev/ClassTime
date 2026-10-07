package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkPlannerTest {

    private val planner = ChunkPlanner()
    private val w = ChunkPlanner.WINDOW_MS

    /** [seconds] 동안 말소리(-20dB)와 짧은 숨(0.5초, -60dB)이 번갈아 나오는 곡선. */
    private fun speech(seconds: Int): List<Float> =
        (0 until seconds * 10).map { if (it % 80 in 75..79) -60f else -20f }

    private fun silence(seconds: Int): List<Float> = List(seconds * 10) { -65f }

    @Test
    fun longSilence_isSkipped_andPadded() {
        // 5분 강의 + 10분 쉬는 시간 + 5분 강의
        val env = (speech(300) + silence(600) + speech(300)).toFloatArray()
        val plan = planner.plan(env)

        assertThat(plan).hasSize(2)
        assertThat(plan[0].first).isEqualTo(0L)
        assertThat(plan[0].last).isAtLeast(300_000L) // 끝에 여유
        assertThat(plan[0].last).isAtMost(301_000L)
        assertThat(plan[1].first).isAtMost(900_000L)
        assertThat(plan[1].first).isAtLeast(899_000L)
        // 보내는 소리는 전체의 절반 남짓
        val sent = plan.sumOf { it.last - it.first }
        assertThat(sent).isLessThan(env.size * w * 6 / 10)
    }

    @Test
    fun shortPause_isKept() {
        val env = (speech(60) + silence(5) + speech(60)).toFloatArray()
        assertThat(planner.plan(env)).hasSize(1)
    }

    @Test
    fun longSpeech_isCutAtQuietMoments_withinLimits() {
        val env = speech(75 * 60).toFloatArray()
        val plan = planner.plan(env)

        assertThat(plan.size).isAtLeast(6)
        // 이어 붙이면 빈틈도 겹침도 없다.
        plan.zipWithNext { a, b -> assertThat(b.first).isEqualTo(a.last) }
        plan.forEach { r ->
            assertThat(r.last - r.first).isAtMost(13 * 60_000L)
            assertThat(r.last - r.first).isAtLeast(ChunkPlanner.MIN_BILLED_MS)
        }
        // 자른 곳은 숨 쉬는 자리(-60dB)다.
        plan.dropLast(1).forEach { r ->
            assertThat(env[(r.last / w).toInt()]).isEqualTo(-60f)
        }
    }

    @Test
    fun tinyTail_isMergedIntoPreviousChunk() {
        val env = speech(13 * 60 + 5).toFloatArray()
        val plan = planner.plan(env)
        plan.forEach { assertThat(it.last - it.first).isAtLeast(ChunkPlanner.MIN_BILLED_MS) }
    }

    @Test
    fun allSilent_hasNothingToSend() {
        assertThat(planner.plan(silence(120).toFloatArray())).isEmpty()
    }

    @Test
    fun threshold_followsRoomNoise_butProtectsQuietVoices() {
        val noisy = FloatArray(1000) { if (it < 200) -42f else -20f }
        assertThat(planner.silenceThreshold(noisy)).isEqualTo(-35f)
        val quiet = FloatArray(1000) { if (it < 200) -70f else -30f }
        assertThat(planner.silenceThreshold(quiet)).isEqualTo(-60f)
    }
}
