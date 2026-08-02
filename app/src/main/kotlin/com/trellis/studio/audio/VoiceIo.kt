package com.trellis.studio.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The agent's ears and mouth: on-device speech-to-text for spoken commands, and
 * text-to-speech so it can talk back while it works.
 *
 * Both Android APIs are main-thread only and stateful, so they live here as one
 * shared instance rather than being rebuilt per call. Speech recognition needs
 * the RECORD_AUDIO permission; [listen] reports that back through [onError]
 * instead of throwing, so a caller without the permission degrades to text.
 */
object VoiceIo {

    private val main = Handler(Looper.getMainLooper())

    // --------------------------------------------------------------- speaking

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayDeque<String>()

    /** Prepares TTS once; safe to call repeatedly. */
    fun warmUp(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                // Flush anything queued while it was still initialising.
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            }
        }
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        main.post {
            val engine = tts
            if (engine == null || !ttsReady) {
                pending.addLast(clean)
                return@post
            }
            engine.speak(clean, TextToSpeech.QUEUE_ADD, null, clean.hashCode().toString())
        }
    }

    fun stopSpeaking() {
        main.post { tts?.stop() }
        pending.clear()
    }

    // -------------------------------------------------------------- listening

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isListening: Boolean get() = listening

    /**
     * Records one utterance and returns the recognised text. All callbacks are
     * delivered on the main thread. Only one recognition runs at a time.
     */
    fun listen(
        context: Context,
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            onError("Speech recognition is not available on this device.")
            return
        }
        main.post {
            if (listening) {
                onError("Already listening.")
                return@post
            }
            recognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = sr
            listening = true

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isBlank()) onError("Didn't catch that.") else onResult(text)
                }

                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
                }

                override fun onError(error: Int) {
                    listening = false
                    onError(errorText(error))
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            runCatching { sr.startListening(intent) }
                .onFailure { listening = false; onError(it.message ?: "Could not start listening.") }
        }
    }

    fun cancelListening() {
        main.post {
            recognizer?.cancel()
            listening = false
        }
    }

    fun release() {
        main.post {
            recognizer?.destroy(); recognizer = null; listening = false
            tts?.shutdown(); tts = null; ttsReady = false
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during recognition."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again."
        else -> "Speech recognition error ($code)."
    }
}
