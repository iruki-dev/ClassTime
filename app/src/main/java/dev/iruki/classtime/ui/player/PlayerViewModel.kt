package dev.iruki.classtime.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.Paragraph
import dev.iruki.classtime.ai.Timestamps
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.audio.PlaybackController
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.ai.TranscriptView
import dev.iruki.classtime.util.SystemScreens
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 과목 표시에 필요한 것: 아이콘 키와 시간표 색. 시간표에서 지운 과목은 없을 수 있다. */
data class SubjectStyle(val icon: String, val colorArgb: Int?)

/**
 * 미니 플레이어와 재생 화면이 함께 쓰는 ViewModel. 실제 재생은 앱 전체가 공유하는
 * [PlaybackController] 가 하므로, 탭을 옮겨도 같은 상태가 이어진다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val player: PlaybackController,
    private val storage: RecordingStorage,
    private val repo: ClassTimeRepository,
    private val queue: TranscriptionQueue,
    aiSettings: AiSettings,
) : ViewModel() {

    val state: StateFlow<PlaybackState> = player.state

    /** 재생하지 못한 이유(권한·파일). 화면이 스낵바로 알린다. */
    val problems: SharedFlow<PlaybackController.Problem> = player.problems

    val styles: StateFlow<Map<String, SubjectStyle>> = repo.courses
        .map { list -> list.associate { it.subject to SubjectStyle(it.icon, it.colorArgb) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 실험적 기능(AI 텍스트 변환) 설정. 꺼져 있으면 텍스트 탭은 이미 있는 글만 보여 준다. */
    val ai: StateFlow<AiConfig> = aiSettings.config

    /** 지금 플레이어에 올라 있는 녹음의 텍스트. 없으면 null. */
    val transcript: StateFlow<TranscriptView?> = player.state
        .map { it.recordingId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id < 0) flowOf(null)
            else combine(queue.observe(id), queue.observeAll()) { t, all ->
                t?.let { mine ->
                    val ahead = all.count { it.stateEnum.active && it.recordingId != id && it.queuedAt < mine.queuedAt }
                    TranscriptView.of(mine, ahead)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(recording: Recording) = player.toggle(recording)
    fun seekTo(ms: Int) = player.seekTo(ms)
    fun seekBy(deltaMs: Int) = player.seekBy(deltaMs)
    fun setSpeed(speed: Float) = player.setSpeed(speed)

    /** 미니 플레이어를 밀어서 치웠을 때. */
    fun close() = player.stop()

    /** 알림·목록에서 텍스트를 열 때: 녹음을 재생기에 올리되 재생은 하지 않는다. */
    fun openForText(recordingId: Long, then: () -> Unit) = viewModelScope.launch {
        val rec = repo.recording(recordingId) ?: return@launch
        player.open(rec)
        then()
    }

    fun openFolder() = SystemScreens.openRecordingsFolder(app)

    fun delete(recording: Recording) {
        player.releaseIfPlaying(recording.id)
        viewModelScope.launch(Dispatchers.IO) {
            storage.delete(Uri.parse(recording.uri))
            queue.remove(recording.id)
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

    // --- 텍스트 ---

    fun convert(recordingId: Long) = viewModelScope.launch { queue.enqueue(recordingId) }
    fun cancelText(recordingId: Long) = viewModelScope.launch { queue.remove(recordingId) }
    fun retryText(recordingId: Long) = viewModelScope.launch { queue.retry(recordingId) }

    /** 처음부터 다시: 결과를 지우고 새로 맡긴다. */
    fun reconvert(recordingId: Long) = viewModelScope.launch {
        queue.remove(recordingId)
        queue.enqueue(recordingId)
    }

    fun deleteText(recordingId: Long) = viewModelScope.launch { queue.remove(recordingId) }

    fun copyText(recording: Recording, paragraphs: List<Paragraph>) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(recording.subject, plainText(recording, paragraphs)))
    }

    fun shareText(recording: Recording, paragraphs: List<Paragraph>) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, recording.fileName.removeSuffix(".m4a"))
            putExtra(Intent.EXTRA_TEXT, plainText(recording, paragraphs))
        }
        app.startActivity(
            Intent.createChooser(intent, app.getString(R.string.ai_menu_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 내보내는 글: 제목 한 줄 + 문단마다 [시각]. */
    private fun plainText(recording: Recording, paragraphs: List<Paragraph>): String = buildString {
        append(recording.fileName.removeSuffix(".m4a")).append("\n\n")
        paragraphs.forEach { append('[').append(Timestamps.format(it.startMs)).append("] ").append(it.text).append("\n\n") }
    }.trimEnd()
}
