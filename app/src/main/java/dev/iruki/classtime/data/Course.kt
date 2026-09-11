package dev.iruki.classtime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 시간표의 한 칸 = "한 과목이 한 요일에 열리는 한 교시".
 *
 * 한 과목이 주 2~3회 열리는 경우가 흔하므로, 같은 과목의 여러 요일 행은 같은 [groupId] 를 공유한다.
 * 편집·삭제는 그룹 단위로 이뤄진다.
 *
 * @param groupId 같은 과목(=같은 강의)을 묶는 식별자. 과목을 처음 추가할 때 UUID 로 생성된다.
 * @param dayOfWeek java.time.DayOfWeek 의 value (월=1 ... 일=7)
 * @param startMinute 자정 기준 분 단위 시작 시각 (예: 09:30 -> 570)
 * @param endMinute 자정 기준 분 단위 종료 시각
 * @param autoRecord 이 수업 시간에 자동으로 녹음을 시작할지 여부
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String = "",
    val subject: String,
    val professor: String = "",
    val room: String = "",
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val autoRecord: Boolean = true,
    val colorArgb: Int = 0xFF1B5E20.toInt(),
) {
    val startHour get() = startMinute / 60
    val startMin get() = startMinute % 60
    val endHour get() = endMinute / 60
    val endMin get() = endMinute % 60
}
