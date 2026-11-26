package dev.iruki.classtime.ai

import dev.iruki.classtime.data.Recording
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/*
 * 강의 요약의 모양. 모든 문장에 원문 문단 번호([refs])가 붙어 있어, 요약에서 바로 그 자리의
 * 대본과 녹음으로 건너갈 수 있다. 문단 번호는 [Transcript.paragraphs] 의 순서(0부터)다.
 */

data class SummaryItem(val text: String, val refs: List<Int>)

data class SummarySection(val heading: String, val refs: List<Int>, val points: List<SummaryItem>)

data class SummaryTerm(val term: String, val definition: String, val refs: List<Int>)

enum class NoticeKind(val key: String) {
    EXAM("exam"), ASSIGNMENT("assignment"), SCHEDULE("schedule"), EMPHASIS("emphasis"), OTHER("other");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

data class SummaryNotice(val kind: NoticeKind, val text: String, val refs: List<Int>)

data class LectureSummary(
    val title: String,
    val overview: String,
    val notices: List<SummaryNotice>,
    val sections: List<SummarySection>,
    val terms: List<SummaryTerm>,
)

/**
 * Claude 에 보내는 요청의 내용. 순수 계산.
 *
 * 토큰을 아끼려고 대본은 ‘[문단 번호] 문장’ 줄로만 보낸다(시각은 앱이 문단 번호로 찾는다).
 * 출력은 구조화 출력(JSON 스키마)으로 받아, 고쳐 읽을 필요 없이 그대로 화면에 그린다.
 */
object SummaryPrompt {

    val SYSTEM = """
        당신은 대학 강의 녹취록을 정리하는 조교입니다. 학생이 녹취록 전체를 다시 읽지 않아도 강의 내용을 빠짐없이 파악할 수 있도록, 정보 밀도가 높은 요약을 만듭니다.

        녹취록
        - 음성 인식으로 만든 것이라 오인식, 끊긴 문장, 잡담이 섞여 있습니다. 과목과 앞뒤 맥락으로 분명한 오인식은 바로잡되, 확인할 수 없는 내용을 지어내지 않습니다. 알아들을 수 없는 부분은 건너뜁니다.
        - 각 줄 앞의 [번호]는 문단 번호입니다.

        담을 것
        - 강의에서 다룬 개념, 정의, 공식, 수치, 예시, 절차, 비교, 주장과 그 근거를 모두 담습니다. 같은 말의 반복, 인사, 출석, 잡담, 수업과 무관한 대화는 뺍니다.
        - 시험·과제·일정·공지, 그리고 교수가 특히 강조한 것("중요하다", "시험에 나온다" 등)은 notices 에 따로 모읍니다. 실제로 말한 것만 담습니다.
        - sections 는 강의가 진행된 순서대로 주제별로 나눕니다. 각 point 는 한 가지 사실을 담은 짧은 개조식 문장("~함", "~임")입니다. 문장은 짧게, 정보는 빠짐없이.
        - terms 에는 강의에서 정의하거나 설명한 용어를 담습니다. 원어가 나오면 함께 적습니다.
        - refs 에는 그 내용이 나온 문단 번호를 가장 관련 깊은 것부터 1~3개 적습니다. 학생이 원문으로 바로 건너가는 데 쓰이므로 정확하게 고릅니다. section 의 refs 는 그 주제가 시작된 문단입니다.
        - title 은 이 강의의 주제 한 줄, overview 는 강의 전체 흐름을 2~4문장으로 씁니다.
        - 강의 내용이 거의 없으면(쉬는 시간, 시험 시간 등) 있는 만큼만 짧게 쓰고, 해당 없는 배열은 비워 둡니다.

        한국어로 쓰되, 전문 용어와 고유명사는 강의에서 쓴 표기를 따릅니다.
    """.trimIndent()

    fun user(recording: Recording, paragraphs: List<Paragraph>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("과목: ").append(recording.subject)
        if (recording.professor.isNotBlank()) append(" · 교수: ").append(recording.professor)
        append(" · 날짜: ").append(DATE.format(Instant.ofEpochMilli(recording.startedAt).atZone(zone)))
        append("\n\n녹취록:\n")
        paragraphs.forEachIndexed { i, p -> append('[').append(i).append("] ").append(p.text.trim()).append('\n') }
    }.trimEnd()

