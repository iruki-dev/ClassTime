package dev.iruki.classtime.schedule

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.TimeUtils

/**
 * 수업 전 알림. ‘곧 수업’과 함께 자동 녹음이 될지를 알려 준다.
 *
 * 녹음이 될지까지 말해 주는 이유: 대기 모드가 꺼져 있거나 마이크가 준비되지 않았으면
 * 자동 녹음이 무음이 된다. 수업 직전에 그걸 알면 앱을 한 번 열어 바로잡을 수 있다.
 */
object ClassReminder {

    private const val TAG = "ClassReminder"
    private const val NOTIF_BASE = 0x5000

    suspend fun post(context: Context, repo: ClassTimeRepository, courseId: Long, exceptionId: Long) {
        if (!AppPermissions.notificationsGranted(context)) return
        val info = when {
            exceptionId >= 0 -> repo.exception(exceptionId)?.let {
                Info(
                    subject = it.subject.ifBlank { context.getString(R.string.subject_makeup) },
                    room = it.room, start = it.startMinute, end = it.endMinute, auto = it.autoRecord,
                )
            }
            courseId >= 0 -> repo.course(courseId)?.let {
                Info(it.subject, it.room, it.startMinute, it.endMinute, it.autoRecord)
            }
            else -> null
        } ?: return

        val minutesLeft = (info.start - TimeUtils.nowMinuteOfDay()).coerceAtLeast(1)
        val ready = repo.standby.value.readyForSilentFreeRecording ||
            AppPermissions.canRecordFromBackground(context)
        val recordLine = context.getString(
            when {
                !info.auto -> R.string.notif_remind_auto_off
                ready -> R.string.notif_remind_auto_on
                else -> R.string.notif_remind_auto_unready
            }
        )
        val time = TimeUtils.minuteToText(info.start) + " – " + TimeUtils.minuteToText(info.end)
        val text = listOf(time, info.room, recordLine).filter { it.isNotBlank() }.joinToString(" · ")

        val open = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ClassTimeApp.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_remind_title, minutesLeft, info.subject))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // 수업이 시작되면 의미가 없으니 그때 사라진다.
            .setTimeoutAfter(minutesLeft * 60_000L)
            .build()
        runCatching {
            ClassTimeApp.notificationManager(context)
                .notify(NOTIF_BASE + ((if (exceptionId >= 0) exceptionId else courseId) % 0x1000).toInt(), notification)
        }.onFailure { AppLog.w(TAG, "수업 전 알림을 띄우지 못했습니다", it) }
    }

    private data class Info(
        val subject: String,
        val room: String,
        val start: Int,
        val end: Int,
        val auto: Boolean,
    )
}
