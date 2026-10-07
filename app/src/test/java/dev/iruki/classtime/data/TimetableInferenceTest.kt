package dev.iruki.classtime.data

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.TimetableInference.Sample
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import org.junit.Test

class TimetableInferenceTest {

    /** 2026-03-02 (월) 이 1주차. */
    private val week1: LocalDate = LocalDate.of(2026, 3, 2)

    private fun at(week: Int, day: DayOfWeek, hour: Int, minute: Int): LocalDateTime =
        week1.plusWeeks((week - 1).toLong())
            .with(TemporalAdjusters.nextOrSame(day))
            .atTime(hour, minute)

    private fun rec(
        subject: String, week: Int, day: DayOfWeek, h: Int, m: Int, minutes: Int, auto: Boolean = true,
    ) = Sample(subject, at(week, day, h, m), minutes, auto)

    private fun infer(samples: List<Sample>) = TimetableInference.infer(samples)

    @Test
    fun regularClass_survivesExtensionsEarlyEndsCancellationsAndAMakeup() {
        val samples = buildList {
            for (w in 1..10) {
                // 4주차 화요일, 7주차 목요일은 휴강.
                if (w != 4) add(rec("자료구조", w, DayOfWeek.TUESDAY, 9, 0, 75))
                if (w != 7) add(rec("자료구조", w, DayOfWeek.THURSDAY, 9, 0, 75))
            }
            add(rec("자료구조", 2, DayOfWeek.TUESDAY, 9, 0, 85))   // 10분 연장
            add(rec("자료구조", 5, DayOfWeek.THURSDAY, 9, 0, 55))  // 일찍 끝남
            add(rec("자료구조", 6, DayOfWeek.FRIDAY, 15, 0, 75))   // 보강 한 번
        }

        val result = infer(samples).single()

        assertThat(result.subject).isEqualTo("자료구조")
        assertThat(result.slots.map { Triple(it.dayOfWeek, it.startMinute, it.endMinute) })
            .containsExactly(
                Triple(2, 9 * 60, 10 * 60 + 15),
                Triple(4, 9 * 60, 10 * 60 + 15),
            ).inOrder()
    }

    @Test
    fun manualRecordingsPressedLate_snapBackToTheRealStart() {
        val samples = listOf(
            rec("미적분", 1, DayOfWeek.MONDAY, 13, 31, 74, auto = false),
            rec("미적분", 2, DayOfWeek.MONDAY, 13, 33, 72, auto = false),
            rec("미적분", 3, DayOfWeek.MONDAY, 13, 30, 75, auto = false),
            rec("미적분", 4, DayOfWeek.MONDAY, 13, 38, 67, auto = false),
        )

        val slot = infer(samples).single().slots.single()

        assertThat(slot.startMinute).isEqualTo(13 * 60 + 30)
        assertThat(slot.endMinute).isEqualTo(14 * 60 + 45)
        assertThat(slot.weeksSeen).isEqualTo(4)
    }

    @Test
    fun autoStartWins_overLateManualStarts() {
        val samples = listOf(
            rec("물리", 1, DayOfWeek.WEDNESDAY, 10, 30, 75),
            rec("물리", 2, DayOfWeek.WEDNESDAY, 10, 41, 64, auto = false),
            rec("물리", 3, DayOfWeek.WEDNESDAY, 10, 30, 75),
        )
        assertThat(infer(samples).single().slots.single().startMinute).isEqualTo(10 * 60 + 30)
    }

    @Test
    fun sameSubjectTwiceOnOneDay_givesTwoSlots() {
        val samples = (1..3).flatMap { w ->
            listOf(
                rec("영어", w, DayOfWeek.MONDAY, 9, 0, 50),
                rec("영어", w, DayOfWeek.MONDAY, 14, 0, 50),
            )
        }
        val slots = infer(samples).single().slots
        assertThat(slots.map { it.startMinute }).containsExactly(9 * 60, 14 * 60).inOrder()
    }

    @Test
    fun oneWeekOfData_isTrustedAsIs() {
        val samples = listOf(
            rec("운영체제", 1, DayOfWeek.TUESDAY, 15, 0, 75),
            rec("운영체제", 1, DayOfWeek.THURSDAY, 15, 0, 75),
        )
        assertThat(infer(samples).single().slots).hasSize(2)
    }

    @Test
    fun shortAccidentalRecordings_areIgnored() {
        val samples = (1..3).map { rec("화학", it, DayOfWeek.FRIDAY, 9, 0, 75) } +
            (1..3).map { rec("화학", it, DayOfWeek.MONDAY, 18, 0, 3) }
        assertThat(infer(samples).single().slots.map { it.dayOfWeek }).containsExactly(5)
    }

    @Test
    fun overlappingSubjects_keepTheMoreRegularOne() {
        val samples = (1..8).map { rec("경제학", it, DayOfWeek.WEDNESDAY, 9, 0, 75) } +
            (1..3).map { rec("잘못 붙인 이름", it, DayOfWeek.WEDNESDAY, 9, 0, 75) }
        val results = infer(samples)
        assertThat(results.map { it.subject }).containsExactly("경제학")
    }

    @Test
    fun ignoredSubjects_andBlankNames_areSkipped() {
        val samples = (1..3).map { rec("기타", it, DayOfWeek.MONDAY, 9, 0, 60) } +
            (1..3).map { rec(" ", it, DayOfWeek.MONDAY, 11, 0, 60) }
        assertThat(TimetableInference.infer(samples, ignoreSubjects = setOf("기타"))).isEmpty()
    }

    @Test
    fun resultsAreOrderedByFirstClassOfTheWeek() {
        val samples = (1..3).flatMap { w ->
            listOf(
                rec("금요일 과목", w, DayOfWeek.FRIDAY, 9, 0, 50),
                rec("월요일 과목", w, DayOfWeek.MONDAY, 13, 0, 50),
            )
        }
        assertThat(infer(samples).map { it.subject }).containsExactly("월요일 과목", "금요일 과목").inOrder()
    }
}
