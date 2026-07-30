package com.trellis.studio.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.data.model.NIM_TTS_MODELS
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.TtsClient
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(settingsVm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsClient = remember { TtsClient(context) }

    var inputText by remember { mutableStateOf("") }
    var selectedModelId by remember { mutableStateOf(AppPrefs.DEFAULT_TTS) }
    var isGenerating by remember { mutableStateOf(false) }
    var audioPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { mediaPlayer.release() } }

    fun playAudio(path: String) {
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(path)
            mediaPlayer.prepare()
            mediaPlayer.start()
            isPlaying = true
            mediaPlayer.setOnCompletionListener { isPlaying = false }
        } catch (e: Exception) { error = "Playback error: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Text("Voice / TTS", style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // TTS Model selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(14.dp),
                onClick = { showModelSheet = true },
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, null, tint = Amber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TTS Model", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            NIM_TTS_MODELS.find { it.id == selectedModelId }?.displayName ?: selectedModelId,
                            style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }

            // Info
            NIM_TTS_MODELS.find { it.id == selectedModelId }?.let { model ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfDark), shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(model.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }

            // Text input
            OutlinedTextField(
                value = inputText, onValueChange = { inputText = it },
                label = { Text("Text to speak", color = TextSecondary) },
                placeholder = { Text("Enter text here…", color = TextDisabled) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                    focusedLabelColor = Purple60,
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 8,
            )

            // Error
            error?.let {
                Text(it, color = Red, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.background(Red.copy(0.1f), RoundedCornerShape(8.dp)).padding(8.dp))
            }

            // Generate button
            Button(
                onClick = {
                    scope.launch {
                        isGenerating = true; error = null
                        val key = settingsVm.nvidiaKey.value
                        ttsClient.synthesize(key, selectedModelId, inputText)
                            .onSuccess { path -> audioPath = path }
                            .onFailure { e -> error = e.message }
                        isGenerating = false
                    }
                },
                enabled = !isGenerating && inputText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = BorderDark),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating Speech…", color = TextPrimary)
                } else {
                    Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Speech", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Audio playback
            audioPath?.let { path ->
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Audiotrack, null, tint = Teal, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Audio ready", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text("Tap Play to listen", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { if (isPlaying) { mediaPlayer.pause(); isPlaying = false } else playAudio(path) }) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Teal, modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { mediaPlayer.stop(); isPlaying = false; audioPath = null }) {
                                Icon(Icons.Default.Stop, null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // TTS Model selector sheet
    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }, containerColor = SurfDark) {
            Column(Modifier.fillMaxHeight(0.6f)) {
                Text("TTS Model", style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                NIM_TTS_MODELS.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.displayName, color = TextPrimary) },
                        supportingContent = { Text(model.description, color = TextSecondary, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            if (model.id == selectedModelId) Icon(Icons.Default.Check, null, tint = Purple60)
                        },
                        modifier = Modifier.clickable { selectedModelId = model.id; showModelSheet = false },
                        colors = ListItemDefaults.colors(containerColor = if (model.id == selectedModelId) CardDark else SurfDark),
                    )
                    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
