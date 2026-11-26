package dev.iruki.classtime.sync

import dev.iruki.classtime.ai.SummaryJson
import dev.iruki.classtime.ai.TextKind
import dev.iruki.classtime.ai.TranscriptJson
import dev.iruki.classtime.ai.TranscriptText
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/*
 * Google Drive 동기화. 녹음 파일은 올리지 않고, 받아 적은 대본과 요약 글만 올린다.
 *
 *   내 드라이브/ClassTime/<과목>/<녹음 이름>.txt
 *   내 드라이브/ClassTime/<과목>/<녹음 이름>_요약.txt
 *
 * 앱에서 바뀌면 덮어쓰고, 앱에서 지우면 드라이브에서도 휴지통으로 보낸다(되살릴 수 있게).
 * 권한은 drive.file — 이 앱이 만든 파일만 보고 고칠 수 있다.
 */

/** 드라이브에 있어야 할 글 하나. */
data class DriveDoc(val key: String, val folder: String, val name: String, val text: String) {
    val hash: String get() = sha256(text)

    companion object {
        fun key(recordingId: Long, kind: TextKind) = "$recordingId:${kind.name}"

        /** 앱의 텍스트·요약에서 드라이브에 있어야 할 글들. 파일로 남기는 글과 같은 모양. */
        fun from(recordings: List<Recording>, transcripts: Map<Long, Transcript>, summaries: Map<Long, Summary>): List<DriveDoc> {
            val out = mutableListOf<DriveDoc>()
            for (r in recordings) {
                val t = transcripts[r.id]?.takeIf { it.stateEnum == TranscriptState.DONE } ?: continue
                val paragraphs = TranscriptJson.paragraphs(t.paragraphs)
                if (paragraphs.isEmpty()) continue
                val folder = TranscriptText.relativePath(r).substringAfterLast('/')
                out += DriveDoc(key(r.id, TextKind.TRANSCRIPT), folder, TranscriptText.fileName(r.fileName), TranscriptText.format(r, paragraphs))
                val s = summaries[r.id]?.takeIf { it.stateEnum == SummaryState.DONE } ?: continue
                val content = runCatching { SummaryJson.parse(s.content, paragraphs.size) }.getOrNull() ?: continue
                out += DriveDoc(
                    key(r.id, TextKind.SUMMARY), folder, TranscriptText.fileName(r.fileName, TextKind.SUMMARY),
                    SummaryJson.format(r, content) { paragraphs.getOrNull(it)?.startMs },
                )
            }
            return out
        }
    }
}

/** 이미 올린 글 하나: 드라이브 파일 id 와 올릴 때의 이름·폴더·내용 지문. */
data class DriveEntry(val id: String, val folder: String, val name: String, val hash: String)

/** 무엇을 만들고, 고치고, 휴지통으로 보낼지. 순수 계산. */
data class DrivePlan(val create: List<DriveDoc>, val update: List<Pair<DriveDoc, DriveEntry>>, val trash: List<Pair<String, DriveEntry>>) {
    val isEmpty get() = create.isEmpty() && update.isEmpty() && trash.isEmpty()

    companion object {
        fun of(desired: List<DriveDoc>, uploaded: Map<String, DriveEntry>): DrivePlan {
            val byKey = desired.associateBy { it.key }
            val create = desired.filter { it.key !in uploaded }
            val update = desired.mapNotNull { d ->
                uploaded[d.key]?.takeIf { it.hash != d.hash || it.name != d.name || it.folder != d.folder }?.let { d to it }
            }
            val trash = uploaded.filterKeys { it !in byKey }.map { it.key to it.value }
            return DrivePlan(create, update, trash)
        }
    }
}

/** 드라이브 REST. 실제: [DriveRest]. */
interface DriveApi {
    /** 파일이 있고 휴지통에 있지 않은지. */
    fun alive(token: String, id: String): Boolean
    fun createFolder(token: String, name: String, parent: String?): String
    fun create(token: String, name: String, parent: String, text: String): String
    /** 내용·이름(·폴더)을 고친다. 파일이 없어졌으면 false. */
    fun update(token: String, id: String, name: String, text: String, addParent: String?, removeParent: String?): Boolean
    fun trash(token: String, id: String)
}

