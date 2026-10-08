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
 * 실제 강의 녹음으로 확인한 환각은 두 가지다.
 * - 조용한 30초 창마다 “감사합니다.”·“시청해주셔서 감사합니다.” 같은 끝인사. 확신(no_speech
 *   0.8–0.9, logprob -0.3–-0.7)이 높게 나와 품질 지표만으로는 거의 걸리지 않는다. 그래서 기기에서
 *   잰 **말소리 근거**([SpeechEvidence])를 함께 본다: 그 시간에 목소리가 없으면 지운다.
 * - 웅성거리는 조별활동에서 30초 창 하나가 “2 2 2 2”·“3.3.3”·“네.” 하나로 무너지는 경우.
 *   이런 창은 [missedWindows] 로 찾아 한 번 더 받아 적는다.
 * - 쉬는 시간 잡담 위에 길게 늘어진 엉뚱한 한 문장(20초에 “예수님의 말씀입니다.”). 이런 문장이
 *   다음 조각의 프롬프트로 넘어가면 그 조각 전체가 “. . .”로 무너지므로 반드시 지운다.
 *
 * 그 밖에는 openai/whisper 의 실패 판정값(compression_ratio > 2.4, no_speech_prob > 0.6 이면서
 * avg_logprob < -1)을 따른다.
 */
object WhisperFilter {

    private val CREDITS = listOf(
        "시청해 주셔서 감사합니다", "시청해주셔서 감사합니다", "구독과 좋아요", "구독 좋아요",
        "좋아요와 구독", "다음 영상에서 만나요", "MBC 뉴스", "KBS 뉴스", "자막 제공", "자막 by",
        "Thanks for watching", "Thank you for watching",
    )

    /**
     * 혼자 나오면 지어낸 끝인사. 무음뿐 아니라 웅성거리는 곳에서도 30초 창 끝마다 정확히 2초짜리로
     * 나오고, 앞 내용을 프롬프트로 주면 no_speech 도 0.0–0.2 로 낮게 나와 지표로 가를 수 없다.
     * 실제로 말했더라도 혼자서는 담긴 내용이 없으므로 늘 지운다.
     */
    private val THANKS = Regex("^(네[,.]? ?)?(시청해 ?주셔서 |들어 ?주셔서 )?(감사합니다|고맙습니다)[.!]?( ?네\\.?)?$")

    /** 혼자 나오면 지어냈을 가능성이 큰 짧은 말(인사·숫자·감탄사). 확신이 낮을 때만 지운다. */
    private val STOCK = Regex("^(네[,.]? ?)?안녕하세요[.!]?$|^[0-9 .,]+$|^(아|어|으|음|네|예)[.?!]?$")

    /** 이보다 말소리 근거가 적은 문장은 무음에서 지어낸 것. 실제 문장은 99% 가 0.17 이상이었다. */
    private const val MIN_SPEECH = 0.08f

    /** 이만큼 말소리 근거가 있으면 Whisper 가 놓친 것으로 보고 다시 받아 적는다. */
    private const val MISSED_SPEECH = 0.3f

    /** 8초 이상인데 초당 1자도 안 되는 문장: 시간도 내용도 믿기 어렵다. */
    internal fun isSparse(startMs: Long, endMs: Long, text: String): Boolean {
        val d = endMs - startMs
        return d >= 8_000 && text.trim().length * 1000.0 / d < 1.0
    }

