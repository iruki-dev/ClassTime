package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 이 필요해 Robolectric 에서 돈다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptJsonTest {

    private val segments = listOf(
        Segment(61_000, 64_000, "오늘은 힙 정렬에 대해서"),
        Segment(64_000, 68_000, "힙 소트는 완전 이진 트리를 써요"),
    )

    @Test
    fun timestamps_useHoursOnlyWhenNeeded() {
        assertThat(Timestamps.format(61_000)).isEqualTo("01:01")
        assertThat(Timestamps.format(3_725_000)).isEqualTo("1:02:05")
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
        val plan = listOf(
            Chunk(listOf(0L..300_000L, 320_000L..600_000L)),
            Chunk(listOf(400_000L..430_000L), retry = true),
        )
        assertThat(TranscriptJson.plan(TranscriptJson.plan(plan))).isEqualTo(plan)
        assertThat(TranscriptJson.segments(TranscriptJson.segments(segments))).isEqualTo(segments)
        val p = listOf(Paragraph(1, "a \"b\"\n"))
        assertThat(TranscriptJson.paragraphs(TranscriptJson.paragraphs(p))).isEqualTo(p)
    }

    @Test
    fun legacyPlan_isReadAsSinglePieceChunks() {
        // 이전 버전에서 진행 중이던 작업.
        assertThat(TranscriptJson.plan("[[0,600000],[600000,900000]]"))
            .containsExactly(Chunk(listOf(0L..600_000L)), Chunk(listOf(600_000L..900_000L))).inOrder()
    }

    @Test
    fun retryHeaders_areParsed() {
        assertThat(AiHttp.parseDuration("7m12.5s")).isEqualTo(432_500)
        assertThat(AiHttp.parseDuration("450ms")).isEqualTo(450)
        assertThat(AiHttp.parseDuration("1h")).isEqualTo(3_600_000)
    }
}
