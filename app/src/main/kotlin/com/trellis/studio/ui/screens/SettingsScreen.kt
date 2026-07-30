package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val nvidiaKey by vm.nvidiaKey.collectAsStateWithLifecycle()
    val falKey by vm.falKey.collectAsStateWithLifecycle()
    val pollKey by vm.pollKey.collectAsStateWithLifecycle()
    val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val maxTokens by vm.maxTokens.collectAsStateWithLifecycle()
    val temperature by vm.temperature.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {

            // ---- API Keys Section ----
            SettingsSection(title = "API Keys", icon = Icons.Default.Key) {
                ApiKeyField(
                    label = "NVIDIA NIM API Key",
                    value = nvidiaKey,
                    hint = "nvapi-…",
                    description = "Used for LLM, Image Gen, TTS, and 3D generation. Get free key at build.nvidia.com",
                    onSave = vm::setNvidiaKey,
                )
                ApiKeyField(
                    label = "fal.ai API Key",
                    value = falKey,
                    hint = "key_id:key_secret",
                    description = "Optional — used for fal.ai TRELLIS 3D generation.",
                    onSave = vm::setFalKey,
                )
                ApiKeyField(
                    label = "Pollinations Key",
                    value = pollKey,
                    hint = "Optional",
                    description = "Optional — Pollinations FLUX is free without a key.",
                    onSave = vm::setPollKey,
                )
            }

            // ---- Chat Settings ----
            SettingsSection(title = "Chat", icon = Icons.Default.Chat) {
                // System prompt
                var localPrompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    label = { Text("System Prompt", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    colors = nimTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 6,
                    trailingIcon = {
                        if (localPrompt != systemPrompt) {
                            IconButton(onClick = { vm.setSystemPrompt(localPrompt) }) {
                                Icon(Icons.Default.Save, "Save", tint = Purple60)
                            }
                        }
                    }
                )
                // Max tokens slider
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Max Tokens", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("$maxTokens", style = MaterialTheme.typography.bodyMedium, color = Purple60)
                    }
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { vm.setMaxTokens(it.toInt()) },
                        valueRange = 256f..8192f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = Purple60, activeTrackColor = Purple60, inactiveTrackColor = BorderDark),
                    )
                }
                // Temperature slider
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Temperature", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(String.format("%.2f", temperature), style = MaterialTheme.typography.bodyMedium, color = Purple60)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { vm.setTemperature(it) },
                        valueRange = 0f..2f,
                        steps = 40,
                        colors = SliderDefaults.colors(thumbColor = Purple60, activeTrackColor = Purple60, inactiveTrackColor = BorderDark),
                    )
                }
            }

            // ---- About ----
            SettingsSection(title = "About NVIDIA NIM", icon = Icons.Default.Info) {
                Text(
                    "The NVIDIA NIM endpoint (integrate.api.nvidia.com) gives access to 100+ LLMs, vision, reasoning, and voice models — all with one nvapi- key.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
                Text(
                    "Trellis Studio v2.0 — Powered by NVIDIA NIM, Jetpack Compose, SceneView",
                    style = MaterialTheme.typography.bodySmall, color = TextDisabled,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = Purple60, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun ApiKeyField(label: String, value: String, hint: String, description: String, onSave: (String) -> Unit) {
    var localVal by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        OutlinedTextField(
            value = localVal,
            onValueChange = { localVal = it },
            placeholder = { Text(hint, color = TextDisabled) },
            modifier = Modifier.fillMaxWidth(),
            colors = nimTextFieldColors(),
            shape = RoundedCornerShape(10.dp),
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    if (localVal != value) {
                        IconButton(onClick = { onSave(localVal) }) {
                            Icon(Icons.Default.Save, "Save", tint = Purple60, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
        )
        Text(description, style = MaterialTheme.typography.bodySmall, color = TextDisabled)
    }
}

@Composable
private fun nimTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
    focusedContainerColor = SurfDark, unfocusedContainerColor = SurfDark,
    focusedLabelColor = Purple60,
)
