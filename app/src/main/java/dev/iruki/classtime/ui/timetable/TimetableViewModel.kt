package dev.iruki.classtime.ui.timetable

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.schedule.ScheduleManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val slots: List<Slot>,
)

class TimetableViewModel(
    private val app: Application,
    private val repo: ClassTimeRepository,
) : ViewModel() {

    val courses: StateFlow<List<Course>> = repo.courses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        slots: List<Slot>,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val gid = groupId ?: UUID.randomUUID().toString()

        if (groupId != null) {
            repo.coursesInGroup(groupId).forEach { ScheduleManager.cancelCourse(app, it.id) }
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
                )
            )
        }
        ScheduleManager.rescheduleAll(app)
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.coursesInGroup(groupId).forEach { ScheduleManager.cancelCourse(app, it.id) }
        repo.deleteCourseGroup(groupId)
        ScheduleManager.rescheduleAll(app)
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
            slots = sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute }))
                .map { Slot(it.dayOfWeek, it.startMinute, it.endMinute) },
        )
    }
}
