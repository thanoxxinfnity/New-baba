package com.trellis.studio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.trellis.studio.data.entity.QueuedJobEntity

@Dao
interface QueueDao {
    @Query("SELECT * FROM queued_jobs ORDER BY createdAt ASC, id ASC")
    suspend fun pending(): List<QueuedJobEntity>

    @Insert
    suspend fun insert(job: QueuedJobEntity): Long

    @Query("UPDATE queued_jobs SET rounds = :rounds WHERE id = :id")
    suspend fun setRounds(id: Long, rounds: Int)

    @Query("DELETE FROM queued_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM queued_jobs")
    suspend fun clear()
}
