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
import kotlin.math.log10

/**
 * 녹음 파일을 다루는 기기 쪽 작업 두 가지.
 *
 * - [envelope]: 디코딩해 [ChunkPlanner.WINDOW_MS] 마다 소리 크기(dBFS)를 잰다.
 * - [cut]: 정한 구간을 **다시 인코딩하지 않고** AAC 프레임 그대로 새 m4a 로 옮긴다.
 *   음질 손실이 없고 빠르며, 96kbps 10분 조각이 약 7MB 라 무료 한도(25MB) 안에 넉넉히 든다.
 */
class AudioChunks(private val context: Context) : AudioSource {

    override fun envelope(uri: String, isStopped: () -> Boolean): FloatArray {
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
        var perWindow = (sampleRate * ChunkPlanner.WINDOW_MS / 1000).toInt() * channels

        val levels = ArrayList<Float>(1 shl 15)
        var sum = 0.0
        var count = 0
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
                        perWindow = (sampleRate * ChunkPlanner.WINDOW_MS / 1000).toInt() * channels
                    }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        while (out.hasRemaining()) {
                            val v = if (floatPcm) out.float.toDouble() else out.short / 32768.0
                            sum += v * v
                            if (++count >= perWindow) {
                                levels += db(sum / count)
                                sum = 0.0
                                count = 0
                            }
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
        if (count > 0) levels += db(sum / count)
        return levels.toFloatArray()
    }

    private fun db(meanSquare: Double): Float =
        if (meanSquare <= 1e-10) -100f else (10 * log10(meanSquare)).toFloat().coerceAtLeast(-100f)

    /** [range](ms) 를 [out] 으로 옮긴다. 시간은 0부터 다시 센다. */
    override fun cut(uri: String, range: LongRange, out: File) {
        out.parentFile?.mkdirs()
        val extractor = open(uri)
        val track = audioTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val dst = muxer.addTrack(format)
            muxer.start()
            val startUs = range.first * 1000
            val endUs = range.last * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
            )
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (base < 0) base = t
                info.set(0, n, t - base, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(dst, buf, info)
                extractor.advance()
            }
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
                extractor.setDataSource(parsed.path ?: uri)
            } else {
                context.contentResolver.openFileDescriptor(parsed, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
            }
        } catch (e: Exception) {
            extractor.release()
            throw IOException("open failed", e)
        }
        return extractor
    }

    private fun audioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("no audio track")
}
