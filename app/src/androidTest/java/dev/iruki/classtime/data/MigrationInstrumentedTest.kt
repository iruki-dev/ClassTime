package dev.iruki.classtime.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 안드로이드 SQLite 위에서 마이그레이션을 돌린다.
 *
 * JVM(Robolectric) 쪽에도 같은 취지의 테스트가 있지만, 그쪽은 데스크톱 SQLite 라
 * 버전·컴파일 옵션이 기기와 다르다. 마이그레이션이 틀리면 결과가 크래시가 아니라
 * **사용자 데이터 손실**이므로, 이 경로만은 진짜 기기에서 한 번 더 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesFromV1ToLatestKeepingExistingRows() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """INSERT INTO courses
                   (subject, professor, room, dayOfWeek, startMinute, endMinute, autoRecord, colorArgb)
                   VALUES ('자료구조', '김교수', '404', 1, 540, 615, 1, -16777216)"""
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
        )

        db.query("SELECT subject, groupId FROM courses").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("자료구조")
            // v1 -> v2 는 기존 행마다 단독 그룹을 만들어 준다.
            assertThat(c.getString(1)).isNotEmpty()
        }
    }

    @Test
    fun migratesFromV2ToLatestBackfillingPeakAmplitude() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                """INSERT INTO recordings
                   (courseId, subject, professor, fileName, uri, relativePath,
                    startedAt, durationMs, sizeBytes, auto, ongoing)
                   VALUES (NULL, '운영체제', '', 'a.m4a', 'content://x/1', 'Music/ClassTime',
                           1700000000000, 1000, 2048, 1, 0)"""
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT peakAmplitude FROM recordings").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            // 기존 행은 '측정 안 됨'(-1) 이어야 한다. 0 이면 무음으로 오인된다.
            assertThat(c.getInt(0)).isEqualTo(-1)
        }
    }
}
