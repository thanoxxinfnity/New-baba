package com.trellis.studio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Generation::class, ChatSession::class, ChatMessageEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun generationDao(): GenerationDao
    abstract fun chatDao(): ChatDao
}
