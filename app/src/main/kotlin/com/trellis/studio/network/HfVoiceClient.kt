package com.trellis.studio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 * Clones a voice on a Hugging Face XTTS Space — free, and (unlike NVIDIA's
 * English-only zero-shot) it speaks the cloned voice in the language you pick
 * (Hindi), so an Indian recording comes back speaking in an Indian accent.
 *
 * It targets a **CPU** XTTS Space (Voice-Cloning-XTTS-v2 style). CPU Spaces are
 * slower than GPU ones (~30–60s a line) but have no ZeroGPU daily quota, so they
 * don't fail with "error in frame handler" once a shared GPU quota runs out —
 * which is exactly what made a GPU Space unreliable.
 *
 * That Space takes the reference audio as a URL, not a file field. So we upload
 * the sample to the Space itself, then hand its own public file URL back as the
 * reference — the Space downloads a file it is already serving, which works.
 * Verified live end-to-end over the raw Gradio HTTP API.
 */
class HfVoiceClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)   // CPU XTTS is slow; hold the SSE open
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()
    private val apiName = "voice_clone_synthesis"

    /**
     * Picks the Space's language for [text]. The Indian accent is carried by the
     * user's own sample either way; language only decides pronunciation rules, so
     * we follow the script actually typed: Devanagari → Hindi, otherwise English
     * (which, spoken through an Indian sample, is Indian-accented English). This
     * avoids XTTS mangling English words when the user typed English but the
     * accent preset said "Hindi".
     */
    private fun languageFor(text: String, accent: String): String {
        val hasDevanagari = text.any { it in 'ऀ'..'ॿ' }
        return when {
            hasDevanagari -> "Hindi"
            accent == "hi" && text.isBlank() -> "Hindi"
            else -> "English"
        }
    }

    /**
     * Clones [sample] and speaks [text] in [accent] ("hi" = Hindi/Indian, "en").
     * The voice timbre comes from the sample; the accent from both the sample and
     * the chosen language.
     */
    suspend fun clone(
        spaceUrl: String,
        hfToken: String,
        sample: File,
        text: String,
        accent: String = "hi",
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(spaceUrl.isNotBlank()) { "Add a Hugging Face voice Space URL in Settings." }
            require(sample.exists() && sample.length() > 1024) { "The voice sample is missing." }
            val base = spaceUrl.trim().trimEnd('/')

            // 1) upload the reference to the Space, then reference it by its own URL
            val serverPath = upload(base, hfToken, sample)
            val referenceUrl = "$base/gradio_api/file=$serverPath"

            // 2) submit the clone job with XTTS defaults + language
            val data = JsonArray(
                listOf(
                    JsonPrimitive(text),                        // text
                    JsonPrimitive(referenceUrl),                // reference_audio_url
                    JsonNull,                                   // example_audio_name (unused)
                    JsonPrimitive(languageFor(text, accent)),   // language
                    JsonPrimitive(0.75),                        // temperature
                    JsonPrimitive(1.0),                         // speed
                    JsonPrimitive(true),                        // do_sample
                    JsonPrimitive(5.0),                         // repetition_penalty
                    JsonPrimitive(1.0),                         // length_penalty
                    JsonPrimitive(30),                          // gpt_cond_len
                    JsonPrimitive(50),                          // top_k
                    JsonPrimitive(0.85),                        // top_p
                    JsonPrimitive(true),                        // remove_silence
                    JsonPrimitive(-45),                         // silence_threshold
                    JsonPrimitive(300),                         // min_silence_len
                    JsonPrimitive(100),                         // keep_silence
                    JsonPrimitive("Native XTTS splitting"),     // splitting method
                    JsonPrimitive(250),                         // max_chars
                    JsonPrimitive(false),                       // enable_preprocessing
                )
            )
            val body = buildJsonObject { put("data", data) }
                .toString().toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia)

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

            // 3) stream the result, then 4) download it
            val audioUrl = streamResult(base, hfToken, eventId)
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
            .addFormDataPart("files", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
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
     * of the produced audio. The Space emits "event: <name>" then "data: <json>"
     * pairs; "heartbeat" events are keep-alives and are ignored.
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
                            "error" -> throw Exception(
                                "The voice Space couldn't process that. Try a clearer, " +
                                    "longer recording, or switch the engine to NVIDIA in Settings."
                            )
                            // "heartbeat"/"generating": keep reading
                        }
                    }
                }
            }
            throw Exception("The voice Space finished without returning audio. Try again in a moment.")
        }
    }

    private fun extractUrl(payload: String): String {
        val first = json.parseToJsonElement(payload).jsonArray.firstOrNull()?.jsonObject
            ?: throw Exception("The voice Space returned no audio.")
        return first["url"]?.jsonPrimitive?.content
            ?: throw Exception("The voice Space returned no audio URL.")
    }

    private fun spaceError(code: Int, body: String): String = when (code) {
        401, 403 -> "Hugging Face rejected the token. Check your HF token in Settings."
        404 -> "Voice Space not found. Check the Space URL in Settings."
        503 -> "The voice Space is starting up. Try again in a minute."
        else -> "Voice Space error ($code)."
    }
}
