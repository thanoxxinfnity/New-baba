package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.prefs.AppPrefs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)

    val nvidiaKey   = prefs.nvidiaKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val falKey      = prefs.falKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val pollKey     = prefs.pollKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val youtubeKey  = prefs.youtubeKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoServerUrl = prefs.videoServerUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val voiceServerUrl = prefs.voiceServerUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val voiceProvider = prefs.voiceProvider.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.VOICE_PROVIDER_NVIDIA)
    val hfToken = prefs.hfToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val hfVoiceSpace = prefs.hfVoiceSpace.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_HF_VOICE_SPACE)
    val selectedLlm = prefs.selectedLlm.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_LLM)
    val selectedImg = prefs.selectedImg.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_IMG)
    val selected3d  = prefs.selected3d.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_3D)
    val selectedTts = prefs.selectedTts.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_TTS)
    val systemPrompt= prefs.systemPrompt.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs.DEFAULT_SYSTEM)
    val maxTokens   = prefs.maxTokens.stateIn(viewModelScope, SharingStarted.Eagerly, 2048)
    val temperature = prefs.temperature.stateIn(viewModelScope, SharingStarted.Eagerly, 0.7f)
    val buildServerUrl = prefs.buildServerUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val hinglishThinking = prefs.hinglishThinking.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setNvidiaKey(v: String)    = viewModelScope.launch { prefs.setNvidiaKey(v.trim()) }
    fun setFalKey(v: String)       = viewModelScope.launch { prefs.setFalKey(v.trim()) }
    fun setPollKey(v: String)      = viewModelScope.launch { prefs.setPollKey(v.trim()) }
    fun setYoutubeKey(v: String)   = viewModelScope.launch { prefs.setYoutubeKey(v.trim()) }
    fun setVideoServerUrl(v: String) = viewModelScope.launch { prefs.setVideoServerUrl(v.trim()) }
    fun setVoiceServerUrl(v: String) = viewModelScope.launch { prefs.setVoiceServerUrl(v.trim()) }
    fun setVoiceProvider(v: String) = viewModelScope.launch { prefs.setVoiceProvider(v) }
    fun setHfToken(v: String) = viewModelScope.launch { prefs.setHfToken(v.trim()) }
    fun setHfVoiceSpace(v: String) = viewModelScope.launch { prefs.setHfVoiceSpace(v.trim()) }
    fun setSelectedLlm(v: String)  = viewModelScope.launch { prefs.setSelectedLlm(v) }
    fun setSelectedImg(v: String)  = viewModelScope.launch { prefs.setSelectedImg(v) }
    fun setSelected3d(v: String)   = viewModelScope.launch { prefs.setSelected3d(v) }
    fun setSelectedTts(v: String)  = viewModelScope.launch { prefs.setSelectedTts(v) }
    fun setSystemPrompt(v: String) = viewModelScope.launch { prefs.setSystemPrompt(v) }
    fun setMaxTokens(v: Int)       = viewModelScope.launch { prefs.setMaxTokens(v) }
    fun setTemperature(v: Float)   = viewModelScope.launch { prefs.setTemperature(v) }
    fun setBuildServerUrl(v: String) = viewModelScope.launch { prefs.setBuildServerUrl(v) }
    fun setHinglishThinking(v: Boolean) = viewModelScope.launch { prefs.setHinglishThinking(v) }
}
