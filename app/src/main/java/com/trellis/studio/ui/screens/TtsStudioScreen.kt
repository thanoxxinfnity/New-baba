package com.trellis.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.DownloadHelper
import com.trellis.studio.data.NvidiaVoiceRepository
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.theme.NimOrange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface TtsState {
    data object Idle : TtsState
    data object Generating : TtsState
    data object Playing : TtsState
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    fun setModel(model: String) { _selectedModel.value = model }

    fun setReferenceAudio(path: String?) { _referenceAudioPath.value = path }

    fun generate(text: String) {
        if (text.isBlank() || _ttsState.value is TtsState.Generating) return
        _ttsState.value = TtsState.Generating
        viewModelScope.launch {
            val bytes = runCatching {
                container.nvidiaVoiceRepository.synthesize(
                    text = text,
                    model = _selectedModel.value,
                    referenceAudioPath = _referenceAudioPath.value
                )
            }.getOrNull()

            if (bytes == null) {
                _ttsState.value = TtsState.Error("Synthesis failed. Check your API key and try again.")
                return@launch
            }

            val path = container.nvidiaVoiceRepository.playAudioBytes(bytes)
            _ttsState.value = TtsState.Done(path)
        }
    }

    fun playAgain(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            _messages.tryEmit("Audio file no longer available. Generate again.")
            return
        }
        viewModelScope.launch {
            container.nvidiaVoiceRepository.playAudioBytes(file.readBytes())
        }
    }

    fun stop() {
        container.nvidiaVoiceRepository.stop()
        val current = _ttsState.value
        if (current is TtsState.Done) {
            // Keep Done state so user can re-play or download
        } else {
            _ttsState.value = TtsState.Idle
        }
    }

    fun download(filePath: String) {
        viewModelScope.launch {
            runCatching { DownloadHelper.saveToDownloads(getApplication(), File(filePath)) }
                .onSuccess { _messages.tryEmit("Saved to $it") }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsStudioScreen(onBack: () -> Unit = {}, viewModel: TtsStudioViewModel = viewModel()) {
    val ttsState by viewModel.ttsState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val referenceAudioPath by viewModel.referenceAudioPath.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Convert text to speech using NVIDIA NIM TTS models. " +
                    "Upload a reference voice clip for zero-shot voice cloning.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── TTS Model selector ───────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "TTS Model",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NimOrange
                    )
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { showModelDropdown = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                selectedModel,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = "Select model",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false }
                        ) {
                            NvidiaVoiceRepository.TTS_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                model,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (model == selectedModel) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = NimOrange
                                                )
                                            }
                                        }
                                    },
                                    onClick = { viewModel.setModel(model); showModelDropdown = false }
                                )
                            }
                        }
                    }
                }
            }

            // ── Voice clone reference ────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Voice Clone (Optional)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NimOrange
                    )
                    Text(
                        "Upload a reference audio clip to clone a voice. Works best with 5–30 second clean audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (referenceAudioPath != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(NimOrange.copy(alpha = 0.1f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AudioFile,
                                contentDescription = null,
                                tint = NimOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                File(referenceAudioPath!!).name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = { viewModel.setReferenceAudio(null) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { audioPicker.launch("audio/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (referenceAudioPath != null) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (referenceAudioPath != null) "Change Voice" else "Upload Voice")
                        }
                    }
                }
            }

            // ── Text input ───────────────────────────────────────────────────
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Text to speak") },
                placeholder = {
                    Text("Enter the text you want to convert to speech. Can be a script, article, or any content.")
                },
                minLines = 5,
                maxLines = 12,
                supportingText = { Text("${text.length} characters") }
            )

            // ── Generate button ──────────────────────────────────────────────
            Button(
                onClick = { viewModel.generate(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = text.isNotBlank() && ttsState !is TtsState.Generating,
                shape = RoundedCornerShape(14.dp)
            ) {
                if (ttsState is TtsState.Generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Synthesizing…")
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Generate Speech")
                }
            }

            // ── Result controls ──────────────────────────────────────────────
            when (val s = ttsState) {
                is TtsState.Done -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = NimOrange.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AudioFile,
                                    contentDescription = null,
                                    tint = NimOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "Audio ready!",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NimOrange
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.playAgain(s.filePath) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.size(4.dp))
                                    Text("Play")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.stop() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.size(4.dp))
                                    Text("Stop")
                                }
                                Button(
                                    onClick = { viewModel.download(s.filePath) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
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
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}
