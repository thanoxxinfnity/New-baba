package com.trellis.studio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Generation::class, ChatSession::class, ChatMessageEntity::class, TtsHistoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun generationDao(): GenerationDao
    abstract fun chatDao(): ChatDao
    abstract fun ttsHistoryDao(): TtsHistoryDao
}
