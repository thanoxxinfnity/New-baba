package com.trellis.studio.data.db

import androidx.room.*
import com.trellis.studio.data.entity.GenerationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationDao {
    @Query("SELECT * FROM generations ORDER BY createdAt DESC")
    fun getAll(): Flow<List<GenerationEntity>>

    @Query("SELECT * FROM generations WHERE type = :type ORDER BY createdAt DESC")
    fun getByType(type: String): Flow<List<GenerationEntity>>

    @Insert
    suspend fun insert(gen: GenerationEntity): Long

    @Query("DELETE FROM generations WHERE id = :id")
    suspend fun delete(id: Long)
}
