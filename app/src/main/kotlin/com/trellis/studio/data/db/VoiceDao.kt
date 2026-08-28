package com.trellis.studio.data.db

import androidx.room.*
import com.trellis.studio.data.entity.VoiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT * FROM voices ORDER BY isCloned DESC, createdAt DESC")
    fun getAll(): Flow<List<VoiceEntity>>

    @Query("SELECT * FROM voices WHERE id = :id")
    suspend fun byId(id: Long): VoiceEntity?

    @Insert
    suspend fun insert(voice: VoiceEntity): Long

    @Update
    suspend fun update(voice: VoiceEntity)

    @Query("DELETE FROM voices WHERE id = :id")
    suspend fun delete(id: Long)
}
