package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, startMinute")
    fun observeAll(): Flow<List<Course>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, startMinute")
    suspend fun getAll(): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): Course?

    @Query("SELECT * FROM courses WHERE groupId = :groupId ORDER BY dayOfWeek, startMinute")
    suspend fun getByGroup(groupId: String): List<Course>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: Course): Long

    @Update
    suspend fun update(course: Course)

    @Delete
    suspend fun delete(course: Course)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM courses WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)
}
