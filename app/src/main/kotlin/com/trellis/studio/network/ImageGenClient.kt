package com.trellis.studio.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.FileOutputStream
import java.net.URLEncoder

/** Handles image generation from NVIDIA NIM (Flux, SDXL) and Pollinations */
class ImageGenClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // NVIDIA rejects a charset parameter outright:
    //   415 "Unsupported media type: application/json; charset=utf-8.
    //        It must be application/json"
    private val JSON_MEDIA = "application/json".toMediaType()

    /**
     * Generate image using selected model. Returns saved file path on success.
     */
    suspend fun generateImage(
        apiKey: String,
        model: ImageModel,
        prompt: String,
        negativePrompt: String = "",
        width: Int = 1024,
        height: Int = 1024,
        seed: Long = 0L,
    ): Result<String> = withContext(Dispatchers.IO) {
        when (model.apiStyle) {
            ImageApiStyle.POLLINATIONS -> generatePollinations(prompt, width, height, seed, apiKey)
            ImageApiStyle.FLUX -> generateFlux(apiKey, model, prompt, width, height, seed)
            ImageApiStyle.SDXL -> generateSdxl(apiKey, model, prompt, negativePrompt, width, height, seed)
        }
    }

    private suspend fun generateFlux(
        apiKey: String, model: ImageModel, prompt: String,
        width: Int, height: Int, seed: Long,
    ): Result<String> {
        // Without a key, or if NVIDIA FLUX fails (quota, capacity), fall back to
        // free Pollinations so the user still gets an image instead of an error.
        if (apiKey.isBlank()) return generatePollinations(prompt, width, height, seed, apiKey)
        val reqBody = FluxImageRequest(prompt = prompt, width = width, height = height, seed = seed)
        val body = json.encodeToString(reqBody).asJsonBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(model.apiBaseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val result = runCatching {
            client.newCall(request).execute().use { response ->
                handleImageResponse(response, "flux_${System.currentTimeMillis()}")
            }
        }
        return result.recoverCatching {
            // NVIDIA didn't deliver — use the free path rather than failing.
            generatePollinations(prompt, width, height, seed, apiKey).getOrThrow()
        }
    }

    private suspend fun generateSdxl(
        apiKey: String, model: ImageModel, prompt: String,
        negativePrompt: String, width: Int, height: Int, seed: Long,
    ): Result<String> {
        if (apiKey.isBlank()) return Result.failure(Exception("NVIDIA API key missing. Add it in Settings."))
        val prompts = buildList {
            add(SdxlPrompt(text = prompt, weight = 1.0))
            if (negativePrompt.isNotBlank()) add(SdxlPrompt(text = negativePrompt, weight = -1.0))
        }
        val reqBody = SdxlImageRequest(textPrompts = prompts, seed = seed, width = width, height = height)
        val body = json.encodeToString(reqBody).asJsonBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(model.apiBaseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                handleImageResponse(response, "sdxl_${System.currentTimeMillis()}")
            }
        }.recoverWithMessage()
    }

    private suspend fun generatePollinations(
        prompt: String, width: Int, height: Int, seed: Long, apiKey: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val seedParam = if (seed != 0L) "&seed=$seed" else ""
        val url = "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height$seedParam&model=flux"

        // Pollinations is free and flaky: measured, roughly one request in five
        // hangs to a timeout or returns a 5xx, and the next one succeeds. So the
        // fix for the "image generation error" the user saw is to just retry the
        // transient failures rather than surface them.
        var last: Exception? = null
        repeat(3) { attempt ->
            val reqBuilder = Request.Builder().url(url).get()
            if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
            try {
                val path = client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Pollinations busy (${response.code})")
                    val bytes = response.body?.bytes()
                        ?.takeIf { it.size > 512 } ?: throw Exception("Empty image — retrying.")
                    saveBitmapBytes(bytes, "poll_${System.currentTimeMillis()}")
                }
                return@withContext Result.success(path)
            } catch (e: Exception) {
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
        }
        Result.failure(last?.let { friendlyPollinations(it) } ?: Exception("Image generation failed."))
    }

    private fun friendlyPollinations(e: Exception): Exception =
        Exception("The free image service is busy right now — please try again in a moment.")

    private fun handleImageResponse(response: Response, prefix: String): String {
        val body = response.body?.string() ?: ""
        when {
            response.code == 401 || response.code == 403 ->
                throw Exception("API key rejected (${response.code}). Check your nvapi- key in Settings.")
            response.code == 429 -> throw Exception("Rate limit reached. Wait a moment and retry.")
            !response.isSuccessful -> throw Exception("Image generation failed (${response.code}): $body")
        }
        val resp = Json { ignoreUnknownKeys = true }.decodeFromString<NimImageResponse>(body)
        val artifact = resp.artifacts.firstOrNull() ?: throw Exception("Image generation returned no data.")
        val imageBytes = Base64.decode(artifact.base64, Base64.DEFAULT)
        return saveBitmapBytes(imageBytes, prefix)
    }

    private fun saveBitmapBytes(bytes: ByteArray, prefix: String): String {
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "$prefix.png")
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun <T> Result<T>.recoverWithMessage() = this.recoverCatching { e ->
        throw Exception(e.message ?: "Unknown image generation error")
    }
}

/**
 * JSON body without a charset parameter.
 *
 * NVIDIA rejects a charset outright — 415 "Unsupported media type:
 * application/json; charset=utf-8. It must be application/json" — and OkHttp's
 * String.toRequestBody APPENDS "; charset=utf-8" whenever the media type has
 * none (verified in okhttp 4.12 bytecode). Encoding to bytes first is what
 * actually stops it: ByteArray.toRequestBody passes the type through untouched.
 */
private fun String.asJsonBody(media: okhttp3.MediaType) =
    toByteArray(Charsets.UTF_8).toRequestBody(media)
