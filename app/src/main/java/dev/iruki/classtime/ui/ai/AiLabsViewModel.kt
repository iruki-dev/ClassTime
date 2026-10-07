package dev.iruki.classtime.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.AiException
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.GroqClient
import dev.iruki.classtime.ai.NvidiaClient
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

/** 어느 서비스의 키인지. */
enum class KeyService { GROQ, NVIDIA }

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

/** 교정 모델 선택지. [available] 은 NVIDIA 목록을 받아 왔을 때만 의미가 있다. */
data class ModelChoice(val id: String, val name: String, val available: Boolean)

@HiltViewModel
class AiLabsViewModel @Inject constructor(
    private val settings: AiSettings,
    private val queue: TranscriptionQueue,
    repo: ClassTimeRepository,
) : ViewModel() {

    private val groq = GroqClient()
    private val nvidia = NvidiaClient()
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
    fun setCorrect(on: Boolean) = settings.setCorrect(on)
    fun setModel(id: String) = settings.setModel(id)

    fun cancel(recordingId: Long) = viewModelScope.launch { queue.remove(recordingId) }

    // --- 키 ---

    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    fun resetKeyCheck() {
        _keyCheck.value = KeyCheck.Idle
    }

    /** 키를 실제로 한 번 써 보고, 되면 저장한다. Groq 를 처음 넣을 때는 외부 전송 안내를 먼저. */
    fun checkKey(service: KeyService, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        _keyCheck.value = KeyCheck.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (service) {
                        KeyService.GROQ -> groq.verify(trimmed)
                        KeyService.NVIDIA -> nvidia.verify(trimmed, probeModel())
                    }
                }
            }
            _keyCheck.value = result.fold(
                onSuccess = {
                    if (!settings.consented) KeyCheck.NeedsConsent(trimmed)
                    else save(service, trimmed)
                },
                onFailure = { e ->
                    if (e is AiException && (e.kind == AiException.Kind.NETWORK)) KeyCheck.Offline else KeyCheck.Invalid
                },
            )
        }
    }

    /** 외부 전송 안내에서 ‘켜기’. */
    fun acceptConsent(service: KeyService) {
        val pending = _keyCheck.value as? KeyCheck.NeedsConsent ?: return
        settings.consented = true
        _keyCheck.value = save(service, pending.key)
    }

    fun deleteKey(service: KeyService) {
        when (service) {
            KeyService.GROQ -> settings.setGroqKey(null)
            KeyService.NVIDIA -> settings.setNvidiaKey(null)
        }
        _keyCheck.value = KeyCheck.Saved
    }

    private fun save(service: KeyService, key: String): KeyCheck {
        when (service) {
            KeyService.GROQ -> settings.setGroqKey(key)
            KeyService.NVIDIA -> settings.setNvidiaKey(key)
        }
        queue.kick()
        return KeyCheck.Saved
    }

    /** 키 확인용 모델: 가벼운 것 → 교정 모델 순으로, 지금 제공 중인 첫 모델. */
    private suspend fun probeModel(): String {
        val live = liveModels() ?: return NvidiaClient.PROBE_MODEL
        return (listOf(NvidiaClient.PROBE_MODEL) + settings.modelOrder()).firstOrNull { it in live }
            ?: NvidiaClient.PROBE_MODEL
    }

    // --- 모델 목록 ---

    private val _models = MutableStateFlow(NvidiaClient.PREFERRED.map { ModelChoice(it, modelName(it), true) })
    val models: StateFlow<List<ModelChoice>> = _models.asStateFlow()

    /** 모델 고르기를 열 때 NVIDIA 에서 지금 제공 중인 모델을 확인한다(키 필요 없음). */
    fun refreshModels() = viewModelScope.launch {
        val live = liveModels() ?: return@launch
        _models.value = NvidiaClient.PREFERRED.map { ModelChoice(it, modelName(it), it in live) }
    }

    private suspend fun liveModels(): Set<String>? = withContext(Dispatchers.IO) {
        runCatching { nvidia.models().toSet() }.getOrNull()
    }

    companion object {
        /** 화면에 보일 모델 이름. 모르는 것은 id 의 마지막 부분. */
        fun modelName(id: String): String = when (id) {
            "deepseek-ai/deepseek-v4.1-flash" -> "DeepSeek V4.1 Flash"
            "moonshotai/kimi-k3" -> "Kimi K3"
            "z-ai/glm-5.3" -> "GLM 5.3"
            "nvidia/nemotron-3-ultra-550b-a55b" -> "Nemotron 3 Ultra"
            else -> id.substringAfterLast('/')
        }
    }
}
