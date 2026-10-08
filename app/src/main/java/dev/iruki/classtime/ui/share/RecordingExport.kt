package dev.iruki.classtime.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.TextFiles
import dev.iruki.classtime.ai.TranscriptJson
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 녹음 내보내기(다른 앱으로 보내기). 텍스트가 있으면 녹음 파일·텍스트 파일 중 고를 수 있다.
 * 텍스트 파일이 아직 없으면(지웠거나 예전 변환) 이때 만든다.
 */
@Singleton
class RecordingExport @Inject constructor(
    @ApplicationContext private val app: Context,
    private val transcripts: TranscriptDao,
    private val files: TextFiles,
) {

    suspend fun send(recordings: List<Recording>, audio: Boolean, text: Boolean) {
        val uris = withContext(Dispatchers.IO) {
            buildList {
                for (r in recordings) {
                    if (audio) add(Uri.parse(r.uri) to "audio/*")
                    if (text) textUri(r)?.let { add(it to "text/plain") }
                }
            }
        }
        if (uris.isEmpty()) return
        val type = uris.map { it.second }.distinct().singleOrNull()?.let { if (it == "audio/*") "audio/mp4" else it } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0].first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { it.first }))
        }
        intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        recordings.singleOrNull()?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it.fileName.substringBeforeLast('.')) }
        app.startActivity(
            Intent.createChooser(intent, app.getString(R.string.recordings_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private suspend fun textUri(recording: Recording): Uri? {
        val t = transcripts.get(recording.id)?.takeIf { it.stateEnum == TranscriptState.DONE } ?: return null
        val uri = runCatching { files.find(recording) ?: files.write(recording, TranscriptJson.paragraphs(t.paragraphs)) }.getOrNull()
        // Android 9 이하의 file:// 은 다른 앱에 넘길 수 없다.
        return uri?.takeIf { it.scheme == "content" }
    }
}
