package com.trellis.studio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * fal.ai's hosted TRELLIS model (https://fal.ai/models/fal-ai/trellis) — a paid,
 * pay-per-use image-to-3D API that (unlike NVIDIA's free preview) accepts real
 * uploaded photos. Flow: upload the image to fal's storage, submit a queue job
 * referencing its URL, poll until complete, then download the resulting GLB.
 */
class FalRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    /** Resolves the current fal.ai key, e.g. "key_id:key_secret". */
    private val apiKeyProvider: () -> String
) : Model3DRepository {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    override suspend fun generateModel(
        imageFile: File,
        onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw AppException.Api("fal.ai API key is missing. Add your key in the Settings tab.")
        }
        val authHeader = "Key $apiKey"

        onStatus("Uploading image…")
        val imageUrl = uploadImage(imageFile, authHeader)

        onStatus("Queuing 3D generation…")
        val submitBody = JSONObject().put("image_url", imageUrl).toString()
            .toRequestBody("application/json".toMediaType())
        val submitJson = requestJson(
            Request.Builder()
                .url("https://queue.fal.run/fal-ai/trellis")
                .header("Authorization", authHeader)
                .post(submitBody)
                .build(),
            "queue submit"
        )
        val statusUrl = submitJson.optString("status_url").takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai did not return a status URL.")
        val responseUrl = submitJson.optString("response_url").takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai did not return a response URL.")

        onStatus("Generating 3D model… this can take 30–90 seconds.")
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            val statusJson = requestJson(
                Request.Builder().url(statusUrl).header("Authorization", authHeader).get().build(),
                "queue status"
            )
            when (statusJson.optString("status")) {
                "COMPLETED" -> break
                "IN_QUEUE", "IN_PROGRESS" -> Unit
                else -> throw AppException.Api(
                    "fal.ai generation failed: ${statusJson.optString("error", "unknown error")}"
                )
            }
            if (System.currentTimeMillis() > deadline) {
                throw AppException.Api("3D generation timed out. Please try again.")
            }
            delay(3_000)
        }

        onStatus("Downloading 3D model…")
        val resultJson = requestJson(
            Request.Builder().url(responseUrl).header("Authorization", authHeader).get().build(),
            "queue result"
        )
        val meshUrl = resultJson.optJSONObject("model_mesh")?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai response did not include a model_mesh URL.")

        val glbBytes = okHttpClient.newCall(
            Request.Builder().url(meshUrl).header("Authorization", authHeader).get().build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppException.Api("Could not download the generated model (HTTP ${response.code}).")
            }
            response.body?.bytes() ?: throw AppException.Api("Downloaded model was empty.")
        }

        val outFile = File(modelsDir, "model_${System.currentTimeMillis()}.glb")
        outFile.writeBytes(glbBytes)
        outFile
    }

    /** Uploads [imageFile] to fal's storage and returns its public access URL. */
    private fun uploadImage(imageFile: File, authHeader: String): String {
        val tokenJson = requestJson(
            Request.Builder()
                .url("https://rest.alpha.fal.ai/storage/auth/token?storage_type=fal-cdn-v3")
                .header("Authorization", authHeader)
                .post("".toRequestBody(null))
                .build(),
            "storage auth"
        )
        val token = tokenJson.optString("token").takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai did not return a storage token.")
        val baseUrl = tokenJson.optString("base_url").takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai did not return a storage base URL.")

        val bytes = imageFile.readBytes()
        val mime = if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            "image/png"
        } else {
            "image/jpeg"
        }
        val uploadJson = requestJson(
            Request.Builder()
                .url("$baseUrl/files/upload")
                .header("Authorization", "Bearer $token")
                .post(bytes.toRequestBody(mime.toMediaType()))
                .build(),
            "storage upload"
        )
        return uploadJson.optString("access_url").takeIf { it.isNotBlank() }
            ?: throw AppException.Api("fal.ai did not return an access URL for the uploaded image.")
    }

    private fun requestJson(request: Request, step: String): JSONObject {
        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200)
                throw AppException.Api(
                    when (response.code) {
                        401, 403 -> "The fal.ai API key was rejected. Check the key in Settings."
                        429 -> "fal.ai rate limit reached. Wait a moment and retry."
                        else -> "fal.ai $step failed (HTTP ${response.code}). $detail"
                    }
                )
            }
            return runCatching { JSONObject(text) }.getOrElse {
                throw AppException.Api("fal.ai $step returned an unexpected response.")
            }
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
