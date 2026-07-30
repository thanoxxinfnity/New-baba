package com.trellis.studio.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generations")
data class GenerationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,         // "image" | "3d"
    val prompt: String? = null,
    val modelId: String? = null,
    val imagePath: String? = null,
    val modelPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
