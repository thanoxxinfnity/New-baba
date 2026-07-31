package com.trellis.studio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.trellis.studio.data.entity.*

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, VoiceEntity::class,
                GenerationEntity::class, TtsHistoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun generationDao(): GenerationDao
    abstract fun ttsDao(): TtsDao
    abstract fun voiceDao(): VoiceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "trellis-studio.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
