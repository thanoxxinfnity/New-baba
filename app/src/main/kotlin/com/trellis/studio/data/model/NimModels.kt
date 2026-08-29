package com.trellis.studio.data.model

/** Represents a single NIM LLM or vision model */
data class LlmModel(
    val id: String,
    val displayName: String,
    val category: String,
    val isVision: Boolean = false,
    val isReasoning: Boolean = false,
    val isCode: Boolean = false,
    val contextK: Int = 128,
    val isFast: Boolean = false,
)

/** Represents a NIM Image Generation model */
data class ImageModel(
    val id: String,
    val displayName: String,
    val category: String,
    val apiBaseUrl: String,
    val apiStyle: ImageApiStyle = ImageApiStyle.POLLINATIONS,
    val defaultWidth: Int = 1024,
    val defaultHeight: Int = 1024,
)

enum class ImageApiStyle { FLUX, SDXL, POLLINATIONS }

// -----------------------------------------------------------------------
// LLM MODELS — every model here was verified HTTP 200 with a live API key
// -----------------------------------------------------------------------
// Re-verified against the live /v1/models list and a real completion call.
// NVIDIA retires models on a published end-of-life date and then answers 410
// Gone — twelve of the entries that used to be here had been retired, including
// the app's own default, so Chat failed for anyone who never changed it. Only
// models that returned HTTP 200 to an actual request are listed.
val NIM_LLM_MODELS: List<LlmModel> = listOf(

    // ── OpenAI OSS (served via NVIDIA NIM) ────────────────────────────
    LlmModel("openai/gpt-oss-20b",                    "GPT OSS 20B",           "OpenAI OSS", isFast = true, contextK = 128),
    LlmModel("openai/gpt-oss-120b",                   "GPT OSS 120B",          "OpenAI OSS",                contextK = 128),

    // ── NVIDIA Nemotron ────────────────────────────────────────────────
    LlmModel("nvidia/nemotron-3.5-lightning-30b-a3b", "Nemotron 3.5 Lightning", "NVIDIA Nemotron", isFast = true, contextK = 128),
    LlmModel("nvidia/nemotron-3-super-120b-a12b",     "Nemotron 3 Super 120B", "NVIDIA Nemotron",           contextK = 128),

    // ── DeepSeek ───────────────────────────────────────────────────────
    LlmModel("deepseek-ai/deepseek-v4-pro-0813",      "DeepSeek V4 Pro",       "DeepSeek",                  contextK = 128),

    // ── Moonshot ───────────────────────────────────────────────────────
    LlmModel("moonshotai/kimi-k3",                    "Kimi K3",               "Moonshot",                  contextK = 128),

    // ── MiniMax ────────────────────────────────────────────────────────
    LlmModel("minimaxai/minimax-m3",                  "MiniMax M3",            "MiniMax", isFast = true, contextK = 128),

    // ── Vision ─────────────────────────────────────────────────────────
    LlmModel("meta/llama-3.2-11b-vision-instruct",    "Llama 3.2 11B Vision",  "Vision", isVision = true, contextK = 128),
    LlmModel("meta/llama-3.2-90b-vision-instruct",    "Llama 3.2 90B Vision",  "Vision", isVision = true, contextK = 128),
)

val NIM_LLM_GROUPS: Map<String, List<LlmModel>> by lazy {
    NIM_LLM_MODELS.groupBy { it.category }
}

// -----------------------------------------------------------------------
// IMAGE GENERATION
// Pollinations is completely free — no API key required, always works.
// NVIDIA FLUX/SDXL endpoints require a separate paid image-gen plan.
// -----------------------------------------------------------------------
val NIM_IMAGE_MODELS: List<ImageModel> = listOf(
    // NVIDIA FLUX.1 — photoreal, needs the nvapi- key. Verified live: `steps` +
    // `cfg_scale`, ~5s a 1024² image. This is the realistic default.
    ImageModel(
        id = "black-forest-labs/flux.1-dev",
        displayName = "FLUX.1 dev (Realistic)",
        category = "NVIDIA — Realistic",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-dev",
        apiStyle = ImageApiStyle.FLUX,
    ),
    ImageModel(
        id = "black-forest-labs/flux.1-schnell",
        displayName = "FLUX.1 schnell (Fast, realistic)",
        category = "NVIDIA — Realistic",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell",
        apiStyle = ImageApiStyle.FLUX,
    ),
    ImageModel(
        id = "pollinations/flux",
        displayName = "FLUX (Free, no key)",
        category = "Pollinations — Free",
        apiBaseUrl = "https://image.pollinations.ai/prompt",
        apiStyle = ImageApiStyle.POLLINATIONS,
    ),
    ImageModel(
        id = "pollinations/turbo",
        displayName = "FLUX Turbo (Fast, free)",
        category = "Pollinations — Free",
        apiBaseUrl = "https://image.pollinations.ai/prompt",
        apiStyle = ImageApiStyle.POLLINATIONS,
    ),
)

val NIM_IMAGE_GROUPS: Map<String, List<ImageModel>> by lazy {
    NIM_IMAGE_MODELS.groupBy { it.category }
}
