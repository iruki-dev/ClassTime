package dev.iruki.classtime.ai

/** Whisper 의 verbose_json 한 문장 + 품질 지표. */
data class RawSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val avgLogprob: Double = 0.0,
    val noSpeechProb: Double = 0.0,
    val compressionRatio: Double = 1.0,
)

/**
 * Whisper large-v3 가 무음·잡음에서 지어내는 문장과 반복 루프를 걸러 낸다. 순수 계산.
 *
 * 기준은 openai/whisper 의 실패 판정값(compression_ratio > 2.4, no_speech_prob > 0.6 이면서
 * avg_logprob < -1)을 따르고, 한국어에서 흔한 ‘끝인사’ 환각은 확신이 낮을 때만 지운다
 * (실제로 수업 끝에 “감사합니다”라고 말할 수도 있으므로).
 */
object WhisperFilter {

    private val CREDITS = listOf(
        "시청해 주셔서 감사합니다", "시청해주셔서 감사합니다", "구독과 좋아요", "구독 좋아요",
        "좋아요와 구독", "다음 영상에서 만나요", "MBC 뉴스", "KBS 뉴스", "자막 제공", "자막 by",
        "Thanks for watching", "Thank you for watching",
    )

    fun clean(segments: List<RawSegment>): List<Segment> {
        val kept = segments.filter { keep(it) }
        val out = mutableListOf<Segment>()
        for (s in kept) {
            val text = collapseRepeats(s.text.trim())
            if (text.isEmpty()) continue
            // 바로 앞과 같은 문장이 되풀이되면 하나만(루프).
            if (out.isNotEmpty() && normalize(out.last().text) == normalize(text)) continue
            out += Segment(s.startMs, s.endMs, text)
        }
        return out
    }

    internal fun keep(s: RawSegment): Boolean {
        val text = s.text.trim()
        if (text.isEmpty()) return false
        if (s.compressionRatio > 2.4) return false
        if (s.noSpeechProb > 0.6 && s.avgLogprob < -1.0) return false
        if (s.avgLogprob < -1.2) return false
        // 길이에 비해 글자가 지나치게 많으면 지어낸 것(한국어 빠른 말 ≈ 초당 8음절).
        val seconds = (s.endMs - s.startMs) / 1000.0
        if (seconds > 0 && text.length / seconds > 25) return false
        if (CREDITS.any { text.contains(it, ignoreCase = true) } &&
            (s.noSpeechProb > 0.2 || s.avgLogprob < -0.6)
        ) return false
        return true
    }

    /** “네 네 네 네 네 네” 같은 같은 말 반복을 세 번까지만 남긴다. */
    internal fun collapseRepeats(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return text
        val out = mutableListOf<String>()
        var run = 0
        for (w in words) {
            run = if (out.isNotEmpty() && out.last() == w) run + 1 else 1
            if (run <= 3) out += w
        }
        return out.joinToString(" ")
    }

    private fun normalize(t: String) = t.filter { it.isLetterOrDigit() }
}

/** 교정 없이 원문을 읽기 좋게: 2초 이상 쉬거나 길어지면 문단을 나눈다. */
object Paragraphs {

    fun fromSegments(segments: List<Segment>, pauseMs: Long = 2_000, maxChars: Int = 360): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var start = -1L
        val buf = StringBuilder()
        var lastEnd = 0L
        for (s in segments) {
            val breakHere = buf.isNotEmpty() &&
                (s.startMs - lastEnd >= pauseMs || buf.length + s.text.length > maxChars)
            if (breakHere) {
                out += Paragraph(start, buf.toString())
                buf.clear()
            }
            if (buf.isEmpty()) start = s.startMs else buf.append(' ')
            buf.append(s.text.trim())
            lastEnd = s.endMs
        }
        if (buf.isNotEmpty()) out += Paragraph(start, buf.toString())
        return out
    }
}
