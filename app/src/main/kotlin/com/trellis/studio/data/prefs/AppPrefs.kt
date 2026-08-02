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
        val KEY_BUILD_SERVER     = stringPreferencesKey("build_server_url")
        val KEY_BUILD_TOKEN      = stringPreferencesKey("build_server_token")
        val KEY_HINGLISH         = booleanPreferencesKey("hinglish_thinking")
        val KEY_AGENT_URL        = stringPreferencesKey("agent_ws_url")
        val KEY_AGENT_MODEL      = stringPreferencesKey("agent_model")
        val KEY_AGENT_TTS        = booleanPreferencesKey("agent_tts")

        // The agent takes one small decision per turn and the user feels every
        // turn's latency, so it defaults to the fastest model that still plans
        // correctly. Measured against the live API on the same task: 8B ~0.67s a
        // turn versus 70B ~2.06s — three times snappier for the same right answer.
        val DEFAULT_AGENT_MODEL  = "meta/llama-3.1-8b-instruct"

        val DEFAULT_LLM     = "meta/llama-3.1-8b-instruct"
        val DEFAULT_IMG     = "pollinations/flux"
        val DEFAULT_3D      = "nvidia"
        // Riva voice, not a NIM model id — TTS goes over gRPC to a function id.
        val DEFAULT_TTS     = "Magpie-Multilingual.EN-US.Sofia"
        val DEFAULT_SYSTEM  = "You are a helpful AI assistant."

        /** Added to the system prompt when Hinglish thinking is on. */
        val HINGLISH_SUFFIX = " Reply in Hinglish (Hindi written in English letters), " +
            "and think in Hinglish too so the user can read your reasoning. " +
            "Keep code and technical terms in English."
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
    val buildServerUrl: Flow<String> = ds.data.catchIO().map { it[KEY_BUILD_SERVER] ?: "" }
    val buildServerToken: Flow<String> = ds.data.catchIO().map { it[KEY_BUILD_TOKEN] ?: "" }
    val hinglishThinking: Flow<Boolean> = ds.data.catchIO().map { it[KEY_HINGLISH] ?: false }
    val agentUrl: Flow<String> = ds.data.catchIO().map { it[KEY_AGENT_URL] ?: "" }
    val agentModel: Flow<String> = ds.data.catchIO().map { it[KEY_AGENT_MODEL] ?: DEFAULT_AGENT_MODEL }
    val agentTts: Flow<Boolean> = ds.data.catchIO().map { it[KEY_AGENT_TTS] ?: true }

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
    suspend fun setBuildServerUrl(v: String) = ds.edit { it[KEY_BUILD_SERVER] = v.trim() }
    suspend fun setBuildServerToken(v: String) = ds.edit { it[KEY_BUILD_TOKEN] = v.trim() }
    suspend fun setHinglishThinking(v: Boolean) = ds.edit { it[KEY_HINGLISH] = v }
    suspend fun setAgentUrl(v: String) = ds.edit { it[KEY_AGENT_URL] = v.trim() }
    suspend fun setAgentModel(v: String) = ds.edit { it[KEY_AGENT_MODEL] = v }
    suspend fun setAgentTts(v: Boolean) = ds.edit { it[KEY_AGENT_TTS] = v }
}

private fun Flow<Preferences>.catchIO() = catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}
