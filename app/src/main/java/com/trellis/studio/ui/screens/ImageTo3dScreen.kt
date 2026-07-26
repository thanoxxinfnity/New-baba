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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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

class ImageTo3dViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _imagePath = MutableStateFlow<String?>(null)
    val imagePath: StateFlow<String?> = _imagePath

    private val _genState = MutableStateFlow<ModelGenState>(ModelGenState.Idle)
    val genState: StateFlow<ModelGenState> = _genState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    /** Picks up an image handed over from the Text-to-Image tab, if any. */
    fun consumePendingImage() {
        container.pendingImagePath?.let { path ->
            container.pendingImagePath = null
            _imagePath.value = path
            _genState.value = ModelGenState.Idle
        }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.imageRepository.importFromGallery(uri) }
                .onSuccess {
                    _imagePath.value = it.absolutePath
                    _genState.value = ModelGenState.Idle
                }
                .onFailure { _messages.tryEmit(it.toUserMessage()) }
        }
    }

    fun generate() {
        val path = _imagePath.value ?: return
        if (_genState.value is ModelGenState.Running) return
        _genState.value = ModelGenState.Running("Preparing image…")
        viewModelScope.launch {
            runCatching {
                container.trellisRepository.generateModel(File(path)) { status ->
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

@Composable
fun ImageTo3dScreen(viewModel: ImageTo3dViewModel = viewModel()) {
    val imagePath by viewModel.imagePath.collectAsState()
    val genState by viewModel.genState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.consumePendingImage() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Image to 3D",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Turn a single image into a textured 3D model with NVIDIA TRELLIS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                if (path != null) {
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
                    enabled = imagePath != null && genState !is ModelGenState.Running
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Generate 3D")
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
