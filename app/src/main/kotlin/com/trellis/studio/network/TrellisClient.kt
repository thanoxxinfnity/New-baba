package com.trellis.studio.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * NVIDIA TRELLIS image → 3D.
 *
 * Payload format was determined against the live API, not guessed:
 *  - `{"image":"<base64>"}`            → 422 "Expected: example_id, got: base64"
 *  - `{"image":"data:…;asset_id,<id>"}`→ 422 "Expected: example_id, got: asset_id"
 *  - `{"image":"data:image/png;example_id,0"}` → 200, returns a real GLB
 *
 * So the deployed function currently accepts only NVIDIA's bundled sample
 * image. User photos are rejected server-side regardless of how they are
 * uploaded, which [generateFromImage] reports honestly instead of failing.
 */
class TrellisClient(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private companion object {
        const val TRELLIS_URL = "https://ai.api.nvidia.com/v1/genai/microsoft/trellis"
        const val ASSETS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/assets"
        const val STATUS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status/"
    }

    /**
     * Text → 3D. Verified live: {"prompt":"a blue ceramic coffee mug"} returns a
     * real textured GLB. Any extra field (seed, mode, …) makes the service 500,
     * so the body deliberately carries nothing else.
     */
    suspend fun generateFromText(apiKey: String, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
            }
            if (prompt.isBlank()) {
                return@withContext Result.failure(Exception("Describe what to build first."))
            }
            val body = buildJsonObject { put("prompt", prompt.trim()) }.toString()
            // The endpoint cold-starts and 500s for the first call fairly often,
            // so a couple of retries is the difference between working and not.
            // Measured: the service 500s on roughly half of first attempts but
            // every tested prompt (sword, robot, car, tree, head) succeeded within
            // five tries, so retry generously with backoff.
            var last: Throwable? = null
            repeat(5) { attempt ->
                runCatching { invoke(apiKey, body, null) }
                    .onSuccess { return@withContext Result.success(it) }
                    .onFailure { e ->
                        last = e
                        if (attempt < 4) delay(5_000L * (attempt + 1))
                    }
            }
            Result.failure(
                Exception(
                    "NVIDIA's 3D service kept failing after 5 attempts — it's overloaded. " +
                        "Try again in a minute. (${last?.message ?: "no detail"})"
                )
            )
        }

    /** Runs the sample TRELLIS job, which is the only input this deployment accepts. */
    suspend fun generateSample(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
        }
        runCatching { invoke(apiKey, """{"image":"data:image/png;example_id,0"}""", null) }
    }

    /**
     * Attempts a user image. Uploads it as an NVCF asset first, and if the
     * service rejects user input, says so plainly.
     */
    suspend fun generateFromImage(
        apiKey: String,
        imageBytes: ByteArray,
        mime: String = "image/png",
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
        }
        runCatching {
            val assetId = uploadAsset(apiKey, imageBytes, mime)
            invoke(apiKey, """{"image":"data:$mime;asset_id,$assetId"}""", assetId)
        }.recoverCatching { e ->
            val msg = e.message.orEmpty()
            if (msg.contains("example_id", ignoreCase = true)) {
                throw Exception(
                    "NVIDIA's TRELLIS endpoint only accepts its own sample image on this " +
                        "account — it rejects uploaded photos. Use \"Generate sample 3D\" to " +
                        "get a real model, or add a fal.ai key for image-to-3D."
                )
            }
            throw e
        }
    }

    /** Reserves an NVCF asset slot and PUTs the bytes to the returned S3 URL. */
    private fun uploadAsset(apiKey: String, bytes: ByteArray, mime: String): String {
        val metaReq = Request.Builder()
            .url(ASSETS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("accept", "application/json")
            .post("""{"contentType":"$mime","description":"trellis-input"}""".toRequestBody(JSON_MEDIA))
            .build()

        val (assetId, uploadUrl) = http.newCall(metaReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("Could not reserve an upload slot (${resp.code}).")
            val obj = json.parseToJsonElement(body).jsonObject
            val id = obj["assetId"]?.jsonPrimitive?.content
                ?: throw Exception("Upload slot response had no assetId.")
            val url = obj["uploadUrl"]?.jsonPrimitive?.content
                ?: throw Exception("Upload slot response had no uploadUrl.")
            id to url
        }

        val putReq = Request.Builder()
            .url(uploadUrl)
            .header("Content-Type", mime)
            .header("x-amz-meta-nvcf-asset-description", "trellis-input")
            .put(bytes.toRequestBody(mime.toMediaType()))
            .build()
        http.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Image upload failed (${resp.code}).")
        }
        return assetId
    }

    /** POSTs to TRELLIS, follows a 202 poll, and saves the resulting GLB. */
    private suspend fun invoke(apiKey: String, bodyJson: String, assetId: String?): String {
        val builder = Request.Builder()
            .url(TRELLIS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("NVCF-POLL-SECONDS", "300")
        if (assetId != null) builder.header("NVCF-INPUT-ASSET-REFERENCES", assetId)

        val request = builder.post(bodyJson.toRequestBody(JSON_MEDIA)).build()

        val payload = http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw Exception("NVIDIA API key rejected. Check it in Settings.")
                resp.code == 202 -> {
                    val reqId = resp.header("NVCF-REQID")
                        ?: throw Exception("TRELLIS accepted the job but returned no request ID.")
                    pollStatus(apiKey, reqId)
                }
                resp.isSuccessful -> body
                resp.code == 422 -> throw Exception(extractDetail(body) ?: "TRELLIS rejected the input.")
                resp.code >= 500 -> throw Exception("TRELLIS failed on the server (${resp.code}). Try again.")
                else -> throw Exception("3D generation failed (${resp.code}): ${extractDetail(body) ?: body.take(160)}")
            }
        }

        val glbBytes = extractGlb(payload)
            ?: throw Exception("The response contained no 3D model.")
        return saveGlb(glbBytes)
    }

    private suspend fun pollStatus(apiKey: String, reqId: String): String {
        repeat(60) {
            delay(5_000)
            val req = Request.Builder()
                .url("$STATUS_URL$reqId")
                .header("Authorization", "Bearer $apiKey")
                .get().build()
            val done = http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 202 -> null                       // still running
                    resp.isSuccessful -> body
                    else -> throw Exception("3D job failed (${resp.code}).")
                }
            }
            if (done != null) return done
        }
        throw Exception("3D generation timed out. Try again.")
    }

    /** Pulls the GLB out of either an artifacts[].base64 payload or a URL. */
    private fun extractGlb(body: String): ByteArray? {
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val b64 = root["artifacts"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("base64")?.jsonPrimitive?.content
            if (!b64.isNullOrBlank()) {
                return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
        // Fallback: a direct .glb link
        Regex("""https://[^"\s]+\.glb""").find(body)?.value?.let { url ->
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return resp.body?.bytes()
            }
        }
        return null
    }

    private fun saveGlb(bytes: ByteArray): String {
        if (bytes.size < 20 || String(bytes, 0, 4) != "glTF") {
            throw Exception("The downloaded file is not a valid GLB model.")
        }
        val dir = File(context.filesDir, "models3d").apply { mkdirs() }
        val file = File(dir, "trellis_${System.currentTimeMillis()}.glb")
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun extractDetail(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)
            ?.get("detail")?.jsonPrimitive?.content
    }.getOrNull()
}
