package dev.iruki.classtime.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 녹음 하드 리밋. [AlarmManager] 를 쓰기 때문에 기기가 잠들어 있어도 정확히 깨어난다.
 *
 * 예전에는 `Handler.postDelayed` 로 워치독을 걸었는데, Handler 는 uptimeMillis 기준이라
 * 깊은 절전에 들어가면 시간이 흐르지 않아 정작 필요할 때 발동하지 않았다.
 */
class RecordingWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "워치독 발동 - 녹음을 강제로 마무리합니다")
        RecordingService.stop(context)
    }

    companion object {
        private const val TAG = "RecordingWatchdog"
        private const val REQUEST_CODE = 0x7A7C
        private const val ACTION = "dev.iruki.classtime.service.WATCHDOG"

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RecordingWatchdogReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun arm(context: Context, triggerAtMillis: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(context)
            am.cancel(pi)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }

        fun disarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context))
        }
    }
}
