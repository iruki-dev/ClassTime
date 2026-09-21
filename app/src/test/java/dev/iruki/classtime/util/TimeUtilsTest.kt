package dev.iruki.classtime.util

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/** 순수 JVM 테스트 — 안드로이드 프레임워크 불필요. */
class TimeUtilsTest {

    @Test
    fun minuteToText_pads() {
        assertThat(TimeUtils.minuteToText(9 * 60 + 5)).isEqualTo("09:05")
        assertThat(TimeUtils.minuteToText(0)).isEqualTo("00:00")
        assertThat(TimeUtils.minuteToText(23 * 60 + 59)).isEqualTo("23:59")
    }

    @Test
    fun formatDuration_switchesToHoursWhenNeeded() {
        assertThat(TimeUtils.formatDuration(0)).isEqualTo("0:00")
        assertThat(TimeUtils.formatDuration(75_000)).isEqualTo("1:15")
        assertThat(TimeUtils.formatDuration(3_600_000 + 5 * 60_000 + 3_000)).isEqualTo("1:05:03")
    }

    @Test
    fun formatSize_isHumanReadable() {
        assertThat(TimeUtils.formatSize(0)).isEqualTo("0 B")
        assertThat(TimeUtils.formatSize(512)).isEqualTo("512 B")
        assertThat(TimeUtils.formatSize(1_500)).isEqualTo("1.5 KB")
        assertThat(TimeUtils.formatSize(43L * 1024 * 1024)).isEqualTo("43.0 MB")
    }

    @Test
    fun fileStamp_hasNoPathHostileCharacters() {
        val stamp = TimeUtils.fileStamp(1_700_000_000_000L)
        assertThat(stamp).matches("\\d{4}-\\d{2}-\\d{2}_\\d{4}")
    }

    @Test
    fun nextOccurrence_laterToday_staysToday() {
        // 2026-03-04 는 수요일. 08:00 기준, 같은 날 수요일 09:00 수업.
        val from = LocalDateTime.of(2026, 3, 4, 8, 0)
        val millis = TimeUtils.nextOccurrenceMillis(dayOfWeek = 3, minuteOfDay = 9 * 60, from = from)
        val result = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 0))
    }

    @Test
    fun nextOccurrence_alreadyPassedToday_rollsToNextWeek() {
        // 수요일 10:00 기준, 수요일 09:00 수업 → 다음 주 수요일.
        val from = LocalDateTime.of(2026, 3, 4, 10, 0)
        val millis = TimeUtils.nextOccurrenceMillis(3, 9 * 60, from)
        val result = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 11, 9, 0))
    }

    @Test
    fun nextOccurrence_exactlyNow_rollsForward() {
        // 시작 시각과 현재가 정확히 같으면 "이번 것은 지났다"로 보고 다음 주로.
        val from = LocalDateTime.of(2026, 3, 4, 9, 0)
        val millis = TimeUtils.nextOccurrenceMillis(3, 9 * 60, from)
        val result = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 11, 9, 0))
    }

    @Test
    fun nextOccurrence_earlierWeekday_goesToThatDayThisWeek() {
        // 수요일 기준 금요일(5) 수업 → 이번 주 금요일.
        val from = LocalDateTime.of(2026, 3, 4, 12, 0)
        val millis = TimeUtils.nextOccurrenceMillis(5, 13 * 60 + 30, from)
        val result = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 6, 13, 30))
    }

    @Test
    fun nextOccurrence_earlierWeekday_goesToNextWeek() {
        // 수요일 기준 월요일(1) 수업 → 다음 주 월요일.
        val from = LocalDateTime.of(2026, 3, 4, 12, 0)
        val millis = TimeUtils.nextOccurrenceMillis(1, 9 * 60, from)
        val result = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 3, 9, 9, 0))
    }

    @Test
    fun dayName_isLocalized() {
        // 로캘을 명시해 기기 설정과 무관하게 검증한다.
        assertThat(TimeUtils.dayName(1, java.util.Locale.KOREA)).isEqualTo("월")
        assertThat(TimeUtils.dayName(7, java.util.Locale.KOREA)).isEqualTo("일")
        assertThat(TimeUtils.dayName(1, java.util.Locale.ENGLISH)).isEqualTo("Mon")
        // 범위 밖 입력은 예외 대신 물음표.
        assertThat(TimeUtils.dayName(0, java.util.Locale.KOREA)).isEqualTo("?")
    }

    @Test
    fun nextOccurrence_returnsLocalDateTime_consistentWithMillis() {
        val from = LocalDateTime.of(2026, 3, 4, 8, 0) // 수요일
        val dt = TimeUtils.nextOccurrence(3, 9 * 60, from)
        assertThat(dt).isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 0))

        val millis = TimeUtils.nextOccurrenceMillis(3, 9 * 60, from)
        val back = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(back).isEqualTo(dt)
    }

    @Test
    fun millisAt_roundTrips() {
        val date = java.time.LocalDate.of(2026, 3, 4)
        val millis = TimeUtils.millisAt(date, 9 * 60 + 30)
        val back = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()
        )
        assertThat(back).isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 30))
    }
}
