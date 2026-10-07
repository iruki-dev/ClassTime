package dev.iruki.classtime.widget

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.Session
import org.junit.Test

class TodayModelTest {

    private fun session(subject: String, start: Int, end: Int) = Session(
        subject = subject, professor = "", room = "", startMinute = start, endMinute = end,
        autoRecord = true, colorArgb = 0, courseId = 1, groupId = "g", exceptionId = null,
    )

    private val day = listOf(
        session("C", 13 * 60, 14 * 60),
        session("A", 9 * 60, 10 * 60 + 15),
        session("B", 10 * 60 + 30, 11 * 60 + 45),
    )

    @Test
    fun beforeFirstClass_nextIsTheEarliest() {
        val m = TodayModel.of(day, 8 * 60, RecordingStatus.Idle)
        assertThat(m.current).isNull()
        assertThat(m.next?.subject).isEqualTo("A")
        assertThat(m.later.map { it.subject }).containsExactly("B", "C").inOrder()
        assertThat(m.totalToday).isEqualTo(3)
    }

    @Test
    fun duringClass_currentIsSet_andNextIsTheFollowingOne() {
        val m = TodayModel.of(day, 9 * 60 + 30, RecordingStatus.Idle)
        assertThat(m.current?.subject).isEqualTo("A")
        assertThat(m.next?.subject).isEqualTo("B")
    }

    @Test
    fun endMinuteIsExclusive() {
        val m = TodayModel.of(day, 10 * 60 + 15, RecordingStatus.Idle)
        assertThat(m.current).isNull()
        assertThat(m.next?.subject).isEqualTo("B")
    }

    @Test
    fun afterLastClass_nothingLeft() {
        val m = TodayModel.of(day, 15 * 60, RecordingStatus.Idle)
        assertThat(m.current).isNull()
        assertThat(m.next).isNull()
        assertThat(m.later).isEmpty()
        assertThat(m.totalToday).isEqualTo(3)
    }
}
