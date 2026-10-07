package dev.iruki.classtime.ai

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 외부 AI 호출 실패. [kind] 에 따라 큐가 기다릴지, 멈출지, 원문으로 끝낼지 정한다. */
class AiException(val kind: Kind, message: String, val retryAfterMs: Long = 0) : IOException(message) {
    enum class Kind {
        /** 키가 없거나 거부됨(401/403). */
        AUTH,
        /** 한도(429). [retryAfterMs] 만큼 기다린다. */
        RATE_LIMIT,
        /** 모델이 내려감(404/410). 다음 모델로. */
        MODEL_GONE,
        /** 파일이 너무 큼(413). */
        TOO_LARGE,
        /** 5xx, 끊긴 스트림, 시간 초과. */
        SERVER,
        /** 연결 자체가 안 됨. */
        NETWORK,
        /** 그 밖의 4xx. */
        BAD_REQUEST,
    }
}

/** HttpURLConnection 위의 얇은 도우미. 추가 라이브러리 없이 multipart 와 SSE 만 다룬다. */
internal object AiHttp {

    fun open(url: String, key: String?, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            if (key != null) setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }

    /** multipart/form-data 로 [fields] 와 파일 하나를 보낸다. 길이를 미리 알려 메모리에 쌓지 않는다. */
    fun postMultipart(conn: HttpURLConnection, fields: List<Pair<String, String>>, fileField: String, file: File, mime: String) {
        val boundary = "----classtime" + UUID.randomUUID().toString().replace("-", "")
        val head = buildString {
            for ((name, value) in fields) {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append(value).append("\r\n")
            }
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
            append("Content-Type: $mime\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        try {
            conn.outputStream.buffered().use { out ->
                out.write(head)
                file.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "upload")
        }
    }

    fun postJson(conn: HttpURLConnection, body: String) {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "send")
        }
    }

    /** 2xx 가 아니면 상태 코드에 맞는 [AiException] 을 던진다. */
    fun check(conn: HttpURLConnection) {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "network")
        }
        if (code in 200..299) return
        val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        throw errorFor(code, body.take(300), retryAfterMs(conn))
    }

    fun errorFor(code: Int, body: String, retryAfterMs: Long): AiException = when (code) {
        401, 403 -> AiException(AiException.Kind.AUTH, "HTTP $code")
        404, 410 -> AiException(AiException.Kind.MODEL_GONE, "HTTP $code $body")
        413 -> AiException(AiException.Kind.TOO_LARGE, "HTTP $code")
        429 -> AiException(AiException.Kind.RATE_LIMIT, "HTTP 429 $body", retryAfterMs)
        in 500..599 -> AiException(AiException.Kind.SERVER, "HTTP $code")
        else -> AiException(AiException.Kind.BAD_REQUEST, "HTTP $code $body")
    }

    /** `retry-after`(초) 또는 Groq 의 `x-ratelimit-reset-*`(예: "7m12.5s", "2.3s"). 없으면 0. */
    fun retryAfterMs(conn: HttpURLConnection): Long {
        conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return listOfNotNull(
            conn.getHeaderField("x-ratelimit-reset-requests"),
            conn.getHeaderField("x-ratelimit-reset-tokens"),
        ).maxOfOrNull { parseDuration(it) } ?: 0L
    }

    /** "1h2m3.5s", "450ms", "12s" → ms. 모르는 형식은 0. */
    fun parseDuration(text: String): Long {
        var total = 0.0
        Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""").findAll(text.trim()).forEach { m ->
            val v = m.groupValues[1].toDouble()
            total += when (m.groupValues[2]) {
                "h" -> v * 3_600_000
                "m" -> v * 60_000
                "s" -> v * 1_000
                else -> v
            }
        }
        return total.toLong()
    }

    fun readBody(conn: HttpURLConnection): String =
        try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException(AiException.Kind.SERVER, "timeout")
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "network")
        }
}
