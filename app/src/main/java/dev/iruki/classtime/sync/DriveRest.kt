package dev.iruki.classtime.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Drive REST v3 를 HttpURLConnection 으로. 필요한 것은 폴더 만들기, 글 올리기·고치기,
 * 휴지통으로 보내기뿐이라 클라이언트 라이브러리를 들이지 않는다.
 */
class DriveRest(
    private val api: String = "https://www.googleapis.com/drive/v3",
    private val upload: String = "https://www.googleapis.com/upload/drive/v3",
) : DriveApi {

    override fun alive(token: String, id: String): Boolean {
        val c = open("GET", "$api/files/${enc(id)}?fields=trashed", token)
        return try {
            when (c.responseCode) {
                404 -> false
                in 200..299 -> !JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("trashed")
                else -> fail(c)
            }
        } finally {
            c.disconnect()
        }
    }

    override fun createFolder(token: String, name: String, parent: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
        parent?.let { meta.put("parents", JSONArray(listOf(it))) }
        val c = open("POST", "$api/files?fields=id", token)
        return try {
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.doOutput = true
            c.outputStream.use { it.write(meta.toString().toByteArray()) }
            idOf(c)
        } finally {
            c.disconnect()
        }
    }

    override fun create(token: String, name: String, parent: String, text: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", TEXT).put("parents", JSONArray(listOf(parent)))
        val c = open("POST", "$upload/files?uploadType=multipart&fields=id", token)
        return try {
            multipart(c, meta, text)
            idOf(c)
        } finally {
            c.disconnect()
        }
    }

    override fun update(token: String, id: String, name: String, text: String, addParent: String?, removeParent: String?): Boolean {
        val q = buildString {
            append("uploadType=multipart&fields=id")
            addParent?.let { append("&addParents=").append(enc(it)) }
            removeParent?.let { append("&removeParents=").append(enc(it)) }
        }
        // HttpURLConnection 은 PATCH 를 모른다. Google API 는 덮어쓰기 헤더를 받아 준다.
        val c = open("POST", "$upload/files/${enc(id)}?$q", token)
        return try {
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            multipart(c, JSONObject().put("name", name), text)
            if (c.responseCode == 404) false else { idOf(c); true }
        } finally {
            c.disconnect()
        }
    }

    override fun trash(token: String, id: String) {
        val c = open("POST", "$api/files/${enc(id)}?fields=id", token)
        try {
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.doOutput = true
            c.outputStream.use { it.write("""{"trashed":true}""".toByteArray()) }
            // 이미 없으면 할 일이 없다.
            if (c.responseCode != 404) idOf(c)
        } finally {
            c.disconnect()
        }
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection =
        try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: IOException) {
            throw DriveException(DriveException.Kind.RETRY, e.message ?: "io")
        }

    private fun multipart(c: HttpURLConnection, meta: JSONObject, text: String) {
        val boundary = "classtime-" + UUID.randomUUID()
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta.toString()).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(TEXT).append("; charset=UTF-8\r\n\r\n")
            append(text).append("\r\n")
            append("--").append(boundary).append("--")
        }.toByteArray()
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
        } catch (e: IOException) {
            throw DriveException(DriveException.Kind.RETRY, e.message ?: "io")
        }
    }

    private fun idOf(c: HttpURLConnection): String {
        val code = try {
            c.responseCode
        } catch (e: IOException) {
            throw DriveException(DriveException.Kind.RETRY, e.message ?: "io")
        }
        if (code !in 200..299) fail(c)
        return JSONObject(c.inputStream.bufferedReader().readText()).getString("id")
    }

    private fun fail(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val body = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val kind = when {
            code == 401 -> DriveException.Kind.AUTH
            body.contains("storageQuotaExceeded") -> DriveException.Kind.QUOTA
            code == 429 || code >= 500 || body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded") -> DriveException.Kind.RETRY
            else -> DriveException.Kind.OTHER
        }
        throw DriveException(kind, "$code ${body.take(160)}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        const val TEXT = "text/plain"
    }
}
