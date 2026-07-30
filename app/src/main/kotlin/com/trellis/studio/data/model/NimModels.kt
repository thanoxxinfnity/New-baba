package com.trellis.studio.data.model

/** Represents a single NIM LLM or vision model available via NVIDIA API */
data class LlmModel(
    val id: String,           // API model string e.g. "meta/llama-3.3-70b-instruct"
    val displayName: String,  // Human-friendly name
    val category: String,     // Group label shown in selector
    val isVision: Boolean = false,
    val isReasoning: Boolean = false,
    val isCode: Boolean = false,
    val contextK: Int = 128,  // Context window in K tokens
)

/** Represents a NIM Image Generation model */
data class ImageModel(
    val id: String,
    val displayName: String,
    val category: String,
    val apiBaseUrl: String,
    val apiStyle: ImageApiStyle = ImageApiStyle.FLUX,
    val defaultWidth: Int = 1024,
    val defaultHeight: Int = 1024,
)

/** Represents a TTS model */
data class TtsModel(
    val id: String,
    val displayName: String,
    val description: String,
)

enum class ImageApiStyle {
    FLUX,   // black-forest-labs style: {prompt, width, height, num_inference_steps, guidance, seed}
    SDXL,   // stability style:        {text_prompts:[{text,weight}], cfg_scale, steps, width, height, seed}
    POLLINATIONS, // free: image.pollinations.ai/prompt/{encoded}
}

// -----------------------------------------------------------------------
// LLM MODELS
// -----------------------------------------------------------------------
val NIM_LLM_MODELS: List<LlmModel> = listOf(
    // NVIDIA Nemotron
    LlmModel("nvidia/llama-3.1-nemotron-70b-instruct",       "Nemotron 70B",          "NVIDIA Nemotron", contextK = 131),
    LlmModel("nvidia/llama-3.1-nemotron-ultra-253b-v1",      "Nemotron Ultra 253B",   "NVIDIA Nemotron", contextK = 128),
    LlmModel("nvidia/llama-3.3-nemotron-super-49b-v1",       "Nemotron Super 49B",    "NVIDIA Nemotron", contextK = 128),
    LlmModel("nvidia/nemotron-mini-4b-instruct",              "Nemotron Mini 4B",      "NVIDIA Nemotron", contextK = 4),

    // Meta Llama 3.3 / 3.1
    LlmModel("meta/llama-3.3-70b-instruct",    "Llama 3.3 70B",          "Meta Llama", contextK = 128),
    LlmModel("meta/llama-3.1-405b-instruct",   "Llama 3.1 405B",         "Meta Llama", contextK = 128),
    LlmModel("meta/llama-3.1-70b-instruct",    "Llama 3.1 70B",          "Meta Llama", contextK = 128),

    // Meta Vision Models
    LlmModel("meta/llama-3.2-90b-vision-instruct", "Llama 3.2 90B Vision", "Meta Llama Vision", isVision = true, contextK = 128),
    LlmModel("meta/llama-3.2-11b-vision-instruct", "Llama 3.2 11B Vision", "Meta Llama Vision", isVision = true, contextK = 128),

    // Meta Small / Code
    LlmModel("meta/llama-3.2-3b-instruct",     "Llama 3.2 3B",           "Meta Llama", contextK = 128),
    LlmModel("meta/llama-3.2-1b-instruct",     "Llama 3.2 1B",           "Meta Llama", contextK = 128),
    LlmModel("meta/codellama-70b-instruct",    "CodeLlama 70B",           "Meta Llama", isCode = true, contextK = 100),

    // DeepSeek
    LlmModel("deepseek-ai/deepseek-r1",        "DeepSeek R1",             "DeepSeek", isReasoning = true, contextK = 64),
    LlmModel("deepseek-ai/deepseek-v3",        "DeepSeek V3",             "DeepSeek", contextK = 128),

    // Mistral
    LlmModel("mistralai/mistral-large-2-instruct",        "Mistral Large 2",       "Mistral", contextK = 128),
    LlmModel("mistralai/mixtral-8x22b-instruct-v0.1",     "Mixtral 8x22B",         "Mistral", contextK = 65),
    LlmModel("mistralai/mixtral-8x7b-instruct-v0.1",      "Mixtral 8x7B",          "Mistral", contextK = 32),
    LlmModel("mistralai/mistral-7b-instruct-v0.3",        "Mistral 7B",            "Mistral", contextK = 32),
    LlmModel("mistralai/codestral-22b-instruct-v0.1",     "Codestral 22B",         "Mistral", isCode = true, contextK = 32),

    // Google Gemma
    LlmModel("google/gemma-2-27b-it",          "Gemma 2 27B",             "Google Gemma", contextK = 8),
    LlmModel("google/gemma-2-9b-it",           "Gemma 2 9B",              "Google Gemma", contextK = 8),
    LlmModel("google/gemma-2-2b-it",           "Gemma 2 2B",              "Google Gemma", contextK = 8),
    LlmModel("google/codegemma-7b-it",         "CodeGemma 7B",            "Google Gemma", isCode = true, contextK = 8),

    // Microsoft Phi
    LlmModel("microsoft/phi-3.5-moe-instruct", "Phi 3.5 MoE",             "Microsoft Phi", contextK = 128),
    LlmModel("microsoft/phi-3.5-mini-instruct","Phi 3.5 Mini",            "Microsoft Phi", contextK = 128),
    LlmModel("microsoft/phi-4",                "Phi 4",                   "Microsoft Phi", contextK = 16),

    // Qwen
    LlmModel("qwen/qwen2.5-72b-instruct",        "Qwen 2.5 72B",          "Qwen Alibaba", contextK = 32),
    LlmModel("qwen/qwq-32b",                     "QwQ 32B (Reasoning)",   "Qwen Alibaba", isReasoning = true, contextK = 32),
    LlmModel("qwen/qwen2.5-coder-32b-instruct",  "Qwen 2.5 Coder 32B",   "Qwen Alibaba", isCode = true, contextK = 32),
    LlmModel("qwen/qwen2.5-coder-7b-instruct",   "Qwen 2.5 Coder 7B",    "Qwen Alibaba", isCode = true, contextK = 32),

    // ZhipuAI GLM
    LlmModel("zhipuai/glm-4-9b-chat",            "GLM 5.2",               "ZhipuAI GLM", contextK = 128),
)

