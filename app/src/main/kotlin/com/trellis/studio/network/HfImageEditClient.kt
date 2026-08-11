package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Free image-to-image editing on a Hugging Face FLUX.1-Kontext Space — "make it
 * night", "add a red hat", "turn the car blue" — the way Gemini's editing works,
 * but without a paid key. NVIDIA's editor refuses uploaded photos and fal.ai
 * needs credits; this Space edits a real uploaded image for free.
 *
 * It speaks the Space's Gradio HTTP API, verified live end-to-end:
 *   1. POST {base}/gradio_api/upload         (multipart) -> ["server/path.png"]
 *   2. POST {base}/gradio_api/call/infer     {data:[img, prompt, …]} -> {event_id}
 *   3. GET  {base}/gradio_api/call/infer/id  (SSE) -> data:[{url:"…image.webp"}]
 *   4. GET  {url}                                         -> the edited bytes
 *
 * The Space runs on a shared free GPU, so the first call after a lull is slow
 * and it can be briefly busy; the caller falls back to fal.ai when set.
 */
class HfImageEditClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Edits [image] per [prompt], writing the result into [outputDir]. */
    suspend fun edit(
        spaceUrl: String,
        hfToken: String,
        image: File,
        prompt: String,
        outputDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(image.exists() && image.length() > 0) { "The image to edit is missing." }
            require(prompt.isNotBlank()) { "Say what to change in the image." }
            outputDir.mkdirs()
            val base = spaceUrl.trim().trimEnd('/')

            val serverPath = upload(base, hfToken, image)
            val fileRef = buildJsonObject {
                put("path", serverPath)
                putJsonObject("meta") { put("_type", "gradio.FileData") }
            }
            // data = [image, prompt, seed, randomize_seed, guidance_scale, steps]
            val data = JsonArray(
                listOf(
                    fileRef,
                    JsonPrimitive(prompt),
                    JsonPrimitive(0),
                    JsonPrimitive(true),
                    JsonPrimitive(2.5),
                    JsonPrimitive(28),
                )
            )
            val body = buildJsonObject { put("data", data) }
                .toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)
            val callReq = Request.Builder()
                .url("$base/gradio_api/call/infer")
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .post(body).build()
            val eventId = client.newCall(callReq).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception(spaceError(resp.code))
                json.parseToJsonElement(txt).jsonObject["event_id"]?.jsonPrimitive?.content
                    ?: throw Exception("The edit Space did not accept the job.")
            }

            val resultUrl = streamResult(base, hfToken, eventId)
            val out = File(outputDir, "edit_${System.currentTimeMillis()}.webp")
            val get = Request.Builder().url(resultUrl)
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .get().build()
            client.newCall(get).execute().use { r ->
                if (!r.isSuccessful) throw Exception("Couldn't download the edited image (${r.code}).")
                out.writeBytes(r.body?.bytes() ?: throw Exception("The edited image was empty."))
            }
            out
        }
    }

    private fun upload(base: String, token: String, file: File): String {
        val part = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody("image/png".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$base/gradio_api/upload")
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
        val req = Request.Builder()
            .url("$base/gradio_api/call/infer/$eventId")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception(spaceError(resp.code))
            val source = resp.body?.source() ?: throw Exception("The edit Space closed the connection.")
            var lastEvent = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> lastEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        when (lastEvent) {
                            "complete" -> return extractUrl(payload)
                            "error" -> throw Exception(
                                "The free edit Space is busy right now. Try again in a moment."
                            )
                        }
                    }
                }
            }
            throw Exception("The edit Space finished without returning an image. Try again.")
        }
    }

    private fun extractUrl(payload: String): String {
        val first = json.parseToJsonElement(payload).jsonArray.firstOrNull()?.jsonObject
            ?: throw Exception("The edit Space returned no image.")
        return first["url"]?.jsonPrimitive?.content
            ?: throw Exception("The edit Space returned no image URL.")
    }

    private fun spaceError(code: Int): String = when (code) {
        401, 403 -> "Hugging Face rejected the token. Check your HF token in Settings."
        404 -> "Edit Space not found."
        503 -> "The edit Space is starting up. Try again in a minute."
        else -> "Edit Space error ($code)."
    }

    companion object {
        const val DEFAULT_EDIT_SPACE = "https://black-forest-labs-flux-1-kontext-dev.hf.space"
    }
}
