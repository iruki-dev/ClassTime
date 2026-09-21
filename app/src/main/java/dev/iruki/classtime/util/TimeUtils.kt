package dev.iruki.classtime.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object TimeUtils {

    /**
     * 파일명용. **반드시 [Locale.ROOT]** 이어야 한다. 기본 로캘을 쓰면 아랍어 등
     * 일부 로캘에서 숫자가 ASCII 가 아닌 글자로 찍혀 파일명이 깨진다.
     */
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.ROOT)

    /** 화면 표시용이므로 기기 로캘을 따른다. */
    private val listStamp = DateTimeFormatter.ofPattern("yyyy.MM.dd (E) HH:mm")

    /**
     * 요일 한 글자. 문자열 리소스를 두지 않고 [DayOfWeek] 의 로캘 데이터를 쓴다.
     * 번역을 직접 관리할 필요가 없고, 새 언어를 추가해도 저절로 맞는다.
     */
    fun dayName(dayOfWeek: Int, locale: Locale = Locale.getDefault()): String =
        displayName(dayOfWeek, TextStyle.SHORT, locale)

    /** 전체 이름("월요일" / "Monday"). 한국어에서 짧은 이름 + "요일" 을 붙이던 것을 대체한다. */
    fun dayNameFull(dayOfWeek: Int, locale: Locale = Locale.getDefault()): String =
        displayName(dayOfWeek, TextStyle.FULL, locale)

    private fun displayName(dayOfWeek: Int, style: TextStyle, locale: Locale): String =
        runCatching { DayOfWeek.of(dayOfWeek).getDisplayName(style, locale) }.getOrDefault("?")

    fun minuteToText(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun nowMinuteOfDay(): Int {
        val t = LocalTime.now()
        return t.hour * 60 + t.minute
    }

    fun todayDowValue(): Int = LocalDate.now().dayOfWeek.value

    fun fileStamp(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(fileStamp)

    fun listStamp(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(listStamp)

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024; i++
        }
        return if (i == 0) "%d %s".format(bytes, units[i]) else "%.1f %s".format(value, units[i])
    }

    /**
     * 주어진 요일/시각(분)이 다음으로 도래하는 [LocalDateTime].
     * 이미 지난(또는 정확히 지금인) 오늘 시각이면 다음 주 같은 요일로 넘어간다.
     */
    fun nextOccurrence(
        dayOfWeek: Int,
        minuteOfDay: Int,
        from: LocalDateTime = LocalDateTime.now(),
    ): LocalDateTime {
        val targetDow = DayOfWeek.of(dayOfWeek)
        val date = from.toLocalDate()
        val daysAhead = (targetDow.value - date.dayOfWeek.value + 7) % 7
        var candidate = date.plusDays(daysAhead.toLong())
            .atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (!candidate.isAfter(from)) {
            candidate = candidate.plusWeeks(1)
        }
        return candidate
    }

    /** [nextOccurrence] 를 epoch millis 로. */
    fun nextOccurrenceMillis(
        dayOfWeek: Int,
        minuteOfDay: Int,
        from: LocalDateTime = LocalDateTime.now(),
    ): Long = nextOccurrence(dayOfWeek, minuteOfDay, from)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 특정 날짜의 특정 분(minute-of-day)을 epoch millis 로. */
    fun millisAt(date: java.time.LocalDate, minuteOfDay: Int): Long =
        date.atTime(minuteOfDay / 60, minuteOfDay % 60)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
