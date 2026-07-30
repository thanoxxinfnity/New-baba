package com.trellis.studio.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/** Handles NVIDIA TRELLIS and fal.ai 3D generation */
class TrellisClient(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json".toMediaType()
    private val NVIDIA_TRELLIS_URL = "https://ai.api.nvidia.com/v1/genai/microsoft/trellis"
    private val NVCF_STATUS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status/"
    private val FAL_TOKEN_URL = "https://rest.alpha.fal.ai/storage/auth/token?storage_type=fal-cdn-v3"

    /**
     * Generate 3D GLB model from an image using NVIDIA TRELLIS.
     * Returns path to downloaded GLB file on success.
     */
    suspend fun generateWithNvidia(apiKey: String, imageBytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(
                Exception("NVIDIA API key is missing. Add it in Settings.")
            )
            runCatching {
                // Upload image as base64 JSON
                val b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                val jsonBody = """{"image":"data:image/png;base64,$b64"}"""
                val body = jsonBody.toRequestBody(JSON_MEDIA)
                val req = Request.Builder()
                    .url(NVIDIA_TRELLIS_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("NVCF-POLL-SECONDS", "300")
                    .post(body)
                    .build()

                var glbUrl: String? = null
                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    when {
                        resp.code == 401 || resp.code == 403 ->
                            throw Exception("NVIDIA API key rejected. Check key in Settings.")
                        resp.code == 202 -> {
                            // Async job - poll for result
                            val reqId = resp.header("NVCF-REQID")
                                ?: throw Exception("TRELLIS did not return a request ID.")
                            glbUrl = pollNvcfStatus(apiKey, reqId)
                        }
                        resp.isSuccessful -> {
                            glbUrl = extractGlbUrl(respBody)
                        }
                        else -> throw Exception("3D generation failed (${resp.code}): $respBody")
                    }
                }
                val url = glbUrl ?: throw Exception("Could not find a GLB model in the API response.")
                downloadGlb(url, "nvidia_${System.currentTimeMillis()}")
            }.recoverCatching { e -> throw Exception(e.message ?: "TRELLIS error") }
        }

    private suspend fun pollNvcfStatus(apiKey: String, reqId: String): String {
        val url = "$NVCF_STATUS_URL$reqId"
        repeat(60) {
            delay(5_000)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use
                if (resp.isSuccessful) {
                    val glbUrl = extractGlbUrl(body)
                    if (glbUrl != null) return glbUrl
                }
            }
        }
        throw Exception("3D generation timed out. Please try again.")
    }

    private fun extractGlbUrl(body: String): String? {
        return Regex("""https://[^"\s]+\.glb""").find(body)?.value
    }

    private fun downloadGlb(url: String, prefix: String): String {
        val dir = File(context.filesDir, "models3d").apply { mkdirs() }
        val file = File(dir, "$prefix.glb")
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Could not download the generated model (HTTP ${resp.code})")
            val bytes = resp.body?.bytes() ?: throw Exception("Downloaded model was empty.")
            FileOutputStream(file).use { it.write(bytes) }
        }
        return file.absolutePath
    }
}
