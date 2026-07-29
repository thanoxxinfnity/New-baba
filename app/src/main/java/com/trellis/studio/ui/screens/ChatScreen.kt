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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.trellis.studio.App
import com.trellis.studio.data.ChatHistoryEntry
import com.trellis.studio.data.ChatModelInfo
import com.trellis.studio.data.ChatRepository
import com.trellis.studio.data.ChatStreamEvent
import com.trellis.studio.data.db.ChatMessageEntity
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.components.MessagePart
import com.trellis.studio.ui.components.parseMessageParts
import com.trellis.studio.ui.theme.NimOrange
import kotlinx.coroutines.Job
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
import java.util.Calendar

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

    private var streamingJob: Job? = null

    fun cancelCurrentStream() {
        streamingJob?.cancel()
        streamingJob = null
        _streamingMessage.value = null
        _isSending.value = false
    }

    init { loadModels() }

    fun loadModels(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            runCatching { container.chatRepository.fetchModels(forceRefresh) }
                .onSuccess { _availableModels.value = it }
                .onFailure { _errorMessage.tryEmit(it.toUserMessage()) }
        }
    }

    fun startNewSession() {
        cancelCurrentStream()
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

        streamingJob = viewModelScope.launch {
            val historyBeforeThis = persistedMessages.value.map {
                ChatHistoryEntry(it.role, it.content, it.imagePath)
            }

            val sid = _sessionId.value ?: run {
                val newId = dao.insertSession(
                    com.trellis.studio.data.db.ChatSession(
                        title     = trimmed.take(48).ifBlank { "Image chat" },
                        model     = _selectedModel.value,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _sessionId.value = newId
                newId
            }

            dao.insertMessage(
                ChatMessageEntity(
                    sessionId        = sid,
                    role             = ChatMessageEntity.ROLE_USER,
                    content          = trimmed,
                    reasoningContent = null,
                    createdAt        = System.currentTimeMillis(),
                    imagePath        = imagePath
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
                            content          = contentBuilder.toString(),
                            reasoningContent = reasoningBuilder.toString()
                        )
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        reasoningBuilder.append(event.text)
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content          = contentBuilder.toString(),
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
                        sessionId        = sid,
                        role             = ChatMessageEntity.ROLE_ASSISTANT,
                        content          = contentBuilder.toString(),
                        reasoningContent = reasoningBuilder.toString().ifBlank { null },
                        createdAt        = System.currentTimeMillis()
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
                        title     = "Image: ${prompt.take(40)}",
                        model     = _selectedModel.value,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _sessionId.value = newId
                newId
            }
            dao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid, role = ChatMessageEntity.ROLE_USER,
                    content   = "Generate image: $prompt", reasoningContent = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            _isGeneratingImage.value = true
            runCatching { container.imageRepository.generateImage(prompt) }
                .onSuccess { file ->
                    dao.insertMessage(
                        ChatMessageEntity(
                            sessionId = sid, role = ChatMessageEntity.ROLE_ASSISTANT,
                            content   = "![generated image](${file.absolutePath})",
                            reasoningContent = null, createdAt = System.currentTimeMillis()
                        )
                    )
                }
                .onFailure { _errorMessage.tryEmit(it.toUserMessage()) }
            _isGeneratingImage.value = false
        }
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-ai/deepseek-r1"
        const val STREAMING_ID  = -1L
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenSessionRequest: Long?,
    onOpenCanvas: () -> Unit,
    onNavigateToTerminal: () -> Unit = {},
    viewModel: ChatViewModel = viewModel()
) {
    val context           = LocalContext.current
    val messages          by viewModel.messages.collectAsState()
    val selectedModel     by viewModel.selectedModel.collectAsState()
    val availableModels   by viewModel.availableModels.collectAsState()
    val isSending         by viewModel.isSending.collectAsState()
    val isGeneratingImage by viewModel.isGeneratingImage.collectAsState()
    val isSpeaking        by viewModel.isSpeaking.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState         = rememberLazyListState()
    var input             by rememberSaveable { mutableStateOf("") }
    var showModelPicker   by remember { mutableStateOf(false) }
    var attachedImageUri  by remember { mutableStateOf<Uri?>(null) }
    var attachedImagePath by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            attachedImageUri = uri
            val ext  = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "jpg"
            val dest = File(context.cacheDir, "chat_img_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            attachedImagePath = dest.absolutePath
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelCurrentStream() }
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

    fun doSend() {
        val text = input.trim()
        val img  = attachedImagePath
        input             = ""
        attachedImageUri  = null
        attachedImagePath = null
        if (img == null && (text.equals("start", ignoreCase = true) ||
                            text.equals("terminal", ignoreCase = true))) {
            onNavigateToTerminal()
        } else {
            viewModel.sendMessage(text, img)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost   = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Top bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isSpeaking) {
                    IconButton(onClick = { viewModel.stopSpeaking() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop speaking",
                            tint = NimOrange)
                    }
                }
                IconButton(onClick = { viewModel.startNewSession() }) {
                    Icon(Icons.Default.Edit, contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            // ── Messages or welcome ───────────────────────────────────────
            if (messages.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    NimWelcomeState()
                }
            } else {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(
                            message      = message,
                            onSpeak      = { viewModel.speak(it) },
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
                                modifier          = Modifier.padding(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                    color = NimOrange)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "Generating image…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // ── Floating input card ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            ) {
                // Attached image preview
                if (attachedImageUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter            = rememberAsyncImagePainter(attachedImageUri),
                            contentDescription = null,
                            modifier           = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale       = ContentScale.Crop
                        )
                        Text(
                            "Image attached",
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick  = { attachedImageUri = null; attachedImagePath = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove",
                                modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Card
                Card(
                    shape     = RoundedCornerShape(22.dp),
                    colors    = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(Modifier.padding(4.dp)) {
                        // Text input
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
                        ) {
                            BasicTextField(
                                value        = input,
                                onValueChange = { input = it },
                                modifier     = Modifier.fillMaxWidth(),
                                textStyle    = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush  = SolidColor(NimOrange),
                                maxLines     = 6,
                                decorationBox = { inner ->
                                    Box {
                                        if (input.isEmpty()) {
                                            Text(
                                                "Message NIM Agent…",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                        }

                        // Tools row
                        Row(
                            modifier             = Modifier.padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
                            verticalAlignment    = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Attach file
                            IconButton(
                                onClick  = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Model selector chip → opens bottom sheet
                            Card(
                                modifier  = Modifier.clickable { showModelPicker = true },
                                shape     = RoundedCornerShape(20.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        modelShortName(selectedModel),
                                        style    = MaterialTheme.typography.labelMedium,
                                        color    = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = "Change model",
                                        modifier = Modifier.size(14.dp),
                                        tint     = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            // Generate image
                            IconButton(
                                onClick  = { if (input.isNotBlank()) viewModel.generateImage(input.trim()) },
                                enabled  = !isGeneratingImage,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = "Generate image",
                                    tint = if (!isGeneratingImage) MaterialTheme.colorScheme.onSurfaceVariant
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }

                            // Send button
                            val canSend = (input.isNotBlank() || attachedImageUri != null) && !isSending
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (canSend) NimOrange
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable(enabled = canSend) { doSend() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color    = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        modifier = Modifier.size(16.dp),
                                        tint     = if (canSend) Color.White
                                                   else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Model picker bottom sheet ─────────────────────────────────────────────
    if (showModelPicker) {
        ModelPickerSheet(
            models       = availableModels,
            currentModel = selectedModel,
            onSelect     = { viewModel.setModel(it) },
            onDismiss    = { showModelPicker = false }
        )
    }
}

// ── Welcome state ─────────────────────────────────────────────────────────────

@Composable
private fun NimWelcomeState() {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 5  -> "Good night."
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else      -> "Good evening."
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint     = NimOrange
        )
        Text(
            greeting,
            style     = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Medium
            ),
            color     = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            "How can I help you today?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

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
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier            = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // AI avatar label
            if (!isUser) {
                Row(
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement  = Arrangement.spacedBy(6.dp),
                    modifier               = Modifier.padding(bottom = 4.dp, start = 4.dp)
                ) {
                    Box(
                        modifier          = Modifier
                            .size(22.dp)
                            .background(NimOrange, CircleShape),
                        contentAlignment  = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint     = Color.White
                        )
                    }
                    Text(
                        "NIM Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Thinking / reasoning block
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
                            modifier          = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null,
                                modifier = Modifier.size(14.dp), tint = NimOrange)
                            Spacer(Modifier.size(6.dp))
                            Text("Thinking", style = MaterialTheme.typography.labelSmall,
                                color = NimOrange, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick  = { thinkingExpanded = !thinkingExpanded },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    if (thinkingExpanded) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (thinkingExpanded) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                message.reasoningContent,
                                style     = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
            }

            // Attached image (user side)
            if (isUser && message.imagePath != null) {
                Image(
                    painter            = rememberAsyncImagePainter(File(message.imagePath)),
                    contentDescription = null,
                    modifier           = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .padding(bottom = 4.dp),
                    contentScale       = ContentScale.Crop
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
                        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
                    else
                        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
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
                                        language     = part.language,
                                        code         = part.code,
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
                var copied by remember { mutableStateOf(false) }
                Row(modifier = Modifier.padding(top = 2.dp, start = if (isUser) 0.dp else 4.dp)) {
                    // One-click copy with visual feedback
                    IconButton(
                        onClick  = {
                            clipboard.setText(AnnotatedString(message.content))
                            copied = true
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(15.dp),
                            tint     = if (copied) Color(0xFF4CAF50)
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LaunchedEffect(copied) {
                        if (copied) {
                            kotlinx.coroutines.delay(1500)
                            copied = false
                        }
                    }
                    if (!isUser) {
                        IconButton(
                            onClick  = { onSpeak(message.content) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── Code block ────────────────────────────────────────────────────────────────

@Composable
private fun CodeBlock(language: String, code: String, onOpenCanvas: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier  = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
        shape     = RoundedCornerShape(10.dp)
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
                    style    = MaterialTheme.typography.labelSmall,
                    color    = NimOrange,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenCanvas, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "Open in canvas",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick  = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    style      = MaterialTheme.typography.bodySmall,
                    color      = Color(0xFFE6EDF3)
                )
            }
        }
    }
}

// ── Typing dots ───────────────────────────────────────────────────────────────

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
        color = NimOrange
    )
}

// ── Model picker bottom sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ModelPickerSheet(
    models: List<ChatModelInfo>,
    currentModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }

    val chatCategories = remember { setOf("Reasoning", "Coding", "Deep Thinking", "Chat & RAG", "Vision & Industrial") }

    val filtered by remember(search, models) {
        derivedStateOf {
            val q = search.trim().lowercase()
            models.filter { m ->
                m.ownedBy in chatCategories &&
                (q.isEmpty() || m.id.lowercase().contains(q) || m.ownedBy.lowercase().contains(q))
            }
        }
    }
    val grouped by remember(filtered) {
        derivedStateOf { filtered.groupBy { it.ownedBy } }
    }

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        sheetState        = sheetState,
        containerColor    = MaterialTheme.colorScheme.surface,
        dragHandle        = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null,
                    tint = NimOrange, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    "Choose Model",
                    style      = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color      = MaterialTheme.colorScheme.onSurface,
                    modifier   = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value         = search,
                onValueChange = { search = it },
                placeholder   = { Text("Search models…") },
                leadingIcon   = {
                    Icon(Icons.Default.Search, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                },
                modifier    = Modifier.fillMaxWidth(),
                shape       = RoundedCornerShape(14.dp),
                singleLine  = true,
                colors      = TextFieldDefaults.colors(
                    focusedIndicatorColor   = NimOrange,
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )
            Spacer(Modifier.size(4.dp))
        }

        LazyColumn(
            modifier       = Modifier.fillMaxHeight(0.65f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            grouped.entries.forEachIndexed { catIndex, (category, catModels) ->
                stickyHeader(key = "header_$category") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = if (catIndex == 0) 0.dp else 8.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text  = category.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NimOrange,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                        HorizontalDivider(
                            modifier  = Modifier.padding(top = 4.dp),
                            color     = NimOrange.copy(alpha = 0.25f),
                            thickness = 0.8.dp
                        )
                    }
                }
                items(catModels, key = { it.id }) { model ->
                    val isSelected = model.id == currentModel
                    val isFast = model.id in ChatRepository.FAST_MODELS
                    Card(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onSelect(model.id); onDismiss() },
                        colors    = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                NimOrange.copy(alpha = 0.12f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape     = RoundedCornerShape(10.dp),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(
                            modifier          = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text       = model.id.substringAfterLast('/'),
                                        style      = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color      = if (isSelected) NimOrange
                                                     else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isFast) {
                                        Text(
                                            "⚡ Fast",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                }
                                Text(
                                    text  = model.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null,
                                    tint     = NimOrange,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun modelShortName(id: String): String {
    val base = id.substringAfterLast('/')
    return when {
        base.length <= 24 -> base
        else              -> base.take(22) + "…"
    }
}
