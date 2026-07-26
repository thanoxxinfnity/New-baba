package com.trellis.studio.data

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

data class ChatModelInfo(val id: String, val ownedBy: String)

sealed interface ChatStreamEvent {
    data class ContentDelta(val text: String) : ChatStreamEvent
    data class ReasoningDelta(val text: String) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
    data object Done : ChatStreamEvent
}

/**
 * Text chat over NVIDIA's OpenAI-compatible NIM API (integrate.api.nvidia.com) — the
 * same nvapi- key already used for TRELLIS powers 100+ chat/reasoning models here too,
 * with no separate signup. Free tier: rate-limited (~40 req/min), no token/credit cap.
 */
class ChatRepository(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String
) {
    private var cachedModels: List<ChatModelInfo>? = null

    suspend fun fetchModels(forceRefresh: Boolean = false): List<ChatModelInfo> {
        if (!forceRefresh) cachedModels?.let { return it }
        val apiKey = requireApiKey()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppException.Api("Could not load the model list (HTTP ${response.code}).")
                }
                val text = response.body?.string().orEmpty()
                val array = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: JSONArray()
                val list = (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    ChatModelInfo(id = obj.optString("id"), ownedBy = obj.optString("owned_by"))
                }.filter { it.id.isNotBlank() }.sortedBy { it.id }
                cachedModels = list
                list
            }
        }
    }

    /** Streams a chat completion; [messages] is ordered (role, content) pairs, oldest first. */
    fun streamChat(model: String, messages: List<Pair<String, String>>): Flow<ChatStreamEvent> = callbackFlow {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            trySend(ChatStreamEvent.Error("NVIDIA API key is missing. Add it in Settings."))
            close()
            return@callbackFlow
        }

        val messagesJson = JSONArray()
        messages.forEach { (role, content) ->
            messagesJson.put(JSONObject().put("role", role).put("content", content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesJson)
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
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(ChatStreamEvent.Done)
                    return
                }
                runCatching {
                    val delta = JSONObject(data)
                        .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val content = delta?.optString("content").orEmpty()
                    val reasoning = delta?.optString("reasoning_content").orEmpty()
                    if (content.isNotEmpty()) trySend(ChatStreamEvent.ContentDelta(content))
                    if (reasoning.isNotEmpty()) trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = when {
                    response != null -> {
                        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        val detail = runCatching {
                            JSONObject(body).optJSONObject("error")?.optString("message")
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        when (response.code) {
                            401, 403 -> "The NVIDIA API key was rejected. Check the key in Settings."
                            429 -> "Rate limit reached (about 40 requests/minute). Wait a moment and retry."
                            else -> detail ?: "Chat request failed (HTTP ${response.code})."
                        }
                    }
                    t != null -> t.toUserMessage()
                    else -> "Chat request failed."
                }
                trySend(ChatStreamEvent.Error(message))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun requireApiKey(): String {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw AppException.Api("NVIDIA API key is missing. Add it in Settings.")
        }
        return apiKey
    }

    private companion object {
        const val BASE_URL = "https://integrate.api.nvidia.com"
    }
}
