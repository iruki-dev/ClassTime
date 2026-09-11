package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleExceptionDao {

    @Query("SELECT * FROM schedule_exceptions ORDER BY epochDay, startMinute")
    fun observeAll(): Flow<List<ScheduleException>>

    @Query("SELECT * FROM schedule_exceptions ORDER BY epochDay, startMinute")
    suspend fun getAll(): List<ScheduleException>

    @Query("SELECT * FROM schedule_exceptions WHERE id = :id")
    suspend fun getById(id: Long): ScheduleException?

    @Query("SELECT * FROM schedule_exceptions WHERE epochDay = :epochDay")
    suspend fun getForDay(epochDay: Long): List<ScheduleException>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exception: ScheduleException): Long

    @Delete
    suspend fun delete(exception: ScheduleException)

    @Query("DELETE FROM schedule_exceptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 지난 예외 정리 (오늘 이전). */
    @Query("DELETE FROM schedule_exceptions WHERE epochDay < :beforeEpochDay")
    suspend fun deleteBefore(beforeEpochDay: Long): Int
}
