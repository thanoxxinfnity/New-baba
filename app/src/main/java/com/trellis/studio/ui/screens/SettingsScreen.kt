package com.trellis.studio.ui.screens

import android.app.Application
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.Model3DProvider

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container
    private val settings = container.settingsRepository

    val provider = settings.provider

    fun currentNvidiaKey() = settings.nvidiaApiKey.value
    fun currentFalKey() = settings.falApiKey.value
    fun currentPollinationsKey() = settings.pollinationsApiKey.value

    fun setProvider(provider: Model3DProvider) = settings.setProvider(provider)
    fun saveNvidiaKey(key: String) = settings.setNvidiaApiKey(key)
    fun saveFalKey(key: String) = settings.setFalApiKey(key)
    fun savePollinationsKey(key: String) = settings.setPollinationsApiKey(key)
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val provider by viewModel.provider.collectAsState()
    var nvidiaKey by rememberSaveable { mutableStateOf(viewModel.currentNvidiaKey()) }
    var falKey by rememberSaveable { mutableStateOf(viewModel.currentFalKey()) }
    var pollinationsKey by rememberSaveable { mutableStateOf(viewModel.currentPollinationsKey()) }
    var showNvidiaKey by rememberSaveable { mutableStateOf(false) }
    var showFalKey by rememberSaveable { mutableStateOf(false) }
    var showPollinationsKey by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var savedTick by remember { mutableStateOf(0) }

    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar("Saved ✔")
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

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Image-to-3D provider", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pick which service generates the 3D model. Each has its own key below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.selectableGroup()) {
                        Model3DProvider.entries.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = provider == option,
                                        onClick = { viewModel.setProvider(option) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = provider == option, onClick = null)
                                Spacer(Modifier.size(8.dp))
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            ApiKeyCard(
                title = "NVIDIA TRELLIS API key",
                description = "Used only when \"NVIDIA TRELLIS\" is selected above. Free key at " +
                    "build.nvidia.com (microsoft/trellis) — starts with \"nvapi-\". Note: NVIDIA's " +
                    "free preview currently only accepts its own sample images and has been " +
                    "returning server errors — see the notice below.",
                keyValue = nvidiaKey,
                onKeyChange = { nvidiaKey = it },
                placeholder = "nvapi-…",
                showKey = showNvidiaKey,
                onToggleShow = { showNvidiaKey = !showNvidiaKey },
                onSave = {
                    viewModel.saveNvidiaKey(nvidiaKey)
                    savedTick++
                }
            )

            ApiKeyCard(
                title = "fal.ai TRELLIS API key",
                description = "Used only when \"fal.ai TRELLIS\" is selected above. Paid, " +
                    "pay-per-use — accepts real uploaded photos. Get a key at " +
                    "fal.ai/dashboard/keys, format \"key_id:key_secret\".",
                keyValue = falKey,
                onKeyChange = { falKey = it },
                placeholder = "key_id:key_secret",
                showKey = showFalKey,
                onToggleShow = { showFalKey = !showFalKey },
                onSave = {
                    viewModel.saveFalKey(falKey)
                    savedTick++
                }
            )

            ApiKeyCard(
                title = "Pollinations TRELLIS API key",
                description = "Used only when \"Pollinations TRELLIS\" is selected above. Free " +
                    "weekly Pollen credit, accepts real uploaded photos, AND supports pure " +
                    "text-to-3D (no image needed — a text-only option appears on the Image-to-3D " +
                    "screen when this provider is active). Get a key at enter.pollinations.ai/keys.",
                keyValue = pollinationsKey,
                onKeyChange = { pollinationsKey = it },
                placeholder = "sk_… or pk_…",
                showKey = showPollinationsKey,
                onToggleShow = { showPollinationsKey = !showPollinationsKey },
                onSave = {
                    viewModel.savePollinationsKey(pollinationsKey)
                    savedTick++
                }
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text("About NVIDIA TRELLIS right now", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "NVIDIA's free/preview TRELLIS endpoint currently only accepts its own " +
                            "sample images, not photos you upload — this is documented by NVIDIA " +
                            "itself, not a bug in this app. It has also been intermittently " +
                            "returning server errors for other developers. If Image-to-3D fails " +
                            "on NVIDIA, switch the provider above to fal.ai or Pollinations, both " +
                            "of which accept real photos today — Pollinations also has a free tier " +
                            "and supports text-to-3D directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "TRELLIS 3D Studio v1.0\nImages: Pollinations FLUX (free, no key) • " +
                    "3D: NVIDIA TRELLIS or fal.ai TRELLIS (key required, above)",
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
private fun ApiKeyCard(
    title: String,
    description: String,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    placeholder: String,
    showKey: Boolean,
    onToggleShow: () -> Unit,
    onSave: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = keyValue,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onToggleShow) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key"
                        )
                    }
                }
            )

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Save")
            }
        }
    }
}
