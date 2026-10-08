package dev.iruki.classtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.ai.TranscriptionQueue
import javax.inject.Inject
import dev.iruki.classtime.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class ClassTimeApp : Application() {

    @Inject lateinit var repository: ClassTimeRepository

    /** 마무리 작업 전용 스코프. 자세한 설명은 [ApplicationScope]. */
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    @Inject lateinit var transcription: TranscriptionQueue

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // 강제 종료 등으로 마무리를 놓친 녹음을 여기서 확정한다
        // (pending 해제 → 재생 가능, 길이·용량 채움, '녹음 중' 해제).
        applicationScope.launch {
            runCatching { repository.healStaleRecordings() }
                .onFailure { AppLog.e(TAG, "시작 시 미완료 녹음 복구 실패", it) }
        }
        keepWidgetInSync()
        backfillTranscriptFiles()
    }

    /** 텍스트 파일 저장이 생기기 전에 끝난 변환도 파일로 남긴다. 한 번만. */
    private fun backfillTranscriptFiles() {
        val prefs = getSharedPreferences("transcript_files", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACKFILLED, false)) return
        applicationScope.launch {
            runCatching { transcription.backfillFiles() }
                .onSuccess { prefs.edit().putBoolean(KEY_BACKFILLED, true).apply() }
                .onFailure { AppLog.w(TAG, "텍스트 파일 채우기 실패", it) }
        }
    }

    /**
     * 녹음 상태나 오늘 수업이 바뀌면 위젯을 다시 그린다. 위젯은 스스로 데이터를 관찰하지 못한다.
     * 시간이 흘러 ‘다음 수업’이 바뀌는 것은 수업 시작·종료 알람(AlarmReceiver)이 맡는다.
     */
    @OptIn(FlowPreview::class)
    private fun keepWidgetInSync() {
        applicationScope.launch {
            combine(
                repository.status.map { Triple(it.active, it.subject, it.plannedEndAt) },
                repository.todaySessions,
            ) { status, sessions -> status to sessions }
                .distinctUntilChanged()
                .debounce(500)
                .collect { TodayWidget.refresh(this@ClassTimeApp) }
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
        val reminder = NotificationChannel(
            CHANNEL_REMINDER,
            getString(R.string.channel_reminder_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_reminder_desc)
        }
        val transcription = NotificationChannel(
            CHANNEL_TRANSCRIPTION,
            getString(R.string.channel_transcription_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        notificationManager(this).createNotificationChannels(listOf(recording, warning, reminder, transcription))
    }

    companion object {
        private const val TAG = "ClassTimeApp"
        private const val KEY_BACKFILLED = "backfilled_v1"

        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WARNING = "warning"
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_TRANSCRIPTION = "transcription"

        fun notificationManager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    }
}
