package com.trellis.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.trellis.studio.App
import com.trellis.studio.data.ChatHistoryEntry
import com.trellis.studio.data.ChatModelInfo
import com.trellis.studio.data.ChatStreamEvent
import com.trellis.studio.data.db.ChatMessageEntity
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.components.MessagePart
import com.trellis.studio.ui.components.parseMessageParts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val reasoningContent: String,
    val isStreaming: Boolean,
    val imagePath: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container
    private val dao       = container.database.chatDao()

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId

    private val persistedMessages: StateFlow<List<ChatMessageEntity>> = _sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeMessages(id) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _streamingMessage = MutableStateFlow<ChatUiMessage?>(null)

    val messages: StateFlow<List<ChatUiMessage>> =
        combine(persistedMessages, _streamingMessage) { persisted, streaming ->
            persisted.map {
                ChatUiMessage(it.id, it.role, it.content, it.reasoningContent.orEmpty(), false, it.imagePath)
            } + listOfNotNull(streaming)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedModel = MutableStateFlow(DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel

    private val _availableModels = MutableStateFlow<List<ChatModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ChatModelInfo>> = _availableModels

    private val _isSending         = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _isGeneratingImage = MutableStateFlow(false)
    val isGeneratingImage: StateFlow<Boolean> = _isGeneratingImage

    private val _isSpeaking        = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessage: SharedFlow<String> = _errorMessage

    init { loadModels() }

    fun loadModels(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            runCatching { container.chatRepository.fetchModels(forceRefresh) }
                .onSuccess { _availableModels.value = it }
                .onFailure { _errorMessage.tryEmit(it.toUserMessage()) }
        }
    }

    fun startNewSession() {
        _sessionId.value = null
        _streamingMessage.value = null
    }

    fun openSession(id: Long) {
        _sessionId.value = id
        _streamingMessage.value = null
    }

    fun setModel(model: String) { _selectedModel.value = model }

    fun speak(text: String) {
        _isSpeaking.value = true
        container.voiceSpeaker.speak(text)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        container.voiceSpeaker.stop()
        _isSpeaking.value = false
    }

    fun openInCanvas(language: String, code: String) {
        container.pendingCanvasContent = language to code
    }

    fun sendMessage(text: String, imagePath: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imagePath == null) return
        if (_isSending.value) return

        viewModelScope.launch {
            val historyBeforeThis = persistedMessages.value.map {
                ChatHistoryEntry(it.role, it.content, it.imagePath)
            }

            val sid = _sessionId.value ?: run {
                val newId = dao.insertSession(
                    com.trellis.studio.data.db.ChatSession(
                        title = trimmed.take(48).ifBlank { "Image chat" },
                        model = _selectedModel.value,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _sessionId.value = newId
                newId
            }

            dao.insertMessage(
                ChatMessageEntity(
                    sessionId  = sid,
                    role       = ChatMessageEntity.ROLE_USER,
                    content    = trimmed,
                    reasoningContent = null,
                    createdAt  = System.currentTimeMillis(),
                    imagePath  = imagePath
                )
            )

            _isSending.value = true
            _streamingMessage.value = ChatUiMessage(
                id = STREAMING_ID, role = ChatMessageEntity.ROLE_ASSISTANT,
                content = "", reasoningContent = "", isStreaming = true
            )

            val contentBuilder   = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var streamError: String? = null

            val newEntry = ChatHistoryEntry(ChatMessageEntity.ROLE_USER, trimmed, imagePath)
            container.chatRepository.streamChat(
                _selectedModel.value,
                historyBeforeThis + newEntry
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        contentBuilder.append(event.text)
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = contentBuilder.toString(),
                            reasoningContent = reasoningBuilder.toString()
                        )
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        reasoningBuilder.append(event.text)
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = contentBuilder.toString(),
                            reasoningContent = reasoningBuilder.toString()
                        )
                    }
                    is ChatStreamEvent.Error -> streamError = event.message
                    ChatStreamEvent.Done    -> Unit
                }
            }

            if (streamError != null && contentBuilder.isEmpty()) {
                _errorMessage.tryEmit(streamError!!)
            } else {
                dao.insertMessage(
                    ChatMessageEntity(
                        sessionId  = sid,
                        role       = ChatMessageEntity.ROLE_ASSISTANT,
                        content    = contentBuilder.toString(),
                        reasoningContent = reasoningBuilder.toString().ifBlank { null },
                        createdAt  = System.currentTimeMillis()
                    )
                )
                streamError?.let { _errorMessage.tryEmit(it) }
            }
            kotlinx.coroutines.delay(80)
            _streamingMessage.value = null
            _isSending.value = false
        }
    }

    fun generateImage(prompt: String) {
        if (prompt.isBlank() || _isGeneratingImage.value) return
        viewModelScope.launch {
            val sid = _sessionId.value ?: run {
                val newId = dao.insertSession(
                    com.trellis.studio.data.db.ChatSession(
                        title = "Image: ${prompt.take(40)}",
                        model = _selectedModel.value,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _sessionId.value = newId
                newId
            }
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = ChatMessageEntity.ROLE_USER,
                    content = "Generate image: $prompt", reasoningContent = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            _isGeneratingImage.value = true
            runCatching { container.imageRepository.generateImage(prompt) }
                .onSuccess { file ->
                    dao.insertMessage(
                        ChatMessageEntity(
                            sessionId = sid, role = ChatMessageEntity.ROLE_ASSISTANT,
                            content = "![generated image](${file.absolutePath})",
                            reasoningContent = null, createdAt = System.currentTimeMillis()
                        )
                    )
                }
                .onFailure { _errorMessage.tryEmit(it.toUserMessage()) }
            _isGeneratingImage.value = false
        }
    }

    companion object {
        const val DEFAULT_MODEL  = "meta/llama-3.3-70b-instruct"
        const val STREAMING_ID   = -1L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenHistory: () -> Unit,
    onOpenSessionRequest: Long?,
    onOpenCanvas: () -> Unit,
    onNavigateToTerminal: () -> Unit = {},
    viewModel: ChatViewModel = viewModel()
) {
    val context            = LocalContext.current
    val messages           by viewModel.messages.collectAsState()
    val selectedModel      by viewModel.selectedModel.collectAsState()
    val availableModels    by viewModel.availableModels.collectAsState()
    val isSending          by viewModel.isSending.collectAsState()
    val isGeneratingImage  by viewModel.isGeneratingImage.collectAsState()
    val isSpeaking         by viewModel.isSpeaking.collectAsState()
    val snackbarHostState  = remember { SnackbarHostState() }
    val listState          = rememberLazyListState()
    var input              by rememberSaveable { mutableStateOf("") }
    var modelMenuExpanded  by remember { mutableStateOf(false) }
    var attachedImageUri   by remember { mutableStateOf<Uri?>(null) }
    var attachedImagePath  by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            attachedImageUri = uri
            // Copy to cache so we can read it later without persistent URI permission
            val ext  = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "jpg"
            val dest = File(context.cacheDir, "chat_img_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            attachedImagePath = dest.absolutePath
        }
    }

    LaunchedEffect(onOpenSessionRequest) {
        onOpenSessionRequest?.let { viewModel.openSession(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Column(modifier = Modifier.clickable { modelMenuExpanded = true }) {
                            Text(
                                modelDisplayName(selectedModel),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "NVIDIA NIM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            if (availableModels.isEmpty()) {
                                DropdownMenuItem(text = { Text("Loading models…") }, onClick = {})
                            }
                            availableModels.take(200).forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                model.id.substringAfterLast('/'),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                model.ownedBy,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setModel(model.id)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = { viewModel.stopSpeaking() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop speaking",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { viewModel.startNewSession() }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "History")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages area
            if (messages.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "NIM AI Agent",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Powered by NVIDIA NIM · 100+ LLM models\n" +
                            "Attach images · Generate images · Build games\n" +
                            "Type \"start\" to open the Linux terminal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            onSpeak  = { viewModel.speak(it) },
                            onOpenCanvas = { language, code ->
                                viewModel.openInCanvas(language, code)
                                onOpenCanvas()
                            }
                        )
                    }
                    if (isGeneratingImage) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "Generating image via Pollinations FLUX…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Attached image preview
            if (attachedImageUri != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(attachedImageUri),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        "Image attached",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { attachedImageUri = null; attachedImagePath = null },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove image",
                            modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Image attach
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Generate image
                IconButton(
                    onClick = { if (input.isNotBlank()) viewModel.generateImage(input.trim()) },
                    enabled = !isGeneratingImage
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Generate image",
                        tint = if (!isGeneratingImage) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Message NIM Agent…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions.Default,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor          = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(20.dp)
                )

                IconButton(
                    onClick = {
                        val text = input.trim()
                        val img  = attachedImagePath
                        input = ""
                        attachedImageUri  = null
                        attachedImagePath = null
                        // Smart commands: "start" or "terminal" → open terminal
                        if (img == null && (text.equals("start", ignoreCase = true) ||
                                            text.equals("terminal", ignoreCase = true))) {
                            onNavigateToTerminal()
                        } else {
                            viewModel.sendMessage(text, img)
                        }
                    },
                    enabled = (input.isNotBlank() || attachedImageUri != null) && !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (input.isNotBlank() || attachedImageUri != null)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(18.dp),
                                tint = if (input.isNotBlank() || attachedImageUri != null)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun modelDisplayName(modelId: String): String = modelId.substringAfterLast('/')

@Composable
private fun ChatBubble(
    message: ChatUiMessage,
    onSpeak: (String) -> Unit,
    onOpenCanvas: (String, String) -> Unit
) {
    val isUser    = message.role == ChatMessageEntity.ROLE_USER
    val clipboard = LocalClipboardManager.current
    var thinkingExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // AI avatar chip
            if (!isUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "N",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        "NIM Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Thinking block (reasoning models)
            if (!isUser && message.reasoningContent.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "Thinking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { thinkingExpanded = !thinkingExpanded },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    if (thinkingExpanded) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        if (thinkingExpanded) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                message.reasoningContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
            }

            // Attached image (user side)
            if (isUser && message.imagePath != null) {
                Image(
                    painter = rememberAsyncImagePainter(File(message.imagePath)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 4.dp),
                    contentScale = ContentScale.Crop
                )
            }

            // Message bubble
            if (message.content.isNotBlank() || message.isStreaming) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    ),
                    shape = if (isUser)
                        RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
                    else
                        RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (message.content.isEmpty() && message.isStreaming) {
                            TypingDots()
                        } else {
                            parseMessageParts(message.content).forEach { part ->
                                when (part) {
                                    is MessagePart.Text -> if (part.text.isNotBlank()) {
                                        Text(
                                            part.text.trim(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isUser)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    is MessagePart.Code -> CodeBlock(
                                        language = part.language,
                                        code = part.code,
                                        onOpenCanvas = { onOpenCanvas(part.language, part.code) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action row
            if (!message.isStreaming && message.content.isNotBlank()) {
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isUser) {
                        IconButton(
                            onClick = { onSpeak(message.content) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String, onOpenCanvas: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0D1117)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenCanvas, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "Open in canvas",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    code.trim(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE6EDF3)
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    var dot by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dot = (dot + 1) % 4
        }
    }
    Text(
        "●".repeat(dot + 1).padEnd(3, '○'),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
}
