package com.trellis.studio.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A 3D job waiting to run, stored so the queue survives the process dying.
 *
 * The queue used to live only in memory: swiping the app away killed the
 * process and every pending job with it, which is why "generate in the
 * background" appeared to do nothing on phones with aggressive task killing.
 */
@Entity(tableName = "queued_jobs")
data class QueuedJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prompt: String,
    val detail: String,
    val rounds: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
