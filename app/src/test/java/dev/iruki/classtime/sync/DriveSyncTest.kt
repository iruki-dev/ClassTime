package dev.iruki.classtime.sync

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.ai.Paragraph
import dev.iruki.classtime.ai.TextKind
import dev.iruki.classtime.ai.TranscriptJson
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import java.io.File
import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 이 필요해 Robolectric 에서 돈다. 드라이브는 가짜 또는 가짜 서버. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DriveSyncTest {

    private fun rec(id: Long, subject: String = "자료구조", name: String = "r$id.m4a") = Recording(
        id = id, subject = subject, professor = "", fileName = name, uri = "content://x/$id",
        relativePath = "Music/ClassTime/$subject", startedAt = 0, durationMs = 60_000, sizeBytes = 1,
    )

    private fun done(id: Long) = Transcript(
        recordingId = id, queuedAt = 0, state = TranscriptState.DONE.name,
        paragraphs = TranscriptJson.paragraphs(listOf(Paragraph(0, "힙은 완전 이진 트리"))),
    )

    private val summaryJson = """{"title":"힙","overview":"","notices":[],"sections":[{"heading":"힙","refs":[0],"points":[]}],"terms":[]}"""

    /** 메모리 드라이브. */
    private class FakeDrive : DriveApi {
        val files = mutableMapOf<String, Triple<String, String, String>>() // id → (name, parent, text)
        val trashed = mutableSetOf<String>()
        val folders = mutableMapOf<String, Pair<String, String?>>()
        var next = 0
        override fun alive(token: String, id: String) = (id in folders || id in files) && id !in trashed
        override fun createFolder(token: String, name: String, parent: String?) = "f${next++}".also { folders[it] = name to parent }
        override fun create(token: String, name: String, parent: String, text: String) = "d${next++}".also { files[it] = Triple(name, parent, text) }
        override fun update(token: String, id: String, name: String, text: String, addParent: String?, removeParent: String?): Boolean {
            val f = files[id] ?: return false
            files[id] = Triple(name, addParent ?: f.second, text)
            return true
        }
        override fun trash(token: String, id: String) { trashed += id }
    }

    private fun stateFile() = File(Files.createTempDirectory("drive").toFile(), "s.json")

    @Test
    fun onlyTextAndSummary_goUp_inSubjectFolders() {
        val recs = listOf(rec(1), rec(2, subject = "운영체제"), rec(3))
        val docs = DriveDoc.from(
            recs,
            mapOf(1L to done(1), 2L to done(2), 3L to done(3).copy(state = TranscriptState.TRANSCRIBING.name)),
            mapOf(1L to Summary(recordingId = 1, model = "m", queuedAt = 0, state = SummaryState.DONE.name, content = summaryJson)),
        )
        assertThat(docs.map { it.name }).containsExactly("r1.txt", "r1_요약.txt", "r2.txt")
        assertThat(docs.map { it.folder }).containsExactly("자료구조", "자료구조", "운영체제")
        assertThat(docs.first().text).contains("[00:00] 힙은 완전 이진 트리")
    }

    @Test
    fun sync_createsOnce_updatesOnChange_trashesWhatIsGone() {
        val drive = FakeDrive()
        val file = stateFile()
        val engine = DriveSyncEngine(drive, DriveState(file))
        val docs = DriveDoc.from(listOf(rec(1), rec(2)), mapOf(1L to done(1), 2L to done(2)), emptyMap())

        assertThat(engine.run("t", docs)).isEqualTo(DriveSyncEngine.Result(2, 0, 0))
        assertThat(drive.folders.values.map { it.first }).containsExactly("ClassTime", "자료구조")
        // 같은 내용이면 아무것도 하지 않는다(기록은 파일에 남아 다음 실행도 안다).
        assertThat(DriveSyncEngine(drive, DriveState(file)).run("t", docs)).isEqualTo(DriveSyncEngine.Result(0, 0, 0))

        // 이름을 바꾸고 하나를 지우면: 하나 고치고 하나 휴지통.
        val renamed = DriveDoc.from(listOf(rec(1, name = "힙정렬.m4a")), mapOf(1L to done(1)), emptyMap())
        assertThat(DriveSyncEngine(drive, DriveState(file)).run("t", renamed)).isEqualTo(DriveSyncEngine.Result(0, 1, 1))
        assertThat(drive.files.values.map { it.first }).contains("힙정렬.txt")
        assertThat(drive.trashed).hasSize(1)
    }

    @Test
    fun deletedRootFolder_meansUploadEverythingAgain() {
        val drive = FakeDrive()
        val engine = DriveSyncEngine(drive, DriveState(stateFile()))
        val docs = DriveDoc.from(listOf(rec(1)), mapOf(1L to done(1)), emptyMap())
        engine.run("t", docs)
        drive.trashed += drive.folders.entries.first { it.value.first == "ClassTime" }.key
        assertThat(engine.run("t", docs).created).isEqualTo(1)
    }

    @Test
    fun removedOnDrive_isUploadedAgain() {
        val drive = FakeDrive()
        val engine = DriveSyncEngine(drive, DriveState(stateFile()))
        engine.run("t", DriveDoc.from(listOf(rec(1)), mapOf(1L to done(1)), emptyMap()))
        drive.files.clear()
        val changed = DriveDoc.from(listOf(rec(1, name = "새이름.m4a")), mapOf(1L to done(1)), emptyMap())
        assertThat(engine.run("t", changed).created).isEqualTo(1)
        assertThat(drive.files.values.single().first).isEqualTo("새이름.txt")
    }

    @Test
    fun plan_movesFilesWhenSubjectChanges() {
        val doc = DriveDoc(DriveDoc.key(1, TextKind.TRANSCRIPT), "운영체제", "a.txt", "x")
        val plan = DrivePlan.of(listOf(doc), mapOf(doc.key to DriveEntry("d1", "자료구조", "a.txt", doc.hash)))
        assertThat(plan.update.single().second.id).isEqualTo("d1")
        assertThat(plan.create).isEmpty()
    }

    @Test
    fun rest_sendsMultipartAndMapsErrors() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"f1"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"d1"}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}"""))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val rest = DriveRest(api = "$base/drive/v3", upload = "$base/upload/drive/v3")
            assertThat(rest.createFolder("tok", "ClassTime", null)).isEqualTo("f1")
            val folderReq = server.takeRequest()
            assertThat(folderReq.getHeader("Authorization")).isEqualTo("Bearer tok")
            assertThat(folderReq.body.readUtf8()).contains("vnd.google-apps.folder")

            assertThat(rest.create("tok", "r1.txt", "f1", "본문")).isEqualTo("d1")
            val up = server.takeRequest()
            assertThat(up.path).startsWith("/upload/drive/v3/files?uploadType=multipart")
            val body = up.body.readUtf8()
            assertThat(body).contains("\"parents\":[\"f1\"]")
            assertThat(body).contains("본문")

            // 드라이브에서 지워진 파일은 false → 엔진이 새로 올린다.
            assertThat(rest.update("tok", "gone", "r1.txt", "x", null, null)).isFalse()
            assertThat(server.takeRequest().getHeader("X-HTTP-Method-Override")).isEqualTo("PATCH")

            val e = runCatching { rest.create("tok", "r2.txt", "f1", "y") }.exceptionOrNull()
            assertThat((e as DriveException).kind).isEqualTo(DriveException.Kind.QUOTA)
        } finally {
            server.shutdown()
        }
    }
}
