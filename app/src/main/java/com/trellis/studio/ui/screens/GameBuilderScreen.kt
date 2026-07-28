package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.ChatHistoryEntry
import com.trellis.studio.data.ChatRepository
import com.trellis.studio.data.ChatStreamEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class GameDimension { TWO_D, THREE_D }

sealed interface GameBuildState {
    data object Idle : GameBuildState
    data class Generating(val progress: String) : GameBuildState
    data class Ready(val html: String) : GameBuildState
    data class GameError(val message: String) : GameBuildState
}

class GameBuilderViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _buildState = MutableStateFlow<GameBuildState>(GameBuildState.Idle)
    val buildState: StateFlow<GameBuildState> = _buildState

    private val _dimension = MutableStateFlow(GameDimension.TWO_D)
    val dimension: StateFlow<GameDimension> = _dimension

    private val _selectedModel = MutableStateFlow(ChatViewModel.DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    fun setDimension(d: GameDimension) { _dimension.value = d }
    fun setModel(m: String) { _selectedModel.value = m }

    fun generateGame(prompt: String) {
        if (prompt.isBlank()) return
        if (_buildState.value is GameBuildState.Generating) return

        viewModelScope.launch {
            _buildState.value = GameBuildState.Generating("Connecting to NIM…")

            val systemPrompt = if (_dimension.value == GameDimension.THREE_D) {
                ChatRepository.GAME_SYSTEM_PROMPT + "\n- Use THREE.js (CDN inline) for 3D rendering"
            } else {
                ChatRepository.GAME_SYSTEM_PROMPT
            }

            val history = listOf(
                ChatHistoryEntry("system", systemPrompt),
                ChatHistoryEntry("user", buildGamePrompt(prompt, _dimension.value))
            )

            val contentBuilder = StringBuilder()

            container.chatRepository.streamChat(_selectedModel.value, history).collect { event ->
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        contentBuilder.append(event.text)
                        _buildState.value = GameBuildState.Generating(
                            "Generating… ${contentBuilder.length} chars"
                        )
                    }
                    is ChatStreamEvent.Error -> {
                        _buildState.value = GameBuildState.GameError(event.message)
                        return@collect
                    }
                    else -> Unit
                }
            }

            val raw = contentBuilder.toString()
            val html = extractHtml(raw)
            if (html != null) {
                _buildState.value = GameBuildState.Ready(html)
            } else {
                _buildState.value = GameBuildState.GameError(
                    "AI didn't return a valid HTML game. Try again with a simpler prompt, " +
                        "or switch to a stronger model."
                )
            }
        }
    }

    fun reset() { _buildState.value = GameBuildState.Idle }

    fun saveHtml(context: Context, html: String) {
        viewModelScope.launch {
            runCatching {
                val fileName = "game_${System.currentTimeMillis()}.html"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri? = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(html.toByteArray())
                    }
                } ?: error("Could not create download file")
                fileName
            }.onSuccess { name ->
                _messages.tryEmit("Saved to Downloads/$name")
            }.onFailure {
                _messages.tryEmit("Save failed: ${it.message}")
            }
        }
    }

    private fun buildGamePrompt(prompt: String, dim: GameDimension): String {
        val dimStr = if (dim == GameDimension.THREE_D) "3D (use THREE.js inline)" else "2D"
        return "Create a $dimStr HTML5 game: $prompt\n\n" +
            "Return ONLY the complete HTML file starting with <!DOCTYPE html>. No markdown, no explanation."
    }

    private fun extractHtml(text: String): String? {
        val start = text.indexOf("<!DOCTYPE html>", ignoreCase = true)
            .takeIf { it >= 0 } ?: text.indexOf("<html", ignoreCase = true)
        if (start < 0) return null
        val trimmed = text.substring(start).trimEnd()
        if (!trimmed.contains("</html>", ignoreCase = true)) return null
        return trimmed
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBuilderScreen(onBack: () -> Unit = {}, viewModel: GameBuilderViewModel = viewModel()) {
    val buildState   by viewModel.buildState.collectAsState()
    val dimension    by viewModel.dimension.collectAsState()
    val context      = LocalContext.current
    val snackState   = remember { SnackbarHostState() }
    var prompt       by rememberSaveable { mutableStateOf("") }
    var showCode     by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost   = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Game Builder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "AI-Powered · HTML5 / 2D / 3D",
                            style  = MaterialTheme.typography.labelSmall,
                            color  = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (buildState is GameBuildState.Ready) {
                        IconButton(onClick = { showCode = !showCode }) {
                            Icon(Icons.Default.Code, contentDescription = "View code",
                                tint = if (showCode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.Default.Close, contentDescription = "New game")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = buildState) {
                is GameBuildState.Idle -> {
                    GameBuildForm(
                        prompt    = prompt,
                        onPromptChange = { prompt = it },
                        dimension = dimension,
                        onDimensionChange = { viewModel.setDimension(it) },
                        onGenerate = { viewModel.generateGame(prompt) }
                    )
                }

                is GameBuildState.Generating -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                state.progress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(0.6f),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                is GameBuildState.Ready -> {
                    Column(Modifier.fillMaxSize()) {
                        // Action row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.saveHtml(context, state.html) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Download HTML")
                            }
                            OutlinedButton(
                                onClick = { viewModel.reset() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.SportsEsports, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("New Game")
                            }
                        }

                        if (showCode) {
                            // Code preview
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0D1117))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    state.html,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE6EDF3)
                                )
                            }
                        } else {
                            // In-app WebView game runner
                            GameWebView(
                                html = state.html,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is GameBuildState.GameError -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "Generation Failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.reset() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Try Again") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameBuildForm(
    prompt: String,
    onPromptChange: (String) -> Unit,
    dimension: GameDimension,
    onDimensionChange: (GameDimension) -> Unit,
    onGenerate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header illustration
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Describe your game — AI will build it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Dimension toggle
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = dimension == GameDimension.TWO_D,
                onClick  = { onDimensionChange(GameDimension.TWO_D) },
                label    = { Text("2D Game") }
            )
            FilterChip(
                selected = dimension == GameDimension.THREE_D,
                onClick  = { onDimensionChange(GameDimension.THREE_D) },
                label    = { Text("3D Game (THREE.js)") }
            )
        }

        // Prompt input
        OutlinedTextField(
            value         = prompt,
            onValueChange = onPromptChange,
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Game Description") },
            placeholder   = {
                Text(
                    when (dimension) {
                        GameDimension.TWO_D   -> "e.g. A Flappy Bird clone with neon colors"
                        GameDimension.THREE_D -> "e.g. A rotating planet with space background"
                    }
                )
            },
            minLines = 3,
            maxLines = 6,
            shape    = RoundedCornerShape(14.dp)
        )

        // Quick suggestions
        Text("Quick ideas:", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val suggestions = if (dimension == GameDimension.TWO_D) {
            listOf("Snake game", "Breakout / Arkanoid", "Space invaders", "Flappy Bird", "Tetris")
        } else {
            listOf("Rotating 3D cube", "Space flight", "3D maze runner", "Particle system")
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { s ->
                FilterChip(
                    selected = false,
                    onClick  = { onPromptChange(s) },
                    label    = { Text(s, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Button(
            onClick  = onGenerate,
            enabled  = prompt.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape    = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Generate & Run Game")
        }

        // Info card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "How it works",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The AI writes a complete self-contained HTML5 game file.\n" +
                        "• 2D games use Canvas API\n" +
                        "• 3D games use an inlined THREE.js build\n" +
                        "• Games run instantly in a WebView\n" +
                        "• Download the HTML to share or run anywhere",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory  = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled  = true
                    domStorageEnabled  = true
                    allowFileAccess    = true
                    mediaPlaybackRequiresUserGesture = false
                }
                webChromeClient = WebChromeClient()
                webViewClient   = WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
