package com.trellis.studio.viewmodel

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.trellis.studio.audio.AudioConverter
import com.trellis.studio.audio.VoiceRecorder
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.TtsHistoryEntity
import com.trellis.studio.data.entity.VoiceEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NvidiaTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Built-in Magpie voices. Names, languages and emotions were read from the
 * service itself via GetRivaSynthesisConfig, not guessed — the voice_name
 * format is Magpie-Multilingual.<LANG>.<Speaker>[.<Emotion>].
 */
data class BuiltInVoice(
    val speaker: String,
    val language: String,
    val languageCode: String,
) {
    fun id(emotion: String = ""): String =
        "Magpie-Multilingual.$language.$speaker" + if (emotion.isNotBlank()) ".$emotion" else ""

    val label: String get() = "$speaker · $language"
}

/**
 * Real speaker → language map, read from GetRivaSynthesisConfig. The old code
 * paired every speaker with every language, which minted voices the service does
 * not have (Hindi Aria, Hindi Mia…) — selecting one failed with "subvoice not
 * found", which is why the Indian/Hindi voices "didn't work". Only the pairings
 * below actually exist.
 */
private val MAGPIE_BY_LANG: List<Pair<Pair<String, String>, List<String>>> = listOf(
    ("EN-US" to "en-US") to listOf("Aria", "Jason", "Leo", "Mia", "Ray", "Sofia"),
    ("HI-IN" to "hi-IN") to listOf("Sofia", "Leo", "Pascal", "Siwei"),
    ("DE-DE" to "de-DE") to listOf("Diego", "Jason", "Leo", "Mia", "Pascal", "Ray"),
    ("ES-US" to "es-US") to listOf("Diego", "Isabela"),
    ("FR-FR" to "fr-FR") to listOf("Louise", "Pascal"),
    ("IT-IT" to "it-IT") to listOf("Isabela", "Pascal"),
    ("PT-BR" to "pt-BR") to listOf("Diego", "Isabela", "Louise"),
    ("JA-JP" to "ja-JP") to listOf("HouZhen", "Isabela", "Louise", "Ray"),
    ("KO-KR" to "ko-KR") to listOf("Aria", "Diego", "HouZhen", "Louise", "Pascal", "Ray"),
    ("ZH-CN" to "zh-CN") to listOf("HouZhen", "Siwei"),
)

/** (display, code) for each language that actually has voices. */
val MAGPIE_LANGUAGES: List<Pair<String, String>> = MAGPIE_BY_LANG.map { it.first }

/** Emotions the service reports for these voices. */
val MAGPIE_EMOTIONS = listOf("", "Neutral", "Calm", "Happy", "Sad", "Angry", "Fearful", "Disgusted")

val MAGPIE_VOICES: List<BuiltInVoice> = MAGPIE_BY_LANG.flatMap { (langPair, speakers) ->
    val (lang, code) = langPair
    speakers.map { BuiltInVoice(it, lang, code) }
}