class DriveException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind {
        /** 토큰이 만료·취소됨. 새 토큰으로 다시. */
        AUTH,
        /** 드라이브 저장 공간이 가득 참. */
        QUOTA,
        /** 연결 안 됨·서버 오류. 잠시 뒤 다시. */
        RETRY,
        OTHER,
    }
}

/**
 * 올린 파일 기록(id·지문)과 폴더 id. 앱 데이터 폴더의 작은 JSON 파일.
 * 지워져도 다음 동기화가 모두 새로 올릴 뿐이다.
 */
class DriveState(private val file: File) {
    var rootId: String? = null
    val folders = mutableMapOf<String, String>()
    val files = mutableMapOf<String, DriveEntry>()

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val o = JSONObject(file.readText())
            rootId = o.optString("root").ifBlank { null }
            o.optJSONObject("folders")?.let { f -> f.keys().forEach { folders[it] = f.getString(it) } }
            o.optJSONObject("files")?.let { f ->
                f.keys().forEach { k ->
                    val e = f.getJSONObject(k)
                    files[k] = DriveEntry(e.getString("id"), e.getString("folder"), e.getString("name"), e.getString("hash"))
                }
            }
        }
    }

    fun reset() {
        rootId = null
        folders.clear()
        files.clear()
        save()
    }

    fun save() {
        val o = JSONObject()
            .put("root", rootId.orEmpty())
            .put("folders", JSONObject(folders as Map<*, *>))
            .put(
                "files",
                JSONObject().apply {
                    files.forEach { (k, e) -> put(k, JSONObject().put("id", e.id).put("folder", e.folder).put("name", e.name).put("hash", e.hash)) }
                },
            )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}

/** 한 번의 동기화. 하나 할 때마다 기록을 저장하므로 중간에 끊겨도 다음에 이어 간다. */
class DriveSyncEngine(private val api: DriveApi, private val state: DriveState) {

    data class Result(val created: Int, val updated: Int, val trashed: Int)

    fun run(token: String, desired: List<DriveDoc>): Result {
        // 사용자가 드라이브에서 ClassTime 폴더를 지웠으면 처음부터 다시 올린다.
        state.rootId?.let { if (!api.alive(token, it)) state.reset() }
        val plan = DrivePlan.of(desired, state.files)
        var created = 0
        var updated = 0
        var trashed = 0
        for ((key, e) in plan.trash) {
            runCatching { api.trash(token, e.id) }.onFailure { if (it is DriveException && it.kind != DriveException.Kind.OTHER) throw it }
            state.files.remove(key)
            state.save()
            trashed++
        }
        for ((d, e) in plan.update) {
            val folder = folder(token, d.folder)
            val moved = e.folder != d.folder
            val ok = api.update(token, e.id, d.name, d.text, if (moved) folder else null, if (moved) state.folders[e.folder]?.takeIf { it != folder } else null)
            if (ok) {
                state.files[d.key] = DriveEntry(e.id, d.folder, d.name, d.hash)
                state.save()
                updated++
            } else {
                // 드라이브에서 지워진 파일: 새로 올린다.
                state.files.remove(d.key)
                create(token, d)
                created++
            }
        }
        for (d in plan.create) {
            create(token, d)
            created++
        }
        return Result(created, updated, trashed)
    }

    private fun create(token: String, d: DriveDoc) {
        val id = api.create(token, d.name, folder(token, d.folder), d.text)
        state.files[d.key] = DriveEntry(id, d.folder, d.name, d.hash)
        state.save()
    }

    private fun folder(token: String, name: String): String {
        val root = state.rootId ?: api.createFolder(token, ROOT, null).also { state.rootId = it; state.save() }
        return state.folders[name] ?: api.createFolder(token, name, root).also { state.folders[name] = it; state.save() }
    }

    companion object {
        const val ROOT = "ClassTime"
    }
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
