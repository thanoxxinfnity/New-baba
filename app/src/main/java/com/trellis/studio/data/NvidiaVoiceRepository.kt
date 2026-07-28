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

    /** Returns true if synthesis + playback started successfully. */
    suspend fun synthesizeAndPlay(text: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return@withContext false

        val model = voiceModelProvider().ifBlank { "elevenlabs/eleven-multilingual-v2" }
        val voicePath = voiceFileProvider()

        val payloadObj = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", "alloy")           // default voice ID; overridden by reference_audio
            put("response_format", "mp3")
            if (voicePath != null) {
                val file = File(voicePath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    put("reference_audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
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
                if (!response.isSuccessful) return@withContext false
                val audioBytes = response.body?.bytes() ?: return@withContext false
                playBytes(audioBytes)
                true
            }
        }.getOrDefault(false)
    }

    private fun playBytes(bytes: ByteArray) {
        val tmp = File(context.cacheDir, "nim_tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(tmp).use { it.write(bytes) }
        stopAndRelease()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(tmp.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                it.release()
                tmp.delete()
            }
        }
    }

    fun stop() = stopAndRelease()

    private fun stopAndRelease() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }
}
