package dev.iruki.classtime.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 화면이 그대로 그리는 AI 설정 상태. 키 자체는 담지 않는다. */
data class AiConfig(
    /** 저장된 Groq 키의 끝 네 자리. 없으면 기능을 켤 수 없다. */
    val groqKeyHint: String? = null,
    /** 저장된 키가 나중에 거부됨(만료·삭제). 설정에서 다시 넣어야 한다. */
    val groqRejected: Boolean = false,
    /** 녹음이 끝나면 바로 변환. */
    val autoTranscribe: Boolean = true,
    /** 실험적 기능 화면의 ‘사용’ 스위치. 끄면 키는 남기고 변환만 멈춘다. */
    val switchOn: Boolean = true,
    /** 저장된 Claude 키의 끝 네 자리. 없으면 요약을 할 수 없다. */
    val claudeKeyHint: String? = null,
    val claudeRejected: Boolean = false,
    /** 텍스트 변환이 끝나면 바로 요약. */
    val autoSummarize: Boolean = true,
    val summaryModel: SummaryModel = SummaryModel.DEFAULT,
) {
    /** 변환을 할 수 있는 상태(키 있음 + 스위치 켬). */
    val enabled get() = groqKeyHint != null && switchOn

    /** 요약을 할 수 있는 상태. 요약할 텍스트가 있어야 하므로 변환 설정과 같은 스위치를 따른다. */
    val summaryEnabled get() = claudeKeyHint != null && switchOn
}

/**
 * 실험적 기능 ‘AI 텍스트 변환’의 설정. 키는 [SecretStore] 에, 나머지는 일반 설정 파일에.
 * 한도 계산을 위한 전송 기록([usage])도 여기 둔다 — 하루치만 남는 작은 목록이다.
 */
class AiSettings(context: Context, private val secrets: Secrets) {

    private val prefs = context.applicationContext.getSharedPreferences("classtime_ai", Context.MODE_PRIVATE)

    init {
        // LLM 교정(NVIDIA)을 뺐다. 예전에 넣은 키와 설정은 남겨 둘 이유가 없으니 지운다.
        if (prefs.contains(LEGACY_NVIDIA_HINT) || prefs.contains(LEGACY_MODEL) || prefs.contains(LEGACY_CORRECT)) {
            secrets.put(LEGACY_NVIDIA_KEY, null)
            prefs.edit()
                .remove(LEGACY_NVIDIA_HINT).remove(LEGACY_NVIDIA_REJECTED).remove(LEGACY_MODEL).remove(LEGACY_CORRECT)
                .apply()
        }
    }

    private val _config = MutableStateFlow(read())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun groqKey(): String? = secrets.get(KEY_GROQ)

    /** 확인을 마친 키만 저장한다. null 이면 지운다. */
    fun setGroqKey(key: String?) {
        secrets.put(KEY_GROQ, key?.trim())
        prefs.edit().putString(HINT_GROQ, key?.trim()?.let(::hint)).putBoolean(REJECTED_GROQ, false).apply()
        refresh()
    }

    /** 저장된 Groq 키가 거부됐다(만료·삭제). */
    fun markRejected() {
        prefs.edit().putBoolean(REJECTED_GROQ, true).apply()
        refresh()
    }

    fun setAutoTranscribe(on: Boolean) = edit { putBoolean(AUTO, on) }

    // --- 강의 요약 (Claude) ---

    fun claudeKey(): String? = secrets.get(KEY_CLAUDE)

    /** 확인을 마친 키만 저장한다. null 이면 지운다. */
    fun setClaudeKey(key: String?) {
        secrets.put(KEY_CLAUDE, key?.trim())
        prefs.edit().putString(HINT_CLAUDE, key?.trim()?.let(::hint)).putBoolean(REJECTED_CLAUDE, false).apply()
        refresh()
    }

    fun markClaudeRejected() = edit { putBoolean(REJECTED_CLAUDE, true) }
    fun setAutoSummarize(on: Boolean) = edit { putBoolean(AUTO_SUMMARY, on) }
    fun setSummaryModel(model: SummaryModel) = edit { putString(SUMMARY_MODEL, model.id) }

    /** 대본을 Anthropic 으로 보낸다는 안내를 확인했는지. */
    var summaryConsented: Boolean
        get() = prefs.getBoolean(CONSENT_SUMMARY, false)
        set(value) = prefs.edit().putBoolean(CONSENT_SUMMARY, value).apply()
    fun setSwitchOn(on: Boolean) = edit { putBoolean(SWITCH, on) }

    /** 클라우드로 소리를 보낸다는 안내를 확인했는지. 처음 켤 때 한 번 보여 준다. */
    var consented: Boolean
        get() = prefs.getBoolean(CONSENT, false)
        set(value) = prefs.edit().putBoolean(CONSENT, value).apply()

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
        groqRejected = prefs.getBoolean(REJECTED_GROQ, false),
        autoTranscribe = prefs.getBoolean(AUTO, true),
        switchOn = prefs.getBoolean(SWITCH, true),
        claudeKeyHint = prefs.getString(HINT_CLAUDE, null),
        claudeRejected = prefs.getBoolean(REJECTED_CLAUDE, false),
        autoSummarize = prefs.getBoolean(AUTO_SUMMARY, true),
        summaryModel = SummaryModel.of(prefs.getString(SUMMARY_MODEL, null).orEmpty()),
    )

    companion object {
        private const val KEY_GROQ = "groq"
        private const val HINT_GROQ = "groq_hint"
        private const val REJECTED_GROQ = "groq_rejected"
        private const val AUTO = "auto_transcribe"
        private const val LEGACY_NVIDIA_KEY = "nvidia"
        private const val LEGACY_NVIDIA_HINT = "nvidia_hint"
        private const val LEGACY_NVIDIA_REJECTED = "nvidia_rejected"
        private const val LEGACY_CORRECT = "correct"
        private const val LEGACY_MODEL = "model"
        private const val SWITCH = "switch_on"
        private const val CONSENT = "consent"
        private const val USAGE = "groq_usage"
        private const val KEY_CLAUDE = "claude"
        private const val HINT_CLAUDE = "claude_hint"
        private const val REJECTED_CLAUDE = "claude_rejected"
        private const val AUTO_SUMMARY = "auto_summarize"
        private const val SUMMARY_MODEL = "summary_model"
        private const val CONSENT_SUMMARY = "consent_summary"

        /** 화면에 보일 키 끝 네 자리. */
        fun hint(key: String): String = "••••" + key.takeLast(4)
    }
}
