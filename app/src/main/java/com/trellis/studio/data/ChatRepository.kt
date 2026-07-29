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
                                404      -> "Model not found (404). Select a different model in the picker."
                                422      -> detail ?: "Invalid request (422). Try a different model or shorter input."
                                429      -> "Rate limit hit (~40 req/min). Wait a moment and retry."
                                else     -> detail
                                    ?: if (body.isNotBlank()) "API error (${resp.code}): ${body.take(200)}"
                                    else "Request failed (HTTP ${resp.code})."
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

        /** Models with consistently low first-token latency. */
        val FAST_MODELS = setOf(
            "meta/llama-3.2-3b-instruct",
            "meta/llama-3.2-1b-instruct",
            "meta/llama-3.1-70b-instruct",
            "mistralai/mistral-7b-instruct-v0.3",
            "nv-mistralai/mistral-nemo-12b-instruct",
            "google/gemma-2-9b-it",
            "google/gemma-2-2b-it",
            "microsoft/phi-3.5-mini-instruct",
            "qwen/qwen2.5-coder-7b-instruct",
            "ibm-granite/granite-3.0-8b-instruct"
        )

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
            // ── Reasoning ─────────────────────────────────────────────────────
            ChatModelInfo("deepseek-ai/deepseek-r1",                        "Reasoning"),
            ChatModelInfo("deepseek-ai/deepseek-r1-distill-llama-70b",      "Reasoning"),
            ChatModelInfo("deepseek-ai/deepseek-r1-distill-qwen-32b",       "Reasoning"),
            ChatModelInfo("nvidia/llama-3.1-nemotron-ultra-253b-v1",        "Reasoning"),
            ChatModelInfo("nvidia/llama-3.1-nemotron-70b-instruct",         "Reasoning"),
            // ── Coding ────────────────────────────────────────────────────────
            ChatModelInfo("deepseek-ai/deepseek-v3",                        "Coding"),
            ChatModelInfo("qwen/qwen2.5-coder-32b-instruct",                "Coding"),
            ChatModelInfo("qwen/qwen2.5-coder-7b-instruct",                 "Coding"),
            ChatModelInfo("mistralai/codestral-22b-instruct-v0.1",          "Coding"),
            ChatModelInfo("meta/codellama-70b-instruct",                    "Coding"),
            // ── Large / Deep Thinking ─────────────────────────────────────────
            ChatModelInfo("meta/llama-3.1-405b-instruct",                   "Large Models"),
            ChatModelInfo("meta/llama-3.3-70b-instruct",                    "Large Models"),
            ChatModelInfo("meta/llama-3.1-70b-instruct",                    "Large Models"),
            ChatModelInfo("mistralai/mixtral-8x22b-instruct-v0.1",          "Large Models"),
            ChatModelInfo("mistralai/mistral-large-2-instruct",             "Large Models"),
            ChatModelInfo("qwen/qwen2.5-72b-instruct",                      "Large Models"),
            ChatModelInfo("01-ai/yi-large",                                 "Large Models"),
            // ── Fast & Efficient ──────────────────────────────────────────────
            ChatModelInfo("meta/llama-3.2-3b-instruct",                     "Fast & Efficient"),
            ChatModelInfo("meta/llama-3.2-1b-instruct",                     "Fast & Efficient"),
            ChatModelInfo("google/gemma-2-9b-it",                           "Fast & Efficient"),
            ChatModelInfo("google/gemma-2-2b-it",                           "Fast & Efficient"),
            ChatModelInfo("mistralai/mistral-7b-instruct-v0.3",             "Fast & Efficient"),
            ChatModelInfo("nv-mistralai/mistral-nemo-12b-instruct",         "Fast & Efficient"),
            ChatModelInfo("microsoft/phi-3.5-mini-instruct",                "Fast & Efficient"),
            ChatModelInfo("ibm-granite/granite-3.0-8b-instruct",            "Fast & Efficient"),
            ChatModelInfo("upstage/solar-10.7b-instruct",                   "Fast & Efficient"),
            // ── General Chat ──────────────────────────────────────────────────
            ChatModelInfo("google/gemma-2-27b-it",                          "General Chat"),
            ChatModelInfo("microsoft/phi-3.5-moe-instruct",                 "General Chat"),
            ChatModelInfo("microsoft/phi-3-medium-4k-instruct",             "General Chat"),
            // ── Vision (multimodal) ───────────────────────────────────────────
            ChatModelInfo("meta/llama-3.2-90b-vision-instruct",             "Vision"),
            ChatModelInfo("meta/llama-3.2-11b-vision-instruct",             "Vision"),
            ChatModelInfo("microsoft/phi-3.5-vision-instruct",              "Vision"),
            // ── Voice & Audio (TTS / STT — not for chat) ──────────────────────
            ChatModelInfo("elevenlabs/eleven-multilingual-v2",              "Voice & Audio"),
            ChatModelInfo("elevenlabs/eleven-turbo-v2",                     "Voice & Audio"),
            ChatModelInfo("nvidia/canary-1b",                               "Voice & Audio"),
            ChatModelInfo("nvidia/parakeet-ctc-0.6b-en",                   "Voice & Audio")
        )
    }
}
