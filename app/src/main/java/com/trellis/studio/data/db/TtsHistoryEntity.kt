package com.trellis.studio.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tts_history")
data class TtsHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "text_input")   val textInput: String,
    @ColumnInfo(name = "model")        val model: String,
    @ColumnInfo(name = "audio_path")   val audioPath: String,
    @ColumnInfo(name = "created_at")   val createdAt: Long
)

@Dao
interface TtsHistoryDao {
    @Query("SELECT * FROM tts_history ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TtsHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TtsHistoryEntity)

    @Query("DELETE FROM tts_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tts_history")
    suspend fun deleteAll()
}
