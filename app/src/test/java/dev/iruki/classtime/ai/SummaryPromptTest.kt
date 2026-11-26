package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.Recording
import java.time.ZoneId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 이 필요해 Robolectric 에서 돈다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SummaryPromptTest {

    private val rec = Recording(
        id = 1, subject = "자료구조", professor = "김교수", fileName = "자료구조_2026-03-04_0930.m4a",
        uri = "content://x/1", relativePath = "Music/ClassTime/자료구조",
        startedAt = 1_772_584_200_000L, durationMs = 75 * 60_000L, sizeBytes = 1,
    )
    private val paragraphs = listOf(
        Paragraph(0, "안녕하세요 오늘은 힙 정렬을 배웁니다."),
        Paragraph(61_000, "힙은 완전 이진 트리이고 부모가 자식보다 크거나 같아요."),
        Paragraph(125_000, "다음 주 화요일에 쪽지시험 봅니다. 힙 정렬 나와요."),
    )

    @Test
    fun user_sendsOnlyNumberedLines_noTimes() {
        val u = SummaryPrompt.user(rec, paragraphs, ZoneId.of("Asia/Seoul"))
        assertThat(u).startsWith("과목: 자료구조 · 교수: 김교수 · 날짜: 2026-03-04\n\n녹취록:\n")
        assertThat(u).contains("[0] 안녕하세요 오늘은 힙 정렬을 배웁니다.\n[1] 힙은")
        assertThat(u).endsWith("[2] 다음 주 화요일에 쪽지시험 봅니다. 힙 정렬 나와요.")
        // 시각은 보내지 않는다(앱이 문단 번호로 찾는다) — 토큰 절약.
        assertThat(u).doesNotContain("01:01")
    }

    @Test
    fun schema_isClosedAndFullyRequired_everywhere() {
        fun check(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    if (node["type"] == "object") {
                        assertThat(node["additionalProperties"]).isEqualTo(false)
                        val props = node["properties"] as Map<*, *>
                        assertThat(node["required"] as List<*>).containsExactlyElementsIn(props.keys)
                    }
                    node.values.forEach(::check)
                }
                is List<*> -> node.forEach(::check)
            }
        }
        val schema = SummaryPrompt.schema()
        check(schema)
        assertThat((schema["properties"] as Map<*, *>).keys).containsExactly("title", "overview", "notices", "sections", "terms")
    }

    @Test
    fun parse_dropsRefsThatDoNotExist_andEmptyItems() {
        val json = """
            {"title":"힙 정렬","overview":"힙과 힙 정렬을 다룸.",
             "notices":[{"kind":"exam","text":"다음 주 화요일 쪽지시험(힙 정렬)","refs":[2, 9]},{"kind":"weird","text":"기타","refs":[]},{"kind":"other","text":" ","refs":[]}],
             "sections":[{"heading":"힙","refs":[1],"points":[{"text":"완전 이진 트리, 부모 ≥ 자식","refs":[1,1,-1]}]},{"heading":"","refs":[],"points":[]}],
             "terms":[{"term":"힙(heap)","definition":"부모가 자식보다 크거나 같은 완전 이진 트리","refs":[1]}]}
        """.trimIndent()
        val s = SummaryJson.parse(json, paragraphCount = 3)
        assertThat(s.notices.map { it.kind }).containsExactly(NoticeKind.EXAM, NoticeKind.OTHER).inOrder()
        assertThat(s.notices.first().refs).containsExactly(2)
        assertThat(s.sections).hasSize(1)
        assertThat(s.sections.single().points.single().refs).containsExactly(1)
        assertThat(SummaryJson.parse(SummaryJson.encode(s), 3)).isEqualTo(s)
    }

    @Test
    fun format_putsOriginalTimesNextToEveryLine() {
        val s = LectureSummary(
            title = "힙 정렬", overview = "힙과 힙 정렬.",
            notices = listOf(SummaryNotice(NoticeKind.EXAM, "다음 주 쪽지시험", listOf(2))),
            sections = listOf(SummarySection("힙", listOf(1), listOf(SummaryItem("완전 이진 트리", listOf(1)), SummaryItem("번호 없음", emptyList())))),
            terms = listOf(SummaryTerm("힙", "완전 이진 트리", listOf(1))),
        )
        val text = SummaryJson.format(rec, s) { paragraphs.getOrNull(it)?.startMs }
        assertThat(text).contains("■ 공지·강조\n- 다음 주 쪽지시험 [02:05]")
        assertThat(text).contains("■ 힙 [01:01]\n- 완전 이진 트리 [01:01]\n- 번호 없음\n")
        assertThat(text).contains("■ 용어\n- 힙: 완전 이진 트리 [01:01]")
    }

    @Test
    fun batchCost_isHalfThePrice() {
        // Opus 5.5: $4/$20 → 배치 $2/$10 per MTok.
        assertThat(SummaryModel.OPUS.batchCost(1_000_000, 0)).isWithin(1e-9).of(2.0)
        assertThat(SummaryModel.OPUS.batchCost(0, 1_000_000)).isWithin(1e-9).of(10.0)
        assertThat(SummaryModel.HAIKU.batchCost(1_000_000, 1_000_000)).isWithin(1e-9).of(0.5 * (0.5 + 2.5))
        assertThat(SummaryModel.of("unknown")).isEqualTo(SummaryModel.OPUS)
    }
}
