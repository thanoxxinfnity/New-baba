package com.trellis.studio.data.db

import androidx.room.*
import com.trellis.studio.data.entity.TtsHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsDao {
    @Query("SELECT * FROM tts_history ORDER BY created_at DESC")
    fun getAll(): Flow<List<TtsHistoryEntity>>

    @Insert
    suspend fun insert(entry: TtsHistoryEntity): Long

    @Query("DELETE FROM tts_history WHERE id = :id")
    suspend fun delete(id: Long)
}
