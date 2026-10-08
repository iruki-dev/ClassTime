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
    fun dropsConfidentThanksWhereThereIsNoVoice() {
        // 실제 쪽지시험 녹음: no_speech 0.9, logprob -0.4 로 품질 지표만으로는 못 거른다.
        val thanks = seg("감사합니다.", 60_000, 62_000, logprob = -0.43, noSpeech = 0.9, ratio = 0.6)
        val silent = SpeechEvidence(BooleanArray(1200))
        assertThat(WhisperFilter.clean(listOf(thanks), silent)).isEmpty()
        // 혼자 나온 끝인사는 말소리 근거·확신과 상관없이 지운다(조별활동 30초 창 끝마다 2초짜리로 나온다).
        assertThat(WhisperFilter.keep(thanks, speech = 0.4f)).isFalse()
        assertThat(WhisperFilter.keep(seg("감사합니다.", 0, 2_000, noSpeech = 0.05, logprob = -0.2), speech = 0.5f)).isFalse()
        assertThat(WhisperFilter.keep(seg(". . .", noSpeech = 0.05), speech = 0.5f)).isFalse()
        // 웅성거림에서 다른 언어로 지어낸 것. 한글 문장 속 한자는 남긴다.
        assertThat(WhisperFilter.keep(seg("開始囉。 開始囉。"), speech = 0.5f)).isFalse()
        assertThat(WhisperFilter.keep(seg("同時に? 同時に?"), speech = 0.5f)).isFalse()
        assertThat(WhisperFilter.keep(seg("오늘은 韓非子 의 법가 사상"), speech = 0.5f)).isTrue()
        assertThat(WhisperFilter.keep(seg("2", noSpeech = 0.72, logprob = -1.1), speech = 0.5f)).isFalse()
        // 쉬는 시간 잡담 위에 18초로 늘어진 엉뚱한 문장.
        assertThat(WhisperFilter.keep(seg("예수님의 말씀입니다.", 0, 18_000, noSpeech = 0.2), speech = 0.17f)).isFalse()
        // 실제로 말한 인사(말소리 있음, no_speech 낮음)는 남긴다.
        assertThat(WhisperFilter.keep(seg("안녕하세요.", noSpeech = 0.1), speech = 0.6f)).isTrue()
    }

    @Test
    fun dropsSentenceInventedOverSilence() {
        val invented = seg("3. 상품권을 구매하여 구매하실 수 있습니다.", 0, 30_000, logprob = -0.4, noSpeech = 0.79)
        val evidence = SpeechEvidence(BooleanArray(300) { it < 15 }) // 30초 중 1.5초만 소리
        assertThat(WhisperFilter.clean(listOf(invented), evidence)).isEmpty()
        val spoken = seg("원고지 첫 줄에 제목을 쓰고", 0, 3_000)
        assertThat(WhisperFilter.clean(listOf(spoken), SpeechEvidence(BooleanArray(30) { it % 3 != 0 }))).hasSize(1)
    }

    @Test
    fun missedWindows_findCollapsedWindowsWithVoiceOnly() {
        // 조별활동: 30초 창이 “네.” 하나로 무너짐(말소리 근거 50%) → 다시 받아 적을 곳(30초 안팎으로 나눔).
        val voice = SpeechEvidence(BooleanArray(400) { it % 2 == 0 })
        val collapsed = listOf(
            seg("근데 히스토그램하고 박스 플롯을 둘 다 하면 되는 거죠?", 0, 5_000),
            seg("네.", 5_000, 35_000, logprob = -1.19, noSpeech = 0.68, ratio = 0.4),
            seg("막대 모양만 맞추는 게 좋지 않을까요?", 35_000, 40_000),
        )
        assertThat(WhisperFilter.missedWindows(collapsed, voice, 40_000))
            .containsExactly(3_000L..19_500L, 19_500L..36_000L).inOrder()

        // 같은 모양이라도 실제로 조용한 곳(시험 시간)이면 다시 보내지 않는다.
        val quiet = SpeechEvidence(BooleanArray(400) { it % 10 == 0 })
        assertThat(WhisperFilter.missedWindows(collapsed, quiet, 40_000)).isEmpty()
    }

    @Test
    fun missedWindows_coverLoopsAndEmptyStretchesAfterAFakeThanks() {
        val voice = SpeechEvidence(BooleanArray(600) { it % 2 == 0 })
        val before = (0 until 10).map { seg("앞에서 하던 이야기 $it 번째", it * 5_000L, (it + 1) * 5_000L) }
        // “2 2 2” 루프는 하나로 묶고, 짧으면 최소 10초로 넓힌다.
        val loop = (0 until 3).map { seg("2", 50_000L + it * 2_000, 52_000L + it * 2_000, logprob = -1.23, noSpeech = 0.72) }
        val after = listOf(seg("뒤 이야기 계속합니다", 56_000, 60_000))
        assertThat(WhisperFilter.missedWindows(before + loop + after, voice, 60_000)).containsExactly(47_500L..57_500L)

        // 프롬프트가 있으면 지어낸 “감사합니다.”의 no_speech 가 낮다. 그 뒤가 비어 있으면 놓친 곳.
        val thanks = seg("감사합니다.", 50_000, 52_000, logprob = -0.2, noSpeech = 0.07)
        assertThat(WhisperFilter.missedWindows(before + thanks, voice, 60_000)).containsExactly(48_000L..60_000L)
    }

    @Test
    fun missedWindows_spendAtMostAThirdOfTheChunk() {
        val voice = SpeechEvidence(BooleanArray(3_000) { true })
        val windows = WhisperFilter.missedWindows(listOf(seg("네.", 0, 2_000, noSpeech = 0.9)), voice, 300_000)
        assertThat(windows).isNotEmpty()
        windows.forEach { assertThat(it.last - it.first).isAtMost(32_000L) }
        assertThat(windows.sumOf { it.last - it.first }).isAtMost(100_000L)
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