data class VoiceUiState(
    val text: String = "",
    val voices: List<VoiceEntity> = emptyList(),
    val selectedVoiceId: Long? = null,
    val selectedBuiltIn: String = "Magpie-Multilingual.EN-US.Aria",
    val emotion: String = "",
    val history: List<TtsHistoryEntity> = emptyList(),

    val isRecording: Boolean = false,
    val recordSeconds: Int = 0,
    val recordedSample: String? = null,

    val isGenerating: Boolean = false,
    val isCloning: Boolean = false,
    val playingPath: String? = null,
    val status: String? = null,
    val error: String? = null,
    /** Accent for a cloned voice via the XTTS server: "hi" (Indian) or "en". */
    val cloneAccent: String = "hi",
    /** True once a voice-clone server URL is set — unlocks accented cloning. */
    val voiceServerReady: Boolean = false,
) {
    val selectedVoice: VoiceEntity?
        get() = voices.find { it.id == selectedVoiceId }

    val voiceLabel: String
        get() = selectedVoice?.name
            ?: selectedBuiltIn.removePrefix("Magpie-Multilingual.")
                .split(".").let { p -> p.getOrNull(1)?.plus(" · ${p.getOrNull(0)}") ?: selectedBuiltIn }

    /** Language code the API needs, derived from the selected voice id. */
    val languageCode: String
        get() = selectedBuiltIn.removePrefix("Magpie-Multilingual.").substringBefore(".")
            .let { tag -> MAGPIE_LANGUAGES.find { it.first == tag }?.second ?: "en-US" }
}

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val tts = NvidiaTtsClient()
    private val remoteVoice = com.trellis.studio.network.RemoteVoiceClient()
    private val hfVoice = com.trellis.studio.network.HfVoiceClient()
    private val recorder = VoiceRecorder(app)

    fun setCloneAccent(code: String) = _state.update { it.copy(cloneAccent = code) }

    private fun accentLabel(code: String) = when (code) {
        "hi" -> "Hindi"; "en" -> "English"; else -> code
    }

    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            db.voiceDao().getAll().collect { list -> _state.update { it.copy(voices = list) } }
        }
        viewModelScope.launch {
            prefs.voiceServerUrl.collect { url ->
                _state.update { it.copy(voiceServerReady = url.isNotBlank()) }
            }
        }
        viewModelScope.launch {
            db.ttsDao().getAll().collect { list -> _state.update { it.copy(history = list) } }
        }
    }

    fun setText(v: String) = _state.update { it.copy(text = v) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun selectVoice(id: Long?) = _state.update { it.copy(selectedVoiceId = id) }
    fun selectBuiltIn(id: String) = _state.update { it.copy(selectedVoiceId = null, selectedBuiltIn = id) }
    fun setEmotion(e: String) = _state.update { it.copy(emotion = e) }

    // ---------------------------------------------------------------- record

    fun startRecording() {
        if (_state.value.isRecording) return
        recorder.start()
            .onSuccess {
                _state.update { it.copy(isRecording = true, recordSeconds = 0, error = null) }
                viewModelScope.launch {
                    while (_state.value.isRecording) {
                        kotlinx.coroutines.delay(1000)
                        _state.update { s -> if (s.isRecording) s.copy(recordSeconds = s.recordSeconds + 1) else s }
                    }
                }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return
        recorder.stop()
            .onSuccess { file ->
                _state.update { it.copy(isRecording = false, recordedSample = file.absolutePath) }
            }
            .onFailure { e ->
                _state.update { it.copy(isRecording = false, error = e.message) }
            }
    }

    /**
     * Imports an audio file as a voice sample. Anything the platform can decode
     * works; it is converted to the mono PCM WAV the clone service requires.
     */
    fun importVoiceSample(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(status = "Reading audio…", error = null) }
            AudioConverter.toWav(getApplication(), uri)
                .onSuccess { file ->
                    val seconds = ((file.length() - 44) / (22050 * 2)).toInt().coerceAtLeast(1)
                    _state.update {
                        it.copy(status = null, recordedSample = file.absolutePath, recordSeconds = seconds)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(status = null, error = e.message ?: "Could not read that audio file.") }
                }
        }
    }

    fun discardRecording() {
        _state.value.recordedSample?.let { runCatching { File(it).delete() } }
        _state.update { it.copy(recordedSample = null, recordSeconds = 0) }
    }

    // ----------------------------------------------------------------- clone

    /** Saves the recorded sample as a named, reusable cloned voice. */
    fun saveClonedVoice(name: String) {
        val sample = _state.value.recordedSample ?: run {
            _state.update { it.copy(error = "Record a voice sample first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isCloning = true, status = "Cloning voice…", error = null) }
            try {
                val apiKey = prefs.nvidiaKey.first()

                // Copy the recording into a permanent, voice-owned file and keep
                // THAT as the sample. The transient recording path was surviving
                // long enough to make the preview but could go missing before the
                // voice was used again — which surfaced later as "sample is missing
                // or too short" at generation. A verified owned copy can't.
                val stable = withContext(Dispatchers.IO) {
                    val src = File(sample)
                    if (!src.exists() || src.length() < 1024)
                        throw Exception("The recording didn't save. Record again for a few seconds.")
                    val dir = File(getApplication<Application>().filesDir, "voices").apply { mkdirs() }
                    File(dir, "clone_${System.currentTimeMillis()}.wav").also { src.copyTo(it, overwrite = true) }
                }

                // Generate a preview so the saved voice can be auditioned instantly.
                val preview = tts.cloneVoice(
                    apiKey = apiKey,
                    text = "Hi, this is $name. This is how I sound.",
                    voiceSample = stable,
                )
                // Save the voice either way: the sample is the valuable part, and
                // the preview can be regenerated once the service answers again.
                val previewPath = preview.getOrNull()?.let {
                    writeAudio(it, "preview_${System.currentTimeMillis()}.wav").absolutePath
                }
                val id = db.voiceDao().insert(
                    VoiceEntity(
                        name = name.ifBlank { "My voice" },
                        samplePath = stable.absolutePath,
                        previewPath = previewPath,
                        isCloned = true,
                    )
                )
                _state.update {
                    it.copy(
                        isCloning = false,
                        status = null,
                        recordedSample = null,
                        recordSeconds = 0,
                        selectedVoiceId = id,
                        error = preview.exceptionOrNull()?.message,
                    )
                }
                previewPath?.let { play(it) }
            } catch (e: Exception) {
                _state.update { it.copy(isCloning = false, status = null, error = e.message) }
            }
        }
    }

    fun deleteVoice(voice: VoiceEntity) = viewModelScope.launch {
        runCatching {
            voice.samplePath?.let { File(it).delete() }
            voice.previewPath?.let { File(it).delete() }
            db.voiceDao().delete(voice.id)
        }
        if (_state.value.selectedVoiceId == voice.id) _state.update { it.copy(selectedVoiceId = null) }
    }

    // -------------------------------------------------------------- generate

    fun generate() {
        val s = _state.value
        val text = s.text.trim()
        if (text.isBlank()) {
            _state.update { it.copy(error = "Type something to say first.") }
            return
        }
        if (s.isGenerating) return

        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, status = "Generating speech…", error = null) }
            try {
                val apiKey = prefs.nvidiaKey.first()
                val voice = s.selectedVoice

                val result = if (voice?.isCloned == true && voice.samplePath != null) {
                    // If the saved recording is gone, say exactly that and which
                    // voice, instead of the generic "sample too short" that left
                    // the user re-trying a voice whose file no longer exists.
                    val sampleFile = File(voice.samplePath)
                    if (!sampleFile.exists() || sampleFile.length() < 1024) {
                        _state.update {
                            it.copy(isGenerating = false, status = null,
                                error = "The recording for \"${voice.name}\" is missing. " +
                                    "Re-record this voice once in the clone panel.")
                        }
                        return@launch
                    }
                    // The clone engine is the user's choice:
                    //  • Hugging Face XTTS Space — keeps YOUR recording's accent
                    //    (Indian), free + on-demand. This is what makes an Indian
                    //    accent possible; NVIDIA's cloner is English-only.
                    //  • a self-hosted XTTS /clone server (advanced), if set.
                    //  • otherwise NVIDIA Magpie (en-US timbre clone), 24/7.
                    val provider = prefs.voiceProvider.first()
                    val voiceServer = prefs.voiceServerUrl.first()
                    when {
                        provider == AppPrefs.VOICE_PROVIDER_HF -> {
                            _state.update { it.copy(status = "Cloning in your accent (Hugging Face)…") }
                            hfVoice.clone(
                                spaceUrl = prefs.hfVoiceSpace.first(),
                                hfToken = prefs.hfToken.first(),
                                sample = sampleFile,
                                text = text,
                            )
                        }
                        voiceServer.isNotBlank() -> {
                            _state.update { it.copy(status = "Cloning in ${accentLabel(s.cloneAccent)}…") }
                            remoteVoice.clone(voiceServer, sampleFile, text, s.cloneAccent)
                        }
                        else -> tts.cloneVoice(apiKey, text, sampleFile)
                    }
                } else {
                    val base = voice?.voiceName?.ifBlank { null } ?: s.selectedBuiltIn
                    val withEmotion = if (s.emotion.isNotBlank()) "$base.${s.emotion}" else base
                    tts.synthesize(apiKey, text, voiceName = withEmotion, languageCode = s.languageCode)
                }

                result.onSuccess { wav ->
                    val file = writeAudio(wav, "tts_${System.currentTimeMillis()}.wav")
                    db.ttsDao().insert(
                        TtsHistoryEntity(
                            textInput = text,
                            model = if (voice?.isCloned == true) "magpie-zeroshot · ${voice.name}"
                            else s.selectedBuiltIn,
                            audioPath = file.absolutePath,
                        )
                    )
                    _state.update { it.copy(isGenerating = false, status = null) }
                    play(file.absolutePath)
                }.onFailure { e ->
                    _state.update { it.copy(isGenerating = false, status = null, error = e.message) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating = false, status = null, error = e.message) }
            }
        }
    }

    // -------------------------------------------------------------- playback

    fun play(path: String) {
        stopPlayback()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { _state.update { it.copy(playingPath = null) } }
                prepare()
                start()
            }
            _state.update { it.copy(playingPath = path) }
        }.onFailure { e ->
            _state.update { it.copy(error = "Could not play the audio: ${e.message}") }
        }
    }

    fun stopPlayback() {
        runCatching { player?.stop(); player?.release() }
        player = null
        _state.update { it.copy(playingPath = null) }
    }

    fun deleteHistory(entry: TtsHistoryEntity) = viewModelScope.launch {
        runCatching {
            File(entry.audioPath).delete()
            db.ttsDao().delete(entry.id)
        }
    }

    private fun writeAudio(bytes: ByteArray, name: String): File {
        val dir = File(getApplication<Application>().filesDir, "voices").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    override fun onCleared() {
        stopPlayback()
        recorder.release()
        super.onCleared()
    }
}
