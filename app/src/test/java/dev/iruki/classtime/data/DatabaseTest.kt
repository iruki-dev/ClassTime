package dev.iruki.classtime.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 실제 Room 데이터베이스를 Robolectric(JVM) 위에서 구동해 DAO 동작을 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var courseDao: CourseDao
    private lateinit var recordingDao: RecordingDao
    private lateinit var termDao: TermDao
    private lateinit var exceptionDao: ScheduleExceptionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        courseDao = db.courseDao()
        recordingDao = db.recordingDao()
        termDao = db.termDao()
        exceptionDao = db.scheduleExceptionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun courses_areOrderedByDayThenStartTime() = runTest {
        courseDao.upsert(Course(subject = "수요오후", dayOfWeek = 3, startMinute = 780, endMinute = 855))
        courseDao.upsert(Course(subject = "월요오전", dayOfWeek = 1, startMinute = 540, endMinute = 615))
        courseDao.upsert(Course(subject = "월요오후", dayOfWeek = 1, startMinute = 630, endMinute = 705))

        val ordered = courseDao.observeAll().first().map { it.subject }
        assertThat(ordered).containsExactly("월요오전", "월요오후", "수요오후").inOrder()
    }

    @Test
    fun upsert_withSameId_replacesRow() = runTest {
        val id = courseDao.upsert(Course(subject = "초안", dayOfWeek = 2, startMinute = 600, endMinute = 675))
        courseDao.upsert(
            Course(id = id, subject = "수정본", dayOfWeek = 2, startMinute = 600, endMinute = 675)
        )
        assertThat(courseDao.getAll()).hasSize(1)
        assertThat(courseDao.getById(id)?.subject).isEqualTo("수정본")
    }

    @Test
    fun deleteCourse_removesIt() = runTest {
        val id = courseDao.upsert(Course(subject = "삭제대상", dayOfWeek = 4, startMinute = 540, endMinute = 600))
        courseDao.delete(courseDao.getById(id)!!)
        assertThat(courseDao.getAll()).isEmpty()
    }

    @Test
    fun ongoingRecording_returnsOnlyTheInProgressOne() = runTest {
        recordingDao.insert(sampleRecording("자료구조", ongoing = false))
        val ongoingId = recordingDao.insert(sampleRecording("알고리즘", ongoing = true))

        val ongoing = recordingDao.getOngoing()
        assertThat(ongoing?.id).isEqualTo(ongoingId)
        assertThat(ongoing?.subject).isEqualTo("알고리즘")
    }

    @Test
    fun clearStaleOngoing_flipsAbandonedRowsToFinished() = runTest {
        recordingDao.insert(sampleRecording("자료구조", ongoing = true))
        recordingDao.insert(sampleRecording("알고리즘", ongoing = true))

        val cleared = recordingDao.clearStaleOngoing()

        assertThat(cleared).isEqualTo(2)
        assertThat(recordingDao.getOngoing()).isNull()
    }

    @Test
    fun healOngoingRecordings_finalizesFileAndFillsSize() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File.createTempFile("rec", ".m4a", ctx.cacheDir).apply {
            writeBytes(ByteArray(4096) { 1 })
        }
        val storage = dev.iruki.classtime.audio.RecordingStorage(ctx)

        recordingDao.insert(
            sampleRecording("자료구조", ongoing = true).copy(
                uri = android.net.Uri.fromFile(file).toString(),
                sizeBytes = 0,
                durationMs = 0,
            )
        )
        // 진행 중인 녹음이 하나도 없어야만 healing 이 돈다
        healOngoingRecordings(recordingDao, storage, isRecordingActive = { false })

        val row = recordingDao.observeAll().first().single()
        assertThat(row.ongoing).isFalse()
        assertThat(row.sizeBytes).isEqualTo(4096)
        file.delete()
    }

    @Test
    fun healOngoingRecordings_skipsWhileRecordingActive() = runTest {
        recordingDao.insert(sampleRecording("자료구조", ongoing = true))
        val storage = dev.iruki.classtime.audio.RecordingStorage(
            ApplicationProvider.getApplicationContext()
        )
        healOngoingRecordings(recordingDao, storage, isRecordingActive = { true })
        assertThat(recordingDao.getOngoing()).isNotNull()
    }

    @Test
    fun forceFinalize_isIdempotent() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File.createTempFile("rec", ".m4a", ctx.cacheDir).apply {
            writeBytes(ByteArray(2048))
        }
        val storage = dev.iruki.classtime.audio.RecordingStorage(ctx)
        val id = recordingDao.insert(
            sampleRecording("알고리즘", ongoing = true).copy(
                uri = android.net.Uri.fromFile(file).toString(), sizeBytes = 0, durationMs = 5000,
            )
        )
        val row = recordingDao.getById(id)!!
        val a = finalizeRecordingRow(recordingDao, storage, row)
        val b = finalizeRecordingRow(recordingDao, storage, a)
        assertThat(a).isEqualTo(b)
        assertThat(b.ongoing).isFalse()
        assertThat(b.durationMs).isEqualTo(5000) // 이미 있던 길이는 유지
        file.delete()
    }

    @Test
    fun recordings_listedNewestFirst() = runTest {
        recordingDao.insert(sampleRecording("옛날", startedAt = 1_000L, ongoing = false))
        recordingDao.insert(sampleRecording("최근", startedAt = 9_000L, ongoing = false))

        val subjects = recordingDao.observeAll().first().map { it.subject }
        assertThat(subjects).containsExactly("최근", "옛날").inOrder()
    }

    @Test
    fun coursesInGroup_andGroupDelete() = runTest {
        courseDao.upsert(Course(groupId = "g-ds", subject = "자료구조", dayOfWeek = 1, startMinute = 540, endMinute = 615))
        courseDao.upsert(Course(groupId = "g-ds", subject = "자료구조", dayOfWeek = 3, startMinute = 540, endMinute = 615))
        courseDao.upsert(Course(groupId = "g-al", subject = "알고리즘", dayOfWeek = 2, startMinute = 600, endMinute = 675))

        assertThat(courseDao.getByGroup("g-ds").map { it.dayOfWeek }).containsExactly(1, 3)

        courseDao.deleteByGroup("g-ds")
        assertThat(courseDao.getAll().map { it.subject }).containsExactly("알고리즘")
    }

    @Test
    fun group_supportsMultipleSlotsPerDayAndDifferentTimesPerDay() = runTest {
        // 회로실습: 수요일에 두 번(오전/오후), 금요일엔 다른 시간
        courseDao.upsert(Course(groupId = "g-lab", subject = "회로실습", dayOfWeek = 3, startMinute = 600, endMinute = 660))
        courseDao.upsert(Course(groupId = "g-lab", subject = "회로실습", dayOfWeek = 3, startMinute = 900, endMinute = 1020))
        courseDao.upsert(Course(groupId = "g-lab", subject = "회로실습", dayOfWeek = 5, startMinute = 780, endMinute = 840))

        val rows = courseDao.getByGroup("g-lab")
        assertThat(rows).hasSize(3)
        assertThat(rows.filter { it.dayOfWeek == 3 }.map { it.startMinute }).containsExactly(600, 900)
        assertThat(rows.first { it.dayOfWeek == 5 }.startMinute).isEqualTo(780)
    }

    @Test
    fun term_isSingleton() = runTest {
        termDao.upsert(Term.of(java.time.LocalDate.of(2026, 3, 2), java.time.LocalDate.of(2026, 6, 19)))
        termDao.upsert(Term.of(java.time.LocalDate.of(2026, 9, 1), null))

        assertThat(termDao.get()?.startDate).isEqualTo(java.time.LocalDate.of(2026, 9, 1))
        assertThat(termDao.get()?.endEpochDay).isNull()
    }

    @Test
    fun exceptions_roundTripWithEnumConverter() = runTest {
        val day = java.time.LocalDate.of(2026, 5, 5)
        exceptionDao.upsert(ScheduleException.cancel(day, courseGroupId = null, subject = "어린이날"))
        exceptionDao.upsert(
            ScheduleException.makeup(java.time.LocalDate.of(2026, 5, 10), "자료구조", 840, 915, autoRecord = false)
        )

        val all = exceptionDao.getAll()
        assertThat(all).hasSize(2)
        val cancel = all.first { it.type == ExceptionType.CANCEL }
        assertThat(cancel.date).isEqualTo(day)
        assertThat(cancel.courseGroupId).isNull()
        val makeup = all.first { it.type == ExceptionType.MAKEUP }
        assertThat(makeup.autoRecord).isFalse()
        assertThat(makeup.endMinute).isEqualTo(915)
    }

    @Test
    fun deleteBefore_prunesPastExceptions() = runTest {
        exceptionDao.upsert(ScheduleException.cancel(java.time.LocalDate.of(2020, 1, 1), null))
        exceptionDao.upsert(ScheduleException.cancel(java.time.LocalDate.of(2999, 1, 1), null))

        val removed = exceptionDao.deleteBefore(java.time.LocalDate.of(2026, 1, 1).toEpochDay())
        assertThat(removed).isEqualTo(1)
        assertThat(exceptionDao.getAll()).hasSize(1)
    }

    @Test
    fun peakAmplitude_roundTripsAndDrivesSilentFlag() = runTest {
        val silentId = recordingDao.insert(
            sampleRecording("자료구조", ongoing = false).copy(peakAmplitude = 0)
        )
        val okId = recordingDao.insert(
            sampleRecording("알고리즘", ongoing = false).copy(peakAmplitude = 12_000)
        )
        val legacyId = recordingDao.insert(sampleRecording("운영체제", ongoing = false))

        assertThat(recordingDao.getById(silentId)!!.isSilent).isTrue()
        assertThat(recordingDao.getById(okId)!!.peakAmplitude).isEqualTo(12_000)
        assertThat(recordingDao.getById(okId)!!.isSilent).isFalse()
        // 측정값이 없는 구버전 행(-1)을 무음으로 오인하면 안 된다.
        assertThat(recordingDao.getById(legacyId)!!.peakAmplitude)
            .isEqualTo(Recording.UNKNOWN_AMPLITUDE)
        assertThat(recordingDao.getById(legacyId)!!.isSilent).isFalse()
    }

    @Test
    fun ongoingRecording_isNeverReportedAsSilent() = runTest {
        // 아직 녹음 중이면 진폭이 0 이어도 '무음 파일'이라고 단정할 수 없다.
        val id = recordingDao.insert(
            sampleRecording("자료구조", ongoing = true).copy(peakAmplitude = 0)
        )
        assertThat(recordingDao.getById(id)!!.isSilent).isFalse()
    }

    @Test
    fun heal_preservesPeakAmplitude() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File.createTempFile("rec", ".m4a", ctx.cacheDir).apply {
            writeBytes(ByteArray(1024))
        }
        val storage = dev.iruki.classtime.audio.RecordingStorage(ctx)
        recordingDao.insert(
            sampleRecording("자료구조", ongoing = true).copy(
                uri = android.net.Uri.fromFile(file).toString(),
                peakAmplitude = 9_000,
            )
        )
        healOngoingRecordings(recordingDao, storage, isRecordingActive = { false })

        val row = recordingDao.observeAll().first().single()
        assertThat(row.ongoing).isFalse()
        assertThat(row.peakAmplitude).isEqualTo(9_000)
        file.delete()
    }

    private fun sampleRecording(
        subject: String,
        startedAt: Long = 5_000L,
        ongoing: Boolean,
    ) = Recording(
        subject = subject,
        fileName = "${subject}_2026-03-04_0930.m4a",
        uri = "content://media/external/audio/media/1",
        relativePath = "Music/ClassTime/$subject",
        startedAt = startedAt,
        ongoing = ongoing,
    )
}
