package dev.iruki.classtime.data

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Test

/**
 * 자동 라벨링 규칙, 학기/휴강/보강 반영, 그리고 "종료 알람 직후 재녹음" 버그의 회귀 테스트.
 * 모두 순수 함수라 프레임워크 없이 검증한다.
 */
class CourseMatchingTest {

    private fun course(
        id: Long, subject: String, dow: Int, start: Int, end: Int,
        auto: Boolean = true, group: String = "g$id",
    ) = Course(
        id = id, groupId = group, subject = subject, dayOfWeek = dow,
        startMinute = start, endMinute = end, autoRecord = auto,
    )

    // 월(1): 자료구조 09:00-10:15, 수(3): 자료구조 09:00-10:15  (같은 그룹 g1)
    private val ds1 = course(1, "자료구조", 1, 540, 615, group = "g1")
    private val ds2 = course(2, "자료구조", 3, 540, 615, group = "g1")
    private val algo = course(3, "알고리즘", 1, 630, 705, group = "g3")
    private val courses = listOf(ds1, ds2, algo)

    private val MON = LocalDate.of(2026, 3, 2)  // 월요일
    private val WED = LocalDate.of(2026, 3, 4)  // 수요일

    // --- sessionsOn ---

    @Test
    fun sessionsOn_regularDay_listsThatDaysCourses() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), term = null)
        assertThat(s.map { it.subject }).containsExactly("자료구조", "알고리즘").inOrder()
    }

    @Test
    fun sessionsOn_beforeTermStart_isEmpty() {
        val term = Term.of(LocalDate.of(2026, 3, 9), null)
        assertThat(CourseMatching.sessionsOn(MON, courses, emptyList(), term)).isEmpty()
    }

    @Test
    fun sessionsOn_afterTermEnd_isEmpty() {
        val term = Term.of(null, LocalDate.of(2026, 3, 1))
        assertThat(CourseMatching.sessionsOn(MON, courses, emptyList(), term)).isEmpty()
    }

    @Test
    fun sessionsOn_courseSpecificCancel_dropsOnlyThatCourse() {
        val ex = listOf(ScheduleException.cancel(MON, courseGroupId = "g1"))
        val s = CourseMatching.sessionsOn(MON, courses, ex, term = null)
        assertThat(s.map { it.subject }).containsExactly("알고리즘")
    }

    @Test
    fun sessionsOn_holidayCancel_dropsEverythingButMakeups() {
        val ex = listOf(
            ScheduleException.cancel(MON, courseGroupId = null),
            ScheduleException.makeup(MON, "영어회화", 13 * 60, 14 * 60),
        )
        val s = CourseMatching.sessionsOn(MON, courses, ex, term = null)
        assertThat(s.map { it.subject }).containsExactly("영어회화")
        assertThat(s.single().isMakeup).isTrue()
    }

    @Test
    fun sessionsOn_makeup_appearsSortedByTime() {
        val ex = listOf(ScheduleException.makeup(MON, "자료구조", 8 * 60, 8 * 60 + 50, courseGroupId = "g1"))
        val s = CourseMatching.sessionsOn(MON, courses, ex, term = null)
        assertThat(s.map { it.subject }).containsExactly("자료구조", "자료구조", "알고리즘").inOrder()
        assertThat(s.first().isMakeup).isTrue()
        assertThat(s.first().startMinute).isEqualTo(8 * 60)
    }

    // --- currentSession (수동 라벨링, ±관용시간) ---

    @Test
    fun currentSession_duringClass() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.currentSession(s, 570)?.subject).isEqualTo("자료구조")
    }

    @Test
    fun currentSession_fiveMinBeforeStart() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.currentSession(s, 535)?.subject).isEqualTo("자료구조")
    }

    @Test
    fun currentSession_inBreak_attributesToJustEndedClass() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.currentSession(s, 620)?.subject).isEqualTo("자료구조")
    }

    @Test
    fun currentSession_wellOutside_isNull() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.currentSession(s, 800)).isNull()
    }

    // --- sessionInProgress (놓친 녹음 따라잡기, 엄격) ---

    @Test
    fun sessionInProgress_duringClass_returnsIt() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.sessionInProgress(s, 570)?.subject).isEqualTo("자료구조")
    }

    @Test
    fun sessionInProgress_atStartMinute_returnsIt() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.sessionInProgress(s, 540)?.subject).isEqualTo("자료구조")
    }

    @Test
    fun sessionInProgress_atEndMinute_isNull_soStopAlarmDoesNotRestart() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.sessionInProgress(s, 615)).isNull()
    }

    @Test
    fun sessionInProgress_inBreak_isNull() {
        val s = CourseMatching.sessionsOn(MON, courses, emptyList(), null)
        assertThat(CourseMatching.sessionInProgress(s, 620)).isNull()
    }

    @Test
    fun sessionInProgress_skipsAutoRecordOff() {
        val list = listOf(course(1, "자료구조", 1, 540, 615, auto = false))
        val s = CourseMatching.sessionsOn(MON, list, emptyList(), null)
        assertThat(CourseMatching.sessionInProgress(s, 570)).isNull()
    }

    // --- nextValidClassDate ---

    private val noonWed = LocalDateTime.of(2026, 3, 4, 12, 0) // 수요일 정오

    @Test
    fun nextValidClassDate_picksUpcomingMonday() {
        // 수요일 정오 기준 자료구조(월/수). 수요일 수업은 이미 지났으니 다음 월요일(3/9).
        val d = CourseMatching.nextValidClassDate(ds1, emptyList(), term = null, from = noonWed)
        assertThat(d).isEqualTo(LocalDate.of(2026, 3, 9))
    }

    @Test
    fun nextValidClassDate_skipsCancelledWeek() {
        val ex = listOf(ScheduleException.cancel(LocalDate.of(2026, 3, 9), "g1"))
        val d = CourseMatching.nextValidClassDate(ds1, ex, term = null, from = noonWed)
        assertThat(d).isEqualTo(LocalDate.of(2026, 3, 16)) // 한 주 건너뜀
    }

    @Test
    fun nextValidClassDate_skipsHolidayWeek() {
        val ex = listOf(ScheduleException.cancel(LocalDate.of(2026, 3, 9), courseGroupId = null))
        val d = CourseMatching.nextValidClassDate(ds1, ex, term = null, from = noonWed)
        assertThat(d).isEqualTo(LocalDate.of(2026, 3, 16))
    }

    @Test
    fun nextValidClassDate_nullAfterTermEnd() {
        val term = Term.of(null, LocalDate.of(2026, 3, 5))
        val d = CourseMatching.nextValidClassDate(ds1, emptyList(), term, from = noonWed)
        assertThat(d).isNull()
    }

    @Test
    fun nextValidClassDate_jumpsToTermStart() {
        val term = Term.of(LocalDate.of(2026, 3, 30), null) // 3/30 월요일 개강
        val d = CourseMatching.nextValidClassDate(ds1, emptyList(), term, from = noonWed)
        assertThat(d).isEqualTo(LocalDate.of(2026, 3, 30))
    }

    @Test
    fun nextValidDate_endAlarm_staysTodayWhileClassInProgress() {
        // 자료구조 09:00-10:15, 지금은 수요일 09:30 (수업 중).
        val midClass = LocalDateTime.of(2026, 3, 4, 9, 30)
        val startDate = CourseMatching.nextValidDate(
            ds2.dayOfWeek, ds2.startMinute, ds2.groupId, emptyList(), null, midClass,
        )
        val endDate = CourseMatching.nextValidDate(
            ds2.dayOfWeek, ds2.endMinute, ds2.groupId, emptyList(), null, midClass,
        )
        // 시작 알람은 다음 주로 밀리지만, 종료 알람은 오늘로 유지되어야 한다(재녹음 방지의 핵심).
        assertThat(startDate).isEqualTo(LocalDate.of(2026, 3, 11))
        assertThat(endDate).isEqualTo(LocalDate.of(2026, 3, 4))
    }

    @Test
    fun nextValidDate_afterClassEnded_bothRollToNextWeek() {
        val afterClass = LocalDateTime.of(2026, 3, 4, 10, 30)
        val startDate = CourseMatching.nextValidDate(
            ds2.dayOfWeek, ds2.startMinute, ds2.groupId, emptyList(), null, afterClass,
        )
        val endDate = CourseMatching.nextValidDate(
            ds2.dayOfWeek, ds2.endMinute, ds2.groupId, emptyList(), null, afterClass,
        )
        assertThat(startDate).isEqualTo(LocalDate.of(2026, 3, 11))
        assertThat(endDate).isEqualTo(LocalDate.of(2026, 3, 11))
    }

    @Test
    fun sessionsOn_sameSubjectTwiceOneDay_bothAppearSortedByTime() {
        // 실습이 수요일에 두 번: 10:00-11:00, 15:00-17:00 (같은 그룹)
        val lab1 = course(10, "회로실습", 3, 600, 660, group = "g-lab")
        val lab2 = course(11, "회로실습", 3, 900, 1020, group = "g-lab")
        val s = CourseMatching.sessionsOn(WED, listOf(lab1, lab2), emptyList(), null)
        assertThat(s.map { it.startMinute }).containsExactly(600, 900).inOrder()
        assertThat(s.map { it.subject }).containsExactly("회로실습", "회로실습")
    }

    @Test
    fun sessionsOn_differentTimePerDay_isRespected() {
        // 자료구조: 월 09:00-10:15, 수 13:30-14:45 (요일마다 시간 다름)
        val monSlot = course(20, "자료구조", 1, 540, 615, group = "g-ds2")
        val wedSlot = course(21, "자료구조", 3, 810, 885, group = "g-ds2")
        val list = listOf(monSlot, wedSlot)

        val mon = CourseMatching.sessionsOn(MON, list, emptyList(), null).single()
        val wed = CourseMatching.sessionsOn(WED, list, emptyList(), null).single()
        assertThat(mon.startMinute).isEqualTo(540)
        assertThat(wed.startMinute).isEqualTo(810)

        // 라벨링도 각 요일의 시간대를 따른다
        assertThat(CourseMatching.currentSession(listOf(wed), 820)?.subject).isEqualTo("자료구조")
        assertThat(CourseMatching.currentSession(listOf(mon), 820)).isNull()
    }

    @Test
    fun withinTerm_boundsAreInclusive() {
        val term = Term.of(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 19))
        assertThat(CourseMatching.withinTerm(term, LocalDate.of(2026, 3, 2))).isTrue()
        assertThat(CourseMatching.withinTerm(term, LocalDate.of(2026, 6, 19))).isTrue()
        assertThat(CourseMatching.withinTerm(term, LocalDate.of(2026, 6, 20))).isFalse()
        assertThat(CourseMatching.withinTerm(null, LocalDate.of(2000, 1, 1))).isTrue()
    }
}
