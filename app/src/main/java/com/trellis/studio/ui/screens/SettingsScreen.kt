package com.trellis.studio.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import kotlinx.coroutines.launch
import com.trellis.studio.data.Model3DProvider
import java.io.File
import java.io.FileOutputStream

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container
    private val settings  = container.settingsRepository

    val provider            = settings.provider
    val nvidiaVoiceEnabled  = settings.nvidiaVoiceEnabled
    val voiceCloneFilePath  = settings.voiceCloneFilePath
    val voiceModel          = settings.voiceModel

    fun currentNvidiaKey()      = settings.nvidiaApiKey.value
    fun currentFalKey()         = settings.falApiKey.value
    fun currentPollinationsKey() = settings.pollinationsApiKey.value
    fun currentVoiceModel()     = settings.voiceModel.value

    fun setProvider(p: Model3DProvider)  = settings.setProvider(p)
    fun saveNvidiaKey(key: String)       = settings.setNvidiaApiKey(key)
    fun saveFalKey(key: String)          = settings.setFalApiKey(key)
    fun savePollinationsKey(key: String) = settings.setPollinationsApiKey(key)
    fun setNvidiaVoiceEnabled(v: Boolean) = settings.setNvidiaVoiceEnabled(v)
    fun setVoiceCloneFilePath(path: String?) = settings.setVoiceCloneFilePath(path)
    fun saveVoiceModel(model: String)    = settings.setVoiceModel(model)

    private val _apiTestResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val apiTestResult: kotlinx.coroutines.flow.StateFlow<String?> = _apiTestResult

    fun testApiKey() {
        viewModelScope.launch {
            _apiTestResult.value = "Testing…"
            val result = container.chatRepository.testApiKey()
            _apiTestResult.value = result ?: "✔ API key is valid — connection OK"
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context             = LocalContext.current
    val provider            by viewModel.provider.collectAsState()
    val nvidiaVoiceEnabled  by viewModel.nvidiaVoiceEnabled.collectAsState()
    val voiceCloneFilePath  by viewModel.voiceCloneFilePath.collectAsState()

    var nvidiaKey       by rememberSaveable { mutableStateOf(viewModel.currentNvidiaKey()) }
    var falKey          by rememberSaveable { mutableStateOf(viewModel.currentFalKey()) }
    var pollinationsKey by rememberSaveable { mutableStateOf(viewModel.currentPollinationsKey()) }
    var voiceModel      by rememberSaveable { mutableStateOf(viewModel.currentVoiceModel()) }
    var showNvidiaKey   by rememberSaveable { mutableStateOf(false) }
    var showFalKey      by rememberSaveable { mutableStateOf(false) }
    var showPollinKey   by rememberSaveable { mutableStateOf(false) }
    val apiTestResult   by viewModel.apiTestResult.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var savedTick by remember { mutableStateOf(0) }

    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar("Saved ✔")
    }

    // Voice sample picker (audio files)
    val voicePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val ext  = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "mp3"
            val dest = File(context.filesDir, "voice_clone_reference.$ext")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            viewModel.setVoiceCloneFilePath(dest.absolutePath)
            savedTick++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            // ── NVIDIA NIM API Key ──────────────────────────────────────────
            SectionCard(title = "NVIDIA NIM API Key") {
                Text(
                    "One nvapi- key powers everything: LLM chat (100+ models), image-to-3D," +
                        " voice synthesis, and vision. Free tier at build.nvidia.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value       = nvidiaKey,
                    onValueChange = { nvidiaKey = it },
                    label       = "API Key (nvapi-…)",
                    show        = showNvidiaKey,
                    onToggle    = { showNvidiaKey = !showNvidiaKey }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveButton(modifier = Modifier.weight(1f)) {
                        viewModel.saveNvidiaKey(nvidiaKey)
                        savedTick++
                    }
                    Button(
                        onClick = { viewModel.saveNvidiaKey(nvidiaKey); viewModel.testApiKey() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.size(6.dp))
                        Text("Test", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (apiTestResult != null) {
                    Spacer(Modifier.height(6.dp))
                    val isOk = apiTestResult?.startsWith("✔") == true
                    Text(
                        apiTestResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOk) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Voice Clone (NVIDIA NIM TTS) ────────────────────────────────
            SectionCard(title = "Voice & Voice Cloning") {
                Text(
                    "Enable NVIDIA NIM voice synthesis. Upload a sample (MP3/WAV) to clone your voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "NVIDIA NIM Voice",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = nvidiaVoiceEnabled,
                        onCheckedChange = { viewModel.setNvidiaVoiceEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor  = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor  = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = voiceModel,
                    onValueChange = { voiceModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Voice model") },
                    placeholder = { Text("elevenlabs/eleven-multilingual-v2") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    }
                )
                Spacer(Modifier.height(8.dp))
                SaveButton {
                    viewModel.saveVoiceModel(voiceModel)
                    savedTick++
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Text(
                    "Voice Clone Reference",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))

                if (voiceCloneFilePath != null) {
                    val fileName = voiceCloneFilePath?.substringAfterLast('/') ?: "voice sample"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { voicePickerLauncher.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Upload Sample")
                    }
                    if (voiceCloneFilePath != null) {
                        Button(
                            onClick = { viewModel.setVoiceCloneFilePath(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                "Clear",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Supported formats: MP3, WAV, OGG · 10–30 seconds of clear speech works best",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Image-to-3D Provider ────────────────────────────────────────
            SectionCard(title = "Image-to-3D Provider") {
                Text(
                    "Choose the backend for 3D model generation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.selectableGroup()) {
                    Model3DProvider.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = provider == option,
                                    onClick  = { viewModel.setProvider(option) },
                                    role     = Role.RadioButton
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = provider == option, onClick = null)
                            Spacer(Modifier.size(8.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            // ── fal.ai Key ──────────────────────────────────────────────────
            SectionCard(title = "fal.ai TRELLIS Key") {
                Text(
                    "Used when fal.ai TRELLIS is selected above. Format: key_id:key_secret",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value       = falKey,
                    onValueChange = { falKey = it },
                    label       = "fal.ai Key",
                    placeholder = "key_id:key_secret",
                    show        = showFalKey,
                    onToggle    = { showFalKey = !showFalKey }
                )
                Spacer(Modifier.height(8.dp))
                SaveButton { viewModel.saveFalKey(falKey); savedTick++ }
            }

            // ── Pollinations Key ────────────────────────────────────────────
            SectionCard(title = "Pollinations TRELLIS Key") {
                Text(
                    "Used when Pollinations TRELLIS is selected. Free weekly Pollen credit. Format: sk_… or pk_…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value       = pollinationsKey,
                    onValueChange = { pollinationsKey = it },
                    label       = "Pollinations Key",
                    placeholder = "sk_… or pk_…",
                    show        = showPollinKey,
                    onToggle    = { showPollinKey = !showPollinKey }
                )
                Spacer(Modifier.height(8.dp))
                SaveButton { viewModel.savePollinationsKey(pollinationsKey); savedTick++ }
            }

            // ── Info Card ───────────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text("About NVIDIA NIM", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "The NVIDIA NIM endpoint (integrate.api.nvidia.com) gives access to 100+ " +
                            "LLMs, vision, reasoning, and voice models — all with one nvapi- key. " +
                            "Voice cloning availability depends on which audio models NVIDIA NIM " +
                            "exposes on your account tier. The free tier covers ~40 req/min.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "NIM AI Agent v2.0  ·  Chat: NVIDIA NIM  ·  Images: Pollinations FLUX (free)  ·  3D: NVIDIA / fal.ai / Pollinations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    show: Boolean,
    onToggle: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (show) VisualTransformation.None
                               else PasswordVisualTransformation(),
        leadingIcon  = { Icon(Icons.Default.Key, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (show) "Hide" else "Show"
                )
            }
        }
    )
}

@Composable
private fun SaveButton(modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(48.dp),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("Save")
    }
}
