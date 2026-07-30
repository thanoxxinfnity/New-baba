package com.trellis.studio.network

import com.trellis.studio.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Handles all NVIDIA NIM LLM (chat completions) API calls */
class NimClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val MODELS_URL = "https://integrate.api.nvidia.com/v1/models"

    /**
     * Send a chat message to NVIDIA NIM and get the full assistant response.
     * @return Result with assistant content string, or failure with error message.
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))

        val reqBody = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
        )
        val body = json.encodeToString(reqBody).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                when {
                    response.code == 401 || response.code == 403 ->
                        throw Exception("API key rejected (${response.code}). Check your nvapi- key in Settings.")
                    response.code == 429 ->
                        throw Exception("Rate limit reached. Wait a moment and retry.")
                    !response.isSuccessful ->
                        throw Exception("API error (${response.code}): $responseBody")
                    else -> {
                        val resp = json.decodeFromString<ChatResponse>(responseBody)
                        resp.choices.firstOrNull()?.message?.content
                            ?: throw Exception("API returned empty response.")
                    }
                }
            }
        }.mapFailure()
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
                // Parse model IDs from the JSON (simple extraction)
                val body = response.body?.string() ?: ""
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                    .map { it.groupValues[1] }.toList()
            }
        }.mapFailure()
    }
}

private fun <T> Result<T>.mapFailure(): Result<T> = this.recoverCatching { e ->
    throw Exception(e.message ?: "Unknown network error")
}
