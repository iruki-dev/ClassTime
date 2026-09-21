package dev.iruki.classtime.data

import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 특정 날짜에 "실제로" 열리는 수업 한 개.
 * 정규 시간표([Course]) 든 보강([ScheduleException]) 이든 이 형태로 통일해서 다룬다.
 */
data class Session(
    val subject: String,
    val professor: String,
    val room: String,
    val startMinute: Int,
    val endMinute: Int,
    val autoRecord: Boolean,
    val colorArgb: Int,
    /** 정규 수업이면 그 Course.id. */
    val courseId: Long?,
    val groupId: String?,
    /** 보강이면 그 ScheduleException.id. */
    val exceptionId: Long?,
) {
    val isMakeup get() = exceptionId != null
}

/**
 * "지금 어느 수업인가?" 및 "이 과목의 다음 수업은 언제인가?" 를 판단하는 순수 규칙.
 * 저장소·안드로이드 프레임워크와 무관하므로 단위 테스트로 그대로 검증한다.
 */
object CourseMatching {

    /** 시작 몇 분 전부터 그 수업으로 인정할지. 강의 시작 직전에 눌러도 라벨이 붙도록. */
    const val LEAD_MINUTES = 5

    /** 종료 후 몇 분까지 그 수업으로 인정할지. 쉬는 시간에 눌러도 직전 수업으로 잡히도록. */
    const val TRAIL_MINUTES = 10

    /** 다음 유효 수업일을 찾을 때 몇 주까지 내다볼지 (한 학기 + 여유). */
    const val HORIZON_WEEKS = 30

    private val MAKEUP_COLOR = 0xFF6A1B9A.toInt()

    fun withinTerm(term: Term?, date: LocalDate): Boolean {
        val afterStart = term?.startDate?.let { !date.isBefore(it) } ?: true
        val beforeEnd = term?.endDate?.let { !date.isAfter(it) } ?: true
        return afterStart && beforeEnd
    }

    private fun isCancelled(
        exceptions: List<ScheduleException>,
        date: LocalDate,
        groupId: String?,
    ): Boolean {
        val epochDay = date.toEpochDay()
        return exceptions.any {
            it.type == ExceptionType.CANCEL && it.epochDay == epochDay &&
                (it.courseGroupId == null || it.courseGroupId == groupId)
        }
    }

    /**
     * [date] 에 실제로 열리는 수업 목록. 학기 밖이면 빈 목록, 공휴일(전체 휴강)이면 그날 보강만.
     */
    fun sessionsOn(
        date: LocalDate,
        courses: List<Course>,
        exceptions: List<ScheduleException>,
        term: Term?,
    ): List<Session> {
        if (!withinTerm(term, date)) return emptyList()

        val epochDay = date.toEpochDay()
        val todays = exceptions.filter { it.epochDay == epochDay }
        val makeups = todays.filter { it.type == ExceptionType.MAKEUP }.map { it.toSession() }

        val allDayOff = todays.any { it.type == ExceptionType.CANCEL && it.courseGroupId == null }
        if (allDayOff) return makeups.sortedBy { it.startMinute }

        val dow = date.dayOfWeek.value
        val regular = courses
            .filter { it.dayOfWeek == dow && !isCancelled(todays, date, it.groupId) }
            .map { it.toSession() }

        return (regular + makeups).sortedBy { it.startMinute }
    }

    /**
     * 수동 녹음의 자동 라벨링용. [LEAD_MINUTES]~[TRAIL_MINUTES] 만큼 넉넉하게 인정한다.
     * 겹치는 수업이 있으면 먼저 시작한 것을 고른다(한 번에 하나만 녹음하므로 결정적이어야 함).
     */
    fun currentSession(sessions: List<Session>, minuteOfDay: Int): Session? =
        sessions.sortedBy { it.startMinute }.firstOrNull {
            minuteOfDay in (it.startMinute - LEAD_MINUTES)..(it.endMinute + TRAIL_MINUTES)
        }

    /**
     * 스케줄러가 "놓친 자동 녹음을 지금 이어서 시작할까?" 를 판단하는 용도.
     * [currentSession] 과 달리 **종료 시각을 넘기면 제외**한다. 그래야 종료 알람이
     * rescheduleAll() 을 다시 호출할 때 방금 끝난 수업이 즉시 재녹음되지 않는다.
     * autoRecord 가 켜진 수업만 대상으로 한다.
     */
    fun sessionInProgress(sessions: List<Session>, minuteOfDay: Int): Session? =
        sessions.filter { it.autoRecord }.sortedBy { it.startMinute }.firstOrNull {
            minuteOfDay >= it.startMinute && minuteOfDay < it.endMinute - 1
        }

    /**
     * [course] 가 다음으로 실제 열리는 날짜(수업 시작 기준). 학기 밖·휴강 주는 건너뛴다.
     * 종강일을 지나면(더 이상 열리지 않으면) null.
     */
    fun nextValidClassDate(
        course: Course,
        exceptions: List<ScheduleException>,
        term: Term?,
        from: LocalDateTime,
    ): LocalDate? = nextValidDate(
        course.dayOfWeek, course.startMinute, course.groupId, exceptions, term, from,
    )

    /**
     * 주어진 요일/시각(minute-of-day)이 [from] 이후로 처음 도래하며 휴강·학기밖이 아닌 날짜.
     * 수업 종료 알람을 잡을 때는 endMinute 를 넘긴다. 이렇게 시작/종료 날짜를 따로 구하면,
     * 이미 진행 중인 수업의 "오늘 종료 알람"이 재설정 과정에서 다음 주로 밀려나지 않는다.
     */
    fun nextValidDate(
        dayOfWeek: Int,
        minuteOfDay: Int,
        groupId: String?,
        exceptions: List<ScheduleException>,
        term: Term?,
        from: LocalDateTime,
    ): LocalDate? {
        var dt = TimeUtils.nextOccurrence(dayOfWeek, minuteOfDay, from)
        repeat(HORIZON_WEEKS) {
            val d = dt.toLocalDate()
            val endDate = term?.endDate
            val startDate = term?.startDate
            when {
                endDate != null && d.isAfter(endDate) -> return null
                startDate != null && d.isBefore(startDate) -> Unit
                isCancelled(exceptions, d, groupId) -> Unit
                else -> return d
            }
            dt = dt.plusWeeks(1)
        }
        return null
    }

    private fun Course.toSession() = Session(
        subject = subject,
        professor = professor,
        room = room,
        startMinute = startMinute,
        endMinute = endMinute,
        autoRecord = autoRecord,
        colorArgb = colorArgb,
        courseId = id,
        groupId = groupId,
        exceptionId = null,
    )

    private fun ScheduleException.toSession() = Session(
        // 빈 과목명을 여기서 채우지 않는다. 표시 문구는 로캘에 따라 달라지므로
        // 이 순수 규칙 계층이 아니라 화면·서비스 계층이 정한다.
        subject = subject,
        professor = professor,
        room = room,
        startMinute = startMinute,
        endMinute = endMinute,
        autoRecord = autoRecord,
        colorArgb = MAKEUP_COLOR,
        courseId = null,
        groupId = courseGroupId,
        exceptionId = id,
    )
}
