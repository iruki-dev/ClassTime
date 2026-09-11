package dev.iruki.classtime.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 녹음 파일을 공용 Music 폴더 아래 과목별 하위 폴더에 저장한다.
 *
 *   Music/ClassTime/<과목명>/<과목명>_2026-03-04_0930.m4a
 *
 * 이 위치는 USB 로 PC 에 연결했을 때 그대로 보이므로 별도 내보내기 없이 파일을 옮길 수 있다.
 * Android 10+ 는 MediaStore(Scoped Storage) 를, 그 이하는 파일 API 를 사용한다.
 */
class RecordingStorage(private val context: Context) {

    data class Target(
        val uri: Uri,
        val fileName: String,
        /** 사용자에게 보여줄 상대 경로. */
        val relativePath: String,
        /** API 28 이하에서만 non-null. */
        val legacyFile: File?,
    )

    fun create(subject: String, fileStamp: String): Target {
        val safeSubject = sanitizeSubject(subject)
        val displayName = displayName(safeSubject, fileStamp)
        val subDir = "$ROOT/$safeSubject"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = "${Environment.DIRECTORY_MUSIC}/$subDir"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection =
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore 항목을 만들 수 없습니다.")
            Target(uri, displayName, relPath, null)
        } else {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val dir = File(musicDir, subDir).apply { mkdirs() }
            val file = File(dir, displayName)
            Target(Uri.fromFile(file), displayName, "${Environment.DIRECTORY_MUSIC}/$subDir", file)
        }
    }

    /** 녹음이 끝난 뒤 호출. 다른 앱(파일 탐색기, PC)에서 보이도록 pending 을 해제하고 크기를 기록한다. */
    fun finalize(uri: Uri, legacyFile: File?): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
            return querySize(uri)
        }
        // 레거시: 미디어 스캐너에 알림.
        // legacyFile 이 없으면(복구 경로에서 호출된 경우) uri 에서 경로를 되짚는다.
        val file = legacyFile ?: uri.path?.takeIf { uri.scheme == "file" }?.let { File(it) }
        if (file != null) {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("audio/mp4"), null
            )
            return file.length()
        }
        return querySize(uri)
    }

    fun querySize(uri: Uri): Long = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).length() } ?: 0L
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize.coerceAtLeast(0) } ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * 완성된 파일의 실제 재생 길이(ms). recorder.stop() 이 실패해 경과시간을 못 구했거나,
     * 앱이 죽어 마무리를 놓친 녹음을 목록에서 복구할 때 쓴다.
     */
    fun probeDurationMs(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun delete(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() } ?: false
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.getOrDefault(false)

    /** 사용자가 목록에서 이름을 바꿀 때. MediaStore 는 실제 파일명도 함께 변경된다. */
    fun rename(uri: Uri, newDisplayName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } else {
            val old = uri.path?.let { File(it) } ?: return false
            old.renameTo(File(old.parentFile, newDisplayName))
        }
    }.getOrDefault(false)

    companion object {
        const val ROOT = "ClassTime"

        /** 파일 시스템에서 문제를 일으키는 문자를 _ 로 바꾸고, 비면 '기타' 로. */
        fun sanitizeSubject(name: String): String {
            val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            return cleaned.ifBlank { "기타" }
        }

        fun displayName(safeSubject: String, fileStamp: String): String =
            "${safeSubject}_$fileStamp.m4a"
    }
}
