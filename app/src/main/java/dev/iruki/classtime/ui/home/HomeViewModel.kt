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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

/** 수동 녹음 시트에서 고를 수 있는 과목. */
data class SubjectOption(val subject: String, val colorArgb: Int)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ClassTimeRepository,
    private val settings: AppSettings,
) : ViewModel() {

    val status: StateFlow<RecordingStatus> = repo.status

    /** 대기 모드 실제 상태(서비스가 마이크를 쥐고 떠 있는지). */
    val standby: StateFlow<StandbyState> = repo.standby

    /** 녹음 중 마이크 입력 크기. ‘소리가 들어오고 있어요’ 표시용. */
    val inputLevel: StateFlow<Int> = repo.inputLevel

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

    /** null 이면 아직 모름(첫 로딩). 빈 시간표 안내가 잠깐 깜빡이지 않게 한다. */
    val hasAnyCourse: StateFlow<Boolean?> = repo.courses
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 시트에서 고를 과목들. 같은 과목의 여러 교시는 하나로. */
    val subjects: StateFlow<List<SubjectOption>> = repo.courses
        .map { list ->
            list.distinctBy { it.groupId }
                .map { SubjectOption(it.subject, it.colorArgb) }
                .distinctBy { it.subject }
                .sortedBy { it.subject }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 오늘 끝까지 녹음된 과목들. 목록에서 ‘녹음됨’은 실제 파일이 있을 때만 표시한다. */
    val recordedToday: StateFlow<Set<String>> = combine(repo.recordings, repo.currentDate) { list, date ->
        val zone = ZoneId.systemDefault()
        list.filter { !it.ongoing && Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == date }
            .map { it.subject }
            .toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val termPhase: StateFlow<TermPhase> = combine(repo.term, repo.currentDate) { term, date ->
        phaseOf(term, date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TermPhase.NONE)

    /** 학기 중이면 개강일 기준 몇 주차인지. 개강일이 없으면 null. */
    val termWeek: StateFlow<Int?> = combine(repo.term, repo.currentDate) { term, date ->
        weekOf(term, date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 1초마다 갱신되는 지금 시각. 경과 시간·남은 시간·다음 수업 계산에 쓴다. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    private val _currentSubject = MutableStateFlow<String?>(null)
    val currentSubject = _currentSubject.asStateFlow()

    fun refreshCurrentSubject() = viewModelScope.launch {
        // 보강은 과목명이 빈 문자열일 수 있다. 빈 값을 그대로 흘리면 화면에
        // 빈 과목명이 찍히므로 null 로 정규화한다.
        _currentSubject.value = repo.currentSession()?.subject?.takeIf { it.isNotBlank() }
    }

    fun startManual(subjectOverride: String? = null) =
        RecordingService.startManual(app, subjectOverride)

    fun stop() = RecordingService.stop(app)

    /** 수업이 늦게 끝날 것 같을 때 녹음 끝을 [minutes] 분 미룬다. */
    fun extend(minutes: Int) = RecordingService.extend(app, minutes)

    private fun phaseOf(term: Term?, today: LocalDate): TermPhase {
        if (term == null || (term.startDate == null && term.endDate == null)) return TermPhase.NONE
        return when {
            term.startDate != null && today.isBefore(term.startDate) -> TermPhase.BEFORE
            term.endDate != null && today.isAfter(term.endDate) -> TermPhase.AFTER
            else -> TermPhase.DURING
        }
    }

    companion object {
        /** 개강일이 속한 주를 1주차로 센다(월요일 시작). 학기 밖이면 null. */
        fun weekOf(term: Term?, today: LocalDate): Int? {
            val start = term?.startDate ?: return null
            if (today.isBefore(start)) return null
            if (term.endDate != null && today.isAfter(term.endDate)) return null
            val startMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
            return (ChronoUnit.DAYS.between(startMonday, today) / 7 + 1).toInt()
        }
    }
}
