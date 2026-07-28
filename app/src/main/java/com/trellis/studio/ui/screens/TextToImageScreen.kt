package com.trellis.studio.ui.screens

import android.app.Application
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trellis.studio.App
import com.trellis.studio.data.db.Generation
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.components.ErrorCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface TextToImageState {
    data object Idle : TextToImageState
    data object Loading : TextToImageState
    data class Success(val imagePath: String) : TextToImageState
    data class Error(val message: String) : TextToImageState
}

class TextToImageViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _state = MutableStateFlow<TextToImageState>(TextToImageState.Idle)
    val state: StateFlow<TextToImageState> = _state

    private var lastPrompt: String? = null

    fun generate(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _state.value is TextToImageState.Loading) return
        lastPrompt = trimmed
        _state.value = TextToImageState.Loading
        viewModelScope.launch {
            runCatching { container.imageRepository.generateImage(trimmed) }
                .onSuccess { file ->
                    container.database.generationDao().insert(
                        Generation(
                            type = Generation.TYPE_IMAGE,
                            prompt = trimmed,
                            imagePath = file.absolutePath,
                            modelPath = null,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    _state.value = TextToImageState.Success(file.absolutePath)
                }
                .onFailure { _state.value = TextToImageState.Error(it.toUserMessage()) }
        }
    }

    fun retry() {
        lastPrompt?.let { generate(it) }
    }

    /** Hands the generated image to the Image-to-3D tab. */
    fun sendTo3d(imagePath: String) {
        container.pendingImagePath = imagePath
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToImageScreen(
    onNavigateTo3d: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: TextToImageViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text to Image") },
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
        containerColor = MaterialTheme.colorScheme.background
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
            "Describe what you want to create. Powered by Pollinations FLUX.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Prompt") },
            placeholder = { Text("A ceramic robot toy, studio lighting, white background") },
            minLines = 3,
            maxLines = 6
        )

        Button(
            onClick = { viewModel.generate(prompt) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = prompt.isNotBlank() && state !is TextToImageState.Loading,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (state is TextToImageState.Loading) "Generating…" else "Generate Image")
        }

        when (val s = state) {
            is TextToImageState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Creating your image…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is TextToImageState.Success -> {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(File(s.imagePath))
                        .crossfade(true)
                        .build(),
                    contentDescription = "Generated image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.generate(prompt) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Regenerate")
                    }
                    Button(
                        onClick = {
                            viewModel.sendTo3d(s.imagePath)
                            onNavigateTo3d()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ViewInAr, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Make it 3D")
                    }
                }
            }

            is TextToImageState.Error -> {
                ErrorCard(message = s.message, onRetry = { viewModel.retry() })
            }

            TextToImageState.Idle -> Unit
        }
    }
    }
}
