package com.trellis.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.trellis.studio.App
import com.trellis.studio.data.DownloadHelper
import com.trellis.studio.data.Model3DProvider
import com.trellis.studio.data.db.Generation
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.components.ErrorCard
import com.trellis.studio.ui.components.ModelViewer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ModelGenState {
    data object Idle : ModelGenState
    data class Running(val status: String) : ModelGenState
    data class Success(val modelPath: String) : ModelGenState
    data class Error(val message: String) : ModelGenState
}

enum class InputMode { IMAGE, TEXT }

class ImageTo3dViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _imagePath = MutableStateFlow<String?>(null)
    val imagePath: StateFlow<String?> = _imagePath

    private val _genState = MutableStateFlow<ModelGenState>(ModelGenState.Idle)
    val genState: StateFlow<ModelGenState> = _genState

    private val _isProcessingImage = MutableStateFlow(false)
    val isProcessingImage: StateFlow<Boolean> = _isProcessingImage

    private val _removeBgEnabled = MutableStateFlow(true)
    val removeBgEnabled: StateFlow<Boolean> = _removeBgEnabled

    private val _inputMode = MutableStateFlow(InputMode.IMAGE)
    val inputMode: StateFlow<InputMode> = _inputMode

    private val _textPrompt = MutableStateFlow("")
    val textPrompt: StateFlow<String> = _textPrompt

    val provider: StateFlow<Model3DProvider> = container.settingsRepository.provider

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    fun setRemoveBgEnabled(enabled: Boolean) {
        _removeBgEnabled.value = enabled
    }

    fun setInputMode(mode: InputMode) {
        _inputMode.value = mode
        _genState.value = ModelGenState.Idle
    }

    fun setTextPrompt(prompt: String) {
        _textPrompt.value = prompt
    }

    /** Picks up an image handed over from the Text-to-Image tab, if any. */
    fun consumePendingImage() {
        container.pendingImagePath?.let { path ->
            container.pendingImagePath = null
            setIncomingImage(File(path))
        }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.imageRepository.importFromGallery(uri) }
                .onSuccess { setIncomingImage(it) }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    private fun setIncomingImage(file: File) {
        _genState.value = ModelGenState.Idle
        if (!_removeBgEnabled.value) {
            _imagePath.value = file.absolutePath
            return
        }
        viewModelScope.launch {
            _isProcessingImage.value = true
            val processed = runCatching { container.backgroundRemover.removeBackground(file) }
                .getOrDefault(file)
            _imagePath.value = processed.absolutePath
            _isProcessingImage.value = false
        }
    }

    fun generate() {
        if (_genState.value is ModelGenState.Running) return
        if (_inputMode.value == InputMode.TEXT) {
            generateFromText()
        } else {
            generateFromImage()
        }
    }

    private fun generateFromImage() {
        val path = _imagePath.value ?: return
        _genState.value = ModelGenState.Running("Preparing image…")
        viewModelScope.launch {
            runCatching {
                container.model3DRepository.generateModel(File(path)) { status ->
                    _genState.value = ModelGenState.Running(status)
                }
            }.onSuccess { modelFile ->
                container.database.generationDao().insert(
                    Generation(
                        type = Generation.TYPE_MODEL,
                        prompt = null,
                        imagePath = path,
                        modelPath = modelFile.absolutePath,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _genState.value = ModelGenState.Success(modelFile.absolutePath)
            }.onFailure {
                _genState.value = ModelGenState.Error(it.toUserMessage())
            }
        }
    }

    private fun generateFromText() {
        val prompt = _textPrompt.value.trim()
        if (prompt.isEmpty()) return
        _genState.value = ModelGenState.Running("Preparing…")
        viewModelScope.launch {
            runCatching {
                container.model3DRepository.generateFromPrompt(prompt) { status ->
                    _genState.value = ModelGenState.Running(status)
                }
            }.onSuccess { modelFile ->
                container.database.generationDao().insert(
                    Generation(
                        type = Generation.TYPE_MODEL,
                        prompt = prompt,
                        imagePath = null,
                        modelPath = modelFile.absolutePath,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _genState.value = ModelGenState.Success(modelFile.absolutePath)
            }.onFailure {
                _genState.value = ModelGenState.Error(it.toUserMessage())
            }
        }
    }

    fun download() {
        val state = _genState.value as? ModelGenState.Success ?: return
        viewModelScope.launch {
            runCatching {
                DownloadHelper.saveToDownloads(getApplication(), File(state.modelPath))
            }.onSuccess { _messages.tryEmit("Saved to $it") }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageTo3dScreen(onBack: () -> Unit = {}, viewModel: ImageTo3dViewModel = viewModel()) {
    val imagePath by viewModel.imagePath.collectAsState()
    val genState by viewModel.genState.collectAsState()
    val isProcessingImage by viewModel.isProcessingImage.collectAsState()
    val removeBgEnabled by viewModel.removeBgEnabled.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val textPrompt by viewModel.textPrompt.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Only Pollinations currently implements real text-to-3D (see Model3DRepository).
    val supportsTextTo3d = provider == Model3DProvider.POLLINATIONS_TRELLIS

    LaunchedEffect(Unit) { viewModel.consumePendingImage() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image to 3D") },
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
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Turn a single image into a textured 3D model. Using: ${provider.label} " +
                    "(change in Settings).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (supportsTextTo3d) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = inputMode == InputMode.IMAGE,
                        onClick = { viewModel.setInputMode(InputMode.IMAGE) },
                        label = { Text("From Image") }
                    )
                    FilterChip(
                        selected = inputMode == InputMode.TEXT,
                        onClick = { viewModel.setInputMode(InputMode.TEXT) },
                        label = { Text("From Text") }
                    )
                }
            }

            if (inputMode == InputMode.TEXT && supportsTextTo3d) {
                OutlinedTextField(
                    value = textPrompt,
                    onValueChange = viewModel::setTextPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Describe the 3D model") },
                    placeholder = { Text("A low-poly treasure chest with a gold lock") },
                    minLines = 3,
                    maxLines = 6
                )
                Button(
                    onClick = { viewModel.generate() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = textPrompt.isNotBlank() && genState !is ModelGenState.Running,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Generate 3D")
                }
            } else {
                // Source image preview / picker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val path = imagePath
                    if (isProcessingImage) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Removing background…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (path != null) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Source image",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No image selected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Remove background",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = removeBgEnabled,
                        onCheckedChange = viewModel::setRemoveBgEnabled
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Pick Image")
                    }
                    Button(
                        onClick = { viewModel.generate() },
                        modifier = Modifier.weight(1f),
                        enabled = imagePath != null &&
                            !isProcessingImage &&
                            genState !is ModelGenState.Running
                    ) {
                        Icon(Icons.Default.ViewInAr, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Generate 3D")
                    }
                }
            }

            when (val s = genState) {
                is ModelGenState.Running -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.2f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                s.status,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                is ModelGenState.Success -> {
                    Text(
                        "Drag to rotate • Pinch to zoom",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        ModelViewer(
                            modelPath = s.modelPath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Button(
                        onClick = { viewModel.download() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Download .glb")
                    }
                }

                is ModelGenState.Error -> {
                    ErrorCard(message = s.message, onRetry = { viewModel.generate() })
                }

                ModelGenState.Idle -> Unit
            }
        }
    }
    }
}
