package dev.iruki.classtime.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiException
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.GroqClient
import dev.iruki.classtime.ai.QuotaLedger
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Recording
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
    data class NeedsConsent(val key: String) : KeyCheck
    data object Saved : KeyCheck
}

/** 대기열 한 줄. */
data class QueueItem(val transcript: Transcript, val recording: Recording)

/** 한도 막대 두 개(분). */
data class QuotaUse(val hourUsed: Int, val hourMax: Int, val dayUsed: Int, val dayMax: Int)

@HiltViewModel
class AiLabsViewModel @Inject constructor(
    private val settings: AiSettings,
    private val queue: TranscriptionQueue,
    repo: ClassTimeRepository,
) : ViewModel() {

    private val groq = GroqClient()
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

    private fun minuteTicks() = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    // --- 스위치·옵션 ---

    fun setSwitchOn(on: Boolean) {
        settings.setSwitchOn(on)
        if (on) queue.kick()
    }

    fun setAutoTranscribe(on: Boolean) = settings.setAutoTranscribe(on)

    fun cancel(recordingId: Long) = viewModelScope.launch { queue.remove(recordingId) }

    // --- 키 ---

    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    fun resetKeyCheck() {
        _keyCheck.value = KeyCheck.Idle
    }

    /** 키를 실제로 한 번 써 보고, 되면 저장한다. 처음 넣을 때는 외부 전송 안내를 먼저. */
    fun checkKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { groq.verify(trimmed) } }
            _keyCheck.value = result.fold(
                onSuccess = { if (!settings.consented) KeyCheck.NeedsConsent(trimmed) else save(trimmed) },
                onFailure = { e ->
                    if (e is AiException && e.kind == AiException.Kind.NETWORK) KeyCheck.Offline else KeyCheck.Invalid
                },
            )
        }
    }

    /** 외부 전송 안내에서 ‘켜기’. */
    fun acceptConsent() {
        val pending = _keyCheck.value as? KeyCheck.NeedsConsent ?: return
        settings.consented = true
        _keyCheck.value = save(pending.key)
    }

    fun deleteKey() {
        settings.setGroqKey(null)
        _keyCheck.value = KeyCheck.Saved
    }

    private fun save(key: String): KeyCheck {
        settings.setGroqKey(key)
        queue.kick()
        return KeyCheck.Saved
    }
}
