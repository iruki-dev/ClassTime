package dev.iruki.classtime.ui.term

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.data.Term
import dev.iruki.classtime.schedule.ScheduleManager
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 드롭다운용 과목 요약. */
data class CourseOption(val groupId: String, val subject: String)

class TermViewModel(
    private val app: Application,
    private val repo: ClassTimeRepository,
) : ViewModel() {

    val term: StateFlow<Term?> = repo.term
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 오늘 이후의 예외만 (지난 것은 자동 정리되지만 표시에서도 숨긴다). */
    val upcomingExceptions: StateFlow<List<ScheduleException>> = repo.exceptions
        .map { list -> list.filter { !it.date.isBefore(LocalDate.now()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val courseOptions: StateFlow<List<CourseOption>> = repo.courses
        .map { list ->
            list.groupBy { it.groupId }
                .map { (gid, rows) -> CourseOption(gid, rows.first().subject) }
                .sortedBy { it.subject }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTerm(start: LocalDate?, end: LocalDate?) = viewModelScope.launch(Dispatchers.IO) {
        repo.upsertTerm(Term.of(start, end))
        ScheduleManager.rescheduleAll(app)
    }

    fun addHoliday(date: LocalDate) = viewModelScope.launch(Dispatchers.IO) {
        repo.upsertException(ScheduleException.cancel(date, courseGroupId = null, subject = "공휴일"))
        ScheduleManager.rescheduleAll(app)
    }

    fun addCancel(date: LocalDate, groupId: String, subject: String) =
        viewModelScope.launch(Dispatchers.IO) {
            repo.upsertException(ScheduleException.cancel(date, groupId, subject))
            ScheduleManager.rescheduleAll(app)
        }

    fun addMakeup(
        date: LocalDate,
        subject: String,
        groupId: String?,
        startMinute: Int,
        endMinute: Int,
        autoRecord: Boolean,
    ) = viewModelScope.launch(Dispatchers.IO) {
        repo.upsertException(
            ScheduleException.makeup(
                date = date,
                subject = subject,
                startMinute = startMinute,
                endMinute = endMinute,
                courseGroupId = groupId,
                autoRecord = autoRecord,
            )
        )
        ScheduleManager.rescheduleAll(app)
    }

    fun remove(exception: ScheduleException) = viewModelScope.launch(Dispatchers.IO) {
        exception.takeIf { it.id != 0L }?.let {
            ScheduleManager.cancelMakeup(app, it.id)
            repo.deleteException(it)
            ScheduleManager.rescheduleAll(app)
        }
    }
}
