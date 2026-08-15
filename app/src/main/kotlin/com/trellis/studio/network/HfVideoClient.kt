package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real, free AI video generation on a Hugging Face LTX-Video Space — text→video
 * and image→video. NVIDIA hosts no video-generation cloud API (only self-hosted
 * Cosmos on your own GPU), so this Space is the reachable free path; verified
 * live end-to-end (a 2-second clip in ~10s).
 *
 * Speaks the Space's Gradio HTTP API (/text_to_video): upload the image if any,
 * submit the job, stream the result, download the mp4.
 */
class HfVideoClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Generates a clip. If [image] is given it's image→video, else text→video.
     * Returns the mp4 bytes.
     */
    suspend fun generate(
        spaceUrl: String,
        hfToken: String,
        prompt: String,
        durationSec: Int = 2,
        image: File? = null,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(prompt.isNotBlank() || image != null) { "Type a prompt (or pick an image)." }
            val base = spaceUrl.trim().trimEnd('/')

            val imagePath: String? = image?.let { uploadImage(base, hfToken, it) }
            val mode = if (imagePath != null) "image-to-video" else "text-to-video"

            // data order (verified against the Space's /text_to_video signature):
            // prompt, negative, input_image, input_video, height, width, mode,
            // duration, frames, seed, randomize, guidance, improve_texture
            val data = JsonArray(listOf(
                JsonPrimitive(prompt.ifBlank { "cinematic footage" }),
                JsonPrimitive("worst quality, blurry, jittery, distorted"),
                imagePath?.let { JsonPrimitive(it) } ?: JsonNull,
                JsonNull,
                JsonPrimitive(512),
                JsonPrimitive(704),
                JsonPrimitive(mode),
                JsonPrimitive(durationSec.coerceIn(1, 5)),
                JsonPrimitive(9),
                JsonPrimitive(42),
                JsonPrimitive(true),
                JsonPrimitive(1),
                JsonPrimitive(true),
            ))
            val body = buildJsonObject { put("data", data) }
                .toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

            val callReq = Request.Builder().url("$base/gradio_api/call/text_to_video")
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .post(body).build()
            val eventId = client.newCall(callReq).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception(spaceError(resp.code))
                json.parseToJsonElement(txt).jsonObject["event_id"]?.jsonPrimitive?.content
                    ?: throw Exception("The video Space did not accept the job.")
            }

            val url = streamResult(base, hfToken, eventId)
            val getReq = Request.Builder().url(url)
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .get().build()
            client.newCall(getReq).execute().use { r ->
                if (!r.isSuccessful) throw Exception("Couldn't download the video (${r.code}).")
                r.body?.bytes() ?: throw Exception("The video was empty.")
            }
        }
    }

    private fun uploadImage(base: String, token: String, file: File): String {
        val part = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody("image/png".toMediaType())).build()
        val req = Request.Builder().url("$base/gradio_api/upload")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(part).build()
        return client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception(spaceError(resp.code))
            json.parseToJsonElement(txt).jsonArray.firstOrNull()?.jsonPrimitive?.content
                ?: throw Exception("Uploading the image failed.")
        }
    }

    private fun streamResult(base: String, token: String, eventId: String): String {
        val req = Request.Builder().url("$base/gradio_api/call/text_to_video/$eventId")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception(spaceError(resp.code))
            val source = resp.body?.source() ?: throw Exception("The video Space closed the connection.")
            var lastEvent = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> lastEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        when (lastEvent) {
                            "complete" -> return extractUrl(payload)
                            "error" -> throw Exception("The free video Space is busy (GPU quota). Try again in a bit.")
                        }
                    }
                }
            }
            throw Exception("The video Space finished without a video. Try again.")
        }
    }

    private fun extractUrl(payload: String): String {
        val first = json.parseToJsonElement(payload).jsonArray.firstOrNull()
            ?: throw Exception("The video Space returned nothing.")
        val vid = first.jsonObject["video"]
        val url = when {
            vid is JsonObject -> vid["url"]?.jsonPrimitive?.content
            vid is JsonPrimitive -> vid.content
            else -> first.jsonObject["url"]?.jsonPrimitive?.content
        }
        return url ?: throw Exception("The video Space returned no video URL.")
    }

    private fun spaceError(code: Int): String = when (code) {
        401, 403 -> "Hugging Face rejected the token. Check your HF token in Settings."
        404 -> "Video Space not found."
        503 -> "The video Space is starting up. Try again in a minute."
        else -> "Video Space error ($code)."
    }

    companion object {
        const val DEFAULT_VIDEO_SPACE = "https://lightricks-ltx-video-distilled.hf.space"
    }
}
