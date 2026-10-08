package dev.iruki.classtime.ai

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.util.SystemScreens
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptFilesTest {

    private val rec = Recording(
        id = 1, subject = "자료구조", professor = "", fileName = "자료구조_2026-03-04_0930.m4a",
        uri = "content://x/1", relativePath = "Music/ClassTime/자료구조", startedAt = 0, durationMs = 60_000, sizeBytes = 1,
    )

    @Test
    fun textFile_sitsBesideTheRecording_underDocuments() {
        assertThat(TranscriptText.fileName(rec.fileName)).isEqualTo("자료구조_2026-03-04_0930.txt")
        assertThat(TranscriptText.relativePath(rec)).isEqualTo("Documents/ClassTime/자료구조")
        // 다른 곳에서 들여온 녹음은 과목 폴더로.
        val imported = rec.copy(subject = "운영체제/실습", relativePath = "Recordings/Voice")
        assertThat(TranscriptText.relativePath(imported)).isEqualTo("Documents/ClassTime/운영체제_실습")
    }

    @Test
    fun textFile_listsParagraphsWithTimes() {
        val text = TranscriptText.format(rec, listOf(Paragraph(0, "오늘은 힙."), Paragraph(3_725_000, "끝.")))
        assertThat(text).isEqualTo("자료구조_2026-03-04_0930\n\n[00:00] 오늘은 힙.\n\n[1:02:05] 끝.\n")
    }

    @Test
    fun folderIntents_neverGrantUriPermissionsTheyDoNotOwn() {
        // 남의 uri 에 권한 부여 플래그를 붙이면 startActivity 가 SecurityException 으로 실패한다(폴더가 안 열리던 원인).
        val intents = SystemScreens.folderIntents()
        assertThat(intents).isNotEmpty()
        intents.forEach { assertThat(it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0) }
        assertThat(intents.first().getStringExtra("samsung.myfiles.intent.extra.START_PATH")).endsWith("Music/ClassTime")
    }

    private class FakeFiles : TextFiles {
        val files = mutableMapOf<Long, String>()
        override fun write(recording: Recording, paragraphs: List<Paragraph>) =
            null.also { files[recording.id] = TranscriptText.format(recording, paragraphs) }
        override fun find(recording: Recording) = if (recording.id in files) android.net.Uri.parse("content://t/${recording.id}") else null
        override fun delete(recording: Recording) { files.remove(recording.id) }
        override fun rename(recording: Recording, newFileName: String) = Unit
    }

    @Test
    fun queue_backfillsOldResults_andDeletesFileWithTheText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val files = FakeFiles()
            val secrets = object : Secrets {
                override fun get(name: String): String? = null
                override fun put(name: String, value: String?) = Unit
            }
            val queue = TranscriptionQueue(context, db.transcriptDao(), AiSettings(context, secrets), db.recordingDao(), files)
            db.recordingDao().insert(rec)
            db.transcriptDao().upsert(
                Transcript(
                    recordingId = 1, queuedAt = 0, state = TranscriptState.DONE.name,
                    paragraphs = TranscriptJson.paragraphs(listOf(Paragraph(0, "오늘은 힙."))),
                )
            )
            queue.backfillFiles()
            assertThat(files.files[1L]).contains("[00:00] 오늘은 힙.")

            queue.remove(1)
            assertThat(files.files).isEmpty()
            assertThat(db.transcriptDao().get(1)).isNull()
        } finally {
            db.close()
        }
    }
}
