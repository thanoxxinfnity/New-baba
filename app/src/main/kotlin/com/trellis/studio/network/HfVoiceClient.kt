package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
 * Clones a voice on a Hugging Face XTTS Space — free, and (unlike NVIDIA's
 * English-only zero-shot) it keeps the accent of your own recording, so an
 * Indian voice sample comes back speaking in an Indian accent.
 *
 * It speaks the Gradio HTTP API of a two-input clone Space (text + reference
 * audio file), verified live against tonyassi/voice-clone:
 *   1. POST {base}/gradio_api/upload            (multipart) -> ["server/path.wav"]
 *   2. POST {base}/gradio_api/call/clone        {data:[text, fileRef]} -> {event_id}
 *   3. GET  {base}/gradio_api/call/clone/{id}   (SSE) -> data:[{url:"…output.wav"}]
 *   4. GET  {url}                                            -> the WAV bytes
 *
 * A free CPU Space is on-demand: it wakes on the first request (cold start can
 * take a minute) and then answers in seconds, so the timeouts are generous.
 */
class HfVoiceClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)   // cold XTTS Space can take a while
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** The Gradio endpoint name of the clone function (tonyassi-style: "clone"). */
    private val apiName = "clone"

    /**
     * Clones [sample] and speaks [text] in that voice. The accent is carried by
     * the sample, so record yourself speaking Indian English / Hindi.
     */
    suspend fun clone(
        spaceUrl: String,
        hfToken: String,
        sample: File,
        text: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(spaceUrl.isNotBlank()) { "Add a Hugging Face voice Space URL in Settings." }
            require(sample.exists() && sample.length() > 1024) { "The voice sample is missing." }
            val base = spaceUrl.trim().trimEnd('/')

            // 1) upload the reference audio
            val serverPath = upload(base, hfToken, sample)

            // 2) submit the clone job
            val fileRef = buildJsonObject {
                put("path", serverPath)
                putJsonObject("meta") { put("_type", "gradio.FileData") }
            }
            val body = buildJsonObject {
                put("data", buildJsonArray {
                    add(text)
                    add(fileRef)
                })
            }.toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

            val callReq = Request.Builder()
                .url("$base/gradio_api/call/$apiName")
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .post(body).build()

            val eventId = client.newCall(callReq).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception(spaceError(resp.code, txt))
                json.parseToJsonElement(txt).jsonObject["event_id"]?.jsonPrimitive?.content
                    ?: throw Exception("The voice Space did not accept the job.")
            }

            // 3) stream the result
            val audioUrl = streamResult(base, hfToken, eventId)

            // 4) download the finished WAV
            val getReq = Request.Builder().url(audioUrl)
                .apply { if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken") }
                .get().build()
            client.newCall(getReq).execute().use { r ->
                if (!r.isSuccessful) throw Exception("Could not download the cloned audio (${r.code}).")
                r.body?.bytes() ?: throw Exception("The cloned audio was empty.")
            }
        }
    }

    private fun upload(base: String, token: String, file: File): String {
        val part = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "files", file.name,
                file.asRequestBody("audio/wav".toMediaType()),
            ).build()
        val req = Request.Builder()
            .url("$base/gradio_api/upload")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(part).build()
        return client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception(spaceError(resp.code, txt))
            json.parseToJsonElement(txt).jsonArray.firstOrNull()?.jsonPrimitive?.content
                ?: throw Exception("Uploading the voice sample failed.")
        }
    }

    /**
     * Reads the Gradio SSE stream until the job completes, then returns the URL
     * of the produced audio. Gradio emits "event: <name>" then "data: <json>"
     * line pairs; we keep the data that follows a terminal event.
     */
    private fun streamResult(base: String, token: String, eventId: String): String {
        val req = Request.Builder()
            .url("$base/gradio_api/call/$apiName/$eventId")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception(spaceError(resp.code, resp.body?.string().orEmpty()))
            val source = resp.body?.source() ?: throw Exception("The voice Space closed the connection.")
            var lastEvent = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> lastEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        when (lastEvent) {
                            "complete" -> return extractUrl(payload)
                            "error" -> throw Exception(errorText(payload))
                            // "generating"/heartbeats: keep reading
                        }
                    }
                }
            }
            throw Exception("The voice Space finished without returning audio. Try again in a moment.")
        }
    }

    private fun extractUrl(payload: String): String {
        val arr = json.parseToJsonElement(payload).jsonArray
        val first = arr.firstOrNull()?.jsonObject
            ?: throw Exception("The voice Space returned no audio.")
        return first["url"]?.jsonPrimitive?.content
            ?: throw Exception("The voice Space returned no audio URL.")
    }

    private fun errorText(payload: String): String = runCatching {
        val el = json.parseToJsonElement(payload)
        el.jsonArray.firstOrNull()?.jsonPrimitive?.content
    }.getOrNull() ?: "The voice Space reported an error."

    private fun spaceError(code: Int, body: String): String = when (code) {
        401, 403 -> "Hugging Face rejected the token. Check your HF token in Settings."
        404 -> "Voice Space not found. Check the Space URL in Settings."
        503 -> "The voice Space is starting up (cold start). Try again in a minute."
        else -> "Voice Space error ($code)."
    }
}
