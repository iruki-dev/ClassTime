package dev.iruki.classtime.ui.common

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ui.ai.MonthBar
import dev.iruki.classtime.ui.ai.lastMonths
import java.time.YearMonth
import org.junit.Test

class LastMonthsTest {
    @Test
    fun sixMonthsEndingNow_acrossYearBoundary_zeroWhereNoUse() {
        val ledger = listOf(
            AiSettings.SummaryMonth("2025-11", 3, 1, 1, 0.20),
            AiSettings.SummaryMonth("2026-02", 9, 1, 1, 0.42),
            AiSettings.SummaryMonth("2025-08", 1, 1, 1, 9.99), // 여섯 달 밖
        )
        assertThat(lastMonths(ledger, YearMonth.of(2026, 2))).containsExactly(
            MonthBar(9, 0.0), MonthBar(10, 0.0), MonthBar(11, 0.20), MonthBar(12, 0.0), MonthBar(1, 0.0), MonthBar(2, 0.42),
        ).inOrder()
    }
}
