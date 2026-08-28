package com.trellis.studio.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clones a voice on a self-hosted XTTS server (a free Kaggle/Colab GPU notebook),
 * which — unlike NVIDIA's English-only zero-shot — speaks the cloned voice in the
 * language you choose, so you get your own voice in an Indian/Hindi accent.
 *
 * The phone sends the sample and text; the GPU does the work and returns a WAV.
 * Contract (see the Kaggle script):
 *   POST /clone {audio_b64, text, language} -> {"audio_b64": "...(wav)"}
 */
class RemoteVoiceClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Speaks [text] in the voice of [sample], in [language] ("hi", "en", …). */
    suspend fun clone(
        baseUrl: String,
        sample: File,
        text: String,
        language: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "Add your voice server URL in Settings (from the Kaggle notebook)." }
            require(sample.exists() && sample.length() > 1024) { "The voice sample is missing." }

            val payload = buildJsonObject {
                put("audio_b64", Base64.encodeToString(sample.readBytes(), Base64.NO_WRAP))
                put("text", text)
                put("language", language)
            }.toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

            val req = Request.Builder().url("${baseUrl.trimEnd('/')}/clone").post(payload).build()
            val b64 = client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 502 || resp.code == 503 || resp.code == 504 ->
                        throw Exception("The voice server isn't responding. Is the Kaggle notebook still running?")
                    !resp.isSuccessful -> throw Exception(errorOf(body, resp.code))
                    else -> json.parseToJsonElement(body).jsonObject["audio_b64"]?.jsonPrimitive?.content
                        ?: throw Exception("The server returned no audio.")
                }
            }
            Base64.decode(b64, Base64.DEFAULT)
        }
    }

    private fun errorOf(body: String, code: Int): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content ?: "Voice server error ($code)."
    }.getOrDefault("Voice server error ($code).")
}
