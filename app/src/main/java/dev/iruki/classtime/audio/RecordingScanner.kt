package dev.iruki.classtime.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.iruki.classtime.util.AppLog
import java.io.File
import java.time.ZoneId

/**
 * 저장 폴더(Music/ClassTime)에 실제로 있는 오디오 파일을 읽는다.
 *
 * 녹음 목록(DB)은 파일의 ‘색인’일 뿐이다. 앱을 다시 설치했거나, PC 에서 파일을 넣었거나,
 * 오류로 DB 가 날아가면 파일은 있는데 목록에는 없는 상태가 된다. 이 클래스가 폴더를 직접 읽어
 * 그 차이를 메울 재료를 만든다. 무엇을 넣고 뺄지는 [RescanPlan] 이 정한다.
 *
 * 다른 앱이 만든 파일(재설치 전의 이 앱 포함)은 오디오 읽기 권한이 있어야 보인다.
 */
class RecordingScanner(private val context: Context) {

    /** 폴더에서 찾은 파일 하나. */
    data class Found(
        val uri: String,
        val fileName: String,
        /** 사용자에게 보여줄 위치. 끝의 / 는 뗀다. 예: Music/ClassTime/자료구조 */
        val relativePath: String,
        val subject: String,
        val startedAt: Long,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    fun scan(): List<Found> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scanMediaStore() else scanFiles()
    }.onFailure { AppLog.e(TAG, "폴더 검사 실패", it) }.getOrDefault(emptyList())

    private fun scanMediaStore(): List<Found> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val root = "${Environment.DIRECTORY_MUSIC}/${RecordingStorage.ROOT}/"
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val found = mutableListOf<Found>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("$root%"),
            null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(id))
                found += describe(
                    uri = uri,
                    fileName = c.getString(name) ?: continue,
                    relativePath = (c.getString(path) ?: root).trimEnd('/'),
                    durationMs = c.getLong(duration),
                    sizeBytes = c.getLong(size),
                    modifiedAt = c.getLong(modified) * 1000,
                ) ?: continue
            }
        }
        return found
    }

    @Suppress("DEPRECATION")
    private fun scanFiles(): List<Found> {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val root = File(music, RecordingStorage.ROOT)
        if (!root.isDirectory) return emptyList()
        val storage = RecordingStorage(context)
        return root.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val uri = Uri.fromFile(f)
            describe(
                uri = uri,
                fileName = f.name,
                relativePath = "${Environment.DIRECTORY_MUSIC}/" + f.parentFile!!.relativeTo(music).path,
                durationMs = storage.probeDurationMs(uri),
                sizeBytes = f.length(),
                modifiedAt = f.lastModified(),
            )
        }.toList()
    }

    private fun describe(
        uri: Uri,
        fileName: String,
        relativePath: String,
        durationMs: Long,
        sizeBytes: Long,
        modifiedAt: Long,
    ): Found? {
        if (fileName.substringAfterLast('.', "").lowercase() !in RecordingStorage.AUDIO_EXTENSIONS) return null
        val parsed = RecordingStorage.parseDisplayName(fileName)
        // 과목: Music/ClassTime/<과목>/ 폴더 이름이 가장 믿을 만하다. 루트에 바로 있으면 파일명에서.
        val folder = relativePath.substringAfter("${RecordingStorage.ROOT}/", "").substringBefore('/')
        val subject = folder.ifBlank { parsed?.first ?: RecordingStorage.sanitizeSubject("") }
        // 시작 시각: 파일명에 있으면 그것, 없으면 마지막 수정 시각(= 녹음이 끝난 때)에서 길이를 뺀다.
        val startedAt = parsed?.second?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: (modifiedAt - durationMs).coerceAtLeast(0)
        return Found(uri.toString(), fileName, relativePath, subject, startedAt, durationMs, sizeBytes)
    }

    private companion object {
        const val TAG = "RecordingScanner"
    }
}
