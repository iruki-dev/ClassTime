package dev.iruki.classtime.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiException
import dev.iruki.classtime.ai.AiSettings
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.ai.BulkPlan
import dev.iruki.classtime.ai.ClaudeClient
import dev.iruki.classtime.ai.SummaryApiException
import dev.iruki.classtime.ai.SummaryEngine
import dev.iruki.classtime.ai.SummaryModel
import dev.iruki.classtime.ai.SummaryQueue
import dev.iruki.classtime.ai.GroqClient
import dev.iruki.classtime.ai.QuotaLedger
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.sync.DriveAuth
import dev.iruki.classtime.sync.DriveConfig
import dev.iruki.classtime.sync.DriveError
import dev.iruki.classtime.sync.DriveSettings
import dev.iruki.classtime.sync.DriveSyncWorker
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 키 시트의 상태. */
sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data object Invalid : KeyCheck
    data object Offline : KeyCheck
    /** 확인은 끝났고, 처음이라 외부 전송 안내에 동의를 기다린다. */
    data class NeedsConsent(val key: String, val provider: KeyProvider) : KeyCheck
    data object Saved : KeyCheck
}

/** 키를 넣는 서비스. Groq: 받아 적기, Claude: 요약. */
enum class KeyProvider { GROQ, CLAUDE }

/** 요약 사용량(추정): 이번 달과 전체. */
data class SummaryUsage(
    val monthCount: Int = 0,
    val monthDollars: Double = 0.0,
    val monthTokens: Long = 0,
    val totalCount: Int = 0,
    val totalDollars: Double = 0.0,
)

/** 대기열 한 줄. */
data class QueueItem(val transcript: Transcript, val recording: Recording)

/** 한도 막대 두 개(분). */
data class QuotaUse(val hourUsed: Int, val hourMax: Int, val dayUsed: Int, val dayMax: Int)

