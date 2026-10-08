package dev.iruki.classtime.ai

import kotlin.math.max
import kotlin.math.min

/**
 * Whisper 에 한 번에 보내는 소리. 녹음의 여러 구간([pieces], ms)을 이어 붙인 것이다.
 *
 * 긴 무음(쉬는 시간·시험 시간)은 빼고 보내므로 한도를 아끼고, Whisper 가 무음에서
 * 지어내는 문장(“감사합니다.” 류)도 원천적으로 줄인다.
 *
 * @param retry 앞 조각에서 Whisper 가 말소리를 놓친 30초 창을 다시 받아 적는 조각.
 */
data class Chunk(val pieces: List<LongRange>, val retry: Boolean = false) {

    init { require(pieces.isNotEmpty()) }

    val durationMs: Long get() = pieces.sumOf { it.last - it.first }

    /** 이어 붙인 소리의 시각 → 녹음 시각. 구간 사이 이음매에서는 [end] 면 앞 구간 끝, 아니면 뒤 구간 처음. */
    fun toSource(localMs: Long, end: Boolean = false): Long {
        var offset = 0L
        for ((i, p) in pieces.withIndex()) {
            val len = p.last - p.first
            val last = i == pieces.lastIndex
            if (localMs < offset + len || (end && localMs == offset + len) || last) {
                return p.first + (localMs - offset).coerceIn(0L, len)
            }
            offset += len
        }
        return pieces.last().last
    }

    /** 이어 붙인 소리의 [local] 구간이 녹음에서 차지하는 부분들. */
    fun sourcePieces(local: LongRange): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var offset = 0L
        for (p in pieces) {
            val len = p.last - p.first
            val a = max(local.first, offset)
            val b = min(local.last, offset + len)
            if (b > a) out += (p.first + a - offset)..(p.first + b - offset)
            offset += len
        }
        return out.ifEmpty { listOf(toSource(local.first)..toSource(local.last, end = true)) }
    }
}

/**
 * 말소리 구간([SpeechDetector.spans])을 Whisper 에 보낼 조각으로 묶는다. 순수 계산.
 *
 * - 가까운 구간끼리 이어 붙여 [targetMs] 안팎의 조각을 만든다. 요청 수가 적어 분당·일당 한도에
 *   덜 걸리고, 조각마다 앞 내용이 이어져 받아 적기가 안정적이다.
 * - 한 구간이 조각에 다 들어가지 않으면 목표 길이 근처에서 **가장 조용한 곳**(숨 쉬는 자리)에서
 *   자른다. 단어가 잘리지 않으므로 조각끼리 겹쳐 보낼 필요가 없다.
 *
 * @param level [AudioProfile.level]. 자를 곳을 찾을 때 쓴다.
 */
class ChunkPlanner(
    private val targetMs: Long = 10 * 60_000L,
    private val maxMs: Long = 13 * 60_000L,
    /** 목표 길이에서 이만큼 앞까지 자를 곳을 찾는다. */
    private val searchMs: Long = 3 * 60_000L,
) {
    private val w = AudioProfile.WINDOW_MS

    fun plan(spans: List<LongRange>, level: FloatArray): List<Chunk> {
        val chunks = mutableListOf<MutableList<LongRange>>()
        var current = mutableListOf<LongRange>()
        var length = 0L
        fun close() {
            if (current.isNotEmpty()) chunks += current
            current = mutableListOf()
            length = 0L
        }
        for (span in spans) {
            var rest = span
            while (true) {
                val len = rest.last - rest.first
                if (length + len <= maxMs) {
                    current += rest
                    length += len
                    break
                }
                val room = targetMs - length
                if (room >= searchMs) {
                    val cut = quietestPoint(level, rest.first + room - searchMs, rest.first + room)
                    current += rest.first..cut
                    rest = cut..rest.last
                }
                close()
            }
            if (length >= targetMs) close()
        }
        close()
        // 마지막 조각이 너무 짧으면(10초 미만) 앞 조각에 붙인다 — 요청마다 최소 10초로 계산된다.
        if (chunks.size >= 2) {
            val last = chunks.last()
            val prev = chunks[chunks.lastIndex - 1]
            if (last.sumOf { it.last - it.first } < MIN_BILLED_MS &&
                (last + prev).sumOf { it.last - it.first } <= maxMs
            ) {
                chunks.removeAt(chunks.lastIndex)
                chunks[chunks.lastIndex] = (prev + last).toMutableList()
            }
        }
        return chunks.map { Chunk(joinTouching(it)) }
    }

    /** 맞닿은 구간(한 구간을 잘라 이어 붙인 경우)은 하나로. */
    private fun joinTouching(pieces: List<LongRange>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        for (p in pieces) {
            val prev = out.lastOrNull()
            if (prev != null && p.first <= prev.last) out[out.lastIndex] = prev.first..max(prev.last, p.last) else out += p
        }
        return out
    }

    /**
     * [from, to] 안에서 가장 조용한 지점. 한 창이 아니라 1초 평균으로 보아, 순간적인 틈보다
     * 말과 말 사이의 숨 쉬는 자리를 고른다. 같으면 목표(to)에 가까운 쪽.
     */
    internal fun quietestPoint(level: FloatArray, from: Long, to: Long): Long {
        if (level.isEmpty()) return to
        val span = (1_000L / w).toInt().coerceAtLeast(1)
        val a = (from / w).toInt().coerceIn(0, level.size - 1)
        val b = (to / w).toInt().coerceIn(0, level.size - 1)
        var best = b
        var bestLevel = Float.MAX_VALUE
        for (i in b downTo a) {
            var sum = 0f
            var n = 0
            for (k in max(0, i - span / 2)..min(level.size - 1, i + span / 2)) {
                sum += level[k]; n++
            }
            // 1초 평균이 같으면 그 창 자체가 조용한 곳(숨 쉬는 한가운데)을 고른다.
            val v = sum / n + level[i] * 0.25f
            if (v < bestLevel - 0.5f) {
                bestLevel = v
                best = i
            }
        }
        return (best * w).coerceIn(from, to)
    }

    companion object {
        /** Groq 는 요청마다 최소 10초로 계산한다. */
        const val MIN_BILLED_MS = 10_000L
    }
}
