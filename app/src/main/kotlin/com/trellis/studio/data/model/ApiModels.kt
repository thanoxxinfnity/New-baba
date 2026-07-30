package com.trellis.studio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Chat Completions (NVIDIA NIM) ----
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,  // "system" | "user" | "assistant"
    val content: String,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null,
)

// ---- Image Generation (FLUX style) ----
@Serializable
data class FluxImageRequest(
    val prompt: String,
    val width: Int = 1024,
    val height: Int = 1024,
    @SerialName("num_inference_steps") val steps: Int = 20,
    val guidance: Double = 3.5,
    val seed: Long = 0L,
)

// ---- Image Generation (SDXL style) ----
@Serializable
data class SdxlImageRequest(
    @SerialName("text_prompts") val textPrompts: List<SdxlPrompt>,
    @SerialName("cfg_scale") val cfgScale: Double = 5.0,
    val sampler: String = "K_DPM_2_ANCESTRAL",
    val seed: Long = 0L,
    val steps: Int = 25,
    val width: Int = 1024,
    val height: Int = 1024,
)

@Serializable
data class SdxlPrompt(val text: String, val weight: Double = 1.0)

// Shared image response
@Serializable
data class NimImageResponse(
    val artifacts: List<NimArtifact> = emptyList(),
)

@Serializable
data class NimArtifact(
    val base64: String = "",
    @SerialName("finishReason") val finishReason: String = "",
)

// ---- TRELLIS 3D Generation ----
@Serializable
data class TrellisStatusResponse(
    val status: String = "",
    @SerialName("output_url") val outputUrl: String? = null,
)

// ---- TTS Request ----
@Serializable
data class TtsRequest(
    val model: String,
    val input: String,
    val voice: String = "nova",
)
