package dev.iruki.classtime.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.AudioRecorder
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.TimeUtils
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 녹음을 담당하는 포그라운드 서비스. 자동(정규 수업) · 보강 · 수동 시작을 모두 처리한다.
 *
 * ## 대기 모드가 필요한 이유
 * 안드로이드는 **서비스가 포그라운드로 전환되는 그 순간의 앱 상태**로 마이크 접근 권한을
 * 고정한다(while-in-use). 앱이 화면에 없을 때 전환되면 마이크가 막히고, `MediaRecorder` 는
 * 예외 없이 **무음을 그대로 인코딩**한다. 수업 시간 알람은 당연히 백그라운드에서 울리므로
 * 알람이 서비스를 새로 띄우는 구조로는 자동 녹음이 영원히 무음이다.
 *
 * 그래서 앱을 열 때 이 서비스를 미리 포그라운드로 올려 두고([ACTION_START_STANDBY]),
 * 수업 시간에는 **이미 권한을 쥔 그 서비스가 그대로 녹음을 시작**한다.
 * 한 번 잡은 권한은 서비스가 살아 있는 동안 유지되므로 앱을 닫거나 화면을 꺼도 녹음된다.
 *
 * 그래서 [startForeground] 는 서비스 인스턴스당 **정확히 한 번만** 호출한다.
 * 다시 호출하면 그 시점의 앱 상태로 권한이 재평가되어 애써 잡은 권한을 잃는다.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var repo: ClassTimeRepository
    @Inject lateinit var storage: RecordingStorage
    @Inject lateinit var settings: AppSettings

    /**
     * 서비스가 죽어도 끝나야 하는 마무리 작업용. [serviceScope] 에서 돌리면
     * stopSelf() 순간 취소되어 파일이 재생 불가 상태로 남는다.
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder by lazy { AudioRecorder(applicationContext) }

    private val stateLock = Mutex()

    // 아래 네 값은 스레드를 넘나든다: onStartCommand / settle / onDestroy 는 메인 스레드에서,
    // startSession / finalizeSession 은 serviceScope(IO) 에서 돈다. @Volatile 이 없으면
    // 한쪽의 쓰기가 다른 쪽에 영원히 보이지 않을 수 있다 (예: 녹음이 시작됐는데도 알림이
    // 계속 '대기 중'으로 남거나, 이미 끝난 세션을 살아 있다고 보고 새 녹음을 건너뛴다).
    //
    // 복합 연산의 원자성은 별도로 보장된다:
    //   - [session] 의 검사-후-대입은 전부 [stateLock] 안에서만 일어난다.
    //   - 나머지 셋은 메인 스레드에서만 쓰이므로 서로에 대해 이미 원자적이다.

    @Volatile private var session: ActiveSession? = null

    /** startForeground() 를 이미 호출했는지. 두 번 부르면 마이크 권한이 재평가된다. */
    @Volatile private var isForeground = false

    /** 포그라운드 전환이 앱이 화면에 있을 때 이뤄졌는지 = 마이크가 열려 있는지. */
    @Volatile private var micReady = false

    /** 녹음이 끝나도 서비스를 살려 둘지. */
    @Volatile private var standbyArmed = false

    private data class ActiveSession(
        val rowId: Long,
        val uri: Uri,
        val legacyPath: String?,
        val subject: String,
        val startedAt: Long,
        val auto: Boolean,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START_STANDBY -> {
                standbyArmed = true
                enterForeground(
                    text = getString(R.string.notif_standby_text),
                    fromForeground = intent.getBooleanExtra(EXTRA_FROM_FOREGROUND, false),
                )
            }

            ACTION_STOP_STANDBY -> {
                standbyArmed = false
                publishStandby()
                if (session == null) leaveForegroundAndStop()
            }

            ACTION_START_AUTO, ACTION_START_MAKEUP, ACTION_START_MANUAL -> {
                // 수동 시작은 사용자가 앱에서 누른 것이므로 앱이 화면에 있다.
                enterForeground(
                    getString(R.string.notif_preparing),
                    fromForeground = action == ACTION_START_MANUAL,
                )
                val spec = when (action) {
                    ACTION_START_AUTO -> Spec.Auto(intent.getLongExtra(EXTRA_COURSE_ID, -1L))
                    ACTION_START_MAKEUP -> Spec.Makeup(intent.getLongExtra(EXTRA_EXCEPTION_ID, -1L))
                    else -> Spec.Manual(intent.getStringExtra(EXTRA_SUBJECT))
                }
                serviceScope.launch { startSession(spec) }
            }

            ACTION_STOP -> finalizeAndSettle("stop-intent")
        }
        return START_NOT_STICKY
    }

    // --- 포그라운드 전환 (마이크 권한이 결정되는 지점) ---

    /**
     * 포그라운드로 올린다. 이미 올라가 있으면 알림만 갱신한다.
     *
     * 단, **마이크 없이 떠 있는 상태에서 앱이 화면에 올라온 경우**에는 일부러 내렸다가 다시
     * 올려 권한을 새로 잡는다. 그래야 "앱을 한 번 열면 다음 수업부터 정상"이 성립한다.
     */
    private fun enterForeground(text: String, fromForeground: Boolean) {
        if (isForeground && !micReady && fromForeground && session == null) {
            AppLog.i(TAG, "마이크 없이 떠 있던 대기 상태 - 지금 앱이 화면에 있으므로 다시 전환합니다")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            isForeground = false
        }
        if (isForeground) {
            if (fromForeground) micReady = true
            updateNotification(text)
            publishStandby()
            return
        }
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isForeground = true
            micReady = fromForeground
            publishStandby()
            AppLog.i(TAG, "포그라운드 전환 (마이크 사용 가능=$micReady)")
        } catch (e: Exception) {
            AppLog.e(TAG, "포그라운드 전환 실패", e)
            finalizeAndSettle("foreground-failed")
        }
    }

    private fun publishStandby() {
        repo.updateStandby(
            StandbyState(active = standbyArmed && isForeground, micReady = micReady)
        )
    }

    // --- 시작 ---

    private sealed interface Spec {
        data class Auto(val courseId: Long) : Spec
        data class Makeup(val exceptionId: Long) : Spec
        data class Manual(val override: String?) : Spec
    }

    private data class Resolved(
        val subject: String,
        val professor: String,
        val courseId: Long?,
        val auto: Boolean,
        /** 계획된 종료 분(minute-of-day). 없으면 상한만 적용. */
        val plannedEndMinute: Int?,
    )

    private suspend fun resolve(spec: Spec): Resolved = when (spec) {
        is Spec.Auto -> {
            val c = repo.course(spec.courseId)
            Resolved(
                subject = c?.subject ?: getString(R.string.subject_unknown),
                professor = c?.professor.orEmpty(),
                courseId = c?.id,
                auto = true,
                plannedEndMinute = c?.endMinute,
            )
        }
        is Spec.Makeup -> {
            val e = repo.exception(spec.exceptionId)
            Resolved(
                subject = e?.subject?.ifBlank { getString(R.string.subject_makeup) }
                    ?: getString(R.string.subject_makeup),
                professor = e?.professor.orEmpty(),
                courseId = null,
                auto = true,
                plannedEndMinute = e?.endMinute,
            )
        }
        is Spec.Manual -> {
            val s = repo.currentSession()
            Resolved(
                subject = spec.override?.takeIf { it.isNotBlank() }
                    ?: s?.subject
                    ?: getString(R.string.subject_unknown),
                professor = s?.professor.orEmpty(),
                courseId = s?.courseId,
                auto = false,
                plannedEndMinute = null,
            )
        }
    }

    private suspend fun startSession(spec: Spec) = stateLock.withLock {
        if (session != null) {
            AppLog.w(TAG, "이미 녹음 중이므로 새 요청 무시: ${spec.javaClass.simpleName}")
            return@withLock
        }
        if (!AppPermissions.micGranted(this)) {
            AppLog.e(TAG, "마이크 권한 없음 - 녹음 불가")
            settle()
            return@withLock
        }
        // 이 서비스가 녹음 중이 아니라면 DB 의 'ongoing' 행은 죽은 잔여물이다.
        // 먼저 치워두지 않으면 다음 수업 자동 녹음이 '이미 녹음 중'으로 오인돼 통째로 건너뛴다.
        runCatching { repo.healStaleRecordings() }
            .onFailure { AppLog.e(TAG, "미완료 녹음 정리 실패 - 이번 녹음이 건너뛰어질 수 있습니다", it) }

        val r = resolve(spec)
        val startedAt = System.currentTimeMillis()
        val target = storage.create(r.subject, TimeUtils.fileStamp(startedAt))

        recorder.onSilenceSuspected = { warnSilence(r.subject) }
        try {
            recorder.start(target.uri)
        } catch (e: Exception) {
            AppLog.e(TAG, "녹음 시작 실패", e)
            runCatching { storage.delete(target.uri) }
                .onFailure { AppLog.w(TAG, "시작 실패한 녹음의 빈 파일을 지우지 못했습니다", it) }
            settle()
            return@withLock
        }

        val rowId = repo.insertRecording(
            Recording(
                courseId = r.courseId,
                subject = r.subject,
                professor = r.professor,
                fileName = target.fileName,
                uri = target.uri.toString(),
                relativePath = target.relativePath,
                startedAt = startedAt,
                auto = r.auto,
                ongoing = true,
            )
        )
        session = ActiveSession(
            rowId = rowId,
            uri = target.uri,
            legacyPath = target.legacyFile?.absolutePath,
            subject = r.subject,
            startedAt = startedAt,
            auto = r.auto,
        )

        repo.updateStatus(
            RecordingStatus(active = true, subject = r.subject, auto = r.auto, startedAt = startedAt)
        )
        armWatchdog(startedAt, r.plannedEndMinute)
        updateNotification(getString(R.string.notif_recording_subject, r.subject))

        if (!micReady) {
            AppLog.e(TAG, "마이크 권한 없이 녹음 시작 - 무음이 될 가능성이 큽니다 (대기 모드 미작동)")
        }
        AppLog.i(TAG, "녹음 시작: ${r.subject} (auto=${r.auto}, micReady=$micReady)")
    }

    /** 워치독 알람: 계획된 종료 시각 + 유예, 그리고 절대 상한 중 이른 쪽. */
    private fun armWatchdog(startedAt: Long, plannedEndMinute: Int?) {
        val capAt = startedAt + MAX_RECORDING_MS
        val plannedAt = plannedEndMinute
            ?.let { TimeUtils.millisAt(LocalDate.now(), it) + WATCHDOG_GRACE_MS }
            ?.takeIf { it > startedAt }
            ?: capAt
        val stopAt = minOf(plannedAt, capAt)
        RecordingWatchdogReceiver.arm(this, stopAt)
        AppLog.i(TAG, "워치독 예약: ${(stopAt - System.currentTimeMillis()) / 1000}s 후")
    }

    // --- 마무리 ---

    /** 어디서 몇 번 불려도 안전. 마무리는 앱 스코프에서 돌아 서비스가 죽어도 완료된다. */
    private fun finalizeAndSettle(reason: String) {
        RecordingWatchdogReceiver.disarm(this)
        appScope.launch {
            withContext(NonCancellable) { finalizeSession(reason) }
            withContext(Dispatchers.Main) { settle() }
        }
    }

    /** 녹음이 끝난 뒤: 대기 모드면 살아남고, 아니면 서비스를 내린다. */
    private fun settle() {
        if (standbyArmed && isForeground) {
            updateNotification(getString(R.string.notif_standby_text))
            publishStandby()
        } else {
            leaveForegroundAndStop()
        }
    }

    private fun leaveForegroundAndStop() {
        isForeground = false
        micReady = false
        publishStandby()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private suspend fun finalizeSession(reason: String) = stateLock.withLock {
        val s = session
        if (s == null) {
            repo.updateStatus(RecordingStatus.Idle)
            return@withLock
        }
        session = null
        recorder.onSilenceSuspected = null

        val result = runCatching { recorder.stop() }.getOrNull()
        val size = runCatching { storage.finalize(s.uri, s.legacyPath?.let { File(it) }) }
            .getOrDefault(0L)
        val duration = result?.durationMs?.takeIf { it > 0 }
            ?: runCatching { storage.probeDurationMs(s.uri) }.getOrDefault(0L)
        val peak = result?.peakAmplitude ?: Recording.UNKNOWN_AMPLITUDE

        repo.recording(s.rowId)?.let { row ->
            repo.updateRecording(
                row.copy(
                    ongoing = false,
                    durationMs = duration,
                    sizeBytes = size,
                    peakAmplitude = peak,
                )
            )
        }
        repo.updateStatus(RecordingStatus.Idle)

        if (result?.cleanStop == false) {
            AppLog.w(TAG, "MediaRecorder 가 정상 종료되지 않았습니다 - 파일이 손상됐을 수 있습니다")
        }
        if (peak == 0 && duration > SILENT_REPORT_MIN_MS) warnSilence(s.subject)
        AppLog.i(
            TAG,
            "녹음 마무리($reason): ${s.subject} ${TimeUtils.formatDuration(duration)} peak=$peak",
        )
    }

    // --- 알림 ---

    private fun updateNotification(text: String) {
        runCatching {
            ClassTimeApp.notificationManager(this).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val recording = session != null
        val builder = NotificationCompat.Builder(this, ClassTimeApp.CHANNEL_RECORDING)
            .setContentTitle(
                if (recording) getString(R.string.notif_recording_title)
                else getString(R.string.notif_standby_title)
            )
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1, stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.notif_stop), stop)
        }
        return builder.build()
    }

    /** 마이크가 차단돼 무음이 녹음되고 있을 때 사용자에게 바로 알린다. */
    private fun warnSilence(subject: String) {
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = getString(
            if (!micReady) R.string.notif_silence_body_background
            else R.string.notif_silence_body_blocked,
            subject,
        )
        val notification = NotificationCompat.Builder(this, ClassTimeApp.CHANNEL_WARNING)
            .setContentTitle(getString(R.string.notif_silence_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        runCatching {
            ClassTimeApp.notificationManager(this).notify(WARN_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        if (session != null) {
            AppLog.w(TAG, "서비스 파괴 - 앱 스코프에서 녹음 마무리")
            RecordingWatchdogReceiver.disarm(this)
            appScope.launch {
                withContext(NonCancellable) { finalizeSession("service-destroyed") }
            }
        }
        isForeground = false
        micReady = false
        publishStandby()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIF_ID = 42
        private const val WARN_NOTIF_ID = 43

        /** 어떤 경우에도 이 길이를 넘겨 녹음하지 않는다 (4시간). */
        private const val MAX_RECORDING_MS = 4L * 60 * 60 * 1000

        /** 종료 알람이 조금 늦어도 잘리지 않도록 워치독에 주는 유예(2분). */
        private const val WATCHDOG_GRACE_MS = 2L * 60 * 1000

        /** 이보다 짧은 녹음은 무음이어도 경고하지 않는다. */
        private const val SILENT_REPORT_MIN_MS = 5_000L

        const val ACTION_START_AUTO = "dev.iruki.classtime.service.START_AUTO"
        const val ACTION_START_MAKEUP = "dev.iruki.classtime.service.START_MAKEUP"
        const val ACTION_START_MANUAL = "dev.iruki.classtime.service.START_MANUAL"
        const val ACTION_START_STANDBY = "dev.iruki.classtime.service.START_STANDBY"
        const val ACTION_STOP_STANDBY = "dev.iruki.classtime.service.STOP_STANDBY"
        const val ACTION_STOP = "dev.iruki.classtime.service.STOP"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_EXCEPTION_ID = "exception_id"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_FROM_FOREGROUND = "from_foreground"

        private fun send(context: Context, intent: Intent) =
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e(TAG, "서비스 시작 실패: ${intent.action}", it) }

        /**
         * 대기 모드를 켠다. **반드시 앱이 화면에 보이는 상태에서** 호출해야 마이크 권한을
         * 함께 잡는다 (Activity 의 onResume 등).
         */
        fun startStandby(context: Context) =
            send(context, Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_STANDBY
                putExtra(EXTRA_FROM_FOREGROUND, true)
            })

        fun stopStandby(context: Context) =
            send(context, Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_STANDBY
            })

        fun startAuto(context: Context, courseId: Long) =
            send(context, Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_AUTO
                putExtra(EXTRA_COURSE_ID, courseId)
            })

        fun startMakeup(context: Context, exceptionId: Long) =
            send(context, Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_MAKEUP
                putExtra(EXTRA_EXCEPTION_ID, exceptionId)
            })

        fun startManual(context: Context, subjectOverride: String? = null) =
            send(context, Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_MANUAL
                if (subjectOverride != null) putExtra(EXTRA_SUBJECT, subjectOverride)
            })

        fun stop(context: Context) = send(context, stopIntent(context))

        private fun stopIntent(context: Context) =
            Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
    }
}
