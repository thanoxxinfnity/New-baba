package com.trellis.studio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.trellis.studio.data.entity.*

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, VoiceEntity::class,
                GenerationEntity::class, TtsHistoryEntity::class, QueuedJobEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun generationDao(): GenerationDao
    abstract fun ttsDao(): TtsDao
    abstract fun voiceDao(): VoiceDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds the pending-job table.
         *
         * Written out rather than left to a destructive fallback: that would drop
         * every table, taking the user's whole gallery with it just to add one.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queued_jobs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `rounds` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "trellis-studio.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
