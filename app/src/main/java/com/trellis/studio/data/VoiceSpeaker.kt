package com.trellis.studio.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads assistant replies aloud with an Indian-English voice. Android's TTS engine
 * doesn't expose a reliable cross-device gender flag, so "male" is best-effort: we
 * pick an en-IN voice whose name doesn't hint "female" and nudge the pitch down
 * slightly; the exact voice ultimately depends on what the device's TTS engine ships.
 */
class VoiceSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val indiaLocale = Locale("en", "IN")
                val result = engine.setLanguage(indiaLocale)
                if (result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE
                ) {
                    val maleVoice = engine.voices?.firstOrNull {
                        it.locale == indiaLocale && !it.name.contains("female", ignoreCase = true)
                    }
                    maleVoice?.let { engine.voice = it }
                    engine.setPitch(0.92f)
                }
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trellis_chat_utterance")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        ready = false
    }
}