    /** 구조화 출력 스키마. 모든 객체는 닫혀 있고 모든 필드가 필수다. */
    fun schema(): Map<String, Any> {
        fun str() = mapOf("type" to "string")
        val refs = mapOf("type" to "array", "items" to mapOf("type" to "integer"))
        fun obj(vararg props: Pair<String, Any>) = mapOf(
            "type" to "object",
            "properties" to mapOf(*props),
            "required" to props.map { it.first },
            "additionalProperties" to false,
        )
        fun list(item: Any) = mapOf("type" to "array", "items" to item)
        return obj(
            "title" to str(),
            "overview" to str(),
            "notices" to list(
                obj(
                    "kind" to mapOf("type" to "string", "enum" to NoticeKind.entries.map { it.key }),
                    "text" to str(),
                    "refs" to refs,
                )
            ),
            "sections" to list(
                obj(
                    "heading" to str(),
                    "refs" to refs,
                    "points" to list(obj("text" to str(), "refs" to refs)),
                )
            ),
            "terms" to list(obj("term" to str(), "definition" to str(), "refs" to refs)),
        )
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}

/** 요약 JSON 을 읽고 쓴다. 저장 형식은 Claude 의 출력 형식과 같다. */
object SummaryJson {

    /**
     * Claude 의 출력(또는 저장한 JSON)을 읽는다. 있지도 않은 문단 번호는 버린다.
     * @param paragraphCount 대본 문단 수. 0 이하면 번호를 거르지 않는다.
     */
    fun parse(json: String, paragraphCount: Int = 0): LectureSummary {
        val o = JSONObject(json)
        fun refs(a: JSONArray?): List<Int> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { n -> n >= 0 && (paragraphCount <= 0 || n < paragraphCount) } }
                .distinct()
        }
        fun <T> items(a: JSONArray?, read: (JSONObject) -> T?): List<T> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(read) }

        return LectureSummary(
            title = o.optString("title").trim(),
            overview = o.optString("overview").trim(),
            notices = items(o.optJSONArray("notices")) { n ->
                n.optString("text").trim().takeIf { it.isNotEmpty() }
                    ?.let { SummaryNotice(NoticeKind.of(n.optString("kind")), it, refs(n.optJSONArray("refs"))) }
            },
            sections = items(o.optJSONArray("sections")) { s ->
                SummarySection(
                    heading = s.optString("heading").trim(),
                    refs = refs(s.optJSONArray("refs")),
                    points = items(s.optJSONArray("points")) { p ->
                        p.optString("text").trim().takeIf { it.isNotEmpty() }?.let { SummaryItem(it, refs(p.optJSONArray("refs"))) }
                    },
                ).takeIf { it.heading.isNotEmpty() || it.points.isNotEmpty() }
            },
            terms = items(o.optJSONArray("terms")) { t ->
                t.optString("term").trim().takeIf { it.isNotEmpty() }
                    ?.let { SummaryTerm(it, t.optString("definition").trim(), refs(t.optJSONArray("refs"))) }
            },
        )
    }

    fun encode(s: LectureSummary): String {
        fun refs(r: List<Int>) = JSONArray(r)
        return JSONObject()
            .put("title", s.title)
            .put("overview", s.overview)
            .put("notices", JSONArray(s.notices.map { JSONObject().put("kind", it.kind.key).put("text", it.text).put("refs", refs(it.refs)) }))
            .put(
                "sections",
                JSONArray(
                    s.sections.map { sec ->
                        JSONObject().put("heading", sec.heading).put("refs", refs(sec.refs))
                            .put("points", JSONArray(sec.points.map { JSONObject().put("text", it.text).put("refs", refs(it.refs)) }))
                    }
                ),
            )
            .put("terms", JSONArray(s.terms.map { JSONObject().put("term", it.term).put("definition", it.definition).put("refs", refs(it.refs)) }))
            .toString()
    }

    /**
     * 파일·복사용 글. 문장마다 원문 시각 [mm:ss] 를 붙여, 녹음에서 그 자리를 찾을 수 있게 한다.
     * @param startOf 문단 번호 → 그 문단의 시작 시각(ms).
     */
    fun format(recording: Recording, s: LectureSummary, startOf: (Int) -> Long?): String = buildString {
        fun at(refs: List<Int>) = refs.firstOrNull()?.let(startOf)?.let { " [" + Timestamps.format(it) + "]" }.orEmpty()
        append(recording.fileName.substringBeforeLast('.', recording.fileName)).append(" · 요약\n\n")
        if (s.title.isNotBlank()) append(s.title).append("\n\n")
        if (s.overview.isNotBlank()) append(s.overview).append("\n\n")
        if (s.notices.isNotEmpty()) {
            append("■ 공지·강조\n")
            s.notices.forEach { append("- ").append(it.text).append(at(it.refs)).append('\n') }
            append('\n')
        }
        s.sections.forEach { sec ->
            append("■ ").append(sec.heading).append(at(sec.refs)).append('\n')
            sec.points.forEach { append("- ").append(it.text).append(at(it.refs)).append('\n') }
            append('\n')
        }
        if (s.terms.isNotEmpty()) {
            append("■ 용어\n")
            s.terms.forEach { append("- ").append(it.term).append(": ").append(it.definition).append(at(it.refs)).append('\n') }
        }
    }.trimEnd() + "\n"
}
