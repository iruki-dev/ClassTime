package dev.iruki.classtime.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.PlaybackController
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.util.SystemScreens
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 과목 표시에 필요한 것: 아이콘 키와 시간표 색. 시간표에서 지운 과목은 없을 수 있다. */
data class SubjectStyle(val icon: String, val colorArgb: Int?)

/**
 * 미니 플레이어와 재생 화면이 함께 쓰는 ViewModel. 실제 재생은 앱 전체가 공유하는
 * [PlaybackController] 가 하므로, 탭을 옮겨도 같은 상태가 이어진다.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val player: PlaybackController,
    private val storage: RecordingStorage,
    private val repo: ClassTimeRepository,
) : ViewModel() {

    val state: StateFlow<PlaybackState> = player.state

    val styles: StateFlow<Map<String, SubjectStyle>> = repo.courses
        .map { list -> list.associate { it.subject to SubjectStyle(it.icon, it.colorArgb) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun toggle(recording: Recording) = player.toggle(recording)
    fun seekTo(ms: Int) = player.seekTo(ms)
    fun seekBy(deltaMs: Int) = player.seekBy(deltaMs)
    fun setSpeed(speed: Float) = player.setSpeed(speed)

    /** 미니 플레이어를 밀어서 치웠을 때. */
    fun close() = player.stop()

    fun openFolder() = SystemScreens.openRecordingsFolder(app)

    fun delete(recording: Recording) {
        player.releaseIfPlaying(recording.id)
        viewModelScope.launch(Dispatchers.IO) {
            storage.delete(Uri.parse(recording.uri))
            repo.deleteRecording(recording)
        }
    }

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
}
