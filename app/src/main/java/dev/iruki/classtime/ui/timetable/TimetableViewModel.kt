package dev.iruki.classtime.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.schedule.ScheduleManager
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 한 과목이 열리는 한 교시: 요일 + 시작/종료(분). 같은 요일에 여러 개일 수도, 요일마다 시간이 달라도 됨. */
data class Slot(val dayOfWeek: Int, val startMinute: Int, val endMinute: Int)

/** 편집 화면이 다루는 "한 과목"(여러 교시를 아우름). */
data class CourseGroup(
    val groupId: String,
    val subject: String,
    val professor: String,
    val room: String,
    val autoRecord: Boolean,
    val colorArgb: Int,
    val icon: String,
    val slots: List<Slot>,
)

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repo: ClassTimeRepository,
    private val scheduleManager: ScheduleManager,
) : ViewModel() {

    val courses: StateFlow<List<Course>> = repo.courses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 지금 보고 있는 주(월요일 시작)의 휴강·보강. */
    val weekExceptions: StateFlow<List<ScheduleException>> =
        combine(repo.exceptions, repo.currentDate) { list, today ->
            val monday = weekStart(today)
            val sunday = monday.plusDays(6)
            list.filter { !it.date.isBefore(monday) && !it.date.isAfter(sunday) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val today: StateFlow<LocalDate> = repo.currentDate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDate.now())

    /** 과목명별 (녹음 수, 총 용량). 수업 상세 시트에 쓴다. */
    val recordingStats: StateFlow<Map<String, Pair<Int, Long>>> = repo.recordings
        .map { list ->
            list.groupBy { it.subject }.mapValues { (_, rows) -> rows.size to rows.sumOf { it.sizeBytes } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 과목 전체(모든 교시)의 자동 녹음을 켜고 끈다. */
    fun setAutoRecord(groupId: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repo.coursesInGroup(groupId).forEach { repo.upsertCourse(it.copy(autoRecord = enabled)) }
        scheduleManager.rescheduleAll()
    }

    /** 오늘 이후 가장 가까운 이 과목 수업 하루를 휴강으로. 이미 지난 오늘 수업은 건너뛴다. */
    fun cancelNext(groupId: String, nowMinute: Int, today: LocalDate = LocalDate.now()) =
        viewModelScope.launch(Dispatchers.IO) {
            val rows = repo.coursesInGroup(groupId)
            if (rows.isEmpty()) return@launch
            val date = (0..7).asSequence()
                .map { today.plusDays(it.toLong()) }
                .firstOrNull { d ->
                    rows.any { it.dayOfWeek == d.dayOfWeek.value && (d != today || it.endMinute > nowMinute) }
                } ?: return@launch
            repo.upsertException(ScheduleException.cancel(date, groupId, rows.first().subject))
            scheduleManager.rescheduleAll()
        }

    suspend fun loadGroup(groupId: String): CourseGroup? =
        repo.coursesInGroup(groupId).takeIf { it.isNotEmpty() }?.toGroup()

    /**
     * 과목 하나를 저장한다. [slots] 하나당 [Course] 행이 하나씩 생긴다.
     * 기존 그룹을 수정할 때는 행을 모두 지우고 새 교시로 다시 만든다(알람도 함께 정리).
     * 교시 수·요일·시간이 자유롭게 바뀔 수 있으므로 이 방식이 가장 단순하고 안전하다.
     */
    fun saveGroup(
        groupId: String?,
        subject: String,
        professor: String,
        room: String,
        autoRecord: Boolean,
        colorArgb: Int,
        icon: String,
        slots: List<Slot>,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val gid = groupId ?: UUID.randomUUID().toString()

        if (groupId != null) {
            repo.coursesInGroup(groupId).forEach { scheduleManager.cancelCourse(it.id) }
            repo.deleteCourseGroup(groupId)
        }
        // 완전히 동일한 교시(요일+시간)는 중복 제거
        slots.distinct().forEach { slot ->
            repo.upsertCourse(
                Course(
                    groupId = gid,
                    subject = subject,
                    professor = professor,
                    room = room,
                    dayOfWeek = slot.dayOfWeek,
                    startMinute = slot.startMinute,
                    endMinute = slot.endMinute,
                    autoRecord = autoRecord,
                    colorArgb = colorArgb,
                    icon = icon,
                )
            )
        }
        scheduleManager.rescheduleAll()
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.coursesInGroup(groupId).forEach { scheduleManager.cancelCourse(it.id) }
        repo.deleteCourseGroup(groupId)
        scheduleManager.rescheduleAll()
    }

    private fun List<Course>.toGroup(): CourseGroup {
        val first = minWithOrNull(compareBy({ it.dayOfWeek }, { it.startMinute })) ?: first()
        return CourseGroup(
            groupId = first.groupId,
            subject = first.subject,
            professor = first.professor,
            room = first.room,
            autoRecord = first.autoRecord,
            colorArgb = first.colorArgb,
            icon = first.icon,
            slots = sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute }))
                .map { Slot(it.dayOfWeek, it.startMinute, it.endMinute) },
        )
    }

    companion object {
        fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
    }
}
