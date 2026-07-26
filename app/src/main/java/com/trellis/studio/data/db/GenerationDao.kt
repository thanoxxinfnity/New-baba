package com.trellis.studio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationDao {

    @Insert
    suspend fun insert(generation: Generation): Long

    @Query("SELECT * FROM generations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Generation>>

    @Query("SELECT * FROM generations WHERE id = :id")
    suspend fun getById(id: Long): Generation?

    @Delete
    suspend fun delete(generation: Generation)
}
