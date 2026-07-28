package com.trellis.studio.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatModelInfo(val id: String, val ownedBy: String)

data class ChatHistoryEntry(val role: String, val content: String, val imagePath: String? = null)

sealed interface ChatStreamEvent {
    data class ContentDelta(val text: String) : ChatStreamEvent
    data class ReasoningDelta(val text: String) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
    data object Done : ChatStreamEvent
}

class ChatRepository(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String
) {
    private var cachedModels: List<ChatModelInfo>? = null

    suspend fun fetchModels(forceRefresh: Boolean = false): List<ChatModelInfo> {
        if (!forceRefresh) cachedModels?.let { return it }
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return FALLBACK_MODELS.also { cachedModels = it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE_URL/v1/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext FALLBACK_MODELS
                    val text  = response.body?.string().orEmpty()
                    val array = runCatching {
                        JSONObject(text).optJSONArray("data")
                    }.getOrNull() ?: return@withContext FALLBACK_MODELS
                    val list  = (0 until array.length()).map { i ->
                        val o = array.getJSONObject(i)
                        ChatModelInfo(o.optString("id"), o.optString("owned_by"))
                    }.filter { it.id.isNotBlank() }.sortedBy { it.id }
                    if (list.isEmpty()) FALLBACK_MODELS else list.also { cachedModels = it }
                }
            }.getOrDefault(FALLBACK_MODELS)
        }
    }

    fun streamChat(model: String, history: List<ChatHistoryEntry>): Flow<ChatStreamEvent> =
        callbackFlow {
            val apiKey = apiKeyProvider()
            if (apiKey.isBlank()) {
                trySend(ChatStreamEvent.Error("NVIDIA API key missing — add it in Settings → NVIDIA NIM API Key."))
                close(); return@callbackFlow
            }
            val payload = JSONObject()
                .put("model", model)
                .put("messages", buildMessagesJson(history))
                .put("stream", true)
                .put("max_tokens", 4096)
                .toString()

            val request = Request.Builder()
                .url("$BASE_URL/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val listener = object : EventSourceListener() {
                override fun onEvent(src: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") { trySend(ChatStreamEvent.Done); return }
                    runCatching {
                        val delta = JSONObject(data)
                            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val content   = delta?.optString("content").orEmpty()
                        val reasoning = delta?.optString("reasoning_content").orEmpty()
                        if (content.isNotEmpty())   trySend(ChatStreamEvent.ContentDelta(content))
                        if (reasoning.isNotEmpty()) trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                    }
                }
                override fun onFailure(src: EventSource, t: Throwable?, resp: Response?) {
                    val msg = when {
                        resp != null -> {
                            val body   = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                            val detail = runCatching {
                                JSONObject(body).optJSONObject("error")?.optString("message")
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                            when (resp.code) {
                                401, 403 -> "API key rejected (401/403). Check your nvapi- key in Settings."
                                429      -> "Rate limit hit (~40 req/min). Wait a moment and retry."
                                else     -> detail ?: "Request failed (HTTP ${resp.code})."
                            }
                        }
                        t != null -> t.toUserMessage()
                        else      -> "Chat request failed. Check your internet & API key."
                    }
                    trySend(ChatStreamEvent.Error(msg)); close()
                }
                override fun onClosed(src: EventSource) { close() }
            }
            val es = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
            awaitClose { es.cancel() }
        }

    /** Test the API key — returns null on success, error message on failure. */
    suspend fun testApiKey(): String? = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) return@withContext "API key is empty."
        runCatching {
            val req = Request.Builder()
                .url("$BASE_URL/v1/models")
                .header("Authorization", "Bearer $key")
                .get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200       -> null
                    401, 403  -> "Invalid API key (${resp.code})."
                    429       -> "Rate limited — key is valid but throttled."
                    else      -> "API returned ${resp.code}."
                }
            }
        }.getOrElse { it.toUserMessage() }
    }

    private fun buildMessagesJson(history: List<ChatHistoryEntry>): JSONArray {
        val arr = JSONArray()
        history.forEach { entry ->
            val msg = JSONObject().put("role", entry.role)
            if (entry.imagePath != null && entry.role == "user") {
                val ca = JSONArray()
                ca.put(JSONObject().put("type", "text").put("text", entry.content))
                val imgFile = File(entry.imagePath)
                if (imgFile.exists()) {
                    val b64  = Base64.encodeToString(imgFile.readBytes(), Base64.NO_WRAP)
                    val mime = when (imgFile.extension.lowercase()) {
                        "png"  -> "image/png"
                        "webp" -> "image/webp"
                        else   -> "image/jpeg"
                    }
                    ca.put(JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
                }
                msg.put("content", ca)
            } else {
                msg.put("content", entry.content)
            }
            arr.put(msg)
        }
        return arr
    }

    companion object {
        const val BASE_URL = "https://integrate.api.nvidia.com"

        const val GAME_SYSTEM_PROMPT = """You are an expert game developer. When asked to create a game, generate a complete, self-contained HTML5 game in a SINGLE HTML file.

Rules:
- All CSS and JavaScript must be inline (no external CDNs)
- Must work on mobile with touch controls
- Include a score system, title screen, and game over screen
- Use Canvas API or CSS animations
- Comment every important code section explaining WHAT it does and WHY
- At the end, add a <!-- FILE STRUCTURE --> comment block explaining every section
- Return ONLY valid HTML starting with <!DOCTYPE html>"""

        val FALLBACK_MODELS = listOf(
            // ── Reasoning ──────────────────────────────────────────────────────
            ChatModelInfo("z.ai/glm-5.2",                            "Reasoning"),
            ChatModelInfo("nvidia/nemotron-3-ultra-550b-a55b",       "Reasoning"),
            ChatModelInfo("deepseek-ai/deepseek-v4-pro",             "Reasoning"),
            ChatModelInfo("deepseek-ai/deepseek-v4-flash",           "Reasoning"),
            ChatModelInfo("kimi/kimi-k3",                             "Reasoning"),
            ChatModelInfo("kimi/kimi-k2.7-code",                     "Reasoning"),
            ChatModelInfo("kimi/kimi-k2.6",                          "Reasoning"),
            ChatModelInfo("poolside/laguna-xs-2.1",                  "Reasoning"),
            ChatModelInfo("thinkingmachines/inkling",                "Reasoning"),
            ChatModelInfo("minimax/minimax-m3",                      "Reasoning"),
            ChatModelInfo("minimax/minimax-m2.7",                    "Reasoning"),
            // ── Coding ────────────────────────────────────────────────────────
            ChatModelInfo("mistralai/codestral-22b",                 "Coding"),
            ChatModelInfo("mistralai/mistral-medium-3.5-128b",       "Coding"),
            ChatModelInfo("meta/codellama-70b-instruct",             "Coding"),
            ChatModelInfo("qwen/qwen3-coder-next",                   "Coding"),
            ChatModelInfo("qwen/qwen3-coder-30b",                    "Coding"),
            // ── Deep Thinking ─────────────────────────────────────────────────
            ChatModelInfo("meta/llama-3.1-405b-instruct",           "Deep Thinking"),
            ChatModelInfo("meta/llama-3.1-70b-instruct",            "Deep Thinking"),
            ChatModelInfo("qwen/qwen-2.5-72b-instruct",             "Deep Thinking"),
            ChatModelInfo("qwen/qwen3.6-plus",                       "Deep Thinking"),
            ChatModelInfo("microsoft/phi-3.5-moe-instruct",         "Deep Thinking"),
            ChatModelInfo("google/gemma-4-31b-it",                   "Deep Thinking"),
            // ── Voice & Audio (TTS / STT — not for chat) ──────────────────────
            ChatModelInfo("nvidia/magpie-tts-zeroshot",              "Voice & Audio"),
            ChatModelInfo("nvidia/magpie-tts-flow",                  "Voice & Audio"),
            ChatModelInfo("nvidia/nemotron-voicechat",               "Voice & Audio"),
            ChatModelInfo("nvidia/personaplex",                      "Voice & Audio"),
            ChatModelInfo("nvidia/magpie-tts-multilingual",          "Voice & Audio"),
            ChatModelInfo("resemble.ai/chatterbox-multilingual-tts", "Voice & Audio"),
            ChatModelInfo("nvidia/nemotron-speech-streaming-en-0.6b","Voice & Audio"),
            ChatModelInfo("nvidia/parakeet-ctc-0.6b-en",            "Voice & Audio"),
            ChatModelInfo("nvidia/parakeet-tdt",                     "Voice & Audio"),
            ChatModelInfo("nvidia/canary",                           "Voice & Audio"),
            // ── Chat & RAG ────────────────────────────────────────────────────
            ChatModelInfo("mistralai/mistral-large-2",               "Chat & RAG"),
            ChatModelInfo("google/gemma-2-27b-it",                   "Chat & RAG"),
            ChatModelInfo("community/gpt-oss-120B",                  "Chat & RAG"),
            ChatModelInfo("community/gpt-oss-20b",                   "Chat & RAG"),
            ChatModelInfo("sarvam/sarvam-m",                         "Chat & RAG"),
            ChatModelInfo("nvidia/nemotron-3-embed-1b",              "Chat & RAG"),
            ChatModelInfo("nvidia/nemotron-3.5-content-safety",      "Chat & RAG"),
            // ── Vision & Industrial ───────────────────────────────────────────
            ChatModelInfo("nvidia/ising-calibration-1.5-31b",       "Vision & Industrial"),
            ChatModelInfo("nvidia/qwen-image-edit-nvpcb-ovsl2sl",    "Vision & Industrial")
        )
    }
}
