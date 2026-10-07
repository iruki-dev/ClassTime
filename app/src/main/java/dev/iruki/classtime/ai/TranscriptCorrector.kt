package dev.iruki.classtime.ai

/**
 * Whisper 원문을 LLM 으로 교정하고 문단으로 묶는다. 프롬프트 만들기와 답 해석은 순수 계산.
 *
 * 원문을 [sectionChars] 정도씩 나눠 보낸다(무료 서버의 시간 초과를 피하고, 중간에 멈춰도
 * 끝낸 부분은 남도록). 각 부분에는 바로 앞에서 다듬은 문단을 ‘참고용’으로 함께 보내
 * 이어지는 문맥과 용어 표기를 맞춘다.
 *
 * 시각은 모델이 지키도록 줄마다 `[mm:ss]` 를 붙여 보내고, 답의 각 문단도 그 형식으로
 * 시작하게 한다. 그래서 교정된 문단을 눌러도 녹음의 그 자리로 갈 수 있다.
 */
object TranscriptCorrector {

    const val SECTION_CHARS = 3_500

    /** 원문을 교정 단위로 나눈다. 2초 이상 쉰 곳에서 끊는 것을 선호한다. */
    fun sections(segments: List<Segment>, sectionChars: Int = SECTION_CHARS): List<List<Segment>> {
        val out = mutableListOf<List<Segment>>()
        var cur = mutableListOf<Segment>()
        var chars = 0
        for ((i, s) in segments.withIndex()) {
            cur += s
            chars += s.text.length
            val next = segments.getOrNull(i + 1) ?: break
            val pause = next.startMs - s.endMs >= 2_000
            if (chars >= sectionChars || (pause && chars >= sectionChars * 0.75)) {
                out += cur
                cur = mutableListOf()
                chars = 0
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    val SYSTEM = """
        너는 대학 강의 녹취록을 다듬는 편집자다. 입력은 음성 인식이 받아 적은 강의이고, 각 줄은 [시각] 문장이다.

        할 일
        1. 잘못 받아 적은 전공 용어, 고유명사, 숫자, 영어 단어를 과목과 앞뒤 문맥에 맞게 고친다.
        2. 말한 내용을 빼거나 요약하거나 덧붙이지 않는다. 어, 음 같은 군말과 같은 말 되풀이만 정리한다.
        3. 띄어쓰기와 문장부호를 바로잡는다.
        4. 이야기 흐름이 바뀌는 곳에서 문단을 나눈다. 한 문단은 보통 2~6문장이다.

        출력 형식
        - 문단과 문단 사이는 빈 줄 하나.
        - 모든 문단은 그 문단 첫 문장의 원래 시각으로 시작한다. 예: [12:34] 문단 내용
        - 문단 말고는 아무것도 쓰지 않는다. 제목, 설명, 목록, 마크다운 금지.
    """.trimIndent()

    fun userPrompt(subject: String, professor: String, previous: String, section: List<Segment>): String = buildString {
        append("과목: ").append(subject.ifBlank { "알 수 없음" })
        if (professor.isNotBlank()) append(" (").append(professor).append(")")
        append("\n\n")
        if (previous.isNotBlank()) {
            append("앞 내용 (참고만 하고 출력하지 말 것)\n").append(previous.takeLast(600)).append("\n\n")
        }
        append("다듬을 부분\n")
        section.forEach { append('[').append(stamp(it.startMs)).append("] ").append(it.text.trim()).append('\n') }
    }

    /**
     * 모델의 답을 문단으로. 형식이 깨졌거나 내용이 크게 줄었으면(요약해 버림) null —
     * 그 부분은 한 번 더 시도하고, 그래도 안 되면 원문 문단으로 대신한다.
     */
    fun parse(answer: String, section: List<Segment>): List<Paragraph>? {
        if (section.isEmpty()) return emptyList()
        val first = section.first().startMs
        val last = section.last().endMs
        val matches = STAMP_LINE.findAll(answer).toList()
        if (matches.isEmpty()) return null

        val out = mutableListOf<Paragraph>()
        var prev = first
        for ((i, m) in matches.withIndex()) {
            val end = matches.getOrNull(i + 1)?.range?.first ?: answer.length
            val text = answer.substring(m.range.last + 1, end).lines().joinToString(" ") { it.trim() }.trim()
            if (text.isEmpty()) continue
            // 범위를 벗어나거나 거꾸로 가는 시각은 앞 문단에 맞춘다.
            val at = parseStamp(m.groupValues[1])?.takeIf { it in (first - 5_000)..(last + 5_000) } ?: prev
            val start = maxOf(at, prev)
            out += Paragraph(start, text)
            prev = start
        }
        val before = section.sumOf { letters(it.text) }
        val after = out.sumOf { letters(it.text) }
        if (out.isEmpty() || after < before * 0.55) return null
        return out
    }

    private fun letters(t: String) = t.count { it.isLetterOrDigit() }

    private val STAMP_LINE = Regex("""(?m)^\s*\[(\d{1,2}:\d{2}(?::\d{2})?)\]""")

    /** 1시간 미만은 mm:ss, 이상은 h:mm:ss. */
    fun stamp(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun parseStamp(text: String): Long? {
        val parts = text.split(':').map { it.toLongOrNull() ?: return null }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return seconds * 1000
    }
}
