package com.trellis.studio.ui.screens

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
import com.trellis.studio.network.TtsClient
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsClient = remember { TtsClient(context) }

    var inputText by remember { mutableStateOf("") }
    var selectedVoice by remember { mutableStateOf("default") }
    var isSpeaking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showVoiceSheet by remember { mutableStateOf(false) }

    // Give TTS time to init, then refresh voice list
    var voices by remember { mutableStateOf(listOf("default")) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        voices = listOf("default") + ttsClient.availableVoices
    }

    DisposableEffect(Unit) { onDispose { ttsClient.release() } }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Column {
                Text(
                    "Voice / TTS",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
                // Info banner
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Using Android built-in TTS — works offline, no API key needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Teal,
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Voice selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(14.dp),
                onClick = { showVoiceSheet = true },
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, null, tint = Amber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            if (selectedVoice == "default") "System Default" else selectedVoice.take(32),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }

            // Text input
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Text to speak", color = TextSecondary) },
                placeholder = { Text("Type something for the AI to say…", color = TextDisabled) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                    focusedLabelColor = Purple60,
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 8,
            )

            // Character count
            Text(
                "${inputText.length} characters",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled,
                modifier = Modifier.align(Alignment.End),
            )

            // Error banner
            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Red.copy(0.12f)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { error = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Speak / Stop row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (isSpeaking) {
                            ttsClient.stop()
                            isSpeaking = false
                        } else {
                            scope.launch {
                                isSpeaking = true
                                error = null
                                ttsClient.synthesize("", "", inputText, selectedVoice)
                                    .onFailure { e -> error = e.message }
                                isSpeaking = false
                            }
                        }
                    },
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSpeaking) Red else Purple40,
                        disabledContainerColor = BorderDark,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        if (isSpeaking) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSpeaking) "Stop Speaking" else "Speak",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // Clear button
                OutlinedButton(
                    onClick = { inputText = ""; error = null },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                ) {
                    Icon(Icons.Default.Clear, null, modifier = Modifier.size(18.dp))
                }
            }

            // Quick phrases
            Text("Quick Phrases", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            val quickPhrases = listOf(
                "Hello! How can I help you today?",
                "The quick brown fox jumps over the lazy dog.",
                "Welcome to Trellis Studio, powered by NVIDIA NIM.",
                "Testing one two three. Audio quality check complete.",
            )
            quickPhrases.forEach { phrase ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { inputText = phrase },
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        phrase,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                    )
                }
            }
        }
    }

    // Voice picker sheet
    if (showVoiceSheet) {
        ModalBottomSheet(onDismissRequest = { showVoiceSheet = false }, containerColor = SurfDark) {
            Column(Modifier.fillMaxHeight(0.65f)) {
                Text(
                    "Select Voice",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
                if (voices.size <= 1) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No voices loaded yet. Wait a moment.", color = TextSecondary)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(voices.size) { i ->
                            val v = voices[i]
                            val label = if (v == "default") "System Default" else v
                            ListItem(
                                headlineContent = { Text(label, color = TextPrimary, maxLines = 1) },
                                trailingContent = {
                                    if (v == selectedVoice) Icon(Icons.Default.Check, null, tint = Purple60)
                                },
                                modifier = Modifier.clickable { selectedVoice = v; showVoiceSheet = false },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (v == selectedVoice) CardDark else SurfDark,
                                ),
                            )
                            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
