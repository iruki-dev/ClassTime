package dev.iruki.classtime.ai

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 녹음 한 개를 [WINDOW_MS] 마다 잰 두 값.
 *
 * - [level]: 소리 크기(dBFS, 0 이하).
 * - [voicing]: 그 창 안의 32ms 조각 가운데 사람 목소리처럼 주기적인(75–400Hz) 조각의 비율(0–1).
 *   의자 끄는 소리·에어컨·종이 넘기는 소리는 커도 주기성이 낮고, 멀리서 작게 말해도 목소리는 높다.
 */
class AudioProfile(val level: FloatArray, val voicing: FloatArray) {
    init { require(level.size == voicing.size) }
    val size get() = level.size

    companion object {
        const val WINDOW_MS = 100L
    }
}

/**
 * 디코딩한 소리를 흘려 넣으면 [AudioProfile] 을 만든다. 순수 계산(기기·테스트 공용).
 *
 * 주기성은 약 8kHz 로 줄여(정수배 평균) 정규화 자기상관의 최댓값으로 본다. 90분 녹음에
 * 수 초면 끝나도록 창 안의 조각만 보고, 거의 무음인 조각은 계산하지 않는다.
 */
class ProfileBuilder(sampleRate: Int) {

    private val factor = max(1, sampleRate / 8_000)
    private val rate = sampleRate / factor
    private val perWindow = max(1, (sampleRate * AudioProfile.WINDOW_MS / 1000).toInt())
    private val sub = rate * 32 / 1000
    private val hop = max(1, sub / 2)
    private val lagMin = rate / 400
    private val lagMax = min(rate / 75, sub - 1)

    private val levels = FloatList()
    private val voicings = FloatList()

    private var sumSquares = 0.0
    private var inWindow = 0
    private var decimSum = 0f
    private var decimCount = 0
    private val low = FloatArray(perWindow / factor + 2)
    private var lowCount = 0
    private val frame = FloatArray(max(sub, 1))

    /** 모노(채널 평균) 샘플 하나, -1..1. */
    fun add(sample: Float) {
        sumSquares += sample * sample
        decimSum += sample
        if (++decimCount == factor) {
            if (lowCount < low.size) low[lowCount++] = decimSum / factor
            decimSum = 0f
            decimCount = 0
        }
        if (++inWindow >= perWindow) closeWindow()
    }

    fun build(): AudioProfile {
        if (inWindow > 0) closeWindow()
        return AudioProfile(levels.toArray(), voicings.toArray())
    }

    private fun closeWindow() {
        val ms = sumSquares / inWindow
        levels.add(if (ms <= 1e-10) -100f else (10 * log10(ms)).toFloat().coerceAtLeast(-100f))
        voicings.add(voicing(low, lowCount))
        sumSquares = 0.0
        inWindow = 0
        lowCount = 0
    }

    private fun voicing(x: FloatArray, n: Int): Float {
        if (sub < 8 || lagMin < 1 || lagMax <= lagMin || n < sub) return 0f
        var frames = 0
        var voiced = 0
        var j = 0
        while (j + sub <= n) {
            frames++
            if (periodicity(x, j) > VOICED) voiced++
            j += hop
        }
        return if (frames == 0) 0f else voiced.toFloat() / frames
    }

    /** x[from, from+sub) 의 평균을 뺀 정규화 자기상관 최댓값(지연 lagMin until lagMax). */
    private fun periodicity(x: FloatArray, from: Int): Float {
        var mean = 0f
        for (k in 0 until sub) mean += x[from + k]
        mean /= sub
        var energy = 0f
        for (k in 0 until sub) {
            val v = x[from + k] - mean
            frame[k] = v
            energy += v * v
        }
        if (energy / sub < SILENT_MS) return 0f
        var best = 0f
        for (lag in lagMin until lagMax) {
            var acc = 0f
            for (k in 0 until sub - lag) acc += frame[k] * frame[k + lag]
            if (acc > best) best = acc
        }
        return best / energy
    }

