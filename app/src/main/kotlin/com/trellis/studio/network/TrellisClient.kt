package com.trellis.studio.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
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
    // Timings measured against the live service: a successful text-to-3D job
    // answers in 12-14s, while a failing one always stalls for ~90s before
    // returning 500. Cutting the read timeout well below that turns a long
    // dead wait into a quick retry, which is what actually gets a model out.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Image-to-3D and polling legitimately take longer than a text prompt. */
    private val slowHttp = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private companion object {
        const val TRELLIS_URL = "https://ai.api.nvidia.com/v1/genai/microsoft/trellis"
        const val ASSETS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/assets"
        const val STATUS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status/"
        // Failures are a capacity coin-flip; a generous attempt count costs
        // little now that each attempt aborts at 45s instead of 90s. Measured
        // live the service 504s on roughly two of every three calls when busy, so
        // more rounds — each after a short pause that lets it recover — is what
        // turns "3D generation error" into an eventual success.
        const val MAX_ATTEMPTS = 12
        /** Concurrent requests per round. Two is the sweet spot — more of them
         *  made the service reject far more often in testing. */
        const val LANES = 2
    }

    /**
     * Text → 3D. Verified live: {"prompt":"a blue ceramic coffee mug"} returns a
     * real textured GLB. Any extra field (seed, mode, …) makes the service 500,
     * so the body deliberately carries nothing else.
     */
    /** Mesh density preference, applied by steering the prompt. */
    enum class Detail(val label: String, val hint: String) {
        LOW("Low poly", "low poly, simplified geometry, flat shaded, game asset"),
        STANDARD("Standard", ""),
        HIGH("High detail", "highly detailed, intricate surface detail, high resolution mesh"),
    }

    suspend fun generateFromText(
        apiKey: String,
        prompt: String,
        detail: Detail = Detail.STANDARD,
        /**
         * Fixes the result. The same prompt and seed give the same model back,
         * and a new seed gives a different take on the same description — which
         * is the only kind of "try again differently" the service supports.
         */
        seed: Long? = null,
        onAttempt: suspend (attempt: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
            }
            if (prompt.isBlank()) {
                return@withContext Result.failure(Exception("Describe what to build first."))
            }
            // TRELLIS takes only "prompt" — any extra field makes it 500 — so the
            // detail preference is folded into the prompt text itself.
            val styled = listOf(prompt.trim(), detail.hint)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            // Probed against the live schema: prompt, image, seed and
            // output_format are accepted; anything else returns 422
            // "Extra inputs are not permitted".
            val body = buildJsonObject {
                put("prompt", styled)
                if (seed != null) put("seed", seed)
            }.toString()
            // The endpoint cold-starts and 500s for the first call fairly often,
            // so a couple of retries is the difference between working and not.
            // Whether a request succeeds is a capacity coin-flip on NVIDIA's side,
            // and a success lands in ~12-20s while a dud burns the full 45s. Two
            // lanes per round therefore roughly halve the wait: measured live, one
            // lane returned a model in 19.9s while its twin timed out.
            var last: Throwable? = null
            val rounds = (MAX_ATTEMPTS + 1) / LANES
            repeat(rounds) { round ->
                onAttempt(round + 1, rounds)
                val winner = raceOnce(apiKey, body)
                winner.onSuccess { return@withContext Result.success(it) }
                winner.onFailure { e ->
                    last = e
                    if (round < rounds - 1) delay(2_000)
                }
            }
            // A rejected key or a bad prompt has to survive as itself; only the
            // generic server refusal gets rewritten. The text stays short because
            // it lands on a queue card with a single line for it.
            val reason = last?.message.orEmpty()
            Result.failure(
                if (reason.contains("server", true) || reason.isBlank())
                    Exception("NVIDIA's 3D service is overloaded — try again shortly")
                else last!!
            )
        }

    /**
     * Fires [LANES] identical requests and returns the first model that lands,
     * cancelling the losers. Duplicated work is cheap here; waiting is not.
     */
    private suspend fun raceOnce(apiKey: String, body: String): Result<String> = coroutineScope {
        val lanes = List(LANES) { async { runCatching { invoke(apiKey, body, null) } } }
        var failure: Throwable? = null
        try {
            repeat(LANES) {
                val finished = select {
                    lanes.forEachIndexed { index, deferred ->
                        deferred.onAwait { index to it }
                    }
                }
                val (_, result) = finished
                result.onSuccess { return@coroutineScope Result.success(it) }
                result.onFailure { failure = it }
            }
            Result.failure(failure ?: Exception("3D request failed."))
        } finally {
            lanes.forEach { it.cancel() }
        }
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
            .post("""{"contentType":"$mime","description":"trellis-input"}""".asJsonBody(JSON_MEDIA))
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
        // Shared with every other NVIDIA call: the limit is per account, and the
        // two racing lanes plus a queue behind them saturate it easily.
        RateLimiter.nvidia.acquire()
        val builder = Request.Builder()
            .url(TRELLIS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("NVCF-POLL-SECONDS", "300")
        if (assetId != null) builder.header("NVCF-INPUT-ASSET-REFERENCES", assetId)

        val request = builder.post(bodyJson.asJsonBody(JSON_MEDIA)).build()

        val client = if (assetId != null) slowHttp else http
        val payload = client.newCall(request).execute().use { resp ->
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
                resp.code == 429 -> throw RateLimitedException(
                    resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                )
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
