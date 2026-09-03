package com.trellis.studio.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.trellis.studio.data.model.NIM_LLM_MODELS
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
        val KEY_AGENT_VISION     = stringPreferencesKey("agent_vision_model")
        val KEY_AGENT_TTS        = booleanPreferencesKey("agent_tts")

        // The agent takes one small decision per turn and the user feels every
        // turn's latency, so it defaults to a small, fast model that still plans
        // correctly. The Llama 8B this used to name was retired by NVIDIA.
        val DEFAULT_AGENT_MODEL  = "openai/gpt-oss-20b"

        // Used only when the screen exposes no accessibility text (Godot, games,
        // canvases): the agent screenshots and this model reads where to tap.
        val DEFAULT_AGENT_VISION = "meta/llama-3.2-90b-vision-instruct"

        val KEY_YT_API           = stringPreferencesKey("youtube_api_key")
        val KEY_VIDEO_SERVER     = stringPreferencesKey("video_server_url")
        val KEY_VOICE_SERVER     = stringPreferencesKey("voice_clone_server_url")

        // Which engine clones a voice. "nvidia" = Magpie zero-shot: 24/7 free but
        // English accent only. "huggingface" = an XTTS Space on Hugging Face: keeps
        // the accent of your own recording (so, Indian), free and on-demand.
        val KEY_VOICE_PROVIDER   = stringPreferencesKey("voice_clone_provider")
        val KEY_HF_TOKEN         = stringPreferencesKey("hf_token")
        val KEY_HF_VOICE_SPACE   = stringPreferencesKey("hf_voice_space_url")
        const val VOICE_PROVIDER_NVIDIA = "nvidia"
        const val VOICE_PROVIDER_HF     = "huggingface"

        val KEY_BOOSTER_AUTO     = booleanPreferencesKey("booster_auto")
        val KEY_BOOSTER_INTERVAL = intPreferencesKey("booster_interval_min")
        // Disposable inboxes: one "address\tpassword" per line. They persist so an
        // inbox is never lost, and you can keep as many as you make.
        val KEY_TEMP_MAILS       = stringPreferencesKey("temp_mails")
        val KEY_QUICK_NOTES      = stringPreferencesKey("quick_notes")
        val KEY_CODE_FOLDER      = stringPreferencesKey("code_folder_uri")
        // Vault: only a salt, a verification token and the AES-GCM ciphertext of
        // the entries are stored — never the master password or plaintext.
        val KEY_VAULT_SALT       = stringPreferencesKey("vault_salt")
        val KEY_VAULT_CHECK      = stringPreferencesKey("vault_check")
        val KEY_VAULT_BLOB       = stringPreferencesKey("vault_blob")
        val KEY_API_HISTORY      = stringPreferencesKey("api_history")
        // Free token from sketchfab.com → Settings → Password & API. Search works
        // without it; only downloading needs one.
        val KEY_SKETCHFAB_TOKEN  = stringPreferencesKey("sketchfab_token")
        // A public XTTS voice-clone Space, verified live to clone in Hindi/Indian
        // accent from an uploaded sample. It runs on CPU (no ZeroGPU daily quota),
        // so it stays reliable where a shared GPU Space fails once quota runs out —
        // just slower (~30–60s a line). Users can duplicate it for a private one.
        val DEFAULT_HF_VOICE_SPACE = "https://minsus-voice-cloning-xtts-v2.hf.space"
        val KEY_GAME_MODEL       = stringPreferencesKey("game_model")
        // Writes the whole game as one GDScript file, so it wants the strongest
        // code model available. GLM, which this used to name, was retired by
        // NVIDIA; DeepSeek V4 Pro is the current best-verified coding model here.
        val DEFAULT_GAME_MODEL   = "deepseek-ai/deepseek-v4-pro-0813"

        val DEFAULT_LLM     = "openai/gpt-oss-20b"
        val DEFAULT_IMG     = "black-forest-labs/flux.1-dev"
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
    val sketchfabToken: Flow<String> = ds.data.catchIO().map { it[KEY_SKETCHFAB_TOKEN] ?: "" }
    // A stored choice can outlive the model it names: NVIDIA retires models on a
    // published date and then answers 410 Gone. Changing the default alone would
    // not have rescued anyone who had ever picked a model, because their saved
    // preference still pointed at the dead one — so a name that is no longer
    // offered falls back to the current default instead of failing every send.
    val selectedLlm: Flow<String> = ds.data.catchIO().map { prefs ->
        val stored = prefs[KEY_SELECTED_LLM] ?: DEFAULT_LLM
        if (NIM_LLM_MODELS.any { it.id == stored }) stored else DEFAULT_LLM
    }
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
    val agentModel: Flow<String> = ds.data.catchIO().map { prefs ->
        val stored = prefs[KEY_AGENT_MODEL] ?: DEFAULT_AGENT_MODEL
        if (NIM_LLM_MODELS.any { it.id == stored }) stored else DEFAULT_AGENT_MODEL
    }
    val agentVisionModel: Flow<String> = ds.data.catchIO().map { it[KEY_AGENT_VISION] ?: DEFAULT_AGENT_VISION }
    val gameModel: Flow<String> = ds.data.catchIO().map { prefs ->
        val stored = prefs[KEY_GAME_MODEL] ?: DEFAULT_GAME_MODEL
        if (NIM_LLM_MODELS.any { it.id == stored }) stored else DEFAULT_GAME_MODEL
    }
    val youtubeKey: Flow<String> = ds.data.catchIO().map { it[KEY_YT_API] ?: "" }
    val videoServerUrl: Flow<String> = ds.data.catchIO().map { it[KEY_VIDEO_SERVER] ?: "" }
    val voiceServerUrl: Flow<String> = ds.data.catchIO().map { it[KEY_VOICE_SERVER] ?: "" }
    val voiceProvider: Flow<String> = ds.data.catchIO().map { it[KEY_VOICE_PROVIDER] ?: VOICE_PROVIDER_NVIDIA }
    val hfToken: Flow<String> = ds.data.catchIO().map { it[KEY_HF_TOKEN] ?: "" }
    val hfVoiceSpace: Flow<String> = ds.data.catchIO().map { it[KEY_HF_VOICE_SPACE]?.ifBlank { DEFAULT_HF_VOICE_SPACE } ?: DEFAULT_HF_VOICE_SPACE }
    val agentTts: Flow<Boolean> = ds.data.catchIO().map { it[KEY_AGENT_TTS] ?: true }
    val boosterAuto: Flow<Boolean> = ds.data.catchIO().map { it[KEY_BOOSTER_AUTO] ?: false }
    val boosterInterval: Flow<Int> = ds.data.catchIO().map { it[KEY_BOOSTER_INTERVAL] ?: 5 }
    val tempMails: Flow<String> = ds.data.catchIO().map { it[KEY_TEMP_MAILS] ?: "" }
    val quickNotes: Flow<String> = ds.data.catchIO().map { it[KEY_QUICK_NOTES] ?: "" }
    suspend fun setQuickNotes(v: String) = ds.edit { it[KEY_QUICK_NOTES] = v }
    val codeFolder: Flow<String> = ds.data.catchIO().map { it[KEY_CODE_FOLDER] ?: "" }
    suspend fun setCodeFolder(v: String) = ds.edit { it[KEY_CODE_FOLDER] = v }
    val vaultSalt: Flow<String> = ds.data.catchIO().map { it[KEY_VAULT_SALT] ?: "" }
    val vaultCheck: Flow<String> = ds.data.catchIO().map { it[KEY_VAULT_CHECK] ?: "" }
    val vaultBlob: Flow<String> = ds.data.catchIO().map { it[KEY_VAULT_BLOB] ?: "" }
    suspend fun setVault(salt: String, check: String) = ds.edit { it[KEY_VAULT_SALT] = salt; it[KEY_VAULT_CHECK] = check }
    suspend fun setVaultBlob(v: String) = ds.edit { it[KEY_VAULT_BLOB] = v }
    val apiHistory: Flow<String> = ds.data.catchIO().map { it[KEY_API_HISTORY] ?: "" }
    suspend fun setApiHistory(v: String) = ds.edit { it[KEY_API_HISTORY] = v }

    suspend fun setNvidiaKey(v: String)   = ds.edit { it[KEY_NVIDIA_API_KEY] = v }
    suspend fun setFalKey(v: String)      = ds.edit { it[KEY_FAL_API_KEY] = v }
    suspend fun setPollKey(v: String)     = ds.edit { it[KEY_POLL_API_KEY] = v }
    suspend fun setSketchfabToken(v: String) = ds.edit { it[KEY_SKETCHFAB_TOKEN] = v }
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
    suspend fun setAgentVisionModel(v: String) = ds.edit { it[KEY_AGENT_VISION] = v }
    suspend fun setGameModel(v: String) = ds.edit { it[KEY_GAME_MODEL] = v }
    suspend fun setYoutubeKey(v: String) = ds.edit { it[KEY_YT_API] = v.trim() }
    suspend fun setVideoServerUrl(v: String) = ds.edit { it[KEY_VIDEO_SERVER] = v.trim().trimEnd('/') }
    suspend fun setVoiceServerUrl(v: String) = ds.edit { it[KEY_VOICE_SERVER] = v.trim().trimEnd('/') }
    suspend fun setVoiceProvider(v: String) = ds.edit { it[KEY_VOICE_PROVIDER] = v }
    suspend fun setHfToken(v: String) = ds.edit { it[KEY_HF_TOKEN] = v.trim() }
    suspend fun setHfVoiceSpace(v: String) = ds.edit { it[KEY_HF_VOICE_SPACE] = v.trim().trimEnd('/') }
    suspend fun setAgentTts(v: Boolean) = ds.edit { it[KEY_AGENT_TTS] = v }
    suspend fun setBoosterAuto(v: Boolean) = ds.edit { it[KEY_BOOSTER_AUTO] = v }
    suspend fun setBoosterInterval(v: Int) = ds.edit { it[KEY_BOOSTER_INTERVAL] = v }
    suspend fun addTempMail(address: String, password: String) = ds.edit {
        val cur = it[KEY_TEMP_MAILS] ?: ""
        it[KEY_TEMP_MAILS] = ("$address\t$password\n" + cur)
    }
    suspend fun removeTempMail(address: String) = ds.edit { p ->
        val kept = (p[KEY_TEMP_MAILS] ?: "").lineSequence()
            .filter { it.isNotBlank() && it.substringBefore('\t') != address }
        p[KEY_TEMP_MAILS] = kept.joinToString("\n").let { if (it.isBlank()) "" else it + "\n" }
    }
}

private fun Flow<Preferences>.catchIO() = catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}
