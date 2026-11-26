package dev.iruki.classtime.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiException
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.ClaudeClient
import dev.iruki.classtime.ai.SummaryApiException
import dev.iruki.classtime.ai.SummaryModel
import dev.iruki.classtime.ai.SummaryQueue
import dev.iruki.classtime.ai.GroqClient
import dev.iruki.classtime.ai.QuotaLedger
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/** 지금까지 끝낸 요약 수와 대략의 요금(달러). */
data class SummaryUsage(val count: Int, val dollars: Double)

/** 대기열 한 줄. */
data class QueueItem(val transcript: Transcript, val recording: Recording)

/** 한도 막대 두 개(분). */
data class QuotaUse(val hourUsed: Int, val hourMax: Int, val dayUsed: Int, val dayMax: Int)

@HiltViewModel
class AiLabsViewModel @Inject constructor(
    private val settings: AiSettings,
    private val queue: TranscriptionQueue,
    private val summaries: SummaryQueue,
    repo: ClassTimeRepository,
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

    val summaryUsage: StateFlow<SummaryUsage> = summaries.observeAll().map { list ->
        val done = list.filter { it.inputTokens > 0 || it.outputTokens > 0 }
        SummaryUsage(
            count = list.count { it.stateEnum == SummaryState.DONE },
            dollars = done.sumOf { SummaryModel.of(it.model).batchCost(it.inputTokens, it.outputTokens) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUsage(0, 0.0))

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