    /**
     * @param evidence [segments] 와 같은 시간축의 말소리 근거. 없으면 품질 지표만 본다.
     */
    fun clean(segments: List<RawSegment>, evidence: SpeechEvidence? = null): List<Segment> {
        val kept = segments.filter { keep(it, evidence?.fraction(it.startMs, it.endMs)) }
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

    /** @param speech 그 시간 동안 말소리 후보 창의 비율([SpeechEvidence.fraction]). */
    internal fun keep(s: RawSegment, speech: Float? = null): Boolean {
        val text = s.text.trim()
        if (text.none { it.isLetterOrDigit() }) return false
        // 웅성거림에서 한국어 대신 중국어·일본어로 지어내는 경우(“開始囉。”). 한글 없이 한자·가나만 있으면 지운다.
        if (text.none { it in '가'..'힣' } && text.any { it.isCjk() }) return false
        if (s.compressionRatio > 2.4) return false
        if (speech != null && speech < MIN_SPEECH) return false
        // 길고 성긴 문장은 말소리가 많을 때만 남긴다(그때는 다시 받아 적어 바꾼다).
        if (speech != null && speech < MISSED_SPEECH && isSparse(s.startMs, s.endMs, text)) return false
        if (s.noSpeechProb > 0.6 && s.avgLogprob < -1.0) return false
        if (s.avgLogprob < -1.2) return false
        // 길이에 비해 글자가 지나치게 많으면 지어낸 것(한국어 빠른 말 ≈ 초당 8음절).
        val seconds = (s.endMs - s.startMs) / 1000.0
        if (seconds > 0 && text.length / seconds > 25) return false
        if (THANKS.matches(text)) return false
        if (STOCK.matches(text) && s.noSpeechProb > 0.5) return false
        if (CREDITS.any { text.contains(it, ignoreCase = true) } &&
            (s.noSpeechProb > 0.2 || s.avgLogprob < -0.6)
        ) return false
        return true
    }

    /**
     * 말소리가 분명히 있는데 Whisper 가 놓친 곳(조각 시간축, ms).
     *
     * 남긴 문장이 덮지 못한 6초 이상의 구간 중 말소리 근거가 30% 이상인 곳이다. Whisper 는 30초 창
     * 하나를 통째로 놓치면 “감사합니다.” 하나만 적고 나머지를 비우거나, 의미 없는 한 문장을 30초에
     * 걸쳐 늘어뜨린다(둘 다 위에서 지워진다). 앞뒤 여유를 붙이고, 30초를 넘으면 30초 안팎으로 나눈다
     * (Whisper 가 한 번에 보는 길이라 가장 안정적이다). 최소 10초(요청당 최소 과금).
     *
     * 시끄러운 녹음에서 한도를 다 쓰지 않도록 합계는 조각 길이의 1/3(최소 1분)까지만.
     */
    fun missedWindows(segments: List<RawSegment>, evidence: SpeechEvidence, lengthMs: Long): List<LongRange> {
        val covered = segments
            .filter { keep(it, evidence.fraction(it.startMs, it.endMs)) && !isSparse(it.startMs, it.endMs, it.text) }
            .map { it.startMs to it.endMs }
            .sortedBy { it.first }
        val gaps = mutableListOf<LongArray>()
        var t = 0L
        for ((a, b) in covered + (lengthMs to lengthMs)) {
            if (a > t) {
                val last = gaps.lastOrNull()
                // 1초도 안 되는 문장 하나로 끊긴 빈 곳은 하나로 본다.
                if (last != null && t - last[1] < 1_000) last[1] = a else gaps += longArrayOf(t, a)
            }
            t = maxOf(t, b)
        }

        val windows = mutableListOf<LongRange>()
        for ((a, b) in gaps.map { it[0] to it[1] }) {
            if (b - a < 6_000 || evidence.fraction(a, b) < MISSED_SPEECH) continue
            var s = (a - 2_000).coerceAtLeast(0)
            var e = (b + 1_000).coerceAtMost(lengthMs)
            val short = ChunkPlanner.MIN_BILLED_MS - (e - s)
            if (short > 0) {
                s = (s - short / 2).coerceAtLeast(0)
                e = (s + ChunkPlanner.MIN_BILLED_MS).coerceAtMost(lengthMs)
            }
            val prev = windows.lastOrNull()
            if (prev != null && s <= prev.last) windows[windows.lastIndex] = prev.first..maxOf(prev.last, e) else windows += s..e
        }

        val parts = windows.flatMap { w ->
            val n = ((w.last - w.first + RETRY_MAX_MS - 1) / RETRY_MAX_MS).coerceAtLeast(1)
            val step = (w.last - w.first) / n
            (0 until n).map { i -> (w.first + i * step)..(if (i == n - 1) w.last else w.first + (i + 1) * step) }
        }
        var left = maxOf(60_000L, lengthMs / 3)
        return parts.filter { p ->
            val cost = maxOf(p.last - p.first, ChunkPlanner.MIN_BILLED_MS)
            (cost <= left).also { if (it) left -= cost }
        }
    }

    /** 다시 받아 적을 때 한 번에 보내는 최대 길이(Whisper 한 창 남짓). */
    private const val RETRY_MAX_MS = 32_000L

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

    private fun Char.isCjk(): Boolean = this in '\u3040'..'\u30FF' || this in '\u4E00'..'\u9FFF'

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
