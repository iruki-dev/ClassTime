package dev.iruki.classtime.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 녹음 파일을 다루는 기기 쪽 작업 두 가지.
 *
 * - [profile]: 디코딩해 [AudioProfile.WINDOW_MS] 마다 소리 크기와 목소리 주기성을 잰다.
 * - [cut]: 정한 구간들을 **다시 인코딩하지 않고** AAC 프레임 그대로 이어 붙여 새 m4a 로 옮긴다.
 *   음질 손실이 없고 빠르며, 96kbps 10분 조각이 약 7MB 라 무료 한도(25MB) 안에 넉넉히 든다.
 *   AAC 가 아닌 파일(들여온 mp3·wav 등)만 AAC 로 바꿔 쓴다.
 */
class AudioChunks(private val context: Context) : AudioSource {

    override fun profile(uri: String, isStopped: () -> Boolean): AudioProfile {
        val extractor = open(uri)
        val track = audioTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var builder: ProfileBuilder? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (isStopped()) throw IOException("stopped")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val b = builder ?: ProfileBuilder(sampleRate).also { builder = it }
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val frameBytes = (if (floatPcm) 4 else 2) * channels
                        while (out.remaining() >= frameBytes) {
                            var sum = 0f
                            repeat(channels) { sum += if (floatPcm) out.float else out.short / 32768f }
                            b.add(sum / channels)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return (builder ?: ProfileBuilder(sampleRate)).build()
    }

    /** [pieces](ms)를 차례로 이어 [out] 으로 옮긴다. 시간은 0부터 이어서 센다. */
    override fun cut(uri: String, pieces: List<LongRange>, out: File) {
        out.parentFile?.mkdirs()
        val extractor = open(uri)
        val track = audioTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
            extractor.release()
            transcode(uri, pieces, out)
            return
        }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val dst = muxer.addTrack(format)
            muxer.start()
            val buf = ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
            )
            // AAC 프레임 하나 = 1024 샘플. 구간을 이을 때 앞 구간 마지막 프레임 길이만큼 띄운다.
            val frameUs = 1024L * 1_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()
            var outUs = 0L
            var written = false
            for (piece in pieces) {
                val startUs = piece.first * 1000
                val endUs = piece.last * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var base = -1L
                var lastUs = -1L
                while (true) {
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = extractor.sampleTime
                    if (t > endUs) break
                    if (base < 0) base = t
                    info.set(0, n, outUs + (t - base), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(dst, buf, info)
                    written = true
                    lastUs = t
                    extractor.advance()
                }
                if (base >= 0) outUs += lastUs - base + frameUs
            }
            if (!written) throw AudioAccessException(AudioAccessException.Reason.FORMAT, "empty range")
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractor.release()
        }
    }

    private fun open(uri: String): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == null || parsed.scheme == "file") {
                val path = parsed.path ?: uri
                if (!File(path).exists()) throw AudioAccessException(AudioAccessException.Reason.MISSING, "missing file")
                extractor.setDataSource(path)
            } else {
                val fd = try {
                    context.contentResolver.openFileDescriptor(parsed, "r")
                } catch (e: SecurityException) {
                    // 다른 앱(또는 재설치 전의 이 앱)이 만든 파일인데 오디오 읽기 권한이 없다.
                    throw AudioAccessException(AudioAccessException.Reason.PERMISSION, e.message ?: "permission", e)
                } catch (e: java.io.FileNotFoundException) {
                    throw AudioAccessException(AudioAccessException.Reason.MISSING, e.message ?: "missing", e)
                } catch (e: IllegalArgumentException) {
                    throw AudioAccessException(AudioAccessException.Reason.MISSING, e.message ?: "bad uri", e)
                } ?: throw AudioAccessException(AudioAccessException.Reason.MISSING, "no descriptor")
                fd.use { extractor.setDataSource(it.fileDescriptor) }
            }
        } catch (e: AudioAccessException) {
            extractor.release()
            throw e
        } catch (e: Exception) {
            extractor.release()
            throw AudioAccessException(AudioAccessException.Reason.FORMAT, e.message ?: "unreadable", e)
        }
        return extractor
    }

    private fun audioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw AudioAccessException(AudioAccessException.Reason.FORMAT, "no audio track")

    /**
     * AAC 가 아닌 녹음(폴더 검사로 들여온 mp3·wav·ogg 등)의 [pieces] 를 AAC m4a 로 바꿔 쓴다.
     * 프레임을 그대로 옮길 수 없는 형식이라 디코딩 → 모노 → (필요하면 리샘플) → AAC 64kbps.
     * 한 구간을 다 넣으면 다음 구간 처음으로 건너뛰고, 구간 밖의 소리는 버린다.
     */
    private fun transcode(uri: String, pieces: List<LongRange>, out: File) {
        val extractor = open(uri)
        val track = audioTrack(extractor)
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        var feeding = 0
        extractor.seekTo(pieces[0].first * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var keepIndex = 0
        var lastKeptUs = -1L

        var inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        // AAC 인코더는 8–48kHz. 그 밖이면 48kHz 로 리샘플한다.
        val outRate = if (inRate in 8_000..48_000) inRate else 48_000

        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        try {
            decoder.configure(inFormat, null, null, 0)
            decoder.start()
            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            out.parentFile?.mkdirs()
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val pcm = ShortQueue()
            val resampler = LinearResampler()
            val info = MediaCodec.BufferInfo()
            var decoderInDone = false
            var decoderOutDone = false
            var encoderInDone = false
            var encoderOutDone = false
            var muxTrack = -1
            var queued = 0L

            while (!encoderOutDone) {
                if (!decoderInDone) {
                    val i = decoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        var n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        var t = extractor.sampleTime
                        while (n >= 0 && t > pieces[feeding].last * 1000 && feeding < pieces.lastIndex) {
                            feeding++
                            extractor.seekTo(pieces[feeding].first * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                            t = extractor.sampleTime
                        }
                        if (n < 0 || t > pieces.last().last * 1000) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderOutDone) {
                    val o = decoder.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frames = info.size / (bytesPerSample * inChannels)
                        val mono = ShortArray(frames)
                        var kept = 0
                        for (f in 0 until frames) {
                            var sum = 0f
                            repeat(inChannels) { sum += if (floatPcm) buf.float else buf.short / 32768f }
                            val at = info.presentationTimeUs + f * 1_000_000L / inRate
                            while (keepIndex < pieces.lastIndex && at > pieces[keepIndex].last * 1000) keepIndex++
                            val piece = pieces[keepIndex]
                            if (at > lastKeptUs && at >= piece.first * 1000 && at <= piece.last * 1000) {
                                lastKeptUs = at
                                mono[kept++] = (sum / inChannels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (kept > 0) {
                            if (inRate == outRate) pcm.add(mono, kept) else resampler.run(mono, kept, inRate, outRate, pcm)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderOutDone = true
                    }
                }
                if (!encoderInDone && (pcm.size > 0 || decoderOutDone)) {
                    val i = encoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = encoder.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                        val pts = queued * 1_000_000L / outRate
                        if (pcm.size == 0) {
                            encoder.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInDone = true
                        } else {
                            val count = minOf(pcm.size, buf.remaining() / 2)
                            pcm.take(count) { buf.putShort(it) }
                            encoder.queueInputBuffer(i, 0, count * 2, pts, 0)
                            queued += count
                        }
                    }
                }
                val o = encoder.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(encoder.outputFormat)
                    mux.start()
                } else if (o >= 0) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxTrack >= 0) {
                        mux.writeSampleData(muxTrack, encoder.getOutputBuffer(o)!!, info)
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderOutDone = true
                }
            }
            if (muxTrack < 0) throw AudioAccessException(AudioAccessException.Reason.FORMAT, "no encoded audio")
            mux.stop()
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { encoder.stop() }
            encoder.release()
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** 디코딩한 소리를 인코더에 넘기기 전까지 쌓아 두는 줄. */
    private class ShortQueue {
        private val chunks = ArrayDeque<ShortArray>()
        private var head = 0
        var size = 0
            private set

        fun add(src: ShortArray, count: Int) {
            if (count <= 0) return
            chunks.addLast(if (count == src.size) src else src.copyOf(count))
            size += count
        }

        inline fun take(count: Int, sink: (Short) -> Unit) {
            var left = count
            while (left > 0) {
                val c = chunks.first()
                val n = minOf(left, c.size - head)
                for (k in head until head + n) sink(c[k])
                head += n
                left -= n
                size -= n
                if (head == c.size) {
                    chunks.removeFirst()
                    head = 0
                }
            }
        }
    }

    /** 단순 선형 보간 리샘플러. 버퍼 경계는 이전 버퍼의 마지막 샘플로 잇는다. */
    private class LinearResampler {
        private var pos = 0.0
        private var carry: Short = 0

        fun run(input: ShortArray, n: Int, inRate: Int, outRate: Int, out: ShortQueue) {
            val step = inRate.toDouble() / outRate
            val result = ShortArray(((n - pos) / step).toInt() + 2)
            var k = 0
            while (pos < n - 1) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = pos - i
                val a = if (i < 0) carry else input[i]
                val b = input[i + 1]
                if (k < result.size) result[k++] = (a + (b - a) * frac).toInt().toShort()
                pos += step
            }
            pos -= n
            carry = input[n - 1]
            out.add(result, k)
        }
    }
}

/** 녹음 파일을 읽지 못한 이유. 엔진이 사용자에게 보일 실패 이유로 바꾼다. */
class AudioAccessException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** 오디오 읽기 권한이 없다(다른 앱이 만든 파일). 권한을 주면 된다. */
        PERMISSION,
        /** 파일이 없다. */
        MISSING,
        /** 열었지만 소리를 읽을 수 없는 형식. */
        FORMAT,
    }
}
