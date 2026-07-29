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
     * Synthesizes [text] using [model] (defaults to settings value) and optional
     * [referenceAudioPath] for zero-shot voice cloning. Returns raw MP3 bytes,
     * or null on failure.
     */
    suspend fun synthesize(
        text: String,
        model: String = voiceModelProvider().ifBlank { DEFAULT_TTS_MODEL },
        referenceAudioPath: String? = voiceFileProvider()
    ): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return@withContext null

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

        runCatching {
            val request = Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/audio/speech")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "audio/mpeg")
                .post(payloadObj.toString().toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        }.getOrNull()
    }

    /** Returns true if synthesis + playback started successfully. */
    suspend fun synthesizeAndPlay(text: String): Boolean {
        val bytes = synthesize(text) ?: return false
        playAudioBytes(bytes)
        return true
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
