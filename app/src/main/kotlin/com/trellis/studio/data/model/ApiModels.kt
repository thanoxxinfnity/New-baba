package com.trellis.studio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ---- Chat Completions (NVIDIA NIM) ----

/**
 * Domain turn used by the app. [imagePath] is only honoured by vision models —
 * NimClient converts it into the OpenAI-style multipart content array.
 */
data class ChatTurn(
    val role: String,              // "system" | "user" | "assistant"
    val text: String,
    val imagePath: String? = null,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

/**
 * Wire format for a request message. [content] is a JsonElement because the API
 * accepts either a plain string OR an array of {type:text|image_url} parts.
 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: JsonElement,
)

fun textMessage(role: String, text: String) = ApiMessage(role, JsonPrimitive(text))

/** Builds the multipart content array that vision models require. */
fun visionMessage(text: String, imageDataUrl: String) = ApiMessage(
    role = "user",
    content = buildJsonArray {
        addJsonObject {
            put("type", "text")
            put("text", text)
        }
        addJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", imageDataUrl) }
        }
    },
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val message: ResponseMessage? = null,
    val delta: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * Response message. Every field is nullable on purpose: NVIDIA reasoning models
 * return `"content": null` when the reply is cut off mid-thought, and putting the
 * answer in `reasoning_content` / `reasoning` instead. A non-null String here
 * would make kotlinx.serialization throw and surface as a chat error.
 */
@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

/** What NimClient hands back to the ViewModel. */
data class ChatResult(
    val content: String,
    val reasoning: String? = null,
)

/** NVIDIA error envelope, so we can show the real message instead of raw JSON. */
@Serializable
data class NimError(
    val detail: String? = null,
    val message: String? = null,
    val title: String? = null,
    val error: NimErrorBody? = null,
)

@Serializable
data class NimErrorBody(val message: String? = null)

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

// ---- Remote build server (tools/trellis_build_server.py) ----

@Serializable
data class BuildServerHealth(
    val ok: Boolean = false,
    val sdk: Boolean = false,
    val sdkPath: String = "",
    val java: Boolean = false,
    val gradle: String = "",
    val canBuildApk: Boolean = false,
    val workdir: String = "",
)

@Serializable
data class RemoteExecRequest(val cmd: String)

@Serializable
data class RemoteExecResult(
    val output: String = "",
    val exitCode: Int = 0,
)

@Serializable
data class RemoteBuildRequest(val files: Map<String, String>)

@Serializable
data class RemoteBuildStarted(val jobId: String)

@Serializable
data class RemoteBuildStatus(
    val state: String = "running",      // running | done | failed
    val log: String? = null,
    val artifact: String? = null,
    val size: Long? = null,
    val error: String? = null,
)

@Serializable
data class RemoteArtifact(
    val name: String,
    val url: String,
    val size: Long = 0L,
)

// ---- TTS Request ----
@Serializable
data class TtsRequest(
    val model: String,
    val input: String,
    val voice: String = "nova",
)
