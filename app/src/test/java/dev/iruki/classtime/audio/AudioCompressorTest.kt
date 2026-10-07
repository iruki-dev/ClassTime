package dev.iruki.classtime.audio

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class AudioCompressorTest {

    private fun pcm(vararg samples: Short): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        b.asShortBuffer().put(samples)
        return b
    }

    private fun ByteBuffer.shorts(): List<Short> {
        val s = order(ByteOrder.nativeOrder()).asShortBuffer()
        return List(s.remaining()) { s.get(it) }
    }

    @Test
    fun stereo_isAveragedToMono() {
        val mono = AudioCompressor.toMono(pcm(100, 300, -200, -400, 32767, 32767), channels = 2)
        assertThat(mono.shorts()).containsExactly(200.toShort(), (-300).toShort(), 32767.toShort()).inOrder()
    }

    @Test
    fun mono_isCopiedAsIs() {
        val mono = AudioCompressor.toMono(pcm(1, 2, 3), channels = 1)
        assertThat(mono.shorts()).containsExactly(1.toShort(), 2.toShort(), 3.toShort()).inOrder()
    }

    @Test
    fun respectsTheBufferWindow() {
        val b = pcm(9, 1, 2, 9)
        b.position(2).limit(6) // 가운데 두 샘플만
        assertThat(AudioCompressor.toMono(b, channels = 1).shorts())
            .containsExactly(1.toShort(), 2.toShort()).inOrder()
    }
}