// Group models by category for the selector sheet
val NIM_LLM_GROUPS: Map<String, List<LlmModel>> by lazy {
    NIM_LLM_MODELS.groupBy { it.category }
}

// -----------------------------------------------------------------------
// IMAGE GENERATION MODELS
// -----------------------------------------------------------------------
val NIM_IMAGE_MODELS: List<ImageModel> = listOf(
    // Flux (Black Forest Labs via NVIDIA)
    ImageModel(
        id = "black-forest-labs/flux-dev",
        displayName = "FLUX Dev",
        category = "FLUX (Black Forest Labs)",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux-dev",
        apiStyle = ImageApiStyle.FLUX,
    ),
    ImageModel(
        id = "black-forest-labs/flux-schnell",
        displayName = "FLUX Schnell (Fast)",
        category = "FLUX (Black Forest Labs)",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux-schnell",
        apiStyle = ImageApiStyle.FLUX,
    ),
    // Stability AI
    ImageModel(
        id = "stabilityai/stable-diffusion-xl",
        displayName = "Stable Diffusion XL",
        category = "Stability AI",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-diffusion-xl",
        apiStyle = ImageApiStyle.SDXL,
    ),
    ImageModel(
        id = "stabilityai/stable-diffusion-3-medium",
        displayName = "Stable Diffusion 3 Medium",
        category = "Stability AI",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-diffusion-3-medium",
        apiStyle = ImageApiStyle.SDXL,
    ),
    // NVIDIA Sana
    ImageModel(
        id = "nvidia/sana",
        displayName = "NVIDIA Sana",
        category = "NVIDIA",
        apiBaseUrl = "https://ai.api.nvidia.com/v1/genai/nvidia/sana",
        apiStyle = ImageApiStyle.FLUX,
    ),
    // Pollinations (free, no key needed)
    ImageModel(
        id = "pollinations/flux",
        displayName = "Pollinations FLUX (Free)",
        category = "Pollinations (Free)",
        apiBaseUrl = "https://image.pollinations.ai/prompt",
        apiStyle = ImageApiStyle.POLLINATIONS,
    ),
)

val NIM_IMAGE_GROUPS: Map<String, List<ImageModel>> by lazy {
    NIM_IMAGE_MODELS.groupBy { it.category }
}

// -----------------------------------------------------------------------
// TTS MODELS
// -----------------------------------------------------------------------
val NIM_TTS_MODELS: List<TtsModel> = listOf(
    TtsModel("nvidia/magpie-tts-flow",            "Magpie TTS Flow",           "High-quality streaming TTS"),
    TtsModel("nvidia/magpie-tts-multilingual",    "Magpie Multilingual",       "Supports 40+ languages"),
    TtsModel("nvidia/magpie-tts-zeroshot",        "Magpie Zero-Shot Cloning",  "Clone voice from audio sample"),
    TtsModel("nvidia/nemotron-voicechat",         "Nemotron Voice Chat",       "Optimized for conversational TTS"),
    TtsModel("nvidia/parakeet-ctc-0.6b-en",      "Parakeet CTC 0.6B",         "Fast English ASR/TTS"),
    TtsModel("resemble.ai/chatterbox-multilingual-tts", "Chatterbox Multilingual", "Expressive multi-language TTS"),
)
