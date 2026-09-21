package dev.iruki.classtime.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.Session
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.data.Term
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.util.AppSettings
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 홈 화면에 보여줄 학기 상태. */
enum class TermPhase { BEFORE, DURING, AFTER, NONE }

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    private val settings: AppSettings,
) : ViewModel() {

    val status: StateFlow<RecordingStatus> = repo.status

    /** 대기 모드 실제 상태(서비스가 마이크를 쥐고 떠 있는지). */
    val standby: StateFlow<StandbyState> = repo.standby

    private val _standbyEnabled = MutableStateFlow(settings.standbyEnabled)
    val standbyEnabled: StateFlow<Boolean> = _standbyEnabled.asStateFlow()

    /** 자동 녹음을 쓰는 과목이 하나라도 있는지. 없으면 대기 모드를 권할 이유가 없다. */
    val hasAutoCourse: StateFlow<Boolean> = repo.courses
        .map { list -> list.any { it.autoRecord } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 대기 모드 켜기/끄기. 켤 때는 이 화면이 보이는 중이므로 서비스가 마이크 권한을
     * 함께 잡는다. 이 타이밍이 자동 녹음 소리 유무를 가른다.
     */
    fun setStandbyEnabled(enabled: Boolean) {
        settings.standbyEnabled = enabled
        _standbyEnabled.value = enabled
        if (enabled) RecordingService.startStandby(app) else RecordingService.stopStandby(app)
    }

    /** 오늘 실제로 열리는 수업(휴강 제외, 보강 포함, 학기 반영). */
    val todaySessions: StateFlow<List<Session>> = repo.todaySessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAnyCourse: StateFlow<Boolean> = repo.courses
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val termPhase: StateFlow<TermPhase> = combine(repo.term, repo.currentDate) { term, date ->
        phaseOf(term, date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TermPhase.NONE)

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs = _elapsedMs.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val s = repo.status.value
                _elapsedMs.value = if (s.active) System.currentTimeMillis() - s.startedAt else 0L
                delay(1_000)
            }
        }
    }

    private val _currentSubject = MutableStateFlow<String?>(null)
    val currentSubject = _currentSubject.asStateFlow()

    fun refreshCurrentSubject() = viewModelScope.launch {
        // 보강은 과목명이 빈 문자열일 수 있다. 빈 값을 그대로 흘리면 화면에
        // "지금은 ‘’ 시간입니다" 가 찍히므로 null 로 정규화한다.
        _currentSubject.value = repo.currentSession()?.subject?.takeIf { it.isNotBlank() }
    }

    fun startManual(subjectOverride: String? = null) =
        RecordingService.startManual(app, subjectOverride)

    fun stop() = RecordingService.stop(app)

    private fun phaseOf(term: Term?, today: LocalDate): TermPhase {
        if (term == null || (term.startDate == null && term.endDate == null)) return TermPhase.NONE
        return when {
            term.startDate != null && today.isBefore(term.startDate) -> TermPhase.BEFORE
            term.endDate != null && today.isAfter(term.endDate) -> TermPhase.AFTER
            else -> TermPhase.DURING
        }
    }
}
