package dev.iruki.classtime.ai

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.Recording
import java.io.File

/** 변환한 텍스트를 파일로 남긴다. 실제: [TranscriptFiles]. */
interface TextFiles {
    /** 쓰거나 덮어쓴다. 쓴 파일의 uri(공유용), 못 쓰면 null. */
    fun write(recording: Recording, paragraphs: List<Paragraph>): Uri?
    fun find(recording: Recording): Uri?
    fun delete(recording: Recording)
    /** 녹음 이름을 바꿀 때 텍스트도 같은 이름으로. */
    fun rename(recording: Recording, newFileName: String)
}

/** 텍스트 파일의 이름·위치·내용. 순수 계산. */
object TranscriptText {

    /** 녹음과 같은 이름, 확장자만 .txt. */
    fun fileName(recordingFileName: String): String = recordingFileName.substringBeforeLast('.', recordingFileName) + ".txt"

    /**
     * Android 는 Music 폴더에 오디오가 아닌 파일을 두지 못하게 하므로, 같은 구조로 Documents 아래에 둔다.
     *   Music/ClassTime/자료구조  →  Documents/ClassTime/자료구조
     */
    fun relativePath(recording: Recording): String {
        val music = Environment.DIRECTORY_MUSIC + "/" + RecordingStorage.ROOT + "/"
        val path = recording.relativePath.trim('/') + "/"
        val sub = if (path.startsWith(music)) path.removePrefix(music).trim('/')
        else RecordingStorage.sanitizeSubject(recording.subject)
        return listOf(Environment.DIRECTORY_DOCUMENTS, RecordingStorage.ROOT, sub).filter { it.isNotBlank() }.joinToString("/")
    }

    /** 제목 한 줄 + 문단마다 [시각]. 복사·보내기와 같은 모양. */
    fun format(recording: Recording, paragraphs: List<Paragraph>): String = buildString {
        append(recording.fileName.substringBeforeLast('.', recording.fileName)).append("\n\n")
        paragraphs.forEach { append('[').append(Timestamps.format(it.startMs)).append("] ").append(it.text).append("\n\n") }
    }.trimEnd() + "\n"
}

/**
 * Documents/ClassTime/<과목>/<녹음 이름>.txt 로 저장한다. USB 로 PC 에 연결하면 녹음과 같은 구조로 보인다.
 * Android 10+ 는 MediaStore, 그 이하는 파일 API.
 */
class TranscriptFiles(private val context: Context) : TextFiles {

    private val resolver get() = context.contentResolver
    /** Android 10+ 경로에서만 쓴다. */
    private val collection: Uri
        @RequiresApi(Build.VERSION_CODES.Q) get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun write(recording: Recording, paragraphs: List<Paragraph>): Uri? {
        val bytes = TranscriptText.format(recording, paragraphs).toByteArray()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = legacyFile(recording, TranscriptText.fileName(recording.fileName))
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
            return Uri.fromFile(file)
        }
        // 이미 있으면 덮어쓴다. 재설치 전에 만든 파일처럼 바꿀 권한이 없으면 새로 만든다.
        find(recording)?.let { uri ->
            val ok = runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            if (ok) return uri
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, TranscriptText.fileName(recording.fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, TranscriptText.relativePath(recording) + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    override fun find(recording: Recording): Uri? = find(recording, TranscriptText.fileName(recording.fileName))

    private fun find(recording: Recording, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyFile(recording, name).takeIf { it.exists() }?.let { Uri.fromFile(it) }
        }
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(TranscriptText.relativePath(recording) + "/", name),
                null,
            )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }.getOrNull()
    }

    override fun delete(recording: Recording) {
        val uri = find(recording) ?: return
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() } else resolver.delete(uri, null, null)
        }
    }

    override fun rename(recording: Recording, newFileName: String) {
        val uri = find(recording) ?: return
        val name = TranscriptText.fileName(newFileName)
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.let { it.renameTo(File(it.parentFile, name)) }
            } else {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(recording: Recording, name: String): File {
        val sub = TranscriptText.relativePath(recording).removePrefix(Environment.DIRECTORY_DOCUMENTS + "/")
        return File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), sub), name)
    }
}
