package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.ChatMessageEntity
import com.trellis.studio.data.entity.ChatSessionEntity
import com.trellis.studio.data.model.ChatMessage
import com.trellis.studio.data.model.NIM_LLM_MODELS
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedModel: String = AppPrefs.DEFAULT_LLM,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db   = AppDatabase.get(app)
    private val prefs= AppPrefs(app)
    private val nim  = NimClient()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Load sessions
        viewModelScope.launch {
            db.chatDao().getAllSessions().collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
            }
        }
        // Load selected model pref
        viewModelScope.launch {
            prefs.selectedLlm.collect { model ->
                _state.update { it.copy(selectedModel = model) }
            }
        }
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(selectedModel = modelId) }
        viewModelScope.launch { prefs.setSelectedLlm(modelId) }
    }

    /** Open an existing session or create a new one */
    fun openSession(sessionId: Long?) {
        _state.update { it.copy(currentSessionId = sessionId, messages = emptyList(), error = null) }
        if (sessionId != null) {
            viewModelScope.launch {
                db.chatDao().getMessages(sessionId).collect { msgs ->
                    _state.update { it.copy(messages = msgs) }
                }
            }
        }
    }

    fun newSession() {
        _state.update { it.copy(currentSessionId = null, messages = emptyList(), error = null) }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        db.chatDao().deleteSession(id)
        if (_state.value.currentSessionId == id) newSession()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Send a user message (optionally with an image file path for vision models)
     */
    fun sendMessage(userText: String, imagePath: String? = null) {
        val currentState = _state.value
        if (userText.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            // Ensure session exists
            val sessionId = currentState.currentSessionId ?: run {
                val title = userText.take(40).let { if (it.length == 40) "$it…" else it }
                val model = currentState.selectedModel
                db.chatDao().insertSession(ChatSessionEntity(title = title, model = model))
            }
            if (currentState.currentSessionId == null) {
                openSession(sessionId)
            }

            // Save user message to DB
            db.chatDao().insertMessage(
                ChatMessageEntity(sessionId = sessionId, role = "user", content = userText, imagePath = imagePath)
            )

            // Build message history for API
            val history = db.chatDao().getMessages(sessionId)
                .first()
                .map { entity ->
                    val content = if (entity.role == "user" && entity.imagePath != null) {
                        // For vision models: embed base64 image in content (simplified text-only for non-vision)
                        if (isVisionModel(currentState.selectedModel)) {
                            buildVisionContent(entity.content, entity.imagePath)
                        } else entity.content
                    } else entity.content
                    ChatMessage(role = entity.role, content = content)
                }

            // Get preferences
            val apiKey   = prefs.nvidiaKey.first()
            val sysPrompt= prefs.systemPrompt.first()
            val maxTok   = prefs.maxTokens.first()
            val temp     = prefs.temperature.first().toDouble()

            val messagesForApi = buildList {
                if (sysPrompt.isNotBlank()) add(ChatMessage("system", sysPrompt))
                addAll(history)
            }

            // Call NVIDIA NIM
            nim.chat(
                apiKey = apiKey,
                model  = currentState.selectedModel,
                messages = messagesForApi,
                maxTokens = maxTok,
                temperature = temp,
            ).onSuccess { response ->
                db.chatDao().insertMessage(
                    ChatMessageEntity(sessionId = sessionId, role = "assistant", content = response)
                )
                _state.update { it.copy(isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private fun isVisionModel(modelId: String): Boolean =
        NIM_LLM_MODELS.find { it.id == modelId }?.isVision ?: false

    private fun buildVisionContent(text: String, imagePath: String?): String {
        // Simple text fallback; full vision support would use multipart content array
        return if (imagePath != null) "$text [Image attached]" else text
    }
}
