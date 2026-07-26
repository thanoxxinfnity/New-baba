package com.trellis.studio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Generation::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun generationDao(): GenerationDao
}
