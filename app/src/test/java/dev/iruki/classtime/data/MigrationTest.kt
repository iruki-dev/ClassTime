package dev.iruki.classtime.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 스키마 마이그레이션 검증.
 *
 * MigrationTestHelper 는 스키마 json 을 assets 로 요구하는데 로컬 단위 테스트에서는 병합되지
 * 않는다. 대신 옛 버전 DB 를 직접 만들고 **Room 이 실제로 열게** 해서, 마이그레이션 SQL 과
 * 컴파일된 스키마가 일치하는지까지 확인한다(어긋나면 Room 이 여기서 예외를 던진다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    // app/schemas/.../<version>.json 의 identityHash
    private val v1Hash = "839050a561be59fbbd39b6707d5f9413"
    private val v2Hash = "45b4f1cbcd1b8b691aa0f7c342e35281"
    private val v3Hash = V3_HASH

    private val v1Tables = listOf(
        "CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`subject` TEXT NOT NULL, `professor` TEXT NOT NULL, `room` TEXT NOT NULL, " +
            "`dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, " +
            "`autoRecord` INTEGER NOT NULL, `colorArgb` INTEGER NOT NULL)",
        RECORDINGS_V2,
    )

    private val v2Tables = listOf(
        "CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`groupId` TEXT NOT NULL, `subject` TEXT NOT NULL, `professor` TEXT NOT NULL, " +
            "`room` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, " +
            "`endMinute` INTEGER NOT NULL, `autoRecord` INTEGER NOT NULL, `colorArgb` INTEGER NOT NULL)",
        RECORDINGS_V2,
        "CREATE TABLE IF NOT EXISTS `term` (`id` INTEGER NOT NULL, `startEpochDay` INTEGER, " +
            "`endEpochDay` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `schedule_exceptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`epochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `courseGroupId` TEXT, " +
            "`subject` TEXT NOT NULL, `professor` TEXT NOT NULL, `room` TEXT NOT NULL, " +
            "`startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, " +
            "`autoRecord` INTEGER NOT NULL, `note` TEXT NOT NULL)",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Room 형식(room_master_table + user_version)을 갖춘 옛 버전 DB 파일을 만든다. */
    private fun createLegacyDatabase(
        version: Int,
        hash: String,
        tables: List<String>,
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        tables.forEach(db::execSQL)
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES(42, '$hash')"
                        )
                        seed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { }
        helper.close()
    }

    private fun openWithMigrations(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

    @Test
    fun migrate1to3_backfillsGroupId_andRoomValidatesSchema() = runBlocking {
        createLegacyDatabase(1, v1Hash, v1Tables) { db ->
            db.execSQL(
                "INSERT INTO courses (id, subject, professor, room, dayOfWeek, startMinute, endMinute, autoRecord, colorArgb) " +
                    "VALUES (7, '자료구조', '김교수', '공학관 401', 1, 540, 615, 1, -16777216)"
            )
        }

        val db = openWithMigrations()
        val courses = db.courseDao().getAll()
        assertThat(courses).hasSize(1)
        assertThat(courses.first().groupId).isEqualTo("g7")

        db.termDao().upsert(Term.of(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 19)))
        assertThat(db.termDao().get()?.startDate).isEqualTo(LocalDate.of(2026, 3, 2))
        db.close()
    }

    @Test
    fun migrate2to3_addsPeakAmplitudeAndKeepsRecordings() = runBlocking {
        createLegacyDatabase(2, v2Hash, v2Tables) { db ->
            db.execSQL(
                "INSERT INTO courses (id, groupId, subject, professor, room, dayOfWeek, startMinute, endMinute, autoRecord, colorArgb) " +
                    "VALUES (3, 'g-abc', '알고리즘', '', '', 3, 600, 675, 1, -16777216)"
            )
            db.execSQL(
                "INSERT INTO recordings (id, courseId, subject, professor, fileName, uri, relativePath, startedAt, durationMs, sizeBytes, auto, ongoing) " +
                    "VALUES (5, 3, '알고리즘', '', 'a.m4a', 'content://x/5', 'Music/ClassTime/알고리즘', 1000, 2000, 300, 1, 0)"
            )
        }

        val db = openWithMigrations()
        val courses = db.courseDao().getAll()
        assertThat(courses.first().groupId).isEqualTo("g-abc") // 기존 groupId 는 그대로

        val rec = db.recordingDao().getById(5)
        assertThat(rec).isNotNull()
        assertThat(rec!!.subject).isEqualTo("알고리즘")
        assertThat(rec.sizeBytes).isEqualTo(300)
        // 기존 행은 '측정 안 됨'이라 무음으로 오인되면 안 된다.
        assertThat(rec.peakAmplitude).isEqualTo(Recording.UNKNOWN_AMPLITUDE)
        assertThat(rec.isSilent).isFalse()
        db.close()
    }

    @Test
    fun migrate3to5_addsIconAndTranscripts() = runBlocking {
        createLegacyDatabase(3, v3Hash, v3Tables) { db ->
            db.execSQL(
                "INSERT INTO courses (id, groupId, subject, professor, room, dayOfWeek, startMinute, endMinute, autoRecord, colorArgb) " +
                    "VALUES (4, 'g-os', '운영체제', '', '', 2, 540, 615, 1, -16777216)"
            )
            db.execSQL(
                "INSERT INTO recordings (id, courseId, subject, professor, fileName, uri, relativePath, startedAt, durationMs, sizeBytes, auto, ongoing, peakAmplitude) " +
                    "VALUES (9, 4, '운영체제', '', 'o.m4a', 'content://x/9', 'Music/ClassTime/운영체제', 1000, 2000, 300, 1, 0, 1200)"
            )
        }

        val db = openWithMigrations()
        // 빈 아이콘 = 과목명으로 짐작. 녹음은 그대로 남는다.
        assertThat(db.courseDao().getAll().single().icon).isEmpty()
        assertThat(db.recordingDao().getById(9)!!.peakAmplitude).isEqualTo(1200)
        // v5: 텍스트 변환 표는 비어서 시작하고, 지워진 녹음의 결과는 치울 수 있다.
        val transcripts = db.transcriptDao()
        assertThat(transcripts.pending()).isEmpty()
        transcripts.upsert(Transcript(recordingId = 9, queuedAt = 1))
        transcripts.upsert(Transcript(recordingId = 77, queuedAt = 2))
        assertThat(transcripts.deleteOrphans()).isEqualTo(1)
        assertThat(transcripts.pending().map { it.recordingId }).containsExactly(9L)
        db.close()
    }

    @Test
    fun migrate1to3_withEmptyDatabase_stillOpens() = runBlocking {
        createLegacyDatabase(1, v1Hash, v1Tables)
        val db = openWithMigrations()
        assertThat(db.courseDao().getAll()).isEmpty()
        assertThat(db.termDao().get()).isNull()
        db.close()
    }

    private val v3Tables = v2Tables.map {
        if (it.contains("`recordings`")) RECORDINGS_V3 else it
    }

    companion object {
        /** app/schemas/.../3.json 의 identityHash */
        private const val V3_HASH = "88fcd3a969b9149ef22f32a4ff4c9f7c"

        private const val RECORDINGS_V3 =
            "CREATE TABLE IF NOT EXISTS `recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`courseId` INTEGER, `subject` TEXT NOT NULL, `professor` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, `uri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `auto` INTEGER NOT NULL, `ongoing` INTEGER NOT NULL, " +
                "`peakAmplitude` INTEGER NOT NULL)"

        private const val RECORDINGS_V2 =
            "CREATE TABLE IF NOT EXISTS `recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`courseId` INTEGER, `subject` TEXT NOT NULL, `professor` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, `uri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `auto` INTEGER NOT NULL, `ongoing` INTEGER NOT NULL)"
    }
}
