package com.trellis.studio.network

import android.util.Base64
import com.trellis.studio.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Handles all NVIDIA NIM LLM (chat completions) API calls */
class NimClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val MODELS_URL = "https://integrate.api.nvidia.com/v1/models"

    /**
     * Send a chat conversation to NVIDIA NIM and get the assistant reply.
     *
     * Handles the two shapes NVIDIA actually returns:
     *  - normal models: `content` is a string
     *  - reasoning models: `content` may be null, with the text in `reasoning_content`
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        isVisionModel: Boolean = false,
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
        }
        if (turns.none { it.role == "user" }) {
            return@withContext Result.failure(Exception("Nothing to send."))
        }

        val apiMessages = turns.map { turn ->
            val dataUrl = if (isVisionModel) turn.imagePath?.let { encodeImage(it) } else null
            if (dataUrl != null) visionMessage(turn.text, dataUrl)
            else textMessage(turn.role, turn.text)
        }

        val reqBody = ChatRequest(
            model = model,
            messages = apiMessages,
            maxTokens = maxTokens.coerceIn(64, 32768),
            temperature = temperature.coerceIn(0.0, 2.0),
        )
        val body = json.encodeToString(reqBody).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw Exception("API key rejected (${response.code}). Check your nvapi- key in Settings.")

                    response.code == 404 ->
                        throw Exception("Model \"$model\" is not enabled for your NVIDIA account. Pick another model.")

                    response.code == 429 ->
                        throw Exception("Rate limit reached. Wait a moment and retry.")

                    // 529 = NVIDIA capacity; 502/503/504 = upstream hiccup. Both are
                    // transient and say nothing about the request being wrong.
                    response.code == 529 || response.code == 503 ->
                        throw Exception("This model is overloaded right now. Try again or pick another model.")

                    response.code == 502 || response.code == 504 ->
                        throw Exception("NVIDIA's server didn't respond. Try again in a moment.")

                    !response.isSuccessful ->
                        throw Exception(friendlyError(response.code, raw))

                    else -> parseReply(raw)
                }
            }
        }.mapFailure()
    }

    /**
     * Streaming chat. Emits reasoning tokens and answer tokens as they arrive so
     * the UI can show the model thinking live.
     *
     * Verified against the live API: reasoning models stream `delta.reasoning_content`
     * first, then `delta.content` once they start answering.
     */
    suspend fun chatStream(
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        isVisionModel: Boolean = false,
        onReasoning: suspend (String) -> Unit = {},
        onContent: suspend (String) -> Unit = {},
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
        }

        val apiMessages = turns.map { turn ->
            val dataUrl = if (isVisionModel) turn.imagePath?.let { encodeImage(it) } else null
            if (dataUrl != null) visionMessage(turn.text, dataUrl)
            else textMessage(turn.role, turn.text)
        }

        val reqBody = ChatRequest(
            model = model,
            messages = apiMessages,
            maxTokens = maxTokens.coerceIn(64, 32768),
            temperature = temperature.coerceIn(0.0, 2.0),
            stream = true,
        )
        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(reqBody).toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    throw Exception(
                        when (response.code) {
                            401, 403 -> "API key rejected (${response.code}). Check your nvapi- key in Settings."
                            404 -> "Model \"$model\" is not enabled for your NVIDIA account. Pick another model."
                            429 -> "Rate limit reached. Wait a moment and retry."
                            503, 529 -> "This model is overloaded right now. Try again or pick another model."
                            502, 504 -> "NVIDIA's server didn't respond. Try again in a moment."
                            else -> friendlyError(response.code, raw)
                        }
                    )
                }

                val answer = StringBuilder()
                val thoughts = StringBuilder()
                var finish: String? = null

                val source = response.body?.source()
                    ?: throw Exception("The server sent an empty stream.")

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break

                    val chunk = runCatching { json.decodeFromString<ChatResponse>(payload) }.getOrNull()
                        ?: continue
                    val choice = chunk.choices.firstOrNull() ?: continue
                    choice.finishReason?.let { finish = it }

                    val delta = choice.delta ?: choice.message ?: continue

                    (delta.reasoningContent ?: delta.reasoning)?.takeIf { it.isNotEmpty() }?.let {
                        thoughts.append(it)
                        onReasoning(it)
                    }
                    delta.content?.takeIf { it.isNotEmpty() }?.let {
                        answer.append(it)
                        onContent(it)
                    }
                }

                val content = answer.toString().trim()
                val reasoning = thoughts.toString().trim().takeIf { it.isNotEmpty() }

                if (content.isEmpty()) {
                    if (finish == "length") {
                        val hint = "The model ran out of tokens while thinking. " +
                            "Raise Max Tokens in Settings, or pick a non-reasoning model."
                        if (reasoning != null) return@use ChatResult(hint, reasoning)
                        throw Exception(hint)
                    }
                    if (reasoning != null) return@use ChatResult(reasoning, null)
                    throw Exception("The model returned an empty reply. Try again or switch model.")
                }
                ChatResult(content, reasoning)
            }
        }.mapFailure()
    }

    /** Turns the API JSON into a usable reply, tolerating null content. */
    private fun parseReply(raw: String): ChatResult {
        val resp = runCatching { json.decodeFromString<ChatResponse>(raw) }.getOrNull()
            ?: throw Exception("Could not read the model's response. Try again or switch model.")

        val choice = resp.choices.firstOrNull()
            ?: throw Exception("The model returned no reply. Try again.")

        val msg = choice.message ?: choice.delta
        val content = msg?.content?.trim().orEmpty()
        val reasoning = (msg?.reasoningContent ?: msg?.reasoning)?.trim()?.takeIf { it.isNotEmpty() }

        // Reasoning models cut off mid-thought return content=null with finish_reason=length.
        if (content.isEmpty()) {
            if (choice.finishReason == "length") {
                val hint = "The model ran out of tokens while thinking. " +
                    "Raise Max Tokens in Settings, or pick a non-reasoning model."
                return if (reasoning != null) ChatResult(hint, reasoning) else throw Exception(hint)
            }
            // Some models put the whole answer in reasoning_content only.
            if (reasoning != null) return ChatResult(reasoning, null)
            throw Exception("The model returned an empty reply. Try again or switch model.")
        }

        return ChatResult(content, reasoning)
    }

    /** Extracts NVIDIA's human-readable error text instead of dumping raw JSON. */
    private fun friendlyError(code: Int, raw: String): String {
        val parsed = runCatching { json.decodeFromString<NimError>(raw) }.getOrNull()
        val detail = parsed?.error?.message
            ?: parsed?.detail
            ?: parsed?.message
            ?: parsed?.title
            ?: raw.take(200).ifBlank { "no details" }
        return when {
            detail.contains("context", true) || detail.contains("token", true) ->
                "Message too long for this model's context window. Start a new chat or pick a bigger model."
            else -> "API error ($code): $detail"
        }
    }

    /** Reads an image off disk and returns a data: URL for the vision content array. */
    private fun encodeImage(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        // NVIDIA caps inline images; ~180KB of base64 keeps us well inside the limit.
        if (file.length() > 900_000) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mime = if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
        return "data:$mime;base64,$b64"
    }

    /** Fetch available models from NVIDIA NIM (for verification) */
    suspend fun fetchAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("API key missing"))
        val request = Request.Builder()
            .url(MODELS_URL)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                    .map { it.groupValues[1] }.toList()
            }
        }.mapFailure()
    }
}

private fun <T> Result<T>.mapFailure(): Result<T> = this.recoverCatching { e ->
    throw Exception(
        when (e) {
            is java.net.UnknownHostException -> "No internet connection."
            is java.net.SocketTimeoutException -> "The model took too long to respond. Try again or pick a faster model."
            else -> e.message ?: "Unknown network error"
        }
    )
}
