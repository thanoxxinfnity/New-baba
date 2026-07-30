package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Named pitch/speed combinations, so users can get distinct-sounding voices. */
private data class VoicePreset(val label: String, val pitch: Float, val speed: Float)

private val PRESETS = listOf(
    VoicePreset("Natural", 1.0f, 1.0f),
    VoicePreset("Deep", 0.7f, 0.92f),
    VoicePreset("Bright", 1.35f, 1.05f),
    VoicePreset("Narrator", 0.9f, 0.85f),
    VoicePreset("Chipmunk", 1.9f, 1.35f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ttsClient = remember { TtsClient(context) }

    var inputText by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<TtsClient.VoiceOption>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<TtsClient.VoiceOption?>(null) }
    var pitch by remember { mutableFloatStateOf(1.0f) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var isSpeaking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showVoiceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { voices = ttsClient.voices() }
    DisposableEffect(Unit) { onDispose { ttsClient.release() } }

    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch {
            isSpeaking = true
            error = null
            ttsClient.speak(text, selectedVoice?.id, pitch, speed)
                .onFailure { e -> error = e.message }
            isSpeaking = false
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Text(
                "Voice / TTS",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Honest capability note
            Card(
                colors = CardDefaults.cardColors(containerColor = Teal.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Info, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Offline device speech — no API key needed. Voice cloning isn't " +
                            "available, but pitch and speed let you shape distinct voices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Teal,
                    )
                }
            }

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
                            selectedVoice?.label ?: "System Default",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "${voices.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                    )
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                }
            }

            // Presets
            Text("Voice Style", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { preset ->
                    val active = pitch == preset.pitch && speed == preset.speed
                    FilterChip(
                        selected = active,
                        onClick = { pitch = preset.pitch; speed = preset.speed },
                        label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple40,
                            selectedLabelColor = TextPrimary,
                            containerColor = CardDark,
                            labelColor = TextSecondary,
                        ),
                    )
                }
            }

            SliderRow("Pitch", pitch) { pitch = it }
            SliderRow("Speed", speed) { speed = it }

            // Text input
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Text to speak", color = TextSecondary) },
                placeholder = { Text("Type something to say…", color = TextDisabled) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                    focusedLabelColor = Purple60,
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 8,
            )

            Text(
                "${inputText.length} characters",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled,
                modifier = Modifier.align(Alignment.End),
            )

            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.12f)),
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

            // Speak / Stop
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (isSpeaking) {
                            ttsClient.stop()
                            isSpeaking = false
                        } else speak(inputText)
                    },
                    enabled = isSpeaking || inputText.isNotBlank(),
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
                    Text(if (isSpeaking) "Stop" else "Speak", style = MaterialTheme.typography.titleMedium)
                }

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
            listOf(
                "Hello! How can I help you today?",
                "The quick brown fox jumps over the lazy dog.",
                "Welcome to Trellis Studio.",
                "Testing one two three. Audio check complete.",
            ).forEach { phrase ->
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

    if (showVoiceSheet) {
        ModalBottomSheet(onDismissRequest = { showVoiceSheet = false }, containerColor = SurfDark) {
            Column(Modifier.fillMaxHeight(0.7f)) {
                Text(
                    "Select Voice",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
                if (voices.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No offline voices found. Install Google Speech Services " +
                                "and download a voice in system settings.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            VoiceRow("System Default", selectedVoice == null) {
                                selectedVoice = null
                                showVoiceSheet = false
                            }
                        }
                        items(voices, key = { it.id }) { v ->
                            VoiceRow(v.label, selectedVoice?.id == v.id) {
                                selectedVoice = v
                                showVoiceSheet = false
                                speak("Hello, this is how I sound.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = TextPrimary, maxLines = 1) },
        trailingContent = { if (selected) Icon(Icons.Default.Check, null, tint = Purple60) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = if (selected) CardDark else SurfDark),
    )
    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(String.format("%.2fx", value), style = MaterialTheme.typography.labelMedium, color = Purple60)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.5f..2.0f,
            colors = SliderDefaults.colors(
                thumbColor = Purple60,
                activeTrackColor = Purple40,
                inactiveTrackColor = BorderDark,
            ),
        )
    }
}
