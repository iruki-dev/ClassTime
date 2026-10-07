package dev.iruki.classtime.ai

import kotlin.math.max
import kotlin.math.min

/**
 * 소리 크기 곡선(envelope)으로 Whisper 에 보낼 조각을 정한다. 순수 계산.
 *
 * 1. **긴 무음은 아예 보내지 않는다.** 쉬는 시간·시험 시간처럼 [skipSilenceMs] 이상 조용한
 *    구간은 빼고, 앞뒤로 [padMs] 만 남긴다. 무료 한도(소리 초 단위)를 아끼고, Whisper 가
 *    무음에서 지어내는 문장(“시청해 주셔서 감사합니다” 류)도 원천적으로 줄인다.
 * 2. **조각은 말이 끊긴 곳에서 자른다.** 목표 길이 근처에서 가장 조용한 곳을 고르므로
 *    단어가 잘리지 않고, 그래서 조각끼리 겹쳐 보낼 필요도 없다.
 *
 * @param envelope [windowMs] 마다의 소리 크기(dBFS, 0 이하). 길이 × windowMs = 녹음 길이.
 */
class ChunkPlanner(
    private val windowMs: Long = WINDOW_MS,
    private val targetMs: Long = 10 * 60_000L,
    private val maxMs: Long = 13 * 60_000L,
    /** 목표 길이에서 이만큼 앞까지 자를 곳을 찾는다. */
    private val searchMs: Long = 3 * 60_000L,
    private val skipSilenceMs: Long = 20_000L,
    private val padMs: Long = 800L,
    /** 이보다 짧은 말소리 덩어리는 버린다(기침, 문 닫는 소리). */
    private val minSpeechMs: Long = 1_000L,
) {

    fun plan(envelope: FloatArray): List<LongRange> {
        if (envelope.isEmpty()) return emptyList()
        val threshold = silenceThreshold(envelope)
        val speech = speechSpans(envelope, threshold)
        return speech.flatMap { split(it, envelope) }
    }

    /**
     * 무음 기준. 강의실마다 바닥 소음이 달라 고정값을 쓰지 않는다: 가장 조용한 10% 를
     * 바닥으로 보고 그보다 10dB 위까지를 무음으로 친다. 단 -35dBFS 를 넘지는 않게(작은 목소리 보호).
     */
    internal fun silenceThreshold(envelope: FloatArray): Float {
        val sorted = envelope.sortedArray()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
        return min(floor + 10f, -35f)
    }

    /** 긴 무음을 뺀 말소리 구간들(ms). */
    internal fun speechSpans(envelope: FloatArray, threshold: Float): List<LongRange> {
        val total = envelope.size * windowMs
        val quiet = envelope.map { it <= threshold }
        val skipWindows = (skipSilenceMs / windowMs).toInt()

        // 긴 무음 구간을 찾는다.
        val gaps = mutableListOf<IntRange>()
        var i = 0
        while (i < quiet.size) {
            if (!quiet[i]) { i++; continue }
            var j = i
            while (j < quiet.size && quiet[j]) j++
            if (j - i >= skipWindows) gaps += i until j
            i = j
        }

        // 무음 사이가 말소리. 앞뒤 여유를 두고, 너무 짧은 것은 버린다.
        val spans = mutableListOf<LongRange>()
        var start = 0L
        for (g in gaps) {
            val end = g.first * windowMs
            addSpan(spans, start, end, total)
            start = (g.last + 1) * windowMs
        }
        addSpan(spans, start, total, total)
        return spans
    }

    private fun addSpan(out: MutableList<LongRange>, start: Long, end: Long, total: Long) {
        if (end - start < minSpeechMs) return
        out += max(0L, start - padMs)..min(total, end + padMs)
    }

    /** 한 말소리 구간을 목표 길이 근처의 조용한 곳에서 자른다. */
    private fun split(span: LongRange, envelope: FloatArray): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var start = span.first
        val end = span.last
        while (end - start > maxMs) {
            val from = start + targetMs - searchMs
            val to = start + targetMs
            val cut = quietestPoint(envelope, from, to)
            out += start..cut
            start = cut
        }
        // 마지막 조각이 너무 짧으면(10초 미만) 앞 조각에 붙인다 — 요청마다 최소 10초로 계산된다.
        if (out.isNotEmpty() && end - start < MIN_BILLED_MS) {
            val last = out.removeAt(out.lastIndex)
            out += last.first..end
        } else {
            out += start..end
        }
        return out
    }

    /**
     * [from, to] 안에서 가장 조용한 지점. 한 창이 아니라 1초 평균으로 보아, 순간적인 틈보다
     * 말과 말 사이의 숨 쉬는 자리를 고른다. 같으면 목표(to)에 가까운 쪽.
     */
    internal fun quietestPoint(envelope: FloatArray, from: Long, to: Long): Long {
        val span = (1_000L / windowMs).toInt().coerceAtLeast(1)
        val a = (from / windowMs).toInt().coerceIn(0, envelope.size - 1)
        val b = (to / windowMs).toInt().coerceIn(0, envelope.size - 1)
        var best = b
        var bestLevel = Float.MAX_VALUE
        for (w in b downTo a) {
            var sum = 0f
            var n = 0
            for (k in max(0, w - span / 2)..min(envelope.size - 1, w + span / 2)) {
                sum += envelope[k]; n++
            }
            // 1초 평균이 같으면 그 창 자체가 조용한 곳(숨 쉬는 한가운데)을 고른다.
            val level = sum / n + envelope[w] * 0.25f
            if (level < bestLevel - 0.5f) {
                bestLevel = level
                best = w
            }
        }
        return best * windowMs
    }

    companion object {
        const val WINDOW_MS = 100L
        /** Groq 는 요청마다 최소 10초로 계산한다. */
        const val MIN_BILLED_MS = 10_000L
    }
}
