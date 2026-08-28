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
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onMenu: () -> Unit = {},
) {
    val nvidiaKey by vm.nvidiaKey.collectAsStateWithLifecycle()
    val buildServerUrl by vm.buildServerUrl.collectAsStateWithLifecycle()
    val hinglish by vm.hinglishThinking.collectAsStateWithLifecycle()
    val falKey by vm.falKey.collectAsStateWithLifecycle()
    val pollKey by vm.pollKey.collectAsStateWithLifecycle()
    val youtubeKey by vm.youtubeKey.collectAsStateWithLifecycle()
    val videoServerUrl by vm.videoServerUrl.collectAsStateWithLifecycle()
    val voiceServerUrl by vm.voiceServerUrl.collectAsStateWithLifecycle()
    val voiceProvider by vm.voiceProvider.collectAsStateWithLifecycle()
    val hfToken by vm.hfToken.collectAsStateWithLifecycle()
    val hfVoiceSpace by vm.hfVoiceSpace.collectAsStateWithLifecycle()
    val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val maxTokens by vm.maxTokens.collectAsStateWithLifecycle()
    val temperature by vm.temperature.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            }
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
                    label = "Hugging Face token",
                    value = hfToken,
                    hint = "hf_…",
                    description = "Free at huggingface.co/settings/tokens (read scope). Powers " +
                        "free image editing (FLUX Kontext) and accented voice cloning.",
                    onSave = vm::setHfToken,
                )
                ApiKeyField(
                    label = "fal.ai API Key",
                    value = falKey,
                    hint = "key_id:key_secret",
                    description = "Optional — a paid fallback for image editing if the free " +
                        "Hugging Face editor is busy.",
                    onSave = vm::setFalKey,
                )
                ApiKeyField(
                    label = "Pollinations Key",
                    value = pollKey,
                    hint = "Optional",
                    description = "Optional — Pollinations FLUX is free without a key.",
                    onSave = vm::setPollKey,
                )
                ApiKeyField(
                    label = "YouTube Data API Key",
                    value = youtubeKey,
                    hint = "AIza…",
                    description = "Optional — lets Video Studio take style cues from popular " +
                        "videos. It searches YouTube; it does not generate video.",
                    onSave = vm::setYoutubeKey,
                )
                ApiKeyField(
                    label = "Video server URL",
                    value = videoServerUrl,
                    hint = "https://xxxx.trycloudflare.com",
                    description = "Optional — for real motion video. Run the VOID Kaggle " +
                        "notebook (free GPU), paste the public URL it prints here, then turn " +
                        "on \"Real motion\" in Video Studio.",
                    onSave = vm::setVideoServerUrl,
                )
                ApiKeyField(
                    label = "Voice clone server URL",
                    value = voiceServerUrl,
                    hint = "https://xxxx.trycloudflare.com",
                    description = "Optional — clone a voice in an Indian/Hindi accent. Run the " +
                        "VOID voice Kaggle notebook (free GPU, XTTS), paste its URL here, then " +
                        "pick your cloned voice and Hindi in the Voice tab.",
                    onSave = vm::setVoiceServerUrl,
                )
            }

            // ---- Voice cloning engine: NVIDIA (24/7, English) vs Hugging Face (your accent) ----
            SettingsSection(title = "Voice Cloning", icon = Icons.Default.RecordVoiceOver) {
                Text(
                    "Choose how your recorded voice is cloned:",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = voiceProvider == AppPrefs.VOICE_PROVIDER_NVIDIA,
                        onClick = { vm.setVoiceProvider(AppPrefs.VOICE_PROVIDER_NVIDIA) },
                        label = { Text("NVIDIA") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple60.copy(alpha = 0.30f),
                            selectedLabelColor = TextPrimary,
                        ),
                    )
                    FilterChip(
                        selected = voiceProvider == AppPrefs.VOICE_PROVIDER_HF,
                        onClick = { vm.setVoiceProvider(AppPrefs.VOICE_PROVIDER_HF) },
                        label = { Text("Hugging Face") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple60.copy(alpha = 0.30f),
                            selectedLabelColor = TextPrimary,
                        ),
                    )
                }
                Text(
                    if (voiceProvider == AppPrefs.VOICE_PROVIDER_HF)
                        "Hugging Face (XTTS): keeps YOUR recording's accent — so record " +
                            "yourself speaking Indian English / Hindi and the clone stays " +
                            "Indian. Free and on-demand; the first request can take ~1 min " +
                            "while the Space wakes up, then it's a few seconds."
                    else
                        "NVIDIA (Magpie): always-on and fast, but the cloned voice speaks " +
                            "with an English (US) accent only.",
                    style = MaterialTheme.typography.bodySmall, color = TextDisabled,
                )
                if (voiceProvider == AppPrefs.VOICE_PROVIDER_HF) {
                    ApiKeyField(
                        label = "Hugging Face token",
                        value = hfToken,
                        hint = "hf_…",
                        description = "Free at huggingface.co/settings/tokens (read scope). " +
                            "Used to call the voice Space.",
                        onSave = vm::setHfToken,
                    )
                    ApiKeyField(
                        label = "Voice Space URL",
                        value = hfVoiceSpace,
                        hint = AppPrefs.DEFAULT_HF_VOICE_SPACE,
                        description = "An XTTS clone Space (text + reference audio). The default " +
                            "works; for a private, always-ready one, duplicate that Space to " +
                            "your own account and paste its URL here.",
                        onSave = vm::setHfVoiceSpace,
                    )
                }
            }

            // ---- Build server: the only way to get a real APK ----
            SettingsSection(title = "Build Server", icon = Icons.Default.Android) {
                Text(
                    "Android can't compile an APK on the phone — the OS blocks running " +
                        "binaries made at runtime, and there's no JDK or SDK on device. " +
                        "Run tools/trellis_build_server.py on a PC that has the Android " +
                        "SDK, expose it with \"ngrok http 8000\", and paste the https URL " +
                        "here. Then the AI can write code, build it, and give you a real .apk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDisabled,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                ApiKeyField(
                    label = "Build server URL",
                    value = buildServerUrl,
                    hint = "https://xxxx.ngrok-free.dev",
                    description = "Leave empty to disable remote builds.",
                    onSave = vm::setBuildServerUrl,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Think in Hinglish",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        Text(
                            "Model reasons and answers in Hinglish, so the thinking bubble is readable.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDisabled,
                        )
                    }
                    Switch(
                        checked = hinglish,
                        onCheckedChange = vm::setHinglishThinking,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyan,
                            checkedTrackColor = Cyan.copy(alpha = 0.35f),
                        ),
                    )
                }
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
                        Text(String.format(java.util.Locale.US, "%.2f", temperature), style = MaterialTheme.typography.bodyMedium, color = Purple60)
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
                    "VOID v2.0 — NVIDIA NIM · Jetpack Compose · SceneView",
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
