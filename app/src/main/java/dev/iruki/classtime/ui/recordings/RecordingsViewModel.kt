package dev.iruki.classtime.ui.recordings

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.util.AppLog
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 재생 상태. [recordingId] 가 -1 이면 정지 상태. */
data class PlaybackState(
    val recordingId: Long = -1L,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    private val storage: RecordingStorage,
) : ViewModel() {
    private var player: MediaPlayer? = null

    /** 과목별로 묶은 목록. 최신 녹음이 있는 과목이 위로. */
    val grouped: StateFlow<List<Pair<String, List<Recording>>>> = repo.recordings
        .map { list ->
            list.groupBy { it.subject }
                .entries
                .sortedByDescending { entry -> entry.value.maxOf { it.startedAt } }
                .map { it.key to it.value.sortedByDescending { r -> r.startedAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playback = MutableStateFlow(PlaybackState())
    val playback = _playback.asStateFlow()

    /** 지금 실제로 녹음이 진행 중인지. false 면 'ongoing' 으로 남은 행은 재생 가능한 완료본으로 취급. */
    val recordingActive: StateFlow<Boolean> = repo.status
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            while (true) {
                val p = player
                if (p != null && _playback.value.playing) {
                    _playback.value = _playback.value.copy(
                        positionMs = runCatching { p.currentPosition }.getOrDefault(0)
                    )
                }
                delay(500)
            }
        }
    }

    fun toggle(recording: Recording) {
        val current = _playback.value
        if (current.recordingId == recording.id) {
            val p = player ?: return
            if (current.playing) {
                p.pause()
                _playback.value = current.copy(playing = false)
            } else {
                p.start()
                _playback.value = current.copy(playing = true)
            }
            return
        }
        viewModelScope.launch {
            // 마무리가 덜 된(pending) 파일이면 먼저 확정해서 재생 가능하게 만든다.
            val ready = if (recording.ongoing || recording.sizeBytes == 0L) {
                repo.forceFinalize(recording)
            } else {
                recording
            }
            if (!tryPlay(ready)) {
                // 그래도 실패하면 한 번 더 강제 확정 후 재시도
                val fixed = repo.forceFinalize(ready)
                tryPlay(fixed)
            }
        }
    }

    private fun tryPlay(recording: Recording): Boolean {
        release()
        return try {
            val p = MediaPlayer().apply {
                setDataSource(app, Uri.parse(recording.uri))
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            player = p
            _playback.value = PlaybackState(
                recordingId = recording.id,
                playing = true,
                positionMs = 0,
                durationMs = p.duration,
            )
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "재생 실패: recordingId=${recording.id}", e)
            _playback.value = PlaybackState()
            false
        }
    }

    fun seekTo(ms: Int) {
        player?.let {
            runCatching { it.seekTo(ms) }
            _playback.value = _playback.value.copy(positionMs = ms)
        }
    }

    fun stopPlayback() {
        release()
        _playback.value = PlaybackState()
    }

    private fun release() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    fun delete(recording: Recording) = viewModelScope.launch(Dispatchers.IO) {
        if (_playback.value.recordingId == recording.id) stopPlayback()
        storage.delete(Uri.parse(recording.uri))
        repo.deleteRecording(recording)
    }

    fun rename(recording: Recording, newBaseName: String) = viewModelScope.launch(Dispatchers.IO) {
        val trimmed = newBaseName.trim()
        if (trimmed.isBlank()) return@launch
        val newFileName = if (trimmed.endsWith(".m4a")) trimmed else "$trimmed.m4a"
        if (storage.rename(Uri.parse(recording.uri), newFileName)) {
            repo.updateRecording(recording.copy(fileName = newFileName))
        }
    }

    /** 다른 앱(드라이브, 메일, 카톡 등)으로 파일 보내기. PC 로 옮기는 또 하나의 경로. */
    fun share(recording: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(recording.uri))
            putExtra(Intent.EXTRA_SUBJECT, recording.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(
            Intent.createChooser(intent, app.getString(R.string.recordings_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 여러 개를 한 번에 보내기 (과목 폴더 통째로 옮길 때). */
    fun shareAll(recordings: List<Recording>) {
        if (recordings.isEmpty()) return
        if (recordings.size == 1) return share(recordings.first())
        val uris = ArrayList(recordings.map { Uri.parse(it.uri) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(
            Intent.createChooser(intent, app.getString(R.string.recordings_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val TAG = "RecordingsViewModel"
    }
}
