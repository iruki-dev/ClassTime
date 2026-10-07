package dev.iruki.classtime.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.LibraryMaintenance
import dev.iruki.classtime.data.RescanResult
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.data.TimetableInference
import dev.iruki.classtime.schedule.ScheduleManager
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 시간표 다시 만들기 진행 단계. */
sealed interface RebuildState {
    data object Idle : RebuildState
    data object Working : RebuildState
    data class Preview(
        val results: List<TimetableInference.Result>,
        val existingSubjects: Set<String>,
        val recordingCount: Int,
    ) : RebuildState
    data class Done(val added: Int) : RebuildState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    private val settings: AppSettings,
    private val maintenance: LibraryMaintenance,
    private val scheduleManager: ScheduleManager,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val reminderMinutes: StateFlow<Int> = settings.reminderMinutes

    /** 알림 시점을 바꾸면 이미 걸린 알람도 새 시점으로 다시 건다. */
    fun setReminderMinutes(minutes: Int) {
        settings.setReminderMinutes(minutes)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { scheduleManager.rescheduleAll() }
                .onFailure { AppLog.e(TAG, "알림 시점 변경 후 재설정 실패", it) }
        }
    }

    // --- 대기 모드 ---

    val standby: StateFlow<StandbyState> = repo.standby

    private val _standbyEnabled = MutableStateFlow(settings.standbyEnabled)
    val standbyEnabled: StateFlow<Boolean> = _standbyEnabled.asStateFlow()

    fun setStandbyEnabled(enabled: Boolean) {
        settings.standbyEnabled = enabled
        _standbyEnabled.value = enabled
        if (enabled) RecordingService.startStandby(app) else RecordingService.stopStandby(app)
    }

    // --- 폴더 검사 ---

    private val _rescanning = MutableStateFlow(false)
    val rescanning = _rescanning.asStateFlow()

    /** 마지막 검사 결과. 설정 줄에 한 줄로 남긴다(화면을 떠나면 사라짐). */
    private val _rescanResult = MutableStateFlow<RescanResult?>(null)
    val rescanResult = _rescanResult.asStateFlow()

    fun rescan() {
        if (_rescanning.value) return
        _rescanning.value = true
        viewModelScope.launch {
            _rescanResult.value = runCatching { maintenance.rescan() }
                .onFailure { AppLog.e(TAG, "폴더 검사 실패", it) }
                .getOrDefault(RescanResult(0, 0))
            _rescanning.value = false
        }
    }

    // --- 시간표 다시 만들기 ---

    private val _rebuild = MutableStateFlow<RebuildState>(RebuildState.Idle)
    val rebuild = _rebuild.asStateFlow()

    fun startRebuild() {
        if (_rebuild.value == RebuildState.Working) return
        _rebuild.value = RebuildState.Working
        viewModelScope.launch {
            val results = runCatching { maintenance.inferTimetable() }
                .onFailure { AppLog.e(TAG, "시간표 짐작 실패", it) }
                .getOrDefault(emptyList())
            val existing = repo.allCourses().map { it.subject }.toSet()
            val count = repo.allRecordings().count { !it.ongoing }
            _rebuild.value = RebuildState.Preview(results, existing, count)
        }
    }

    fun applyRebuild() {
        val preview = _rebuild.value as? RebuildState.Preview ?: return
        _rebuild.value = RebuildState.Working
        viewModelScope.launch {
            val added = runCatching { maintenance.applyInferred(preview.results) }
                .onFailure { AppLog.e(TAG, "시간표 복원 실패", it) }
                .getOrDefault(0)
            _rebuild.value = RebuildState.Done(added)
        }
    }

    fun dismissRebuild() {
        _rebuild.value = RebuildState.Idle
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
