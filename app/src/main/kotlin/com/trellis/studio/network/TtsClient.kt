package com.trellis.studio.network

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Text-to-Speech using Android's built-in engine — works offline, no API key.
 *
 * Note: this engine does not support voice *cloning*. It exposes the voices
 * installed on the device, which can be reshaped with pitch and speed.
 */
class TtsClient(context: Context) {

    private var tts: TextToSpeech? = null

    /** Completes once the engine reports its init result. */
    private val initialised = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.language = Locale.getDefault()
            }
            // complete() is a no-op if already completed, so double callbacks are safe.
            initialised.complete(ok)
        }
    }

    /** Suspends until the engine is ready. Returns false if it failed to start. */
    private suspend fun awaitReady(): Boolean = try {
        withTimeout(READY_TIMEOUT_MS) { initialised.await() }
    } catch (e: TimeoutCancellationException) {
        false
    }

    /** Offline voices installed on this device. */
    suspend fun voices(): List<VoiceOption> {
        if (!awaitReady()) return emptyList()
        val all = runCatching { tts?.voices }.getOrNull() ?: return emptyList()
        return all
            .filter { !it.isNetworkConnectionRequired }
            .sortedWith(compareBy({ it.locale.displayLanguage }, { it.name }))
            .map { VoiceOption(id = it.name, label = prettyLabel(it)) }
    }

    private fun prettyLabel(v: Voice): String {
        val lang = runCatching { v.locale.displayName }.getOrNull().orEmpty()
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "Very High"
            v.quality >= Voice.QUALITY_HIGH -> "High"
            v.quality >= Voice.QUALITY_NORMAL -> "Normal"
            else -> "Low"
        }
        return if (lang.isBlank()) v.name else "$lang · $quality"
    }

    /**
     * Speak [text] aloud and suspend until playback finishes.
     *
     * @param voiceId a value from [voices], or null for the system default
     * @param pitch   0.5 (deep) … 2.0 (high), 1.0 = natural
     * @param speed   0.5 (slow) … 2.0 (fast), 1.0 = natural
     */
    suspend fun speak(
        text: String,
        voiceId: String? = null,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
    ): Result<Unit> {
        if (text.isBlank()) return Result.failure(Exception("Nothing to speak — type some text first."))
        if (!awaitReady()) {
            return Result.failure(
                Exception("No text-to-speech engine available. Install Google Speech Services, then try again.")
            )
        }
        val engine = tts ?: return Result.failure(Exception("Text-to-speech was shut down."))

        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))

        if (voiceId != null) {
            runCatching { engine.voices?.find { it.name == voiceId } }
                .getOrNull()?.let { engine.voice = it }
        }

        // Very long input can be silently dropped by the engine, so cap it.
        val safeText = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text

        return runCatching {
            suspendCancellableCoroutine { cont ->
                val id = "tts_${System.nanoTime()}"
                // The platform can deliver onDone and onError for the same utterance;
                // resuming a continuation twice throws, so allow exactly one.
                val done = AtomicBoolean(false)

                fun finish(result: Result<Unit>) {
                    if (done.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(result)
                    }
                }

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) finish(Result.success(Unit))
                    }

                    @Deprecated("Required override; the int variant is used on API 21+.")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) finish(Result.failure(Exception("Playback failed.")))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) {
                            finish(Result.failure(Exception("Playback failed (code $errorCode).")))
                        }
                    }
                })

                cont.invokeOnCancellation { runCatching { engine.stop() } }

                val queued = engine.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, id)
                if (queued == TextToSpeech.ERROR) {
                    finish(Result.failure(Exception("The speech engine refused the request.")))
                }
            }.getOrThrow()
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    data class VoiceOption(val id: String, val label: String)

    private companion object {
        const val READY_TIMEOUT_MS = 8_000L
        const val MAX_CHARS = 3900
    }
}
