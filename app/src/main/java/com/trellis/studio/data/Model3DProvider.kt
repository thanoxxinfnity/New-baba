package com.trellis.studio.data

import java.io.File

/** A backend that can turn a 2D image (or, if supported, a text prompt) into a 3D (.glb) model. */
interface Model3DRepository {
    suspend fun generateModel(imageFile: File, onStatus: (String) -> Unit = {}): File

    suspend fun generateFromPrompt(prompt: String, onStatus: (String) -> Unit = {}): File {
        throw AppException.Api(
            "Text-to-3D isn't supported by this provider. Switch to Pollinations TRELLIS in Settings."
        )
    }

    /** Whether [generateFromPrompt] is meaningfully implemented. */
    val supportsTextTo3d: Boolean get() = false
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
    ),
    POLLINATIONS_TRELLIS(
        label = "Pollinations TRELLIS",
        keyHint = "sk_… or pk_…",
        keyHelpUrl = "enter.pollinations.ai/keys"
    )
}
