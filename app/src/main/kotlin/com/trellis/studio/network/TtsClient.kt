package com.trellis.studio.network

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Text-to-Speech using Android's built-in engine (works offline, no API key needed). */
class TtsClient(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    val availableVoices: List<String> get() = tts?.voices
        ?.filter { !it.isNetworkConnectionRequired }
        ?.map { it.name }
        ?.sorted()
        ?: listOf("Default")

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
        }
    }

    /** Speak text aloud. Returns success when speech finishes, or failure with reason. */
    suspend fun synthesize(
        @Suppress("UNUSED_PARAMETER") apiKey: String,
        @Suppress("UNUSED_PARAMETER") model: String,
        text: String,
        voice: String = "default",
    ): Result<String> {
        if (!ready) return Result.failure(Exception("TTS engine not initialized yet. Try again."))
        if (text.isBlank()) return Result.failure(Exception("Text is empty."))

        // Apply selected voice if available
        if (voice != "default") {
            tts?.voices?.find { it.name == voice }?.let { tts?.voice = it }
        }

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "tts_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) cont.resume(Result.success("spoken"))
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) cont.resume(Result.failure(Exception("TTS playback error.")))
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == utteranceId) cont.resume(Result.failure(Exception("TTS error code $errorCode.")))
                }
            })
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to utteranceId))
            }
            if (result == TextToSpeech.ERROR) {
                cont.resume(Result.failure(Exception("TTS speak() failed.")))
            }
        }
    }

    fun stop() { tts?.stop() }

    fun release() { tts?.shutdown(); tts = null; ready = false }
}
