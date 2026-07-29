package com.trellis.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.DownloadHelper
import com.trellis.studio.data.NvidiaVoiceRepository
import com.trellis.studio.data.SynthesisException
import com.trellis.studio.data.db.TtsHistoryEntity
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.theme.NimOrange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface TtsState {
    data object Idle : TtsState
    data object Generating : TtsState
    data class Done(val filePath: String) : TtsState
    data class Error(val message: String) : TtsState
}

class TtsStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState

    private val _selectedModel = MutableStateFlow(NvidiaVoiceRepository.DEFAULT_TTS_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel

    private val _referenceAudioPath = MutableStateFlow<String?>(null)
    val referenceAudioPath: StateFlow<String?> = _referenceAudioPath

    val history: StateFlow<List<TtsHistoryEntity>> =
        container.database.ttsHistoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    fun setModel(model: String) { _selectedModel.value = model }

    fun setReferenceAudio(path: String?) { _referenceAudioPath.value = path }

    fun generate(text: String) {
        if (text.isBlank() || _ttsState.value is TtsState.Generating) return
        _ttsState.value = TtsState.Generating
        viewModelScope.launch {
            runCatching {
                container.nvidiaVoiceRepository.synthesize(
                    text = text,
                    model = _selectedModel.value,
                    referenceAudioPath = _referenceAudioPath.value
                )
            }.onSuccess { bytes ->
                val path = container.nvidiaVoiceRepository.playAudioBytes(bytes)
                container.database.ttsHistoryDao().insert(
                    TtsHistoryEntity(
                        textInput  = text.take(200),
                        model      = _selectedModel.value,
                        audioPath  = path,
                        createdAt  = System.currentTimeMillis()
                    )
                )
                _ttsState.value = TtsState.Done(path)
            }.onFailure { e ->
                _ttsState.value = TtsState.Error(
                    if (e is SynthesisException) e.message!! else e.toUserMessage()
                )
            }
        }
    }

    fun playHistory(entry: TtsHistoryEntity) {
        val file = File(entry.audioPath)
        if (!file.exists()) {
            _messages.tryEmit("Audio file missing — please regenerate.")
            return
        }
        viewModelScope.launch {
            runCatching { container.nvidiaVoiceRepository.playAudioBytes(file.readBytes()) }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    fun stop() { container.nvidiaVoiceRepository.stop() }

    fun download(filePath: String) {
        viewModelScope.launch {
            runCatching { DownloadHelper.saveToDownloads(getApplication(), File(filePath)) }
                .onSuccess { _messages.tryEmit("Saved to $it") }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { container.database.ttsHistoryDao().delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsStudioScreen(onBack: () -> Unit = {}, viewModel: TtsStudioViewModel = viewModel()) {
    val ttsState by viewModel.ttsState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val referenceAudioPath by viewModel.referenceAudioPath.collectAsState()
    val history by viewModel.history.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "mp3"
            val dest = File(context.cacheDir, "ref_voice_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { out -> ins.copyTo(out) }
            }
            viewModel.setReferenceAudio(dest.absolutePath)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Studio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ttsState is TtsState.Done || ttsState is TtsState.Generating) {
                        IconButton(onClick = { viewModel.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = NimOrange)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = MaterialTheme.colorScheme.background
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    text     = { Text("Generate") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    icon     = {
                        Row(
                            verticalAlignment    = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Text("History (${history.size})")
                        }
                    }
                )
            }

            when (selectedTab) {
                0 -> GenerateTab(
                    text               = text,
                    onTextChange       = { text = it },
                    selectedModel      = selectedModel,
                    onModelChange      = viewModel::setModel,
                    showModelDropdown  = showModelDropdown,
                    onShowDropdown     = { showModelDropdown = it },
                    referenceAudioPath = referenceAudioPath,
                    onPickAudio        = { audioPicker.launch("audio/*") },
                    onClearAudio       = { viewModel.setReferenceAudio(null) },
                    ttsState           = ttsState,
                    onGenerate         = { viewModel.generate(text) },
                    onStop             = viewModel::stop,
                    onDownload         = viewModel::download
                )
                1 -> HistoryTab(
                    history    = history,
                    onPlay     = viewModel::playHistory,
                    onDownload = { viewModel.download(it.audioPath) },
                    onDelete   = { viewModel.deleteHistory(it.id) }
                )
            }
        }
    }
}

@Composable
private fun GenerateTab(
    text: String,
    onTextChange: (String) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    showModelDropdown: Boolean,
    onShowDropdown: (Boolean) -> Unit,
    referenceAudioPath: String?,
    onPickAudio: () -> Unit,
    onClearAudio: () -> Unit,
    ttsState: TtsState,
    onGenerate: () -> Unit,
    onStop: () -> Unit,
    onDownload: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── TTS Model ───────────────────────────────────────────────────────
        Text(
            "TTS Model",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = NimOrange
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onShowDropdown(true) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Mic, contentDescription = null,
                    tint = NimOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    selectedModel,
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color    = MaterialTheme.colorScheme.onSurface
                )
                Icon(Icons.Default.ExpandMore, contentDescription = "Select",
                    modifier = Modifier.size(18.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showModelDropdown,
                onDismissRequest = { onShowDropdown(false) }
            ) {
                NvidiaVoiceRepository.TTS_MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model,
                                    style    = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (model == selectedModel) {
                                    Icon(Icons.Default.Check, null,
                                        modifier = Modifier.size(14.dp), tint = NimOrange)
                                }
                            }
                        },
                        onClick = { onModelChange(model); onShowDropdown(false) }
                    )
                }
            }
        }

        // ── Voice Clone ─────────────────────────────────────────────────────
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Voice Clone (Optional)",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = NimOrange
        )
        Text(
            "Upload a 5–30 second clean audio clip to clone a voice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(referenceAudioPath != null) {
            if (referenceAudioPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NimOrange.copy(alpha = 0.12f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AudioFile, null,
                        tint = NimOrange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        File(referenceAudioPath).name,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    IconButton(onClick = onClearAudio, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        OutlinedButton(
            onClick  = onPickAudio,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                if (referenceAudioPath != null) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(if (referenceAudioPath != null) "Change Voice Clip" else "Upload Voice Clip")
        }

        // ── Text input ──────────────────────────────────────────────────────
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value         = text,
            onValueChange = onTextChange,
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Text to speak") },
            placeholder   = { Text("Enter your script, article, or any text to convert to speech…") },
            minLines      = 5,
            maxLines      = 14,
            supportingText = { Text("${text.length} characters") }
        )

        // ── Generate button ─────────────────────────────────────────────────
        Button(
            onClick  = onGenerate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled  = text.isNotBlank() && ttsState !is TtsState.Generating,
            shape    = RoundedCornerShape(14.dp)
        ) {
            if (ttsState is TtsState.Generating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp),
                    color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Synthesizing…")
            } else {
                Icon(Icons.Default.Mic, null)
                Spacer(Modifier.size(8.dp))
                Text("Generate Speech")
            }
        }

        // ── Result ──────────────────────────────────────────────────────────
        when (val s = ttsState) {
            is TtsState.Done -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = NimOrange.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AudioFile, null, tint = NimOrange,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Audio ready!", style = MaterialTheme.typography.titleSmall,
                                color = NimOrange)
                        }
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick  = { onGenerate() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.size(4.dp))
                                Text("Replay")
                            }
                            OutlinedButton(
                                onClick  = onStop,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.size(4.dp))
                                Text("Stop")
                            }
                            Button(
                                onClick  = { onDownload(s.filePath) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Save")
                            }
                        }
                    }
                }
            }
            is TtsState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Error", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text(s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistoryTab(
    history: List<TtsHistoryEntity>,
    onPlay: (TtsHistoryEntity) -> Unit,
    onDownload: (TtsHistoryEntity) -> Unit,
    onDelete: (TtsHistoryEntity) -> Unit
) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.History, null,
                    modifier = Modifier.size(48.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "No voice history yet.\nGenerate speech to see it here.",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(history, key = { it.id }) { entry ->
            val fileExists = File(entry.audioPath).exists()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (fileExists) 0.5f else 0.25f
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment  = Alignment.Top,
                        modifier           = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.textInput,
                                style    = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color    = if (fileExists) MaterialTheme.colorScheme.onSurface
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${entry.model.substringAfterLast('/')}  •  ${fmt.format(Date(entry.createdAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick  = { onDelete(entry) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, null,
                                modifier = Modifier.size(16.dp),
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick  = { onPlay(entry) },
                            modifier = Modifier.weight(1f),
                            enabled  = fileExists
                        ) {
                            Icon(Icons.Default.PlayArrow, null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Play")
                        }
                        OutlinedButton(
                            onClick  = { onDownload(entry) },
                            modifier = Modifier.weight(1f),
                            enabled  = fileExists
                        ) {
                            Icon(Icons.Default.Download, null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Save")
                        }
                    }
                    if (!fileExists) {
                        Text(
                            "File deleted. Regenerate from Generate tab.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
