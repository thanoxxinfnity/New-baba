package com.trellis.studio.data

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speaks text using either the NVIDIA NIM voice-synthesis API (when enabled + key set)
 * or Android's on-device TTS as a fallback.
 */
class VoiceSpeaker(
    context: Context,
    private val nvidiaVoiceRepository: NvidiaVoiceRepository,
    private val nvidiaVoiceEnabledProvider: () -> Boolean
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val indiaLocale = Locale("en", "IN")
                val result = engine.setLanguage(indiaLocale)
                if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                    val maleVoice = engine.voices?.firstOrNull {
                        it.locale == indiaLocale && !it.name.contains("female", ignoreCase = true)
                    }
                    maleVoice?.let { engine.voice = it }
                    engine.setPitch(0.92f)
                }
                ttsReady = true
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (nvidiaVoiceEnabledProvider()) {
            scope.launch {
                val ok = nvidiaVoiceRepository.synthesizeAndPlay(text)
                if (!ok) speakWithAndroidTts(text)
            }
        } else {
            speakWithAndroidTts(text)
        }
    }

    private fun speakWithAndroidTts(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nim_agent_utterance")
    }

    fun stop() {
        nvidiaVoiceRepository.stop()
        tts?.stop()
    }

    fun shutdown() {
        nvidiaVoiceRepository.stop()
        tts?.shutdown()
        ttsReady = false
    }
}
