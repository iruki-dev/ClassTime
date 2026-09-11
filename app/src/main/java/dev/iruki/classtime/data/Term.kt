package dev.iruki.classtime.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 학기 기간. 한 행만 존재한다(id = 1).
 * 자동 녹음 알람은 이 기간 안에서만 잡힌다.
 * 개강 전에는 아무 것도 녹음하지 않고, 종강일이 지나면 모든 자동 녹음이 멈춘다.
 *
 * 값이 null 이면 "제한 없음"(항상 켜짐)을 뜻한다.
 */
@Entity(tableName = "term")
data class Term(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
) {
    val startDate: LocalDate? get() = startEpochDay?.let(LocalDate::ofEpochDay)
    val endDate: LocalDate? get() = endEpochDay?.let(LocalDate::ofEpochDay)

    companion object {
        const val SINGLETON_ID = 1

        fun of(start: LocalDate?, end: LocalDate?) = Term(
            startEpochDay = start?.toEpochDay(),
            endEpochDay = end?.toEpochDay(),
        )
    }
}
