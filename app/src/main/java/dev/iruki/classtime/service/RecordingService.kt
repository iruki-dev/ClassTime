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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.AudioRecorder
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.TimeUtils
import java.io.File
import java.time.LocalDate
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
class RecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: ClassTimeRepository
    private lateinit var storage: RecordingStorage
    private lateinit var settings: AppSettings
    private val recorder by lazy { AudioRecorder(applicationContext) }

    private val stateLock = Mutex()
    private var session: ActiveSession? = null

    /** startForeground() 를 이미 호출했는지. 두 번 부르면 마이크 권한이 재평가된다. */
    private var isForeground = false

    /** 포그라운드 전환이 앱이 화면에 있을 때 이뤄졌는지 = 마이크가 열려 있는지. */
    private var micReady = false

    /** 녹음이 끝나도 서비스를 살려 둘지. */
    private var standbyArmed = false

    private data class ActiveSession(
        val rowId: Long,
        val uri: Uri,
        val legacyPath: String?,
        val subject: String,
        val startedAt: Long,
        val auto: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        repo = ClassTimeRepository.get(this)
        storage = RecordingStorage(applicationContext)
        settings = AppSettings(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START_STANDBY -> {
                standbyArmed = true
                enterForeground(
                    text = STANDBY_TEXT,
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
                enterForeground("녹음 준비 중…", fromForeground = action == ACTION_START_MANUAL)
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
            Log.i(TAG, "마이크 없이 떠 있던 대기 상태 - 지금 앱이 화면에 있으므로 다시 전환합니다")
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
            Log.i(TAG, "포그라운드 전환 (마이크 사용 가능=$micReady)")
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 전환 실패", e)
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
            Resolved(c?.subject ?: "기타", c?.professor.orEmpty(), c?.id, auto = true, c?.endMinute)
        }
        is Spec.Makeup -> {
            val e = repo.exception(spec.exceptionId)
            Resolved(
                subject = e?.subject?.ifBlank { "보강" } ?: "보강",
                professor = e?.professor.orEmpty(),
                courseId = null,
                auto = true,
                plannedEndMinute = e?.endMinute,
            )
        }
        is Spec.Manual -> {
            val s = repo.currentSession()
            Resolved(
                subject = spec.override?.takeIf { it.isNotBlank() } ?: s?.subject ?: "기타",
                professor = s?.professor.orEmpty(),
                courseId = s?.courseId,
                auto = false,
                plannedEndMinute = null,
            )
        }
    }

    private suspend fun startSession(spec: Spec) = stateLock.withLock {
        if (session != null) {
            Log.w(TAG, "이미 녹음 중이므로 새 요청 무시: $spec")
            return@withLock
        }
        if (!AppPermissions.micGranted(this)) {
            Log.e(TAG, "마이크 권한 없음 - 녹음 불가")
            settle()
            return@withLock
        }
        // 이 서비스가 녹음 중이 아니라면 DB 의 'ongoing' 행은 죽은 잔여물이다.
        // 먼저 치워두지 않으면 다음 수업 자동 녹음이 '이미 녹음 중'으로 오인돼 통째로 건너뛴다.
        runCatching { repo.healStaleRecordings() }

        val r = resolve(spec)
        val startedAt = System.currentTimeMillis()
        val target = storage.create(r.subject, TimeUtils.fileStamp(startedAt))

        recorder.onSilenceSuspected = { warnSilence(r.subject) }
        try {
            recorder.start(target.uri)
        } catch (e: Exception) {
            Log.e(TAG, "녹음 시작 실패", e)
            runCatching { storage.delete(target.uri) }
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
        updateNotification("‘${r.subject}’ 녹음 중")

        if (!micReady) {
            Log.e(TAG, "마이크 권한 없이 녹음 시작 - 무음이 될 가능성이 큽니다 (대기 모드 미작동)")
        }
        Log.i(TAG, "녹음 시작: ${r.subject} (auto=${r.auto}, micReady=$micReady)")
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
        Log.i(TAG, "워치독 예약: ${(stopAt - System.currentTimeMillis()) / 1000}s 후")
    }

    // --- 마무리 ---

    /** 어디서 몇 번 불려도 안전. 마무리는 앱 스코프에서 돌아 서비스가 죽어도 완료된다. */
    private fun finalizeAndSettle(reason: String) {
        RecordingWatchdogReceiver.disarm(this)
        ClassTimeApp.appScope(this).launch {
            withContext(NonCancellable) { finalizeSession(reason) }
            withContext(Dispatchers.Main) { settle() }
        }
    }

    /** 녹음이 끝난 뒤: 대기 모드면 살아남고, 아니면 서비스를 내린다. */
    private fun settle() {
        if (standbyArmed && isForeground) {
            updateNotification(STANDBY_TEXT)
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
            Log.w(TAG, "MediaRecorder 가 정상 종료되지 않았습니다 - 파일이 손상됐을 수 있습니다")
        }
        if (peak == 0 && duration > SILENT_REPORT_MIN_MS) warnSilence(s.subject)
        Log.i(
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
        val body = if (!micReady) {
            "‘$subject’ 녹음에 소리가 들어오지 않습니다. 녹음이 시작될 때 앱이 화면에 없어서 " +
                "안드로이드가 마이크를 막았습니다. 앱을 열면 지금부터라도 소리가 들어오고, " +
                "다음 수업부터는 정상적으로 녹음됩니다."
        } else {
            "‘$subject’ 녹음에 소리가 들어오지 않습니다. 다른 앱이 마이크를 쓰고 있거나 " +
                "기기 설정에서 마이크가 차단된 상태일 수 있습니다."
        }
        val notification = NotificationCompat.Builder(this, ClassTimeApp.CHANNEL_WARNING)
            .setContentTitle("무음이 녹음되고 있습니다")
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
            Log.w(TAG, "서비스 파괴 - 앱 스코프에서 녹음 마무리")
            RecordingWatchdogReceiver.disarm(this)
            ClassTimeApp.appScope(this).launch {
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

        private const val STANDBY_TEXT = "대기 중 · 수업 시간이 되면 자동으로 녹음합니다"

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
                .onFailure { Log.e(TAG, "서비스 시작 실패: ${intent.action}", it) }

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
