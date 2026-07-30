package com.trellis.studio.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "trellis_prefs")

class AppPrefs(private val context: Context) {

    companion object {
        val KEY_NVIDIA_API_KEY   = stringPreferencesKey("nvidia_api_key")
        val KEY_FAL_API_KEY      = stringPreferencesKey("fal_api_key")
        val KEY_POLL_API_KEY     = stringPreferencesKey("pollinations_api_key")
        val KEY_SELECTED_LLM     = stringPreferencesKey("selected_llm_model")
        val KEY_SELECTED_IMG     = stringPreferencesKey("selected_img_model")
        val KEY_SELECTED_3D      = stringPreferencesKey("selected_3d_backend") // "nvidia" | "fal" | "pollinations"
        val KEY_SELECTED_TTS     = stringPreferencesKey("selected_tts_model")
        val KEY_VOICE_ENABLED    = booleanPreferencesKey("nvidia_voice_enabled")
        val KEY_SYSTEM_PROMPT    = stringPreferencesKey("system_prompt")
        val KEY_MAX_TOKENS       = intPreferencesKey("max_tokens")
        val KEY_TEMPERATURE      = floatPreferencesKey("temperature")

        val DEFAULT_LLM     = "nvidia/llama-3.1-nemotron-70b-instruct"
        val DEFAULT_IMG     = "black-forest-labs/flux-dev"
        val DEFAULT_3D      = "nvidia"
        val DEFAULT_TTS     = "nvidia/magpie-tts-flow"
        val DEFAULT_SYSTEM  = "You are a helpful AI assistant."
    }

    private val ds = context.dataStore

    val nvidiaKey: Flow<String>   = ds.data.catchIO().map { it[KEY_NVIDIA_API_KEY] ?: "" }
    val falKey: Flow<String>      = ds.data.catchIO().map { it[KEY_FAL_API_KEY] ?: "" }
    val pollKey: Flow<String>     = ds.data.catchIO().map { it[KEY_POLL_API_KEY] ?: "" }
    val selectedLlm: Flow<String> = ds.data.catchIO().map { it[KEY_SELECTED_LLM] ?: DEFAULT_LLM }
    val selectedImg: Flow<String> = ds.data.catchIO().map { it[KEY_SELECTED_IMG] ?: DEFAULT_IMG }
    val selected3d: Flow<String>  = ds.data.catchIO().map { it[KEY_SELECTED_3D] ?: DEFAULT_3D }
    val selectedTts: Flow<String> = ds.data.catchIO().map { it[KEY_SELECTED_TTS] ?: DEFAULT_TTS }
    val voiceEnabled: Flow<Boolean> = ds.data.catchIO().map { it[KEY_VOICE_ENABLED] ?: false }
    val systemPrompt: Flow<String> = ds.data.catchIO().map { it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM }
    val maxTokens: Flow<Int>      = ds.data.catchIO().map { it[KEY_MAX_TOKENS] ?: 2048 }
    val temperature: Flow<Float>  = ds.data.catchIO().map { it[KEY_TEMPERATURE] ?: 0.7f }

    suspend fun setNvidiaKey(v: String)   = ds.edit { it[KEY_NVIDIA_API_KEY] = v }
    suspend fun setFalKey(v: String)      = ds.edit { it[KEY_FAL_API_KEY] = v }
    suspend fun setPollKey(v: String)     = ds.edit { it[KEY_POLL_API_KEY] = v }
    suspend fun setSelectedLlm(v: String) = ds.edit { it[KEY_SELECTED_LLM] = v }
    suspend fun setSelectedImg(v: String) = ds.edit { it[KEY_SELECTED_IMG] = v }
    suspend fun setSelected3d(v: String)  = ds.edit { it[KEY_SELECTED_3D] = v }
    suspend fun setSelectedTts(v: String) = ds.edit { it[KEY_SELECTED_TTS] = v }
    suspend fun setVoiceEnabled(v: Boolean) = ds.edit { it[KEY_VOICE_ENABLED] = v }
    suspend fun setSystemPrompt(v: String)= ds.edit { it[KEY_SYSTEM_PROMPT] = v }
    suspend fun setMaxTokens(v: Int)      = ds.edit { it[KEY_MAX_TOKENS] = v }
    suspend fun setTemperature(v: Float)  = ds.edit { it[KEY_TEMPERATURE] = v }
}

private fun Flow<Preferences>.catchIO() = catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}
