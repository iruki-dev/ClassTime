package dev.iruki.classtime.ui.recordings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.audio.PlaybackController
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.TextFiles
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.ui.share.RecordingExport
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    private val storage: RecordingStorage,
    private val player: PlaybackController,
    private val transcription: TranscriptionQueue,
    private val textFiles: TextFiles,
    private val export: RecordingExport,
    aiSettings: AiSettings,
) : ViewModel() {

    /** 실험적 기능: 녹음 id → 텍스트 변환 상태. 기능을 쓰지 않으면 비어 있다. */
    val transcripts: StateFlow<Map<Long, Transcript>> = transcription.observeAll()
        .map { list -> list.associateBy { it.recordingId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val ai: StateFlow<AiConfig> = aiSettings.config

    fun convert(recording: Recording) = viewModelScope.launch { transcription.enqueue(recording.id) }

    /** 과목별로 묶은 목록. 최신 녹음이 있는 과목이 위로. */
    val grouped: StateFlow<List<Pair<String, List<Recording>>>> = repo.recordings
        .map { list ->
            list.groupBy { it.subject }
                .entries
                .sortedByDescending { entry -> entry.value.maxOf { it.startedAt } }
                .map { it.key to it.value.sortedByDescending { r -> r.startedAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 과목명 → 아이콘 키. 시간표에서 지운 과목은 없을 수 있다(그때는 이름으로 짐작). */
    val subjectIcons: StateFlow<Map<String, String>> = repo.courses
        .map { list -> list.associate { it.subject to it.icon } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 과목 칩으로 거르기. 여러 개를 고를 수 있고, 비어 있으면 전체. */
    private val _filter = MutableStateFlow<Set<String>>(emptySet())
    val filter = _filter.asStateFlow()

    fun toggleFilter(subject: String) {
        _filter.value = _filter.value.let { if (subject in it) it - subject else it + subject }
    }

    fun clearFilter() {
        _filter.value = emptySet()
    }

    val playback: StateFlow<PlaybackState> = player.state

    /** 지금 실제로 녹음이 진행 중인지. false 면 'ongoing' 으로 남은 행은 재생 가능한 완료본으로 취급. */
    val recordingActive: StateFlow<Boolean> = repo.status
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggle(recording: Recording) = player.toggle(recording)

    fun delete(recording: Recording) = viewModelScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { player.releaseIfPlaying(recording.id) }
        storage.delete(Uri.parse(recording.uri))
        transcription.remove(recording.id)
        repo.deleteRecording(recording)
    }

    fun rename(recording: Recording, newBaseName: String) = viewModelScope.launch(Dispatchers.IO) {
        val trimmed = newBaseName.trim()
        if (trimmed.isBlank()) return@launch
        val newFileName = if (trimmed.endsWith(".m4a")) trimmed else "$trimmed.m4a"
        if (storage.rename(Uri.parse(recording.uri), newFileName)) {
            runCatching { textFiles.rename(recording, newFileName) }
            repo.updateRecording(recording.copy(fileName = newFileName))
        }
    }

    /**
     * 다른 앱(드라이브, 메일, 카톡 등)으로 보내기. PC 로 옮기는 또 하나의 경로.
     * 여러 개면 과목 폴더 통째로. 텍스트가 있으면 화면이 무엇을 보낼지 먼저 묻는다.
     */
    fun export(recordings: List<Recording>, audio: Boolean = true, text: Boolean = false) =
        viewModelScope.launch { export.send(recordings, audio, text) }

}
