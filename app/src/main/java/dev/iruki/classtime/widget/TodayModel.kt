package dev.iruki.classtime.widget

import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.Session

/**
 * 위젯이 그릴 내용. 위젯은 1초마다 다시 그릴 수 없으므로(시스템이 막는다) 계산은 한 번만 하고,
 * 수업 시작·종료 알람과 녹음 상태가 바뀔 때마다 새로 만든다.
 */
data class TodayModel(
    val recording: RecordingStatus,
    /** 지금 진행 중인 수업. */
    val current: Session?,
    /** 그다음 수업. */
    val next: Session?,
    /** next 뒤로 오늘 남은 수업. */
    val later: List<Session>,
    val totalToday: Int,
) {
    companion object {
        val Empty = TodayModel(RecordingStatus.Idle, null, null, emptyList(), 0)

        fun of(sessions: List<Session>, nowMinute: Int, recording: RecordingStatus): TodayModel {
            val sorted = sessions.sortedBy { it.startMinute }
            val current = sorted.lastOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
            val upcoming = sorted.filter { it.startMinute > nowMinute }
            return TodayModel(
                recording = recording,
                current = current,
                next = upcoming.firstOrNull(),
                later = upcoming.drop(1),
                totalToday = sorted.size,
            )
        }
    }
}
