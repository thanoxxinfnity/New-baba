package com.trellis.studio.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Pollinations' own TRELLIS-based 3D endpoint (gen.pollinations.ai/3d) — a single
 * synchronous GET that returns the GLB directly, no polling. Supports both
 * image-to-3D (upload a reference photo first) and pure text-to-3D. Uses a free
 * weekly Pollen credit grant; get a key at enter.pollinations.ai/keys.
 */
class PollinationsTrellisRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String
) : Model3DRepository {

    override val supportsTextTo3d: Boolean = true

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    override suspend fun generateModel(imageFile: File, onStatus: (String) -> Unit): File {
        val apiKey = requireApiKey()
        onStatus("Uploading image…")
        val imageUrl = uploadImage(imageFile, apiKey)
        onStatus("Generating 3D model… this can take 30–90 seconds.")
        return downloadGlb(
            prompt = "3D asset generated from the reference photo",
            imageUrl = imageUrl,
            apiKey = apiKey
        )
    }

    override suspend fun generateFromPrompt(prompt: String, onStatus: (String) -> Unit): File {
        val apiKey = requireApiKey()
        onStatus("Generating 3D model from your description… this can take 30–90 seconds.")
        return downloadGlb(prompt = prompt, imageUrl = null, apiKey = apiKey)
    }

    private fun requireApiKey(): String {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw AppException.Api("Pollinations API key is missing. Add your key in the Settings tab.")
        }
        return apiKey
    }

    /** Uploads [imageFile] to Pollinations' media store and returns its public URL. */
    private fun uploadImage(imageFile: File, apiKey: String): String {
        val mime = if (imageFile.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://media.pollinations.ai/upload")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw pollinationsError(response.code, text)
            val url = runCatching { JSONObject(text).optString("url") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: throw AppException.Api("Pollinations upload did not return a URL.")
            return url
        }
    }

    private fun downloadGlb(prompt: String, imageUrl: String?, apiKey: String): File {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val urlBuilder = StringBuilder("https://gen.pollinations.ai/3d/$encodedPrompt")
            .append("?model=trellis-2-low")
            .append("&seed=").append(Random.nextInt(0, 1_000_000))
        if (imageUrl != null) {
            urlBuilder.append("&image=").append(URLEncoder.encode(imageUrl, "UTF-8"))
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw pollinationsError(response.code, response.body?.string().orEmpty())
            }
            val bytes = response.body?.bytes()
                ?: throw AppException.Api("Pollinations returned an empty response.")
            val outFile = File(modelsDir, "model_${System.currentTimeMillis()}.glb")
            outFile.writeBytes(bytes)
            return outFile
        }
    }

    private fun pollinationsError(code: Int, rawBody: String): AppException.Api {
        val message = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return AppException.Api(
            when (code) {
                401 -> "The Pollinations API key was rejected. Check the key in Settings."
                402 -> "Not enough Pollen credit on this Pollinations key. Top up at enter.pollinations.ai."
                422 -> message ?: "Pollinations rejected the request (invalid input or content policy)."
                else -> message ?: "Pollinations 3D generation failed (HTTP $code)."
            }
        )
    }
}
