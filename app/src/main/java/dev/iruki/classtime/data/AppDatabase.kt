package dev.iruki.classtime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Course::class, Recording::class, Term::class, ScheduleException::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun recordingDao(): RecordingDao
    abstract fun termDao(): TermDao
    abstract fun scheduleExceptionDao(): ScheduleExceptionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classtime.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }

        /**
         * v1 -> v2: 과목에 groupId 추가(기존 행은 각자 단독 그룹으로 백필),
         * 학기(term) / 예외(schedule_exceptions) 테이블 추가.
         * 실제 마이그레이션과 마이그레이션 테스트가 같은 SQL 을 공유한다.
         */
        internal val MIGRATION_1_2_SQL: List<String> = listOf(
            "ALTER TABLE `courses` ADD COLUMN `groupId` TEXT NOT NULL DEFAULT ''",
            "UPDATE `courses` SET `groupId` = 'g' || `id`",
            """CREATE TABLE IF NOT EXISTS `term` (
                `id` INTEGER NOT NULL,
                `startEpochDay` INTEGER,
                `endEpochDay` INTEGER,
                PRIMARY KEY(`id`)
            )""",
            """CREATE TABLE IF NOT EXISTS `schedule_exceptions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `epochDay` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `courseGroupId` TEXT,
                `subject` TEXT NOT NULL DEFAULT '',
                `professor` TEXT NOT NULL DEFAULT '',
                `room` TEXT NOT NULL DEFAULT '',
                `startMinute` INTEGER NOT NULL DEFAULT 0,
                `endMinute` INTEGER NOT NULL DEFAULT 0,
                `autoRecord` INTEGER NOT NULL DEFAULT 1,
                `note` TEXT NOT NULL DEFAULT ''
            )""",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /**
         * v2 -> v3: 녹음에 최대 진폭 기록을 추가한다. 마이크가 차단된 채로 녹음된
         * '무음 파일'을 목록에서 바로 알아보기 위한 값이다. 기존 행은 -1(측정 안 됨).
         */
        internal val MIGRATION_2_3_SQL: List<String> = listOf(
            "ALTER TABLE `recordings` ADD COLUMN `peakAmplitude` INTEGER NOT NULL DEFAULT -1",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }
    }
}
