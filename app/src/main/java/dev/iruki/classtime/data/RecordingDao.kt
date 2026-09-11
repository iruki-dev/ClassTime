package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE ongoing = 1 LIMIT 1")
    suspend fun getOngoing(): Recording?

    @Query("SELECT * FROM recordings WHERE ongoing = 1")
    suspend fun getAllOngoing(): List<Recording>

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 앱이 비정상 종료되어 '녹음 중'으로 남은 행을 정리한다. */
    @Query("UPDATE recordings SET ongoing = 0 WHERE ongoing = 1")
    suspend fun clearStaleOngoing(): Int
}
