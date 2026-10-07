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
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDateTime

/**
 * 수업 전 알림. “선형대수 · 10분 후” / “자연대 211 · 13:00 – 14:15 · 자동 녹음”.
 *
 * 자동 녹음이 될 수업이면 ‘이번엔 녹음 안 함’ 버튼을 단다. 누르면 그 날짜의 그 수업만 건너뛴다.
 * 녹음이 무음이 될 상황(대기 모드 미준비)이면 마지막 칸에 그 사실을 적어, 수업 전에 앱을 열게 한다.
 */
object ClassReminder {

    const val ACTION_SKIP = "dev.iruki.classtime.action.SKIP_ONCE"
    const val EXTRA_SKIP_KEY = "skip_key"
    const val EXTRA_NOTIF_ID = "notif_id"

    private const val TAG = "ClassReminder"
    private const val NOTIF_BASE = 0x5000

    suspend fun post(context: Context, repo: ClassTimeRepository, courseId: Long, exceptionId: Long) {
        if (!AppPermissions.notificationsGranted(context)) return
        val info = when {
            exceptionId >= 0 -> repo.exception(exceptionId)?.let {
                Info(it.subject.ifBlank { context.getString(R.string.subject_makeup) }, it.room, it.startMinute, it.endMinute, it.autoRecord)
            }
            courseId >= 0 -> repo.course(courseId)?.let {
                Info(it.subject, it.room, it.startMinute, it.endMinute, it.autoRecord)
            }
            else -> null
        } ?: return

        val minutesLeft = (info.start - TimeUtils.nowMinuteOfDay()).let { if (it < 0) it + 24 * 60 else it }.coerceAtLeast(1)
        val classDay = LocalDateTime.now().plusMinutes(minutesLeft.toLong()).toLocalDate().toEpochDay()
        val ready = repo.standby.value.readyForSilentFreeRecording || AppPermissions.canRecordFromBackground(context)
        val recordLine = context.getString(
            when {
                !info.auto -> R.string.notif_remind_auto_off
                ready -> R.string.notif_remind_auto_on
                else -> R.string.notif_remind_auto_unready
            }
        )
        val time = TimeUtils.minuteToText(info.start) + " – " + TimeUtils.minuteToText(info.end)
        val text = listOf(info.room, time, recordLine).filter { it.isNotBlank() }.joinToString(" · ")
        val notifId = NOTIF_BASE + ((if (exceptionId >= 0) exceptionId else courseId) % 0x1000).toInt()

        val open = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, ClassTimeApp.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_remind_title, info.subject, minutesLeft))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // 수업이 시작되면 의미가 없으니 그때 사라진다.
            .setTimeoutAfter(minutesLeft * 60_000L)
        if (info.auto) {
            val key = if (exceptionId >= 0) AppSettings.makeupSkipKey(exceptionId, classDay)
            else AppSettings.courseSkipKey(courseId, classDay)
            val skip = PendingIntent.getBroadcast(
                context, notifId,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_SKIP
                    putExtra(EXTRA_SKIP_KEY, key)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.notif_remind_skip), skip)
        }
        runCatching { ClassTimeApp.notificationManager(context).notify(notifId, builder.build()) }
            .onFailure { AppLog.w(TAG, "수업 전 알림을 띄우지 못했습니다", it) }
    }

    fun dismiss(context: Context, notifId: Int) {
        runCatching { ClassTimeApp.notificationManager(context).cancel(notifId) }
    }

    private data class Info(
        val subject: String,
        val room: String,
        val start: Int,
        val end: Int,
        val auto: Boolean,
    )
}