    private class FloatList {
        private var data = FloatArray(1 shl 12)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    private companion object {
        /** 이보다 주기적이면 목소리 조각. */
        const val VOICED = 0.5f
        /** 약 -70dBFS. 이보다 조용한 조각은 볼 것 없이 무음. */
        const val SILENT_MS = 1e-7f
    }
}

/**
 * 말소리가 있는 곳을 찾는다. 순수 계산.
 *
 * 실제 강의 녹음(일반 강의·쉬는 시간·쪽지시험·조별활동)과 Whisper 전사를 맞대어 정한 값이다.
 * 소리 크기만으로는 조용한 시험 시간의 바스락거림과 멀리서 작게 말하는 소리를 가르지 못해,
 * **목소리 주기성**을 함께 본다.
 *
 * 1. 바닥 소음: 앞뒤 5분 창의 하위 10% 크기. 강의실·마이크마다 달라 고정값을 쓰지 않는다.
 * 2. 한 창이 말소리 후보: (주기성 ≥ 0.25 이고 바닥 + 3dB 또는 -40dBFS 위) 또는 바닥 + 10dB 위.
 *    -40dBFS 는 쉬지 않고 이어지는 말이 바닥을 끌어올려도 목소리를 놓치지 않게 하는 안전판.
 * 3. 앞뒤 4초 중 15% 이상이 후보면 말소리. 2초 안의 틈은 잇고, 앞뒤로 1초 여유를 둔다.
 */
class SpeechDetector(
    private val floorWindowMs: Long = 300_000L,
    private val floorPercentile: Float = 0.10f,
    private val loudDb: Float = 10f,
    private val voicedRatio: Float = 0.25f,
    private val voicedDb: Float = 3f,
    private val voicedAbsDb: Float = -40f,
    private val densityWindowMs: Long = 4_000L,
    private val densityMin: Float = 0.15f,
    private val mergeGapMs: Long = 2_000L,
    private val minSpeechMs: Long = 500L,
    private val padMs: Long = 1_000L,
    /** 이보다 짧은 무음은 잘라 내지 않고 그대로 보낸다(말의 흐름·문장 사이). */
    private val keepGapMs: Long = 5_000L,
) {
    private val w = AudioProfile.WINDOW_MS

    /** 창마다 말소리 후보인지. 걸러 내기([WhisperFilter])의 근거로도 쓴다. */
    fun active(profile: AudioProfile): BooleanArray {
        val floor = floor(profile.level)
        return BooleanArray(profile.size) { i ->
            val l = profile.level[i]
            (profile.voicing[i] >= voicedRatio && (l > floor[i] + voicedDb || l > voicedAbsDb)) || l > floor[i] + loudDb
        }
    }

    /** 보낼 구간들(ms, 겹치지 않고 오름차순). */
    fun spans(profile: AudioProfile): List<LongRange> = spans(active(profile))

    fun spans(active: BooleanArray): List<LongRange> {
        val n = active.size
        if (n == 0) return emptyList()
        // 앞뒤 densityWindow 안의 후보 비율.
        val k = ((densityWindowMs / w).toInt()) or 1
        val half = k / 2
        val prefix = IntArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + if (active[i]) 1 else 0
        val speech = BooleanArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            (prefix[b] - prefix[a]).toFloat() / k >= densityMin
        }

        val runs = mutableListOf<LongArray>()
        var i = 0
        while (i < n) {
            if (!speech[i]) { i++; continue }
            var j = i
            while (j < n && speech[j]) j++
            val last = runs.lastOrNull()
            if (last != null && (i - last[1]) * w < mergeGapMs) last[1] = j.toLong() else runs += longArrayOf(i.toLong(), j.toLong())
            i = j
        }

        val total = n * w
        val out = mutableListOf<LongRange>()
        for ((a, b) in runs.map { it[0] to it[1] }) {
            if ((b - a) * w < minSpeechMs) continue
            val s = max(0L, a * w - padMs)
            val e = min(total, b * w + padMs)
            val prev = out.lastOrNull()
            if (prev != null && s - prev.last < keepGapMs) out[out.lastIndex] = prev.first..e else out += s..e
        }
        return out
    }

    /**
     * 창마다 바닥 소음(dBFS). 0.25dB 칸 막대그래프를 밀어 가며 1초마다 백분위를 구한다
     * (90분 녹음이라도 한 번 훑기).
     */
    internal fun floor(level: FloatArray): FloatArray {
        val n = level.size
        val out = FloatArray(n)
        if (n == 0) return out
        val hist = IntArray(BINS)
        fun bin(v: Float) = ((v.coerceIn(-100f, 0f) + 100f) * 4).toInt().coerceIn(0, BINS - 1)
        val half = (floorWindowMs / w / 2).toInt()
        val step = (1_000L / w).toInt()
        var lo = 0
        var hi = 0
        var count = 0
        var i = 0
        while (i < n) {
            val a = max(0, i - half)
            val b = min(n, i + half)
            while (hi < b) { hist[bin(level[hi++])]++; count++ }
            while (lo < a) { hist[bin(level[lo++])]--; count-- }
            val target = (count * floorPercentile).toInt()
            var seen = 0
            var k = 0
            while (k < BINS - 1 && seen + hist[k] <= target) { seen += hist[k]; k++ }
            val f = max(k / 4f - 100f, FLOOR_MIN)
            for (t in i until min(n, i + step)) out[t] = f
            i += step
        }
        return out
    }

    private companion object {
        const val BINS = 401
        /** 디지털 무음(-100dB)이 길게 이어져도 바닥을 이보다 낮게 잡지 않는다. */
        const val FLOOR_MIN = -60f
    }
}

/** 창마다 말소리 후보인지([SpeechDetector.active]). 시간 구간의 비율을 묻는다. */
class SpeechEvidence(val active: BooleanArray) {

    /** [startMs, endMs) 가운데 말소리 후보 창의 비율. 범위 밖은 0. */
    fun fraction(startMs: Long, endMs: Long): Float {
        val w = AudioProfile.WINDOW_MS
        val a = (startMs / w).toInt().coerceAtLeast(0)
        val b = max(a + 1, ((endMs + w - 1) / w).toInt())
        var hit = 0
        for (i in a until b) if (i < active.size && active[i]) hit++
        return hit.toFloat() / (b - a)
    }

    /** 조각(이어 붙인 구간들)의 시간축으로 옮긴 근거. */
    fun slice(chunk: Chunk): SpeechEvidence {
        val w = AudioProfile.WINDOW_MS
        val out = ArrayList<Boolean>()
        for (p in chunk.pieces) {
            for (i in (p.first / w).toInt() until ((p.last + w - 1) / w).toInt()) out += i in active.indices && active[i]
        }
        return SpeechEvidence(out.toBooleanArray())
    }

    fun toBytes(): ByteArray = ByteArray(active.size) { if (active[it]) 1 else 0 }

    companion object {
        fun fromBytes(bytes: ByteArray) = SpeechEvidence(BooleanArray(bytes.size) { bytes[it].toInt() != 0 })
    }
}
