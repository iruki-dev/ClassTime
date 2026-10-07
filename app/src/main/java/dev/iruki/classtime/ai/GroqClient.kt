package dev.iruki.classtime.ai

import java.io.File
import org.json.JSONObject

/**
 * Groq Whisper(large-v3) 받아 적기.
 * https://console.groq.com/docs/speech-to-text — m4a 그대로 받으며 무료 등급은 파일당 25MB.
 */
class GroqClient(private val baseUrl: String = BASE_URL) : SpeechToText {

    /** 키가 쓸 수 있는지. 모델 목록 조회는 소리 한도를 쓰지 않는다. */
    fun verify(key: String) {
        val conn = AiHttp.open("$baseUrl/models", key, readTimeoutMs = 20_000)
        try {
            AiHttp.check(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * [file] 을 받아 적는다. 시간은 이 파일 기준(0부터).
     * @param prompt 과목명·앞 조각의 끝부분. 224토큰 제한이라 짧게.
     */
    override fun transcribe(key: String, file: File, prompt: String, language: String): List<RawSegment> {
        val conn = AiHttp.open("$baseUrl/audio/transcriptions", key, readTimeoutMs = 180_000)
        try {
            val fields = buildList {
                add("model" to MODEL)
                add("response_format" to "verbose_json")
                add("temperature" to "0")
                add("timestamp_granularities[]" to "segment")
                if (language.isNotBlank()) add("language" to language)
                if (prompt.isNotBlank()) add("prompt" to prompt)
            }
            AiHttp.postMultipart(conn, fields, "file", file, "audio/mp4")
            AiHttp.check(conn)
            return parse(AiHttp.readBody(conn))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1"
        const val MODEL = "whisper-large-v3"

        /** 무료 등급 파일 크기 한도. 조각은 이보다 훨씬 작게 만든다. */
        const val MAX_FILE_BYTES = 25L * 1024 * 1024

        internal fun parse(body: String): List<RawSegment> {
            val segments = JSONObject(body).optJSONArray("segments") ?: return emptyList()
            return (0 until segments.length()).map { i ->
                val s = segments.getJSONObject(i)
                RawSegment(
                    startMs = (s.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (s.optDouble("end", 0.0) * 1000).toLong(),
                    text = s.optString("text"),
                    avgLogprob = s.optDouble("avg_logprob", 0.0),
                    noSpeechProb = s.optDouble("no_speech_prob", 0.0),
                    compressionRatio = s.optDouble("compression_ratio", 1.0),
                )
            }
        }
    }
}
