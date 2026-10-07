package dev.iruki.classtime.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 화면이 그대로 그리는 AI 설정 상태. 키 자체는 담지 않는다. */
data class AiConfig(
    /** Groq 키가 저장돼 있으면 기능이 켜진 것. */
    val groqKeyHint: String? = null,
    val nvidiaKeyHint: String? = null,
    /** 저장된 키가 나중에 거부됨(만료·삭제). 설정에서 다시 넣어야 한다. */
    val groqRejected: Boolean = false,
    val nvidiaRejected: Boolean = false,
    /** 녹음이 끝나면 바로 변환. */
    val autoTranscribe: Boolean = true,
    /** NVIDIA 키가 있을 때 교정까지. */
    val correct: Boolean = true,
    /** 고른 교정 모델. 빈 값이면 [NvidiaClient.PREFERRED] 순서. */
    val model: String = "",
) {
    val enabled get() = groqKeyHint != null
    val canCorrect get() = enabled && nvidiaKeyHint != null && correct
}

/**
 * 실험적 기능 ‘AI 텍스트 변환’의 설정. 키는 [SecretStore] 에, 나머지는 일반 설정 파일에.
 * 한도 계산을 위한 전송 기록([usage])도 여기 둔다 — 하루치만 남는 작은 목록이다.
 */
class AiSettings(context: Context, private val secrets: Secrets) {

    private val prefs = context.applicationContext.getSharedPreferences("classtime_ai", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun groqKey(): String? = secrets.get(KEY_GROQ)
    fun nvidiaKey(): String? = secrets.get(KEY_NVIDIA)

    /** 확인을 마친 키만 저장한다. null 이면 지운다. */
    fun setGroqKey(key: String?) {
        secrets.put(KEY_GROQ, key?.trim())
        prefs.edit().putString(HINT_GROQ, key?.trim()?.let(::hint)).putBoolean(REJECTED_GROQ, false).apply()
        refresh()
    }

    fun setNvidiaKey(key: String?) {
        secrets.put(KEY_NVIDIA, key?.trim())
        prefs.edit().putString(HINT_NVIDIA, key?.trim()?.let(::hint)).putBoolean(REJECTED_NVIDIA, false).apply()
        refresh()
    }

    fun markRejected(groq: Boolean) {
        prefs.edit().putBoolean(if (groq) REJECTED_GROQ else REJECTED_NVIDIA, true).apply()
        refresh()
    }

    fun setAutoTranscribe(on: Boolean) = edit { putBoolean(AUTO, on) }
    fun setCorrect(on: Boolean) = edit { putBoolean(CORRECT, on) }
    fun setModel(model: String) = edit { putString(MODEL, model) }

    /** 클라우드로 소리를 보낸다는 안내를 확인했는지. 처음 켤 때 한 번 보여 준다. */
    var consented: Boolean
        get() = prefs.getBoolean(CONSENT, false)
        set(value) = prefs.edit().putBoolean(CONSENT, value).apply()

    /** 교정에 쓸 모델 순서: 고른 것 → 기본 순서. */
    fun modelOrder(): List<String> = (listOf(_config.value.model) + NvidiaClient.PREFERRED).filter { it.isNotBlank() }.distinct()

    // --- 전송 기록 (Groq 한도) ---

    @Synchronized
    fun usage(): List<QuotaLedger.Use> {
        val json = prefs.getString(USAGE, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> QuotaLedger.Use(o.getLong("a"), o.getInt("s")) } }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun recordUse(use: QuotaLedger.Use, ledger: QuotaLedger) {
        val kept = ledger.prune(usage(), use.at) + use
        val a = JSONArray(kept.map { JSONObject().put("a", it.at).put("s", it.seconds) })
        prefs.edit().putString(USAGE, a.toString()).apply()
        _usageTick.value = _usageTick.value + 1
    }

    private val _usageTick = MutableStateFlow(0)
    /** 기록이 늘 때마다 바뀐다. 사용량 표시가 이것을 보고 다시 계산한다. */
    val usageTick: StateFlow<Int> = _usageTick.asStateFlow()

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        refresh()
    }

    private fun refresh() {
        _config.value = read()
    }

    private fun read() = AiConfig(
        groqKeyHint = prefs.getString(HINT_GROQ, null),
        nvidiaKeyHint = prefs.getString(HINT_NVIDIA, null),
        groqRejected = prefs.getBoolean(REJECTED_GROQ, false),
        nvidiaRejected = prefs.getBoolean(REJECTED_NVIDIA, false),
        autoTranscribe = prefs.getBoolean(AUTO, true),
        correct = prefs.getBoolean(CORRECT, true),
        model = prefs.getString(MODEL, "") ?: "",
    )

    companion object {
        private const val KEY_GROQ = "groq"
        private const val KEY_NVIDIA = "nvidia"
        private const val HINT_GROQ = "groq_hint"
        private const val HINT_NVIDIA = "nvidia_hint"
        private const val REJECTED_GROQ = "groq_rejected"
        private const val REJECTED_NVIDIA = "nvidia_rejected"
        private const val AUTO = "auto_transcribe"
        private const val CORRECT = "correct"
        private const val MODEL = "model"
        private const val CONSENT = "consent"
        private const val USAGE = "groq_usage"

        /** 화면에 보일 키 끝 네 자리. */
        fun hint(key: String): String = "••••" + key.takeLast(4)
    }
}
