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
val NIM_LLM_MODELS: List<LlmModel> = listOf(

    // ── Meta Llama ─────────────────────────────────────────────────────
    LlmModel("meta/llama-3.2-1b-instruct",            "Llama 3.2 1B",          "Meta Llama", isFast = true,  contextK = 128),
    LlmModel("meta/llama-3.1-8b-instruct",            "Llama 3.1 8B",          "Meta Llama", isFast = true,  contextK = 128),
    LlmModel("meta/llama-3.1-70b-instruct",           "Llama 3.1 70B",         "Meta Llama",                 contextK = 128),

    // ── Meta Llama Vision ──────────────────────────────────────────────
    LlmModel("meta/llama-3.2-11b-vision-instruct",    "Llama 3.2 11B Vision",  "Meta Vision", isVision = true, contextK = 128),
    LlmModel("meta/llama-3.2-90b-vision-instruct",    "Llama 3.2 90B Vision",  "Meta Vision", isVision = true, contextK = 128),

    // ── NVIDIA Nemotron ────────────────────────────────────────────────
    LlmModel("nvidia/nemotron-mini-4b-instruct",      "Nemotron Mini 4B",      "NVIDIA Nemotron", isFast = true, contextK = 4),
    LlmModel("nvidia/nvidia-nemotron-nano-9b-v2",     "Nemotron Nano 9B",      "NVIDIA Nemotron", isFast = true, contextK = 128),
    LlmModel("nvidia/llama-3.3-nemotron-super-49b-v1","Nemotron Super 49B",    "NVIDIA Nemotron",              contextK = 128),

    // ── NVIDIA Vision-Language ─────────────────────────────────────────
    LlmModel("nvidia/nemotron-nano-12b-v2-vl",        "Nemotron Nano 12B VL",  "NVIDIA Vision", isVision = true, contextK = 128),
    LlmModel("nvidia/llama-3.1-nemotron-nano-vl-8b-v1","Nemotron Nano VL 8B", "NVIDIA Vision", isVision = true, contextK = 128),

    // ── Mistral ────────────────────────────────────────────────────────
    LlmModel("mistralai/mistral-nemotron",            "Mistral Nemotron",      "Mistral", isFast = true, contextK = 128),
    LlmModel("mistralai/mistral-medium-3.5-128b",     "Mistral Medium 3.5",    "Mistral",                 contextK = 128),

    // ── DeepSeek ───────────────────────────────────────────────────────
    LlmModel("deepseek-ai/deepseek-v4-flash",         "DeepSeek V4 Flash",     "DeepSeek", isFast = true, contextK = 128),

    // ── MiniMax ────────────────────────────────────────────────────────
    LlmModel("minimaxai/minimax-m3",                  "MiniMax M3",            "MiniMax", isFast = true, contextK = 128),

    // ── ZhipuAI GLM ───────────────────────────────────────────────────
    LlmModel("z-ai/glm-5.2",                          "GLM 5.2",               "ZhipuAI GLM",              contextK = 128),

    // ── OpenAI OSS (served via NVIDIA NIM) ────────────────────────────
    LlmModel("openai/gpt-oss-20b",                    "GPT OSS 20B",           "OpenAI OSS", isFast = true, contextK = 128),

    // ── Poolside ───────────────────────────────────────────────────────
    LlmModel("poolside/laguna-xs-2.1",                "Laguna XS 2.1",         "Poolside", isFast = true, contextK = 128),

    // ── StepFun ────────────────────────────────────────────────────────
    LlmModel("stepfun-ai/step-3.7-flash",             "Step 3.7 Flash",        "StepFun", isFast = true, contextK = 128),
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
