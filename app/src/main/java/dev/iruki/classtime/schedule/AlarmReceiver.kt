package dev.iruki.classtime.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.iruki.classtime.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 수업 시작/종료 시각에 울리는 정확 알람을 받는다.
 * 시작이면 녹음 서비스를 켜고, 종료면 끄며, 매번 다음 회차 알람을 다시 건다.
 *
 * 여기서는 절대 "놓친 녹음 따라잡기"를 하지 않는다. 그건 [ScheduleManager.catchUpIfMidClass]
 * 의 몫이고, 앱 실행/부팅 때만 실행된다. (종료 알람이 재녹음을 유발하던 버그의 해결책)
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra(ScheduleManager.EXTRA_COURSE_ID, -1L)
        val exceptionId = intent.getLongExtra(ScheduleManager.EXTRA_EXCEPTION_ID, -1L)
        Log.i(TAG, "알람 수신 action=${intent.action} course=$courseId makeup=$exceptionId")

        when (intent.action) {
            ScheduleManager.ACTION_START -> when {
                exceptionId >= 0 -> RecordingService.startMakeup(context, exceptionId)
                courseId >= 0 -> RecordingService.startAuto(context, courseId)
            }

            ScheduleManager.ACTION_STOP -> RecordingService.stop(context)
        }

        // 다음 회차(다음 주 정규 수업 등)를 위해 알람만 다시 건다.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduleManager.rescheduleAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "알람 재설정 실패", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
