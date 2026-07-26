package com.trellis.studio.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generations")
data class Generation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [TYPE_IMAGE] for text-to-image results, [TYPE_MODEL] for 3D generations. */
    val type: String,
    val prompt: String?,
    /** Source/preview image on disk — used as the history thumbnail. */
    val imagePath: String?,
    /** Generated .glb file on disk (null for image-only entries). */
    val modelPath: String?,
    val createdAt: Long
) {
    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_MODEL = "model"
    }
}
