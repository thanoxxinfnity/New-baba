package com.trellis.studio.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stores user settings — the active 3D provider and its API keys — locally on the device. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _nvidiaApiKey = MutableStateFlow(prefs.getString(KEY_NVIDIA_API_KEY, "").orEmpty())
    val nvidiaApiKey: StateFlow<String> = _nvidiaApiKey

    private val _falApiKey = MutableStateFlow(prefs.getString(KEY_FAL_API_KEY, "").orEmpty())
    val falApiKey: StateFlow<String> = _falApiKey

    private val _provider = MutableStateFlow(
        runCatching {
            Model3DProvider.valueOf(prefs.getString(KEY_PROVIDER, null).orEmpty())
        }.getOrDefault(Model3DProvider.NVIDIA_TRELLIS)
    )
    val provider: StateFlow<Model3DProvider> = _provider

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

    fun setProvider(provider: Model3DProvider) {
        prefs.edit { putString(KEY_PROVIDER, provider.name) }
        _provider.value = provider
    }

    private companion object {
        const val KEY_NVIDIA_API_KEY = "nvidia_api_key"
        const val KEY_FAL_API_KEY = "fal_api_key"
        const val KEY_PROVIDER = "model3d_provider"
    }
}
