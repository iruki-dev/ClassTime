package dev.iruki.classtime.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.iruki.classtime.util.AppLog

/**
 * 한 번의 녹음 결과.
 *
 * @param durationMs 실제 녹음 길이. [SystemClock.elapsedRealtime] 기준이라 시스템 시계가 바뀌어도 정확하다.
 * @param cleanStop MediaRecorder.stop() 이 정상 종료됐는지. false 면 파일이 깨졌을 수 있다.
 * @param peakAmplitude 녹음 내내 관측한 최대 진폭(0..32767). 0 이면 **마이크가 막혀 무음이 녹음된 것**.
 */
data class RecordingResult(
    val durationMs: Long,
    val cleanStop: Boolean,
    val peakAmplitude: Int,
)

/**
 * MediaRecorder 래퍼. AAC / MPEG-4 (.m4a) 컨테이너로 녹음한다. 한 번에 하나만.
 *
 * 녹음하는 동안 진폭을 계속 표본화해 [peakAmplitude] 를 누적한다. 안드로이드는 백그라운드에서
 * 시작된 포그라운드 서비스의 마이크를 **예외 없이 조용히 음소거**하기 때문에(무음 파일이 그대로
 * 생성된다), 진폭 관측이 그 상황을 알아낼 수 있는 유일한 방법이다.
 */
class AudioRecorder(private val context: Context) {

    // 진폭 폴링 스레드가 [recorder] 와 [startedElapsed] 를 읽는 동안 서비스 코루틴이
    // start()/stop() 에서 그 둘을 쓴다. @Volatile 이 없으면 폴링 스레드가 이미 해제된
    // MediaRecorder 를 계속 붙잡거나(IllegalStateException), 멈춘 녹음을 계속 표본화한다.
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var startedElapsed = 0L

    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    @Volatile private var peak = 0

    val isRecording get() = recorder != null

    /** 지금까지 관측한 최대 진폭. 0 이면 아직 아무 소리도 들어오지 않았다는 뜻. */
    val peakAmplitude get() = peak

    /** 무음이 [SILENCE_VERDICT_MS] 이상 이어지면 호출된다(한 번만). */
    var onSilenceSuspected: (() -> Unit)? = null

    fun start(output: Uri) {
        check(recorder == null) { "이미 녹음이 진행 중입니다." }

        val descriptor = context.contentResolver.openFileDescriptor(output, "w")
            ?: error("출력 파일을 열 수 없습니다: $output")
        pfd = descriptor

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(descriptor.fileDescriptor)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 실패하면 자원을 남기지 않는다.
            runCatching { rec.reset() }
            runCatching { rec.release() }
            runCatching { descriptor.close() }
            pfd = null
            throw e
        }

        // 폴링 스레드는 recorder != null 을 보는 순간부터 startedElapsed 를 쓴다.
        // 그러므로 recorder 를 마지막에 대입해야 한 틱도 엉뚱한 기준시각으로 돌지 않는다.
        peak = 0
        startedElapsed = SystemClock.elapsedRealtime()
        recorder = rec
        startAmplitudePolling()
    }

    fun stop(): RecordingResult {
        val rec = recorder ?: return RecordingResult(0L, cleanStop = false, peakAmplitude = peak)
        val duration = SystemClock.elapsedRealtime() - startedElapsed
        stopAmplitudePolling()

        var clean = true
        try {
            rec.stop()
        } catch (e: RuntimeException) {
            // 녹음 데이터가 거의 없으면 stop() 이 던진다. 이 경우 파일에 moov 가 없어 재생 불가.
            AppLog.w(TAG, "MediaRecorder.stop() 실패 - 파일이 불완전할 수 있습니다", e)
            clean = false
        } finally {
            runCatching { rec.reset() }
            runCatching { rec.release() }
            recorder = null
            runCatching { pfd?.close() }
            pfd = null
        }
        return RecordingResult(duration.coerceAtLeast(0L), clean, peak)
    }

    // --- 진폭 표본화 ---

    private fun startAmplitudePolling() {
        val thread = HandlerThread("amplitude-poll").apply { start() }
        val handler = Handler(thread.looper)
        pollThread = thread
        pollHandler = handler

        // 첫 호출은 start() 이후 누적값이라 버린다(항상 0 에 가깝다).
        runCatching { recorder?.maxAmplitude }

        var silenceReported = false
        val tick = object : Runnable {
            override fun run() {
                val rec = recorder ?: return
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                if (amp > peak) peak = amp

                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                if (!silenceReported && peak == 0 && elapsed >= SILENCE_VERDICT_MS) {
                    silenceReported = true
                    AppLog.e(TAG, "무음 감지: ${elapsed}ms 동안 진폭이 0 - 마이크가 차단된 상태로 보입니다")
                    runCatching { onSilenceSuspected?.invoke() }
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(tick, POLL_INTERVAL_MS)
    }

    private fun stopAmplitudePolling() {
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollHandler = null
        pollThread = null
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val POLL_INTERVAL_MS = 500L

        /** 이 시간 동안 진폭이 0 이면 마이크가 막혔다고 판단한다. */
        const val SILENCE_VERDICT_MS = 8_000L
    }
}
