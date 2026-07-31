package com.trellis.studio.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved voice. Built-in Magpie voices carry a [voiceName]; cloned voices
 * carry a [samplePath] pointing at the recording they were cloned from.
 */
@Entity(tableName = "voices")
data class VoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** User-facing name, e.g. "My voice" or "Sofia". */
    val name: String,
    /** Magpie voice id for built-ins; empty for cloned voices. */
    val voiceName: String = "",
    /** WAV recording used as the zero-shot prompt; null for built-ins. */
    val samplePath: String? = null,
    /** Short generated clip so the voice can be previewed without a round trip. */
    val previewPath: String? = null,
    val isCloned: Boolean = false,
    val language: String = "en-US",
    val createdAt: Long = System.currentTimeMillis(),
)
