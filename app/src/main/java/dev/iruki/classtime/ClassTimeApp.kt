package dev.iruki.classtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.iruki.classtime.data.ClassTimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClassTimeApp : Application() {

    val repository: ClassTimeRepository by lazy { ClassTimeRepository.get(this) }

    /**
     * 서비스 수명과 무관하게 살아 있는 스코프. 녹음 마무리(파일 확정 + DB 갱신)처럼
     * 서비스가 종료돼도 반드시 끝나야 하는 작업에 쓴다.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // 강제 종료 등으로 마무리를 놓친 녹음을 여기서 확정한다
        // (pending 해제 → 재생 가능, 길이·용량 채움, '녹음 중' 해제).
        applicationScope.launch { runCatching { repository.healStaleRecordings() } }
    }

    private fun createChannels() {
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.channel_recording_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_recording_desc)
            setShowBadge(false)
        }
        // 무음 녹음 경고는 놓치면 학기 내내 빈 파일만 쌓이므로 눈에 띄어야 한다.
        val warning = NotificationChannel(
            CHANNEL_WARNING,
            getString(R.string.channel_warning_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_warning_desc)
        }
        notificationManager(this).createNotificationChannels(listOf(recording, warning))
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WARNING = "warning"

        fun notificationManager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun repository(context: Context): ClassTimeRepository =
            (context.applicationContext as ClassTimeApp).repository

        fun appScope(context: Context): CoroutineScope =
            (context.applicationContext as ClassTimeApp).applicationScope
    }
}