@HiltViewModel
class AiLabsViewModel @Inject constructor(
    private val settings: AiSettings,
    private val queue: TranscriptionQueue,
    private val summaries: SummaryQueue,
    private val repo: ClassTimeRepository,
    private val drive: DriveSettings,
    private val driveAuth: DriveAuth,
    @ApplicationContext private val app: Context,
) : ViewModel() {

    private val groq = GroqClient()
    private val claude = ClaudeClient()
    private val ledger = QuotaLedger()

    val config: StateFlow<AiConfig> = settings.config

    val queueItems: StateFlow<List<QueueItem>> = combine(queue.observeAll(), repo.recordings) { ts, recs ->
        val byId = recs.associateBy { it.id }
        ts.filter { it.stateEnum.active }
            .sortedBy { it.queuedAt }
            .mapNotNull { t -> byId[t.recordingId]?.let { QueueItem(t, it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 한도는 굴러가는 창이라 기록이 늘 때와 1분마다 다시 센다. */
    val quota: StateFlow<QuotaUse> = combine(settings.usageTick, minuteTicks()) { _, now ->
        val (hour, day) = ledger.usedSeconds(settings.usage(), now)
        QuotaUse(hour / 60, ledger.hourlySeconds / 60, day / 60, ledger.dailySeconds / 60)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuotaUse(0, ledger.hourlySeconds / 60, 0, ledger.dailySeconds / 60))

    val summaryUsage: StateFlow<SummaryUsage> = settings.usageTick.map {
        val months = settings.summaryMonths()
        val now = months.firstOrNull { it.month == SummaryEngine.month(System.currentTimeMillis()) }
        SummaryUsage(
            monthCount = now?.count ?: 0,
            monthDollars = now?.dollars ?: 0.0,
            monthTokens = (now?.inputTokens ?: 0) + (now?.outputTokens ?: 0),
            totalCount = months.sumOf { it.count },
            totalDollars = months.sumOf { it.dollars },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUsage())

    private fun minuteTicks() = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    // --- 스위치·옵션 ---

    fun setSwitchOn(on: Boolean) {
        settings.setSwitchOn(on)
        if (on) {
            queue.kick()
            summaries.kick()
        }
    }

    fun setAutoTranscribe(on: Boolean) = settings.setAutoTranscribe(on)
    fun setAutoSummarize(on: Boolean) = settings.setAutoSummarize(on)
    fun setSummaryModel(model: SummaryModel) = settings.setSummaryModel(model)

    fun cancel(recordingId: Long) = viewModelScope.launch { queue.remove(recordingId) }

    // --- Google Drive 동기화 ---

    val driveConfig: StateFlow<DriveConfig> = drive.config

    /**
     * 드라이브 연결. 처음이면 Google 의 허락 화면을 띄워야 하므로 [onConsent] 로 화면에 넘긴다.
     * 이미 허락했으면 바로 연결되고 동기화를 시작한다.
     */
    fun connectDrive(onConsent: (PendingIntent) -> Unit) = viewModelScope.launch {
        val result = runCatching { driveAuth.authorize() }.getOrElse { e ->
            drive.failed(if (DriveAuth.isSetupError(e)) DriveError.SETUP else DriveError.OTHER)
            return@launch
        }
        val intent = result.pendingIntent
        if (result.hasResolution() && intent != null) onConsent(intent) else finishConnect(result)
    }

    /** 허락 화면에서 돌아왔을 때. */
    fun onDriveConsent(data: Intent?) {
        val result = runCatching { driveAuth.fromIntent(data) }.getOrElse { e ->
            drive.failed(if (DriveAuth.isSetupError(e)) DriveError.SETUP else DriveError.OTHER)
            return
        }
        finishConnect(result)
    }

    private fun finishConnect(result: AuthorizationResult) {
        if (result.accessToken == null) {
            drive.failed(DriveError.OTHER)
            return
        }
        drive.connected(runCatching { result.toGoogleSignInAccount()?.email }.getOrNull())
        DriveSyncWorker.kick(app, delayMs = 0)
    }

    fun syncDriveNow() = DriveSyncWorker.kick(app, delayMs = 0)

    /** 연결 끊기. 드라이브에 올린 파일은 그대로 두고, 이 기기에서 더는 맞추지 않는다. */
    fun disconnectDrive() = viewModelScope.launch {
        driveAuth.revoke(drive.config.value.email)
        drive.disconnected()
        DriveSyncWorker.stateFile(app).delete()
    }

    // --- 전체 텍스트 변환·요약 ---

    /** 지금 맡길 것을 센다. 화면이 확인 창에 보여 준다. */
    fun planBulk(onPlan: (BulkPlan) -> Unit) = viewModelScope.launch {
        val recs = repo.recordings.first()
        val ts = queue.observeAll().first().associateBy { it.recordingId }
        val ss = summaries.observeAll().first().associateBy { it.recordingId }
        val c = settings.config.value
        onPlan(BulkPlan.of(recs, ts, ss, summaryEnabled = c.summaryEnabled && !c.claudeRejected))
    }

    /** 텍스트가 없는 녹음은 변환한 뒤 요약까지, 요약만 없는 녹음은 요약을 맡긴다. */
    fun runBulk(plan: BulkPlan) = viewModelScope.launch {
        val summarizeToo = config.value.summaryEnabled && !config.value.claudeRejected
        for (id in plan.transcribe) {
            queue.enqueue(id)
            if (summarizeToo) summaries.enqueueAfterText(id)
        }
        for (id in plan.summarize) summaries.enqueue(id)
        queue.kick()
        summaries.kick()
    }

    // --- 키 ---

    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    fun resetKeyCheck() {
        _keyCheck.value = KeyCheck.Idle
    }

    /** 키를 실제로 한 번 써 보고, 되면 저장한다(토큰을 쓰지 않는 조회). 처음 넣을 때는 외부 전송 안내를 먼저. */
    fun checkKey(provider: KeyProvider, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { if (provider == KeyProvider.GROQ) groq.verify(trimmed) else claude.verify(trimmed) }
            }
            _keyCheck.value = result.fold(
                onSuccess = { if (!consented(provider)) KeyCheck.NeedsConsent(trimmed, provider) else save(provider, trimmed) },
                onFailure = { e ->
                    val offline = (e is AiException && e.kind == AiException.Kind.NETWORK) ||
                        (e is SummaryApiException && e.kind == SummaryApiException.Kind.NETWORK)
                    if (offline) KeyCheck.Offline else KeyCheck.Invalid
                },
            )
        }
    }

    /** 외부 전송 안내에서 ‘켜기’. */
    fun acceptConsent() {
        val pending = _keyCheck.value as? KeyCheck.NeedsConsent ?: return
        if (pending.provider == KeyProvider.GROQ) settings.consented = true else settings.summaryConsented = true
        _keyCheck.value = save(pending.provider, pending.key)
    }

    fun deleteKey(provider: KeyProvider) {
        if (provider == KeyProvider.GROQ) settings.setGroqKey(null) else settings.setClaudeKey(null)
        _keyCheck.value = KeyCheck.Saved
    }

    private fun consented(provider: KeyProvider) =
        if (provider == KeyProvider.GROQ) settings.consented else settings.summaryConsented

    private fun save(provider: KeyProvider, key: String): KeyCheck {
        if (provider == KeyProvider.GROQ) {
            settings.setGroqKey(key)
            queue.kick()
        } else {
            settings.setClaudeKey(key)
            summaries.kick()
        }
        return KeyCheck.Saved
    }
}
