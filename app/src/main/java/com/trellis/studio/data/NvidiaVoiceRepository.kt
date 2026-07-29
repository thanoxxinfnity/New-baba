package com.trellis.studio.data

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class SynthesisException(message: String) : Exception(message)

/**
 * Uses NVIDIA NIM's OpenAI-compatible audio/speech endpoint to synthesize text.
 * If a voice-clone reference file is provided, it is Base64-encoded and passed
 * as "reference_audio" — supporting NVIDIA voice models that accept zero-shot
 * voice cloning (e.g. elevenlabs/eleven-multilingual-v2 on NIM).
 */
class NvidiaVoiceRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val voiceFileProvider: () -> String?,
    private val voiceModelProvider: () -> String
) {
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Synthesizes [text] using [model] and optional [referenceAudioPath] for
     * zero-shot voice cloning. Returns raw MP3 bytes, or throws [SynthesisException].
     */
    suspend fun synthesize(
        text: String,
        model: String = voiceModelProvider().ifBlank { DEFAULT_TTS_MODEL },
        referenceAudioPath: String? = voiceFileProvider()
    ): ByteArray = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) throw SynthesisException("NVIDIA API key is missing. Add it in Settings.")

        val payloadObj = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", "alloy")
            put("response_format", "mp3")
            if (referenceAudioPath != null) {
                val file = File(referenceAudioPath)
                if (file.exists()) {
                    put("reference_audio", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                }
            }
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "audio/mpeg")
            .post(payloadObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.bytes()
                    ?: throw SynthesisException("Empty response from TTS API.")
                response.code == 401 || response.code == 403 ->
                    throw SynthesisException("API key rejected (${response.code}). Check your key in Settings.")
                response.code == 404 ->
                    throw SynthesisException("TTS model not found (404). Select a different model.")
                response.code == 422 -> {
                    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    val detail = runCatching {
                        JSONObject(body).optJSONObject("detail")?.toString()
                            ?: JSONObject(body).optString("message")
                    }.getOrNull().takeIf { !it.isNullOrBlank() }
                    throw SynthesisException("Invalid TTS request (422): ${detail ?: body.take(150)}")
                }
                else -> {
                    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    throw SynthesisException("TTS API error (${response.code}): ${body.take(200)}")
                }
            }
        }
    }

    /** Returns true if synthesis + playback started successfully. */
    suspend fun synthesizeAndPlay(text: String): Boolean {
        return runCatching { playAudioBytes(synthesize(text)); true }.getOrDefault(false)
    }

    /** Writes [bytes] to cache and starts playback. Returns the cache file path. */
    fun playAudioBytes(bytes: ByteArray): String {
        val tmp = File(context.cacheDir, "nim_tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(tmp).use { it.write(bytes) }
        stopAndRelease()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(tmp.absolutePath)
            prepare()
            start()
            setOnCompletionListener { it.release() }
        }
        return tmp.absolutePath
    }

    fun stop() = stopAndRelease()

    companion object {
        const val DEFAULT_TTS_MODEL = "elevenlabs/eleven-multilingual-v2"

        val TTS_MODELS = listOf(
            "nvidia/magpie-tts-zeroshot",
            "nvidia/magpie-tts-flow",
            "nvidia/nemotron-voicechat",
            "nvidia/personaplex",
            "nvidia/magpie-tts-multilingual",
            "resemble.ai/chatterbox-multilingual-tts",
            "elevenlabs/eleven-multilingual-v2"
        )
    }

    private fun stopAndRelease() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }
}
