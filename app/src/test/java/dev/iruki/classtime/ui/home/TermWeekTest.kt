package dev.iruki.classtime.ui.home

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.Term
import java.time.LocalDate
import org.junit.Test

/** 순수 JVM 테스트 — 홈 부제의 ‘n주차’. */
class TermWeekTest {

    private val term = Term.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15)) // 9/1 은 화요일

    @Test
    fun firstWeek_countsFromTheMondayOfTheStartWeek() {
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 9, 1))).isEqualTo(1)
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 9, 6))).isEqualTo(1)
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 9, 7))).isEqualTo(2)
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 10, 7))).isEqualTo(6)
    }

    @Test
    fun outsideTerm_hasNoWeek() {
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 8, 31))).isNull()
        assertThat(HomeViewModel.weekOf(term, LocalDate.of(2026, 12, 16))).isNull()
        assertThat(HomeViewModel.weekOf(null, LocalDate.of(2026, 10, 7))).isNull()
    }
}
