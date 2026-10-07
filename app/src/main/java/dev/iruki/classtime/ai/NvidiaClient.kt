package dev.iruki.classtime.ai

import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject

/**
 * NVIDIA API 카탈로그(build.nvidia.com)의 OpenAI 호환 채팅.
 *
 * 무료 등급은 분당 약 40회. 요청이 서버 대기열에서 수십 초 기다리기도 해서 **스트리밍**으로
 * 받는다(조용한 채로 300초가 지나면 서버가 끊는다). 추론 모델의 생각 과정은
 * `reasoning_content` 로 따로 오므로 버리고, 본문에 섞여 오는 `<think>` 류 태그도 지운다.
 * 모델은 자주 내려가므로(404/410) 고정하지 않고 [models] 로 살아 있는 것을 확인한다.
 */
class NvidiaClient(private val baseUrl: String = BASE_URL) : ChatModel {

    /** 지금 제공 중인 모델 id. 이 호출은 키를 확인하지 않는다(키 없이도 200). */
    fun models(): List<String> {
        val conn = AiHttp.open("$baseUrl/models", key = null, readTimeoutMs = 20_000)
        try {
            AiHttp.check(conn)
            val data = JSONObject(AiHttp.readBody(conn)).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).map { data.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 키 확인. 모델 목록은 키를 보지 않으므로 1토큰짜리 채팅을 보낸다.
     * 인증을 통과하기만 하면(200, 400, 429 …) 유효한 키다.
     */
    fun verify(key: String, model: String) {
        val conn = AiHttp.open("$baseUrl/chat/completions", key, readTimeoutMs = 120_000)
        try {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            AiHttp.postJson(conn, body.toString())
            try {
                AiHttp.check(conn)
            } catch (e: AiException) {
                if (e.kind == AiException.Kind.AUTH || e.kind == AiException.Kind.NETWORK || e.kind == AiException.Kind.MODEL_GONE) throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 대화 한 번. 최종 답만 돌려준다. */
    override fun chat(key: String, model: String, system: String, user: String, maxTokens: Int): String {
        val conn = AiHttp.open("$baseUrl/chat/completions", key, readTimeoutMs = IDLE_TIMEOUT_MS)
        conn.setRequestProperty("Accept", "text/event-stream")
        try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("temperature", 0.3)
                .put("max_tokens", maxTokens)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
            AiHttp.postJson(conn, body.toString())
            AiHttp.check(conn)
            return stripThinking(readStream(conn))
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(conn: java.net.HttpURLConnection): String {
        val out = StringBuilder()
        var finished = false
        try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { finished = true; break }
                    val choice = runCatching { JSONObject(data).getJSONArray("choices").getJSONObject(0) }
                        .getOrNull() ?: continue
                    choice.optJSONObject("delta")?.let { d ->
                        if (!d.isNull("content")) out.append(d.optString("content"))
                    }
                    val reason = choice.optString("finish_reason")
                    if (reason.isNotBlank() && reason != "null") {
                        finished = true
                        if (reason == "length") throw AiException(AiException.Kind.SERVER, "truncated")
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw AiException(AiException.Kind.SERVER, "stream idle")
        } catch (e: java.io.IOException) {
            if (e is AiException) throw e
            throw AiException(AiException.Kind.NETWORK, e.message ?: "stream")
        }
        // 서버가 마무리 없이 스트림을 닫으면 답이 잘렸을 수 있다.
        if (!finished) throw AiException(AiException.Kind.SERVER, "stream cut")
        return out.toString()
    }

    companion object {
        const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private const val IDLE_TIMEOUT_MS = 300_000

        /**
         * 기본 교정 모델과 대체 순서. 앞의 것이 내려가 있으면 다음 것을 쓴다.
         * 사용자가 설정에서 고른 모델이 있으면 그것이 맨 앞에 온다.
         */
        val PREFERRED = listOf(
            "deepseek-ai/deepseek-v4.1-flash",
            "moonshotai/kimi-k3",
            "z-ai/glm-5.3",
            "nvidia/nemotron-3-ultra-550b-a55b",
        )

        /** 키 확인용으로 가벼운 모델. 없으면 교정 모델로 확인한다. */
        const val PROBE_MODEL = "openai/gpt-oss-20b"

        private val THINK = Regex("""<(think|thinking|reasoning)>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)

        internal fun stripThinking(text: String): String {
            var t = THINK.replace(text, "")
            // 닫는 태그만 남은 경우(여는 태그가 템플릿에 있었음): 그 앞을 모두 버린다.
            Regex("""</(think|thinking|reasoning)>""", RegexOption.IGNORE_CASE).findAll(t).lastOrNull()?.let {
                t = t.substring(it.range.last + 1)
            }
            return t.trim()
        }
    }
}
