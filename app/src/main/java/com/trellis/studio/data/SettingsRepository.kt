package com.trellis.studio.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _nvidiaApiKey = MutableStateFlow(prefs.getString(KEY_NVIDIA_API_KEY, "").orEmpty())
    val nvidiaApiKey: StateFlow<String> = _nvidiaApiKey

    private val _falApiKey = MutableStateFlow(prefs.getString(KEY_FAL_API_KEY, "").orEmpty())
    val falApiKey: StateFlow<String> = _falApiKey

    private val _pollinationsApiKey =
        MutableStateFlow(prefs.getString(KEY_POLLINATIONS_API_KEY, "").orEmpty())
    val pollinationsApiKey: StateFlow<String> = _pollinationsApiKey

    private val _provider = MutableStateFlow(
        runCatching {
            Model3DProvider.valueOf(prefs.getString(KEY_PROVIDER, null).orEmpty())
        }.getOrDefault(Model3DProvider.NVIDIA_TRELLIS)
    )
    val provider: StateFlow<Model3DProvider> = _provider

    /** Path to a voice audio file the user uploaded for voice-clone TTS. */
    private val _voiceCloneFilePath =
        MutableStateFlow(prefs.getString(KEY_VOICE_CLONE_PATH, null))
    val voiceCloneFilePath: StateFlow<String?> = _voiceCloneFilePath

    /** When true, NVIDIA NIM voice synthesis (with optional clone) is used over Android TTS. */
    private val _nvidiaVoiceEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_NVIDIA_VOICE_ENABLED, false))
    val nvidiaVoiceEnabled: StateFlow<Boolean> = _nvidiaVoiceEnabled

    /** Selected NVIDIA voice model for TTS. */
    private val _voiceModel = MutableStateFlow(
        prefs.getString(KEY_VOICE_MODEL, DEFAULT_VOICE_MODEL).orEmpty()
    )
    val voiceModel: StateFlow<String> = _voiceModel

    fun setNvidiaApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit { putString(KEY_NVIDIA_API_KEY, trimmed) }
        _nvidiaApiKey.value = trimmed
    }

    fun setFalApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit { putString(KEY_FAL_API_KEY, trimmed) }
        _falApiKey.value = trimmed
    }

    fun setPollinationsApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit { putString(KEY_POLLINATIONS_API_KEY, trimmed) }
        _pollinationsApiKey.value = trimmed
    }

    fun setProvider(provider: Model3DProvider) {
        prefs.edit { putString(KEY_PROVIDER, provider.name) }
        _provider.value = provider
    }

    fun setVoiceCloneFilePath(path: String?) {
        prefs.edit { if (path != null) putString(KEY_VOICE_CLONE_PATH, path) else remove(KEY_VOICE_CLONE_PATH) }
        _voiceCloneFilePath.value = path
    }

    fun setNvidiaVoiceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NVIDIA_VOICE_ENABLED, enabled) }
        _nvidiaVoiceEnabled.value = enabled
    }

    fun setVoiceModel(model: String) {
        prefs.edit { putString(KEY_VOICE_MODEL, model) }
        _voiceModel.value = model
    }

    private companion object {
        const val KEY_NVIDIA_API_KEY        = "nvidia_api_key"
        const val KEY_FAL_API_KEY           = "fal_api_key"
        const val KEY_POLLINATIONS_API_KEY  = "pollinations_api_key"
        const val KEY_PROVIDER              = "model3d_provider"
        const val KEY_VOICE_CLONE_PATH      = "voice_clone_file_path"
        const val KEY_NVIDIA_VOICE_ENABLED  = "nvidia_voice_enabled"
        const val KEY_VOICE_MODEL           = "nvidia_voice_model"
        const val DEFAULT_VOICE_MODEL       = "elevenlabs/eleven-multilingual-v2"
    }
}
