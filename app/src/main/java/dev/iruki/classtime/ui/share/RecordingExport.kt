package dev.iruki.classtime.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.SummaryJson
import dev.iruki.classtime.ai.TextFiles
import dev.iruki.classtime.ai.TextKind
import dev.iruki.classtime.ai.TranscriptJson
import dev.iruki.classtime.ai.write
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.SummaryDao
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 녹음 내보내기(다른 앱으로 보내기). 텍스트·요약이 있으면 녹음 파일·텍스트 파일·요약 파일 중 고를 수 있다.
 * 파일이 아직 없으면(지웠거나 예전 결과) 이때 만든다.
 */
@Singleton
class RecordingExport @Inject constructor(
    @ApplicationContext private val app: Context,
    private val transcripts: TranscriptDao,
    private val summaries: SummaryDao,
    private val files: TextFiles,
) {

    /** 녹음마다 보낼 수 있는 것. 화면이 무엇을 물을지 정한다. */
    data class Available(val text: Boolean, val summary: Boolean)

    suspend fun available(recordings: List<Recording>): Available = withContext(Dispatchers.IO) {
        Available(
            text = recordings.any { transcripts.get(it.id)?.stateEnum == TranscriptState.DONE },
            summary = recordings.any { summaries.get(it.id)?.stateEnum == SummaryState.DONE },
        )
    }

    suspend fun send(recordings: List<Recording>, audio: Boolean, text: Boolean, summary: Boolean = false) {
        val uris = withContext(Dispatchers.IO) {
            buildList {
                for (r in recordings) {
                    if (audio) add(Uri.parse(r.uri) to "audio/*")
                    if (text) textUri(r)?.let { add(it to "text/plain") }
                    if (summary) summaryUri(r)?.let { add(it to "text/plain") }
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

    private suspend fun summaryUri(recording: Recording): Uri? {
        val s = summaries.get(recording.id)?.takeIf { it.stateEnum == SummaryState.DONE } ?: return null
        val paragraphs = transcripts.get(recording.id)?.let { TranscriptJson.paragraphs(it.paragraphs) }.orEmpty()
        val uri = runCatching {
            files.find(recording, TextKind.SUMMARY) ?: run {
                val parsed = SummaryJson.parse(s.content, paragraphs.size)
                files.write(recording, TextKind.SUMMARY, SummaryJson.format(recording, parsed) { paragraphs.getOrNull(it)?.startMs })
            }
        }.getOrNull()
        return uri?.takeIf { it.scheme == "content" }
    }
}
