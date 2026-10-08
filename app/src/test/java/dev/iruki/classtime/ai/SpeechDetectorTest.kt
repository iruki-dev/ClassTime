package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

class SpeechDetectorTest {

    private val detector = SpeechDetector()

    /** 16kHz 로 [seconds] 초를 만들어 [ProfileBuilder] 에 흘린다. */
    private fun profileOf(seconds: Double, rate: Int = 16_000, sample: (t: Double) -> Float): AudioProfile {
        val b = ProfileBuilder(rate)
        val n = (seconds * rate).toInt()
        for (i in 0 until n) b.add(sample(i.toDouble() / rate))
        return b.build()
    }

    /** 목소리 흉내: 기본 주파수 140Hz 와 배음. */
    private fun voice(t: Double, amp: Double) =
        (amp * (sin(2 * PI * 140 * t) + 0.5 * sin(2 * PI * 280 * t) + 0.3 * sin(2 * PI * 420 * t))).toFloat()

    @Test
    fun builder_tellsVoiceFromNoise() {
        val rnd = Random(1)
        val noisy = profileOf(2.0) { (rnd.nextFloat() - 0.5f) * 0.2f }
        val voiced = profileOf(2.0) { voice(it, 0.02) + (rnd.nextFloat() - 0.5f) * 0.004f }
        assertThat(noisy.size).isEqualTo(20)
        assertThat(noisy.voicing.average()).isLessThan(0.1)
        // 작게(-40dB 남짓) 말해도 목소리는 주기적이다.
        assertThat(voiced.voicing.average()).isGreaterThan(0.8)
        assertThat(voiced.level.average()).isLessThan(-30.0)
    }

    @Test
    fun builder_handlesDigitalSilence_andOddRates() {
        val p = profileOf(1.05, rate = 44_100) { 0f }
        assertThat(p.size).isEqualTo(11)
        assertThat(p.level.all { it == -100f }).isTrue()
        assertThat(p.voicing.all { it == 0f }).isTrue()
    }

    /**
     * 쪽지시험처럼: 강의 3분 → 15분 동안 바스락거림(바닥보다 5–8dB 큰, 주기성 없는 소리)과
     * 가끔 짧은 안내 한마디 → 강의 3분.
     */
    @Test
    fun quizSilence_isLeftOut_butShortAnnouncementIsKept() {
        val n = 21 * 60 * 10
        val level = FloatArray(n) { -45f }
        val voicing = FloatArray(n)
        val rnd = Random(7)
        fun lecture(from: Int, to: Int) {
            for (i in from until to) {
                val breath = i % 80 >= 74
                level[i] = if (breath) -44f else -28f + rnd.nextFloat() * 6
                voicing[i] = if (breath) 0f else 0.4f + rnd.nextFloat() * 0.4f
            }
        }
        lecture(0, 1800)
        lecture(n - 1800, n)
        for (i in 1800 until n - 1800) {
            if (rnd.nextFloat() < 0.08f) level[i] = -45f + 5f + rnd.nextFloat() * 3 // 연필·종이
            voicing[i] = if (rnd.nextFloat() < 0.02f) 0.25f else 0f
        }
        // 10분쯤 “3분 남았습니다”(2초, 작은 목소리).
        val ann = 1800 + 6000
        for (i in ann until ann + 20) { level[i] = -36f; voicing[i] = 0.5f }

        val spans = detector.spans(AudioProfile(level, voicing))
        val sent = spans.sumOf { it.last - it.first }
        assertThat(sent).isLessThan(9 * 60_000L) // 강의 6분 + 안내 + 여유
        assertThat(spans.any { ann * 100L in it }).isTrue()
        assertThat(spans.first().first).isEqualTo(0L)
        assertThat(spans.last().last).isEqualTo(n * 100L)
    }

    @Test
    fun quietDistantVoice_isKept_evenInANoisyRoom() {
        // 바닥 -40dB 의 시끄러운 방에서 바닥보다 4dB 큰 목소리.
        val n = 6000
        val level = FloatArray(n) { -40f }
        val voicing = FloatArray(n)
        for (i in 3000 until 3600) { level[i] = -36f; voicing[i] = 0.5f }
        val spans = detector.spans(AudioProfile(level, voicing))
        assertThat(spans).hasSize(1)
        assertThat(spans[0].first).isAtMost(300_000L)
        assertThat(spans[0].last).isAtLeast(360_000L)
    }

    @Test
    fun shortPauses_areKept_asOneSpan() {
        val n = 1200
        val level = FloatArray(n) { if (it in 500..530) -60f else -25f }
        val voicing = FloatArray(n) { if (it in 500..530) 0f else 0.5f }
        assertThat(detector.spans(AudioProfile(level, voicing))).hasSize(1)
    }

    @Test
    fun floor_followsTheRoom_butNotDigitalSilence() {
        val level = FloatArray(6000) { if (it < 3000) -100f else -42f }
        val floor = detector.floor(level)
        assertThat(floor[0]).isEqualTo(-60f)
        assertThat(floor[5999]).isWithin(0.5f).of(-42f)
    }

    @Test
    fun evidence_sliceFollowsTheChunk() {
        val active = BooleanArray(1000) { it in 100..199 }
        val e = SpeechEvidence(active)
        assertThat(e.fraction(10_000, 20_000)).isEqualTo(1f)
        assertThat(e.fraction(0, 20_000)).isEqualTo(0.5f)
        val local = e.slice(Chunk(listOf(15_000L..20_000L, 50_000L..55_000L)))
        assertThat(local.fraction(0, 5_000)).isEqualTo(1f)
        assertThat(local.fraction(5_000, 10_000)).isEqualTo(0f)
        assertThat(SpeechEvidence.fromBytes(e.toBytes()).active).isEqualTo(active)
    }
}
