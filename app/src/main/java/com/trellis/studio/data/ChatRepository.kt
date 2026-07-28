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

/** A message in the history with an optional attached image path. */
data class ChatHistoryEntry(val role: String, val content: String, val imagePath: String? = null)

sealed interface ChatStreamEvent {
    data class ContentDelta(val text: String) : ChatStreamEvent
    data class ReasoningDelta(val text: String) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
    data object Done : ChatStreamEvent
}

/**
 * Text (and vision) chat over NVIDIA's OpenAI-compatible NIM API.
 * When a message has an [ChatHistoryEntry.imagePath], the image is Base64-encoded
 * and sent as a multimodal content block — compatible with vision models like
 * meta/llama-3.2-90b-vision-instruct on NVIDIA NIM.
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

    /** Streams a chat completion. [history] entries may include an image path for vision. */
    fun streamChat(model: String, history: List<ChatHistoryEntry>): Flow<ChatStreamEvent> = callbackFlow {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            trySend(ChatStreamEvent.Error("NVIDIA API key is missing. Add it in Settings."))
            close()
            return@callbackFlow
        }

        val messagesJson = buildMessagesJson(history)

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
                    val content  = delta?.optString("content").orEmpty()
                    val reasoning = delta?.optString("reasoning_content").orEmpty()
                    if (content.isNotEmpty())   trySend(ChatStreamEvent.ContentDelta(content))
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
                            401, 403 -> "NVIDIA API key rejected. Check Settings."
                            429      -> "Rate limit reached (~40 req/min). Wait a moment."
                            else     -> detail ?: "Chat request failed (HTTP ${response.code})."
                        }
                    }
                    t != null -> t.toUserMessage()
                    else      -> "Chat request failed."
                }
                trySend(ChatStreamEvent.Error(message))
                close()
            }

            override fun onClosed(eventSource: EventSource) { close() }
        }

        val eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildMessagesJson(history: List<ChatHistoryEntry>): JSONArray {
        val array = JSONArray()
        history.forEach { entry ->
            val msgObj = JSONObject().put("role", entry.role)
            if (entry.imagePath != null && entry.role == "user") {
                // Multimodal content block
                val contentArr = JSONArray()
                contentArr.put(JSONObject().put("type", "text").put("text", entry.content))
                val imgFile = File(entry.imagePath)
                if (imgFile.exists()) {
                    val b64 = Base64.encodeToString(imgFile.readBytes(), Base64.NO_WRAP)
                    val mimeType = when (imgFile.extension.lowercase()) {
                        "png"  -> "image/png"
                        "webp" -> "image/webp"
                        else   -> "image/jpeg"
                    }
                    contentArr.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:$mimeType;base64,$b64"))
                    )
                }
                msgObj.put("content", contentArr)
            } else {
                msgObj.put("content", entry.content)
            }
            array.put(msgObj)
        }
        return array
    }

    private fun requireApiKey(): String {
        val k = apiKeyProvider()
        if (k.isBlank()) throw AppException.Api("NVIDIA API key is missing. Add it in Settings.")
        return k
    }

    private companion object {
        const val BASE_URL = "https://integrate.api.nvidia.com"
    }
}
