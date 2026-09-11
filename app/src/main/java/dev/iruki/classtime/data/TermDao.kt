package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    @Query("SELECT * FROM term WHERE id = ${Term.SINGLETON_ID}")
    fun observe(): Flow<Term?>

    @Query("SELECT * FROM term WHERE id = ${Term.SINGLETON_ID}")
    suspend fun get(): Term?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: Term)
}
