package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WhisperFilterTest {

    private fun seg(text: String, start: Long = 0, end: Long = 4_000, logprob: Double = -0.3, noSpeech: Double = 0.02, ratio: Double = 1.5) =
        RawSegment(start, end, text, logprob, noSpeech, ratio)

    @Test
    fun keepsNormalLectureSpeech() {
        val out = WhisperFilter.clean(listOf(seg("오늘은 힙 정렬을 배워 보겠습니다.")))
        assertThat(out.single().text).isEqualTo("오늘은 힙 정렬을 배워 보겠습니다.")
    }

    @Test
    fun dropsCreditsHallucinationOnSilence() {
        val out = WhisperFilter.clean(listOf(seg("시청해 주셔서 감사합니다.", noSpeech = 0.7, logprob = -1.1)))
        assertThat(out).isEmpty()
    }

    @Test
    fun keepsRealThanksAtTheEndOfClass() {
        val out = WhisperFilter.clean(listOf(seg("네, 오늘 수업 들어 주셔서 감사합니다.")))
        assertThat(out).hasSize(1)
    }

    @Test
    fun dropsRepetitionLoops() {
        assertThat(WhisperFilter.keep(seg("감사합니다 ".repeat(30), ratio = 6.0))).isFalse()
        val out = WhisperFilter.clean(
            listOf(
                seg("다음 시간에 계속하겠습니다.", 0, 2_000),
                seg("다음 시간에 계속하겠습니다", 2_000, 4_000),
            )
        )
        assertThat(out).hasSize(1)
    }

    @Test
    fun dropsTooManyCharactersForTheTime() {
        assertThat(WhisperFilter.keep(seg("가".repeat(80), 0, 1_000))).isFalse()
    }

    @Test
    fun collapsesWordRuns() {
        assertThat(WhisperFilter.collapseRepeats("네 네 네 네 네 네 알겠습니다")).isEqualTo("네 네 네 알겠습니다")
    }

    @Test
    fun paragraphs_breakOnPauses() {
        val p = Paragraphs.fromSegments(
            listOf(
                Segment(0, 3_000, "첫 문장."),
                Segment(3_200, 6_000, "둘째 문장."),
                Segment(9_000, 12_000, "다른 이야기."),
            )
        )
        assertThat(p.map { it.startMs }).containsExactly(0L, 9_000L).inOrder()
        assertThat(p.first().text).isEqualTo("첫 문장. 둘째 문장.")
    }
}
