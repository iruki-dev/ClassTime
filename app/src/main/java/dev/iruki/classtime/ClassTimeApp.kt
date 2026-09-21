package dev.iruki.classtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.util.AppLog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ClassTimeApp : Application() {

    @Inject lateinit var repository: ClassTimeRepository

    /** 마무리 작업 전용 스코프. 자세한 설명은 [ApplicationScope]. */
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // 강제 종료 등으로 마무리를 놓친 녹음을 여기서 확정한다
        // (pending 해제 → 재생 가능, 길이·용량 채움, '녹음 중' 해제).
        applicationScope.launch {
            runCatching { repository.healStaleRecordings() }
                .onFailure { AppLog.e(TAG, "시작 시 미완료 녹음 복구 실패", it) }
        }
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
        private const val TAG = "ClassTimeApp"

        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WARNING = "warning"

        fun notificationManager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    }
}
