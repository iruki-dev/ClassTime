package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    fun observe(recordingId: Long): Flow<Transcript?>

    @Query("SELECT * FROM transcripts")
    fun observeAll(): Flow<List<Transcript>>

    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    suspend fun get(recordingId: Long): Transcript?

    /** 아직 끝나지 않은 작업, 대기열 순서대로. */
    @Query("SELECT * FROM transcripts WHERE state NOT IN ('DONE', 'FAILED') ORDER BY queuedAt")
    suspend fun pending(): List<Transcript>

    @Upsert
    suspend fun upsert(transcript: Transcript)

    @Query("DELETE FROM transcripts WHERE recordingId = :recordingId")
    suspend fun delete(recordingId: Long)

    /** 녹음이 지워졌는데 남은 결과를 치운다. */
    @Query("DELETE FROM transcripts WHERE recordingId NOT IN (SELECT id FROM recordings)")
    suspend fun deleteOrphans(): Int
}
