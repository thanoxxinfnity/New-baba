package com.trellis.studio.viewmodel

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.audio.VoiceIo
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import com.trellis.studio.network.NvidiaTtsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * A hands-free spoken conversation with the AI: you talk, it thinks, and it
 * answers out loud — in a cloned voice if you have one, so it can even reply in
 * your own voice. It runs as a loop — listen, reply, listen again — until you
 * stop it.
 *
 * Every piece already existed (speech recognition, the chat model, Riva TTS);
 * this is the conductor that keeps them taking turns.
 */
class LiveChatViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }
    enum class Role { YOU, AI }
    data class Line(val role: Role, val text: String)

    /** A voice the AI can answer in: a cloned one, or a built-in Magpie voice. */
    data class VoiceChoice(val key: String, val label: String)

    data class State(
        val phase: Phase = Phase.IDLE,
        val running: Boolean = false,
        val lines: List<Line> = emptyList(),
        val partial: String = "",
        val voiceLabel: String = "AI voice",
        val voices: List<VoiceChoice> = emptyList(),
        val voiceKey: String = "builtin:Magpie-Multilingual.EN-US.Aria",
        val error: String? = null,
    )

    private val prefs = AppPrefs(app)
    private val db = AppDatabase.get(app)
    private val nim = NimClient()
    private val tts = NvidiaTtsClient()
    private val hfVoice = com.trellis.studio.network.HfVoiceClient()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    init {
        // Offer built-in voices plus any cloned ones the user has made.
        viewModelScope.launch {
            db.voiceDao().getAll().collect { list ->
                val choices = buildList {
                    add(VoiceChoice("builtin:Magpie-Multilingual.EN-US.Aria", "AI · English"))
                    add(VoiceChoice("builtin:Magpie-Multilingual.HI-IN.Sofia", "AI · Hindi"))
                    list.filter { it.isCloned && it.samplePath != null }
                        .forEach { add(VoiceChoice("clone:${it.id}", "Your voice · ${it.name}")) }
                }
                _state.value = _state.value.copy(voices = choices)
                _state.value = _state.value.copy(
                    voiceLabel = choices.firstOrNull { it.key == _state.value.voiceKey }?.label ?: "AI voice",
                )
            }
        }
    }

    fun setVoice(key: String) {
        val label = _state.value.voices.firstOrNull { it.key == key }?.label ?: "AI voice"
        _state.value = _state.value.copy(voiceKey = key, voiceLabel = label)
    }

    fun start() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, error = null)
        viewModelScope.launch { loop() }
    }

    fun stop() {
        _state.value = _state.value.copy(running = false, phase = Phase.IDLE, partial = "")
        VoiceIo.cancelListening()
        VoiceIo.stopSpeaking()
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    fun clear() { stop(); _state.value = State() }

    // ------------------------------------------------------------------ loop

    private suspend fun loop() {
        val app = getApplication<Application>()
        while (_state.value.running) {
            // 1) Listen.
            _state.value = _state.value.copy(phase = Phase.LISTENING, partial = "")
            val heard = listen()
            if (!_state.value.running) break
            if (heard.isBlank()) continue          // nothing caught — just listen again
            add(Role.YOU, heard)

            // 2) Think.
            _state.value = _state.value.copy(phase = Phase.THINKING, partial = "")
            val apiKey = prefs.nvidiaKey.first()
            val model = prefs.agentModel.first()
            val reply = nim.chat(
                apiKey = apiKey, model = model,
                turns = history(),
                maxTokens = 300, temperature = 0.7,
            ).map { it.content.ifBlank { it.reasoning.orEmpty() } }
                .getOrElse {
                    _state.value = _state.value.copy(error = it.message ?: "The model didn't answer.")
                    ""
                }
            if (reply.isBlank()) continue
            add(Role.AI, reply)
            if (!_state.value.running) break

            // 3) Speak.
            _state.value = _state.value.copy(phase = Phase.SPEAKING)
            speak(app, apiKey, reply)
        }
        _state.value = _state.value.copy(phase = Phase.IDLE)
    }

    private suspend fun listen(): String = suspendCancellableCoroutine { cont ->
        VoiceIo.listen(
            context = getApplication(),
            onResult = { if (cont.isActive) cont.resume(it) },
            onPartial = { _state.value = _state.value.copy(partial = it) },
            onError = { if (cont.isActive) cont.resume("") },
        )
        cont.invokeOnCancellation { VoiceIo.cancelListening() }
    }

    /** Speaks [text] in the chosen voice — a cloned one or a built-in — and waits. */
    private suspend fun speak(app: Application, apiKey: String, text: String) {
        val key = _state.value.voiceKey
        val wav = if (key.startsWith("clone:")) {
            val id = key.removePrefix("clone:").toLongOrNull()
            val sample = id?.let { db.voiceDao().byId(it)?.samplePath }?.let(::File)?.takeIf { it.exists() }
            when {
                sample == null -> tts.synthesize(apiKey, text.take(600)).getOrNull()
                // Honor the clone engine choice: Hugging Face XTTS keeps your own
                // accent (Indian); NVIDIA is faster but English-only.
                prefs.voiceProvider.first() == AppPrefs.VOICE_PROVIDER_HF ->
                    hfVoice.clone(
                        spaceUrl = prefs.hfVoiceSpace.first(),
                        hfToken = prefs.hfToken.first(),
                        sample = sample,
                        text = text.take(600),
                    ).getOrNull() ?: tts.cloneVoice(apiKey, text.take(600), sample).getOrNull()
                else -> tts.cloneVoice(apiKey, text.take(600), sample).getOrNull()
            }
        } else {
            val name = key.removePrefix("builtin:")
            val lang = if (name.contains("HI-IN")) "hi-IN" else "en-US"
            tts.synthesize(apiKey, text.take(600), voiceName = name, languageCode = lang).getOrNull()
        }

        if (wav == null) {
            // Fall back to the phone's own TTS so the conversation still flows.
            VoiceIo.warmUp(app)
            VoiceIo.speak(text)
            kotlinx.coroutines.delay((text.length * 60L).coerceIn(1200, 8000))
            return
        }
        val file = File(app.cacheDir, "live_reply.wav").apply { writeBytes(wav) }
        playAndWait(file)
    }

    private suspend fun playAndWait(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                prepare()
                start()
            }
        }.onFailure { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { runCatching { player?.stop() } }
    }

    // --------------------------------------------------------------- helpers

    private fun add(role: Role, text: String) {
        _state.value = _state.value.copy(lines = (_state.value.lines + Line(role, text)).takeLast(50))
    }

    /** Recent conversation as chat turns, with a light system prompt. */
    private fun history(): List<ChatTurn> {
        val turns = ArrayList<ChatTurn>()
        turns += ChatTurn("system",
            "You are a friendly voice assistant. Keep replies short and natural — a " +
                "sentence or two — since they are spoken aloud. Match the user's language " +
                "(Hindi, Hinglish or English).")
        _state.value.lines.takeLast(16).forEach {
            turns += ChatTurn(if (it.role == Role.YOU) "user" else "assistant", it.text)
        }
        return turns
    }

    override fun onCleared() { stop(); super.onCleared() }
}
