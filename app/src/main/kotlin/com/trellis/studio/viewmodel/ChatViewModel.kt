package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.ChatMessageEntity
import com.trellis.studio.data.entity.ChatSessionEntity
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.data.model.LlmModel
import com.trellis.studio.data.model.NIM_IMAGE_MODELS
import com.trellis.studio.data.model.NIM_LLM_MODELS
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.ImageGenClient
import com.trellis.studio.network.NimClient
import com.trellis.studio.network.RemoteApkBuilder
import com.trellis.studio.network.TtydClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedModel: String = AppPrefs.DEFAULT_LLM,

    // --- live streaming state, drives the thinking bubble ---
    /** Reasoning tokens received so far for the in-flight reply. */
    val streamingReasoning: String = "",
    /** Answer tokens received so far for the in-flight reply. */
    val streamingContent: String = "",
    /** True while reasoning tokens are still arriving. */
    val isThinking: Boolean = false,
    /** Seconds the model spent thinking, shown on the collapsed bubble. */
    val thoughtSeconds: Int = 0,

    /** When on, the next message generates an image instead of text. */
    val imageMode: Boolean = false,
    /** Transient progress note, e.g. "Generating image…". */
    val statusMessage: String? = null,
) {
    /** True once the reply has started but nothing has been persisted yet. */
    val isStreaming: Boolean
        get() = isLoading && (streamingReasoning.isNotEmpty() || streamingContent.isNotEmpty())
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db   = AppDatabase.get(app)
    private val prefs= AppPrefs(app)
    private val nim  = NimClient()
    private val imageClient = ImageGenClient(app)
    private val ttyd = TtydClient()
    private val apkBuilder = RemoteApkBuilder(ttyd)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Collector for the open session's messages. Cancelled before starting a new one
     *  so old sessions can't keep pushing their messages into the current screen. */
    private var messagesJob: Job? = null

    /** Guards against two sends racing and double-inserting. */
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            db.chatDao().getAllSessions().collect { sessions ->
                _state.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            prefs.selectedLlm.collect { model ->
                _state.update { it.copy(selectedModel = model) }
            }
        }
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(selectedModel = modelId, error = null) }
        viewModelScope.launch { prefs.setSelectedLlm(modelId) }
    }

    /** Open an existing session, replacing any previous message collector. */
    fun openSession(sessionId: Long?) {
        messagesJob?.cancel()
        messagesJob = null
        _state.update { it.copy(currentSessionId = sessionId, messages = emptyList(), error = null) }
        if (sessionId != null) {
            messagesJob = viewModelScope.launch {
                db.chatDao().getMessages(sessionId).collect { msgs ->
                    _state.update { current ->
                        // Ignore late emissions from a session the user already left.
                        if (current.currentSessionId == sessionId) current.copy(messages = msgs) else current
                    }
                }
            }
        }
    }

    fun newSession() {
        messagesJob?.cancel()
        messagesJob = null
        _state.update { it.copy(currentSessionId = null, messages = emptyList(), error = null) }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        runCatching { db.chatDao().deleteSession(id) }
        if (_state.value.currentSessionId == id) newSession()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Toggles "draw this" mode, where the next message generates an image. */
    fun setImageMode(on: Boolean) = _state.update { it.copy(imageMode = on) }

    /**
     * Phrases that mean "make me a picture". Used to auto-route a message to
     * image generation the way Gemini does, without needing a command.
     */
    private fun looksLikeImageRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.startsWith("/image") || t.startsWith("/img") || t.startsWith("/draw")) return true

        // Phrases are specific enough to match anywhere in the message.
        val phrases = listOf("generate an image", "generate image", "create an image",
            "make an image", "picture of", "image of", "photo of",
            "photo bana", "image bana", "bana do")
        if (phrases.any { t.contains(it) }) return true

        // Single words must match as whole words. Substring matching turned
        // "how do I withdraw money" into a drawing request (withdraw → draw), and
        // "file a complaint" into a painting one (complaint → paint).
        val verbs = listOf("draw", "paint", "illustrate", "banao")
        return verbs.any { verb -> Regex("\\b${Regex.escape(verb)}\\b").containsMatchIn(t) }
    }

    /** Strips the leading command so the prompt reads naturally. */
    private fun cleanImagePrompt(text: String): String =
        text.trim().removePrefix("/image").removePrefix("/img").removePrefix("/draw").trim()
            .ifBlank { text.trim() }

    /**
     * Generates an image and stores it on an assistant message, so it renders
     * inline in the conversation.
     */
    private suspend fun generateImageReply(sessionId: Long, prompt: String) {
        _state.update { it.copy(statusMessage = "Generating image…") }
        // The picked model is an NVIDIA NIM one (FLUX by default), so it needs the
        // nvapi- key. Reading pollKey here meant chat images ran with a blank key
        // and always silently dropped to Pollinations — which is why pictures made
        // in Chat came out softer than the same prompt on the Create screen.
        val apiKey = prefs.nvidiaKey.first()
        val modelId = prefs.selectedImg.first()
        val model = NIM_IMAGE_MODELS.find { it.id == modelId } ?: NIM_IMAGE_MODELS.first()

        imageClient.generateImage(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            width = 1024,
            height = 1024,
            seed = System.currentTimeMillis() % 100000,
        ).onSuccess { path ->
            db.chatDao().insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = "Here's \"$prompt\".",
                    imagePath = path,
                )
            )
            db.generationDao().insert(
                GenerationEntity(type = "image", prompt = prompt, imagePath = path)
            )
            _state.update { it.copy(isLoading = false, statusMessage = null, error = null) }
        }.onFailure { e ->
            _state.update {
                it.copy(isLoading = false, statusMessage = null, error = e.message ?: "Image generation failed")
            }
        }
    }

    /**
     * Writes an Android project with the model, then builds it on the user's
     * terminal. If that terminal is offline the code is still delivered, with
     * instructions — the work is not lost.
     */
    private suspend fun buildAppReply(sessionId: Long, request: String) {
        _state.update { it.copy(statusMessage = "Writing the project…") }
        val apiKey = prefs.nvidiaKey.first()

        val reply = nim.chat(
            apiKey = apiKey,
            model = _state.value.selectedModel,
            turns = AppBuilder.projectPrompt(request),
            maxTokens = 4096,
            temperature = 0.3,
        ).getOrElse { e ->
            db.chatDao().insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId, role = "assistant",
                    content = "Couldn't write the project: ${e.message}",
                )
            )
            _state.update { it.copy(isLoading = false, statusMessage = null) }
            return
        }

        val files = AppBuilder.withDefaults(AppBuilder.parseProject(reply.content))
        // Always show the code, whether or not a build is possible.
        db.chatDao().insertMessage(
            ChatMessageEntity(sessionId = sessionId, role = "assistant", content = reply.content)
        )

        if (files.isEmpty()) {
            _state.update { it.copy(isLoading = false, statusMessage = null) }
            return
        }

        val terminalUrl = prefs.buildServerUrl.first()
        if (terminalUrl.isBlank()) {
            db.chatDao().insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId, role = "assistant",
                    content = "No build terminal is set. Add its URL in Settings, " +
                        "then say \"terminal is on\" and I'll compile this.",
                )
            )
            _state.update { it.copy(isLoading = false, statusMessage = null) }
            return
        }

        _state.update { it.copy(statusMessage = "Building APK on your machine…") }
        apkBuilder.build(terminalUrl, "voidapp", files)
            .onSuccess { artifact ->
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = "assistant",
                        content = "**APK ready — ${artifact.name}**\n\n" +
                            "Download: ${artifact.url}\n\n" +
                            "It's also in the Builds tab, where you can install it directly.",
                    )
                )
            }
            .onFailure { e ->
                val offline = e.message?.contains("offline", true) == true ||
                    e.message?.contains("reach", true) == true
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = "assistant",
                        content = if (offline) AppBuilder.OFFLINE_HINT
                        else "Build failed: ${e.message}",
                    )
                )
            }
        _state.update { it.copy(isLoading = false, statusMessage = null) }
    }

    /**
     * Send a user message, optionally with an image (vision models only).
     */
    fun sendMessage(userText: String, imagePath: String? = null) {
        val text = userText.trim()
        if (text.isBlank()) return
        if (sendJob?.isActive == true) return

        sendJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    streamingReasoning = "",
                    streamingContent = "",
                    isThinking = false,
                    thoughtSeconds = 0,
                )
            }
            try {
                val modelId = _state.value.selectedModel
                val model = NIM_LLM_MODELS.find { it.id == modelId }

                // Ensure a session exists, and open it so the UI follows along.
                var sessionId = _state.value.currentSessionId
                if (sessionId == null) {
                    val title = text.take(40).let { if (text.length > 40) "$it…" else it }
                    sessionId = db.chatDao().insertSession(
                        ChatSessionEntity(title = title, model = modelId)
                    )
                    openSession(sessionId)
                }

                // Only keep the image if the model can actually see it.
                val visionCapable = model?.isVision == true
                val storedImage = imagePath?.takeIf { visionCapable }

                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "user",
                        content = text,
                        imagePath = storedImage,
                    )
                )

                // "Build me an app" → write the project, then compile it on the
                // user's own machine and hand back a downloadable APK.
                if (AppBuilder.looksLikeAppRequest(text)) {
                    buildAppReply(sessionId, AppBuilder.cleanRequest(text))
                    return@launch
                }

                // Image request? Generate a picture and render it in the thread.
                if (_state.value.imageMode || looksLikeImageRequest(text)) {
                    generateImageReply(sessionId, cleanImagePrompt(text))
                    return@launch
                }

                val sysPrompt = prefs.systemPrompt.first()
                val requestedMax = prefs.maxTokens.first()
                val temp = prefs.temperature.first().toDouble()
                val apiKey = prefs.nvidiaKey.first()

                // Never ask for more output tokens than the model's window can hold.
                val windowTokens = (model?.contextK ?: 128) * 1024
                val maxTok = requestedMax.coerceAtMost(windowTokens / 2).coerceAtLeast(64)

                val hinglish = prefs.hinglishThinking.first()
                val effectivePrompt =
                    if (hinglish) sysPrompt + AppPrefs.HINGLISH_SUFFIX else sysPrompt

                val history = db.chatDao().getMessages(sessionId).first()
                val turns = buildTurns(
                    history = history,
                    systemPrompt = effectivePrompt,
                    windowTokens = windowTokens,
                    reservedTokens = maxTok,
                    visionCapable = visionCapable,
                )

                val startedAt = System.currentTimeMillis()

                nim.chatStream(
                    apiKey = apiKey,
                    model = modelId,
                    turns = turns,
                    maxTokens = maxTok,
                    temperature = temp,
                    isVisionModel = visionCapable,
                    onReasoning = { token ->
                        _state.update {
                            it.copy(
                                streamingReasoning = it.streamingReasoning + token,
                                isThinking = true,
                            )
                        }
                    },
                    onContent = { token ->
                        _state.update { current ->
                            // First answer token ends the thinking phase and freezes the timer.
                            val seconds = if (current.isThinking) {
                                ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(1)
                            } else current.thoughtSeconds
                            current.copy(
                                streamingContent = current.streamingContent + token,
                                isThinking = false,
                                thoughtSeconds = seconds,
                            )
                        }
                    },
                ).onSuccess { result ->
                    db.chatDao().insertMessage(
                        ChatMessageEntity(
                            sessionId = sessionId,
                            role = "assistant",
                            content = result.content,
                            reasoningContent = result.reasoning,
                        )
                    )
                    // Clear the live buffers now that the message is persisted.
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            streamingReasoning = "",
                            streamingContent = "",
                            isThinking = false,
                        )
                    }
                }.onFailure { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Request failed",
                            streamingReasoning = "",
                            streamingContent = "",
                            isThinking = false,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected (DB, encoding, …) must still clear the spinner.
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Something went wrong",
                        streamingReasoning = "",
                        streamingContent = "",
                        isThinking = false,
                    )
                }
            }
        }
    }

    /**
     * Converts stored messages into API turns, dropping the oldest ones so the
     * conversation always fits the model's context window.
     */
    private fun buildTurns(
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        windowTokens: Int,
        reservedTokens: Int,
        visionCapable: Boolean,
    ): List<ChatTurn> {
        // ~4 chars per token, plus headroom for an inline image.
        var budgetChars = ((windowTokens - reservedTokens).coerceAtLeast(512)) * 4
        if (visionCapable && history.any { it.imagePath != null }) budgetChars -= 6000
        budgetChars = budgetChars.coerceAtLeast(1000)

        if (systemPrompt.isNotBlank()) budgetChars -= systemPrompt.length

        // Walk newest → oldest so the most recent context always survives.
        val kept = ArrayDeque<ChatMessageEntity>()
        var used = 0
        for (msg in history.asReversed()) {
            val cost = msg.content.length + 16
            if (used + cost > budgetChars && kept.isNotEmpty()) break
            kept.addFirst(msg)
            used += cost
        }

        return buildList {
            if (systemPrompt.isNotBlank()) add(ChatTurn("system", systemPrompt))
            kept.forEach { msg ->
                add(ChatTurn(role = msg.role, text = msg.content, imagePath = msg.imagePath))
            }
        }
    }
}
