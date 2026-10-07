package dev.iruki.classtime.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 재생 상태. [recording] 이 null 이면 아무것도 재생하지 않는 상태. */
data class PlaybackState(
    val recording: Recording? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** 재생 속도. 복습할 때 1.5배속이 흔하다. */
    val speed: Float = 1f,
) {
    val recordingId: Long get() = recording?.id ?: -1L
}

/**
 * 앱 전체가 함께 쓰는 재생기. 예전에는 녹음 탭의 ViewModel 이 들고 있어서 탭을 옮기면
 * 미니 플레이어가 사라졌다. 프로세스에 하나만 두고 모든 화면이 같은 상태를 본다.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state = _state.asStateFlow()

    /** 같은 녹음이면 재생/일시정지, 다른 녹음이면 그것을 처음부터. */
    fun toggle(recording: Recording) {
        val current = _state.value
        if (current.recordingId == recording.id) {
            val p = player ?: return
            if (current.playing) {
                runCatching { p.pause() }
                _state.value = current.copy(playing = false)
            } else {
                runCatching { p.start() }
                if (current.speed != 1f) applySpeed(p, current.speed)
                _state.value = current.copy(playing = true)
                startTicker()
            }
            return
        }
        scope.launch {
            // 마무리가 덜 된(pending) 파일이면 먼저 확정해서 재생 가능하게 만든다.
            val ready = if (recording.ongoing || recording.sizeBytes == 0L) repo.forceFinalize(recording) else recording
            withContext(Dispatchers.Main) {
                if (tryPlay(ready)) return@withContext
                // 그래도 실패하면 한 번 더 강제 확정 후 재시도
                val fixed = withContext(Dispatchers.IO) { repo.forceFinalize(ready) }
                tryPlay(fixed)
            }
        }
    }

    private fun tryPlay(recording: Recording): Boolean {
        val speed = _state.value.speed
        release()
        return try {
            val p = MediaPlayer().apply {
                setDataSource(app, Uri.parse(recording.uri))
                setOnCompletionListener { onCompleted() }
                prepare()
                start()
            }
            player = p
            if (speed != 1f) applySpeed(p, speed)
            _state.value = PlaybackState(recording, playing = true, positionMs = 0, durationMs = p.duration, speed = speed)
            startTicker()
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "재생 실패: recordingId=${recording.id}", e)
            _state.value = PlaybackState(speed = speed)
            false
        }
    }

    /** 끝까지 들으면 미니 플레이어는 남기고 처음으로 되감는다. 다시 누르면 처음부터. */
    private fun onCompleted() {
        runCatching { player?.seekTo(0) }
        _state.value = _state.value.copy(playing = false, positionMs = 0)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value.playing) {
                val p = player ?: break
                _state.value = _state.value.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(0))
                delay(TICK_MS)
            }
        }
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        runCatching { p.seekTo(ms) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    /** 지금 위치에서 [deltaMs] 만큼 앞/뒤로. */
    fun seekBy(deltaMs: Int) {
        val s = _state.value
        if (s.recording == null) return
        seekTo((s.positionMs + deltaMs).coerceIn(0, s.durationMs.coerceAtLeast(0)))
    }

    fun setSpeed(speed: Float) {
        val p = player
        val s = _state.value
        // 일시정지 중에 속도를 바꾸면 일부 기기에서 재생이 시작돼 버린다. 재생 중일 때만 바로 적용하고,
        // 아니면 다음 재생 때 적용한다.
        if (p != null && s.playing) applySpeed(p, speed)
        _state.value = s.copy(speed = speed)
    }

    private fun applySpeed(p: MediaPlayer, speed: Float) {
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
            .onFailure { AppLog.w(TAG, "재생 속도 변경 실패", it) }
    }

    /** 재생을 끝내고 미니 플레이어를 닫는다(밀어서 치우기). 속도 설정은 남긴다. */
    fun stop() {
        release()
        _state.value = PlaybackState(speed = _state.value.speed)
    }

    /** 목록에서 지우거나 압축으로 파일이 바뀌는 녹음이면 먼저 놓는다. */
    fun releaseIfPlaying(recordingId: Long) {
        if (_state.value.recordingId == recordingId) stop()
    }

    private fun release() {
        ticker?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val TICK_MS = 250L
    }
}
