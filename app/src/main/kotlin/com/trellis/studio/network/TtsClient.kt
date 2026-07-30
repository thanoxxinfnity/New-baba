package com.trellis.studio.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/** Handles NVIDIA NIM Text-to-Speech generation */
class TtsClient(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val TTS_URL = "https://integrate.api.nvidia.com/v1/audio/speech"
    private val JSON_MEDIA = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Synthesize speech from text using NVIDIA NIM TTS.
     * Returns path to saved audio file.
     */
    suspend fun synthesize(
        apiKey: String,
        model: String,
        text: String,
        voice: String = "nova",
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(
            Exception("NVIDIA API key missing. Add it in Settings.")
        )
        if (text.isBlank()) return@withContext Result.failure(Exception("Text input is empty."))

        runCatching {
            // Build JSON body safely
            val jsonBody = buildTtsJson(model = model, input = text, voice = voice)
            val body = jsonBody.toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(TTS_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: byteArrayOf()
                when {
                    resp.code == 404 -> throw Exception("TTS model not found (404). Select a different model.")
                    resp.code == 401 || resp.code == 403 ->
                        throw Exception("API key rejected. Check your nvapi- key in Settings.")
                    !resp.isSuccessful ->
                        throw Exception("TTS generation failed (HTTP ${resp.code})")
                    bytes.isEmpty() -> throw Exception("TTS returned empty audio data.")
                    else -> {
                        val dir = File(context.filesDir, "tts_audio").apply { mkdirs() }
                        val file = File(dir, "tts_${System.currentTimeMillis()}.mp3")
                        FileOutputStream(file).use { out -> out.write(bytes) }
                        file.absolutePath
                    }
                }
            }
        }.recoverCatching { e -> throw Exception(e.message ?: "TTS error") }
    }

    private fun buildTtsJson(model: String, input: String, voice: String): String {
        // Escape special characters for JSON
        val safeInput = input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"model":"$model","input":"$safeInput","voice":"$voice"}"""
    }
}
