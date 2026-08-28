package com.trellis.studio.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * Image-to-image editing — change an existing picture from a written
 * instruction, the way Gemini's image editing works: "make it night", "add a
 * red hat", "turn the car blue".
 *
 * This runs on fal.ai's FLUX Kontext, which is the reachable model that actually
 * accepts an uploaded image and edits it. The free generators do not: NVIDIA's
 * Kontext only runs its own sample image, and Pollinations' Kontext is
 * enterprise-only — both were probed and both reject user photos. So real
 * editing needs a fal.ai key (it has free starting credits); the app already
 * stores one for image-to-3D.
 *
 * fal.run answers synchronously with the result's URL, which is then downloaded.
 */
class FalImageClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Edits [image] according to [prompt] and saves the result into [outputDir].
     */
    suspend fun edit(
        falKey: String,
        image: File,
        prompt: String,
        outputDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(falKey.isNotBlank()) {
                "Image editing needs a fal.ai key. Add it in Settings — fal.ai gives free starting credits."
            }
            require(image.exists() && image.length() > 0) { "The image to edit is missing." }
            require(prompt.isNotBlank()) { "Say what to change in the image." }
            outputDir.mkdirs()

            val dataUri = "data:${mimeOf(image)};base64," +
                Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)

            val body = buildJsonObject {
                put("prompt", prompt)
                put("image_url", dataUri)
                put("num_images", 1)
                put("output_format", "jpeg")
                // Keep the composition close to the original, as an edit should.
                put("guidance_scale", 3.5)
            }.toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Key $falKey")
                .header("Accept", "application/json")
                .post(body)
                .build()

            val resultUrl = client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        throw Exception("fal.ai key rejected. Check it in Settings.")
                    resp.code == 402 ->
                        throw Exception("fal.ai says this account is out of credits.")
                    resp.code == 422 ->
                        throw Exception("fal.ai rejected the request: ${detail(text)}")
                    !resp.isSuccessful ->
                        throw Exception("fal.ai error (${resp.code}). Try again.")
                    else -> firstImageUrl(text)
                        ?: throw Exception("fal.ai returned no image.")
                }
            }

            // fal returns a URL to the edited image; download and save it.
            val out = File(outputDir, "edit_${System.currentTimeMillis()}.jpg")
            val get = Request.Builder().url(resultUrl).get().build()
            client.newCall(get).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Couldn't download the edited image (${resp.code}).")
                val bytes = resp.body?.bytes() ?: throw Exception("The edited image was empty.")
                out.writeBytes(bytes)
            }
            out
        }
    }

    private fun firstImageUrl(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["images"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
    }.getOrNull()

    private fun detail(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["detail"]?.toString() ?: body.take(160)
    }.getOrDefault(body.take(160))

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private companion object {
        // FLUX Kontext [dev] — accepts an input image and edits it from a prompt.
        const val ENDPOINT = "https://fal.run/fal-ai/flux-kontext/dev"
    }
}
