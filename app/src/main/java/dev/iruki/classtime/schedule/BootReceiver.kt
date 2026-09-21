package dev.iruki.classtime.schedule

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.ReceiverWork.runAsync
import javax.inject.Inject

/**
 * 재부팅·앱 업데이트·시간(대) 변경 후 정확 알람이 사라지므로 다시 걸고,
 * 마침 수업 중이면 놓친 녹음을 이어서 시작한다.
 *
 * 재부팅 직후에는 앱이 화면에 없어 **마이크 권한을 쥔 대기 모드를 만들 수 없다.**
 * 그대로 두면 다음 수업이 무음으로 녹음되므로, 앱을 한 번 열어 달라고 알린다.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduleManager: ScheduleManager
    @Inject lateinit var repository: ClassTimeRepository
    @Inject lateinit var settings: AppSettings

    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i(TAG, "부팅/시간 변경 수신: ${intent.action}")
        val rebooted = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED

        runAsync(TAG, "부팅 후 알람 재설정") {
            scheduleManager.rescheduleAll()
            scheduleManager.catchUpIfMidClass()
            if (rebooted) maybeAskToOpenApp(context)
        }
    }

    private suspend fun maybeAskToOpenApp(context: Context) {
        if (!settings.standbyEnabled) return
        if (!AppPermissions.micGranted(context)) return
        val hasAutoCourse = runCatching { repository.allCourses().any { it.autoRecord } }
            .onFailure { AppLog.e(TAG, "과목 조회 실패 - 부팅 후 안내를 건너뜁니다", it) }
            .getOrDefault(false)
        if (!hasAutoCourse) return

        val body = context.getString(R.string.notif_reboot_body)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ClassTimeApp.CHANNEL_WARNING)
            .setContentTitle(context.getString(R.string.notif_reboot_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        runCatching {
            ClassTimeApp.notificationManager(context).notify(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val NOTIF_ID = 44
    }
}
