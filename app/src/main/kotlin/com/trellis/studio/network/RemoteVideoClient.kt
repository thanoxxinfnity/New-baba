package com.trellis.studio.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to a video-generation server the user runs themselves — a free Kaggle
 * (or Colab) GPU notebook exposing a public URL. This is how VOID gets real
 * motion video for free: the heavy model runs on the borrowed GPU, the phone
 * just sends a prompt and saves the .mp4 that comes back.
 *
 * The server contract (see the Kaggle script):
 *   GET  /health   -> {"ok":true,"ready":true,...}
 *   POST /generate {mode,prompt,seconds,image_b64?} -> {"video_b64":"...(mp4)"}
 */
class RemoteVideoClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Generation on a T4 can take a couple of minutes, so read waits long.
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Quick reachability + readiness check, so the UI can tell if the URL is live. */
    suspend fun health(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${clean(baseUrl)}/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body?.string().orEmpty()
                json.parseToJsonElement(body).jsonObject["ready"]?.jsonPrimitive?.content == "true"
            }
        }
    }

    /**
     * Generates a clip. [mode] is "t2v" or "i2v"; for i2v pass [imageBytes].
     * Saves the returned mp4 into [outputDir].
     */
    suspend fun generate(
        baseUrl: String,
        mode: String,
        prompt: String,
        seconds: Int,
        outputDir: File,
        imageBytes: ByteArray? = null,
        seed: Long = 0L,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) {
                "Add your video server URL in Settings (from the Kaggle notebook)."
            }
            outputDir.mkdirs()

            val payload = buildJsonObject {
                put("mode", mode)
                put("prompt", prompt)
                put("seconds", seconds)
                put("seed", seed)
                if (mode == "i2v" && imageBytes != null) {
                    put("image_b64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                }
            }.toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

            val req = Request.Builder()
                .url("${clean(baseUrl)}/generate")
                .post(payload)
                .build()

            val b64 = client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 507 -> throw Exception("The GPU ran out of memory — try fewer seconds.")
                    resp.code == 502 || resp.code == 503 || resp.code == 504 ->
                        throw Exception("The video server isn't responding. Is the Kaggle notebook still running?")
                    !resp.isSuccessful -> throw Exception(errorOf(text, resp.code))
                    else -> json.parseToJsonElement(text).jsonObject["video_b64"]
                        ?.jsonPrimitive?.content
                        ?: throw Exception("The server returned no video.")
                }
            }

            val out = File(outputDir, "motion_${System.currentTimeMillis()}.mp4")
            out.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            out
        }
    }

    private fun errorOf(body: String, code: Int): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
            ?: "Video server error ($code)."
    }.getOrDefault("Video server error ($code).")

    private fun clean(url: String) = url.trim().trimEnd('/')
}
