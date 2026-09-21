package dev.iruki.classtime.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.CourseMatching
import dev.iruki.classtime.data.ExceptionType
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 시간표 + 학기 + 예외(휴강/보강)를 정확 알람으로 변환한다.
 *
 * - [rescheduleAll] : 모든 알람을 지우고 다시 건다. 절대 녹음을 직접 시작하지 않는다.
 * - [catchUpIfMidClass] : 앱 실행/부팅 시점에만 호출. 지금이 수업 중인데 녹음이 없으면 시작한다.
 *
 * 이렇게 "재설정"과 "놓친 녹음 따라잡기"를 분리해서, 종료 알람이 재설정을 트리거해도
 * 방금 끝난 수업이 다시 녹음되는 일이 없도록 한다.
 */
@Singleton
class ScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ClassTimeRepository,
) {

    private fun alarmManager() =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** id 를 요청코드로. 상위 비트를 잘라내 부호 반전이나 MAKEUP_BIT 침범을 막는다. */
    private fun requestCode(id: Long, start: Boolean, makeup: Boolean): Int {
        val body = ((id and ID_MASK).toInt() shl 1) or (if (start) 0 else 1)
        return if (makeup) MAKEUP_BIT or body else body
    }

    private fun courseRequestCode(courseId: Long, start: Boolean): Int =
        requestCode(courseId, start, makeup = false)

    private fun makeupRequestCode(exceptionId: Long, start: Boolean): Int =
        requestCode(exceptionId, start, makeup = true)

    private fun pi(requestCode: Int, start: Boolean, extraKey: String, id: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = if (start) ACTION_START else ACTION_STOP
            putExtra(extraKey, id)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun coursePi(courseId: Long, start: Boolean) =
        pi(courseRequestCode(courseId, start), start, EXTRA_COURSE_ID, courseId)

    private fun makeupPi(exceptionId: Long, start: Boolean) =
        pi(makeupRequestCode(exceptionId, start), start, EXTRA_EXCEPTION_ID, exceptionId)

    // --- 개별 취소 (VM 이 과목/예외 삭제 시 호출) ---

    fun cancelCourse(courseId: Long) {
        val am = alarmManager()
        am.cancel(coursePi(courseId, true))
        am.cancel(coursePi(courseId, false))
    }

    fun cancelMakeup(exceptionId: Long) {
        val am = alarmManager()
        am.cancel(makeupPi(exceptionId, true))
        am.cancel(makeupPi(exceptionId, false))
    }

    // --- 전체 재설정 ---

    suspend fun rescheduleAll() {
        val am = alarmManager()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        repo.pruneOldExceptions()

        val courses = repo.allCourses()
        val exceptions = repo.allExceptions()
        val term = repo.termOnce()

        var armed = 0

        // 정규 수업: 시작 알람과 종료 알람의 날짜를 각각 구한다.
        // (이미 진행 중인 수업이면 종료 날짜 = 오늘, 시작 날짜 = 다음 주 → 오늘 종료 알람이 유지됨)
        for (course in courses) {
            am.cancel(coursePi(course.id, true))
            am.cancel(coursePi(course.id, false))
            if (!course.autoRecord) continue

            val startDate = CourseMatching.nextValidDate(
                course.dayOfWeek, course.startMinute, course.groupId, exceptions, term, now,
            )
            val endDate = CourseMatching.nextValidDate(
                course.dayOfWeek, course.endMinute, course.groupId, exceptions, term, now,
            )
            if (startDate == null && endDate == null) continue
            startDate?.let {
                scheduleAt(am, coursePi(course.id, true), it, course.startMinute)
            }
            endDate?.let {
                scheduleAt(am, coursePi(course.id, false), it, course.endMinute)
            }
            armed++
        }

        // 보강: 미래 날짜 & 학기 안 & 자동 녹음 켜짐인 것만.
        for (ex in exceptions) {
            am.cancel(makeupPi(ex.id, true))
            am.cancel(makeupPi(ex.id, false))
            if (ex.type != ExceptionType.MAKEUP || !ex.autoRecord) continue

            val date = ex.date
            if (date.isBefore(today)) continue
            if (!CourseMatching.withinTerm(term, date)) continue
            val startAt = TimeUtils.millisAt(date, ex.startMinute)
            if (startAt <= System.currentTimeMillis()) continue
            scheduleAt(am, makeupPi(ex.id, true), date, ex.startMinute)
            scheduleAt(am, makeupPi(ex.id, false), date, ex.endMinute)
            armed++
        }

        AppLog.i(TAG, "알람 재설정 완료: $armed 건 (오늘 $today, 학기=$term)")
    }

    /**
     * 앱을 수업 도중에 켰거나 부팅했을 때: 지금 진행 중인 자동 녹음 대상 수업이 있고
     * 아직 녹음이 없으면 즉시 시작한다. **재설정 경로에서는 절대 호출하지 않는다.**
     */
    suspend fun catchUpIfMidClass() {
        if (!AppPermissions.micGranted(context)) return
        // 프로세스가 죽어 남은 '녹음 중' 행을 먼저 치운다. 그냥 두면 그 행 때문에
        // "이미 녹음 중"으로 오인돼 놓친 수업을 영영 따라잡지 못한다.
        runCatching { repo.healStaleRecordings() }
            .onFailure { AppLog.e(TAG, "미완료 녹음 정리 실패 - 놓친 수업을 따라잡지 못합니다", it) }
        if (repo.ongoingRecording() != null) return

        val session = repo.sessionInProgress() ?: return
        when {
            session.exceptionId != null -> RecordingService.startMakeup(context, session.exceptionId)
            session.courseId != null -> RecordingService.startAuto(context, session.courseId)
        }
        AppLog.i(TAG, "수업 중 진입 - 놓친 녹음 시작: ${session.subject}")
    }

    private fun scheduleAt(
        am: AlarmManager,
        pendingIntent: PendingIntent,
        date: LocalDate,
        minuteOfDay: Int,
    ) {
        val triggerAt = TimeUtils.millisAt(date, minuteOfDay)
        if (triggerAt <= System.currentTimeMillis()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "정확 알람 권한 없음 - 부정확 알람으로 대체", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    companion object {
        const val ACTION_START = "dev.iruki.classtime.action.ALARM_START"
        const val ACTION_STOP = "dev.iruki.classtime.action.ALARM_STOP"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_EXCEPTION_ID = "exception_id"

        private const val TAG = "ScheduleManager"

        /** 보강 알람 요청코드가 정규 수업 코드와 겹치지 않도록 하는 오프셋. */
        private const val MAKEUP_BIT = 0x40000000

        /** 요청코드 본체에 쓸 수 있는 id 비트 수 (MAKEUP_BIT 와 부호 비트를 건드리지 않도록). */
        private const val ID_MASK = 0x0FFFFFFFL
    }
}
