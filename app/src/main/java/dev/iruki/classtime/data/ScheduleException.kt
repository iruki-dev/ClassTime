package dev.iruki.classtime.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ExceptionType {
    /** 휴강 / 공휴일 — 그날 해당 수업(또는 전체)을 열지 않는다. */
    CANCEL,

    /** 보강 — 시간표에 없는 날짜/시간에 한 번 열리는 수업. */
    MAKEUP,
}

/**
 * 정규 시간표에 대한 1회성 예외.
 *
 * - [type] = CANCEL:
 *     [courseGroupId] 가 있으면 그 과목만 휴강, null 이면 그날 전체 휴강(공휴일).
 * - [type] = MAKEUP:
 *     [subject]/[startMinute]/[endMinute] 로 그날 한 번 수업이 열린다.
 *     [courseGroupId] 는 원 과목과 연결(선택). [autoRecord] 로 자동 녹음 여부 지정.
 *
 * @param epochDay 예외가 적용되는 날짜(LocalDate.toEpochDay()).
 */
@Entity(tableName = "schedule_exceptions")
data class ScheduleException(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val type: ExceptionType,
    val courseGroupId: String? = null,
    val subject: String = "",
    val professor: String = "",
    val room: String = "",
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val autoRecord: Boolean = true,
    val note: String = "",
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    companion object {
        fun cancel(date: LocalDate, courseGroupId: String?, subject: String = "", note: String = "") =
            ScheduleException(
                epochDay = date.toEpochDay(),
                type = ExceptionType.CANCEL,
                courseGroupId = courseGroupId,
                subject = subject,
                note = note,
            )

        fun makeup(
            date: LocalDate,
            subject: String,
            startMinute: Int,
            endMinute: Int,
            courseGroupId: String? = null,
            professor: String = "",
            room: String = "",
            autoRecord: Boolean = true,
        ) = ScheduleException(
            epochDay = date.toEpochDay(),
            type = ExceptionType.MAKEUP,
            courseGroupId = courseGroupId,
            subject = subject,
            professor = professor,
            room = room,
            startMinute = startMinute,
            endMinute = endMinute,
            autoRecord = autoRecord,
        )
    }
}
