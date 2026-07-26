package com.trellis.studio.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stores user settings (the NVIDIA API key) locally on the device. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_TRELLIS_API_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit { putString(KEY_TRELLIS_API_KEY, trimmed) }
        _apiKey.value = trimmed
    }

    private companion object {
        const val KEY_TRELLIS_API_KEY = "trellis_api_key"
    }
}
