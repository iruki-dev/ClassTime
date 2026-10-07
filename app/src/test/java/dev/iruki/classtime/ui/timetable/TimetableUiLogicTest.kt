package dev.iruki.classtime.ui.timetable

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.data.ScheduleException
import java.time.LocalDate
import org.junit.Test

/** 순수 JVM 테스트 — 수업 편집의 시간 묶음과 시간표 칸 계산. */
class TimetableUiLogicTest {

    private val monday = LocalDate.of(2026, 10, 5)

    @Test
    fun groupSlots_foldsSameTimeAcrossDays() {
        val slots = listOf(
            Slot(4, 540, 615), Slot(2, 540, 615), Slot(3, 900, 1000),
        )
        val groups = groupSlots(slots)
        assertThat(groups).containsExactly(
            setOf(2, 4) to (540 to 615),
            setOf(3) to (900 to 1000),
        ).inOrder()
    }

    @Test
    fun toSlots_isInverseOfGroupSlots_andDropsDuplicates() {
        val groups = listOf(
            TimeGroup(1, setOf(2, 4), 540, 615),
            TimeGroup(2, setOf(4), 540, 615),
        )
        assertThat(groups.toSlots()).containsExactly(Slot(2, 540, 615), Slot(4, 540, 615))
    }

    @Test
    fun timeGroup_needsADayAndPositiveLength() {
        assertThat(TimeGroup(1, emptySet(), 540, 615).valid).isFalse()
        assertThat(TimeGroup(1, setOf(1), 615, 540).valid).isFalse()
        assertThat(TimeGroup(1, setOf(1), 540, 615).valid).isTrue()
    }

    @Test
    fun buildBlocks_marksCancelledAutoOffAndMakeup() {
        val ds = course("ds", "자료구조", dow = 2)
        val arch = course("arch", "컴퓨터구조", dow = 4, auto = false)
        val la = course("la", "선형대수", dow = 4)
        val exceptions = listOf(
            ScheduleException.cancel(monday.plusDays(1), "ds"),            // 화: 자료구조만 휴강
            ScheduleException.cancel(monday.plusDays(3), null),            // 목: 하루 전체 휴강
            ScheduleException.makeup(monday.plusDays(4), "", 600, 700, "ds"), // 금: 자료구조 보강
        )
        val blocks = buildBlocks(listOf(ds, arch, la), exceptions, monday)

        assertThat(blocks.first { it.subject == "자료구조" && it.dayOfWeek == 2 }.kind).isEqualTo(BlockKind.CANCELLED)
        // 하루 전체 휴강은 자동 녹음 꺼짐보다 우선한다.
        assertThat(blocks.first { it.subject == "컴퓨터구조" }.kind).isEqualTo(BlockKind.CANCELLED)
        assertThat(blocks.first { it.subject == "선형대수" }.kind).isEqualTo(BlockKind.CANCELLED)
        val makeup = blocks.first { it.kind == BlockKind.MAKEUP }
        assertThat(makeup.dayOfWeek).isEqualTo(5)
        assertThat(makeup.subject).isEqualTo("자료구조") // 빈 과목명은 연결된 과목에서
    }

    @Test
    fun buildBlocks_autoOffWithoutException() {
        val arch = course("arch", "컴퓨터구조", dow = 1, auto = false)
        assertThat(buildBlocks(listOf(arch), emptyList(), monday).single().kind).isEqualTo(BlockKind.AUTO_OFF)
    }

    @Test
    fun weekStart_isMonday() {
        assertThat(TimetableViewModel.weekStart(LocalDate.of(2026, 10, 7))).isEqualTo(monday)
        assertThat(TimetableViewModel.weekStart(LocalDate.of(2026, 10, 11))).isEqualTo(monday)
    }

    private fun course(gid: String, subject: String, dow: Int, auto: Boolean = true) = Course(
        groupId = gid, subject = subject, dayOfWeek = dow, startMinute = 540, endMinute = 615, autoRecord = auto,
    )
}
