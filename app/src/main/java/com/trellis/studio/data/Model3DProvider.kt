package com.trellis.studio.data

import java.io.File

/** A backend that can turn a 2D image into a 3D (.glb) model. */
interface Model3DRepository {
    suspend fun generateModel(imageFile: File, onStatus: (String) -> Unit = {}): File
}

enum class Model3DProvider(val label: String, val keyHint: String, val keyHelpUrl: String) {
    NVIDIA_TRELLIS(
        label = "NVIDIA TRELLIS",
        keyHint = "nvapi-…",
        keyHelpUrl = "build.nvidia.com (microsoft/trellis)"
    ),
    FAL_TRELLIS(
        label = "fal.ai TRELLIS",
        keyHint = "key_id:key_secret",
        keyHelpUrl = "fal.ai/dashboard/keys"
    )
}
