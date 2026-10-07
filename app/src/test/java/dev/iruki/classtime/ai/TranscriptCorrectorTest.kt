package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 이 필요해 Robolectric 에서 돈다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptCorrectorTest {

    private val section = listOf(
        Segment(61_000, 64_000, "오늘은 힙 정렬에 대해서"),
        Segment(64_000, 68_000, "어 힙 소트는 완전 이진 트리를 써요"),
        Segment(70_000, 74_000, "그 다음 시간 복잡도를 볼게요"),
    )

    @Test
    fun stamps_roundTrip() {
        assertThat(TranscriptCorrector.stamp(61_000)).isEqualTo("01:01")
        assertThat(TranscriptCorrector.stamp(3_725_000)).isEqualTo("1:02:05")
        assertThat(TranscriptCorrector.parseStamp("1:02:05")).isEqualTo(3_725_000L)
        assertThat(TranscriptCorrector.parseStamp("01:01")).isEqualTo(61_000L)
    }

    @Test
    fun prompt_carriesTimesAndContext() {
        val p = TranscriptCorrector.userPrompt("자료구조", "김교수", "앞 문단", section)
        assertThat(p).contains("과목: 자료구조 (김교수)")
        assertThat(p).contains("[01:01] 오늘은 힙 정렬에 대해서")
        assertThat(p).contains("앞 내용")
    }

    @Test
    fun parse_readsParagraphsWithTimes() {
        val answer = """
            [01:01] 오늘은 힙 정렬에 대해서, 힙 정렬은 완전 이진 트리를 써요.

            [01:10] 그다음 시간 복잡도를 볼게요.
        """.trimIndent()
        val out = TranscriptCorrector.parse(answer, section)!!
        assertThat(out.map { it.startMs }).containsExactly(61_000L, 70_000L).inOrder()
        assertThat(out[1].text).isEqualTo("그다음 시간 복잡도를 볼게요.")
    }

    @Test
    fun parse_rejectsSummaries_andMissingFormat() {
        assertThat(TranscriptCorrector.parse("[01:01] 힙 정렬.", section)).isNull()
        assertThat(TranscriptCorrector.parse("오늘은 힙 정렬에 대해서 배웠습니다. 완전 이진 트리를 씁니다. 시간 복잡도도 봅니다.", section)).isNull()
    }

    @Test
    fun parse_clampsOutOfRangeOrBackwardTimes() {
        val answer = "[09:59] 오늘은 힙 정렬에 대해서, 힙 정렬은 완전 이진 트리를 써요.\n\n[00:30] 그다음 시간 복잡도를 볼게요."
        val out = TranscriptCorrector.parse(answer, section)!!
        assertThat(out.map { it.startMs }).containsExactly(61_000L, 61_000L).inOrder()
    }

    @Test
    fun sections_preferPauses_andCoverEverything() {
        val segs = (0 until 200).map { Segment(it * 5_000L, it * 5_000L + 4_000, "가".repeat(40)) }
        val out = TranscriptCorrector.sections(segs, sectionChars = 1_000)
        assertThat(out.flatten()).isEqualTo(segs)
        out.dropLast(1).forEach { s -> assertThat(s.sumOf { it.text.length }).isAtMost(1_000) }
    }

    @Test
    fun stripsReasoningTags() {
        assertThat(NvidiaClient.stripThinking("<think>생각</think>\n[00:01] 본문")).isEqualTo("[00:01] 본문")
        assertThat(NvidiaClient.stripThinking("생각 중...</think>[00:01] 본문")).isEqualTo("[00:01] 본문")
    }

    @Test
    fun groqVerboseJson_isParsed() {
        val body = """{"text":"x","segments":[{"start":1.5,"end":3.25,"text":" 안녕하세요","avg_logprob":-0.2,"no_speech_prob":0.01,"compression_ratio":1.3}]}"""
        val s = GroqClient.parse(body).single()
        assertThat(s.startMs).isEqualTo(1_500)
        assertThat(s.endMs).isEqualTo(3_250)
        assertThat(s.noSpeechProb).isEqualTo(0.01)
    }

    @Test
    fun json_roundTrips() {
        val plan = listOf(0L..600_000L, 600_000L..1_200_000L)
        assertThat(TranscriptJson.plan(TranscriptJson.plan(plan))).isEqualTo(plan)
        assertThat(TranscriptJson.segments(TranscriptJson.segments(section))).isEqualTo(section)
        val p = listOf(Paragraph(1, "a \"b\"\n"))
        assertThat(TranscriptJson.paragraphs(TranscriptJson.paragraphs(p))).isEqualTo(p)
    }

    @Test
    fun retryHeaders_areParsed() {
        assertThat(AiHttp.parseDuration("7m12.5s")).isEqualTo(432_500)
        assertThat(AiHttp.parseDuration("450ms")).isEqualTo(450)
        assertThat(AiHttp.parseDuration("1h")).isEqualTo(3_600_000)
    }
}
