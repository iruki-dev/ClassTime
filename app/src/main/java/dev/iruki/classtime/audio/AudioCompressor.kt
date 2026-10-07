package dev.iruki.classtime.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 녹음을 음성에 맞는 낮은 비트레이트로 다시 인코딩한다.
 *
 * 원본은 96kbps AAC 로 녹음한다(시간당 약 43MB). 강의는 말소리라 32kbps 모노 AAC 로도
 * 알아듣는 데 지장이 없고, 크기는 약 1/3 이 된다.
 *
 * **압축본도 그대로 .m4a(AAC) 다.** zip 처럼 풀어야 들을 수 있는 압축이 아니라 어느 플레이어에서나
 * 바로 재생된다. 그래서 ‘압축 풀기’가 따로 필요 없다. 대신 손실 압축이므로 원래 음질로 되돌릴
 * 수는 없다 — 화면에서 이 점을 분명히 알린다.
 *
 * 파이프라인: MediaExtractor → 디코더(PCM 16bit) → 모노로 섞기 → AAC 인코더 → MediaMuxer(MP4)
 */
class AudioCompressor(private val context: Context) {

    /**
     * [input] 을 다시 인코딩해 [output] 에 쓴다. 끝까지 성공해야만 [output] 이 완성된다.
     * @param onProgress 0..1
     * @return 결과 재생 길이(ms)
     */
    fun compress(
        input: Uri,
        output: File,
        bitRate: Int = TARGET_BIT_RATE,
        onProgress: (Float) -> Unit = {},
    ): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("오디오 트랙이 없습니다")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val totalUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inFormat, null, null, 0)
                start()
            }
            val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var extractorDone = false
            var decoderDone = false
            var encoderInputDone = false
            var encoderDone = false
            var muxTrack = -1
            var samplesQueued = 0L
            var lastUs = 0L
            // 디코더가 내놓았지만 아직 인코더에 못 넣은 모노 PCM.
            var pending: ByteBuffer? = null

            while (!encoderDone) {
                // 1) 파일 → 디코더
                if (!extractorDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) 디코더 → 모노 PCM (인코더가 앞의 것을 다 먹었을 때만 새로 꺼낸다)
                if (!decoderDone && pending == null) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        o >= 0 -> {
                            if (info.size > 0) {
                                val pcm = decoder.getOutputBuffer(o)!!
                                pcm.position(info.offset).limit(info.offset + info.size)
                                pending = toMono(pcm, channels)
                                lastUs = info.presentationTimeUs
                            }
                            decoder.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        }
                    }
                }

                // 3) 모노 PCM → 인코더
                if (!encoderInputDone) {
                    val p = pending
                    if (p != null) {
                        val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = encoder.getInputBuffer(i)!!
                            buf.clear()
                            val n = minOf(buf.remaining(), p.remaining())
                            val slice = p.duplicate().apply { limit(position() + n) }
                            buf.put(slice)
                            p.position(p.position() + n)
                            val pts = samplesQueued * 1_000_000L / sampleRate
                            encoder.queueInputBuffer(i, 0, n, pts, 0)
                            samplesQueued += n / 2
                            if (!p.hasRemaining()) pending = null
                        }
                    } else if (decoderDone) {
                        val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val pts = samplesQueued * 1_000_000L / sampleRate
                            encoder.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        }
                    }
                }

                // 4) 인코더 → MP4
                val o = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    o >= 0 -> {
                        val data = encoder.getOutputBuffer(o)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            data.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, data, info)
                        }
                        encoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }

                if (totalUs > 0) onProgress((lastUs.toFloat() / totalUs).coerceIn(0f, 1f))
            }
            onProgress(1f)
            return samplesQueued * 1000L / sampleRate
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    companion object {
        /** 말소리 강의에 충분한 비트레이트. 원본(96kbps)의 1/3. */
        const val TARGET_BIT_RATE = 32_000

        /** 원본 대비 압축본 크기 비율(대략). 화면의 ‘약 n MB 줄어요’ 계산에 쓴다. */
        const val SIZE_RATIO = TARGET_BIT_RATE / 96_000f

        private const val TIMEOUT_US = 2_000L
        private const val PCM_CHUNK_BYTES = 16 * 1024

        /** 16bit PCM 을 모노로. 여러 채널이면 평균을 낸다. 새 버퍼를 돌려준다. */
        internal fun toMono(pcm: ByteBuffer, channels: Int): ByteBuffer {
            val src = pcm.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            if (channels <= 1) {
                val out = ByteBuffer.allocate(src.remaining() * 2).order(ByteOrder.nativeOrder())
                out.asShortBuffer().put(src)
                return out
            }
            val frames = src.remaining() / channels
            val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
            val dst = out.asShortBuffer()
            for (f in 0 until frames) {
                var sum = 0
                for (ch in 0 until channels) sum += src.get(f * channels + ch)
                dst.put((sum / channels).toShort())
            }
            return out
        }
    }
}
