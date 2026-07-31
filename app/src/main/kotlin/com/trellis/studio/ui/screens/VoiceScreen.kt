package com.trellis.studio.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.data.entity.VoiceEntity
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.MAGPIE_EMOTIONS
import com.trellis.studio.viewmodel.MAGPIE_LANGUAGES
import com.trellis.studio.viewmodel.MAGPIE_VOICES
import com.trellis.studio.viewmodel.VoiceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    vm: VoiceViewModel = viewModel(),
    onMenu: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording() }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // ---- Top bar ------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(onMenu)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showVoiceSheet = true }) {
                Icon(Icons.Default.GraphicEq, "Voices", tint = TextSecondary)
            }
        }

        // ---- Centre: greeting or the last generations ----------------------
        if (state.history.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    null,
                    tint = Cyan,
                    modifier = Modifier.size(46.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "Speak in any voice.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "NVIDIA Magpie voices, or clone your own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true,
            ) {
                items(state.history, key = { it.id }) { entry ->
                    val playing = state.playingPath == entry.audioPath
                    Row(
                        Modifier.fillMaxWidth()
                            .glass(shape = RoundedCornerShape(18.dp), glow = if (playing) Cyan else null)
                            .clickable {
                                if (playing) vm.stopPlayback() else vm.play(entry.audioPath)
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (playing) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                            null,
                            tint = if (playing) Cyan else Purple60,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.textInput,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 2,
                            )
                            Text(
                                entry.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextDisabled,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { vm.deleteHistory(entry) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.DeleteOutline, "Delete",
                                tint = TextDisabled, modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- Status / errors ----------------------------------------------
        state.status?.let {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(13.dp), color = Cyan, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = Cyan)
            }
        }
        state.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .glass(shape = RoundedCornerShape(12.dp), fill = Red.copy(alpha = 0.14f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(err, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::clearError, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(13.dp))
                }
            }
        }

        // ---- Recording banner ---------------------------------------------
        AnimatedVisibility(visible = state.isRecording || state.recordedSample != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .glass(shape = RoundedCornerShape(16.dp), glow = if (state.isRecording) Red else Teal)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRecording) {
                    PingDot(color = Red, size = 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Recording… ${state.recordSeconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Red,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::stopRecording) { Text("Stop", color = Red) }
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Sample ready (${state.recordSeconds}s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Teal,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showNameDialog = true }) { Text("Save voice", color = Cyan) }
                    IconButton(onClick = vm::discardRecording, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Discard", tint = TextDisabled, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        // ---- Composer: the input card from the reference design -------------
        Surface(
            color = Color_Transparent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(shape = RoundedCornerShape(26.dp), fill = CardHigh.copy(alpha = 0.92f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = state.text,
                    onValueChange = vm::setText,
                    placeholder = { Text("Type what to say…", color = TextDisabled) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Color_Transparent,
                        unfocusedContainerColor = Color_Transparent,
                        focusedIndicatorColor = Color_Transparent,
                        unfocusedIndicatorColor = Color_Transparent,
                        cursorColor = Cyan,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Record a voice to clone
                    IconButton(
                        onClick = {
                            if (state.isRecording) vm.stopRecording()
                            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.size(38.dp).clip(CircleShape)
                            .background(if (state.isRecording) Red.copy(alpha = 0.2f) else CardDark),
                    ) {
                        Icon(
                            if (state.isRecording) Icons.Default.Stop else Icons.Default.Add,
                            "Clone a voice",
                            tint = if (state.isRecording) Red else TextSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))

                    // Voice selector pill
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(CardDark)
                            .clickable { showVoiceSheet = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.selectedVoice?.isCloned == true) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Cyan, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            state.voiceLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Default.ExpandMore, null,
                            tint = TextSecondary, modifier = Modifier.size(15.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Speak
                    val ready = state.text.isNotBlank() && !state.isGenerating
                    IconButton(
                        onClick = { if (state.isGenerating) vm.stopPlayback() else vm.generate() },
                        enabled = ready || state.isGenerating,
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(if (ready) Purple40 else CardDark),
                    ) {
                        if (state.isGenerating) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.GraphicEq, "Speak",
                                tint = if (ready) TextPrimary else TextDisabled,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Voice picker -----------------------------------------------------
    if (showVoiceSheet) {
        ModalBottomSheet(onDismissRequest = { showVoiceSheet = false }, containerColor = SurfDark) {
            Column(Modifier.fillMaxHeight(0.75f)) {
                Text(
                    "Voices",
                    style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.voices.any { it.isCloned }) {
                        item {
                            SectionLabel("Your cloned voices")
                        }
                        items(state.voices.filter { it.isCloned }, key = { it.id }) { v ->
                            ClonedVoiceRow(
                                voice = v,
                                selected = state.selectedVoiceId == v.id,
                                playing = state.playingPath == v.previewPath,
                                onSelect = { vm.selectVoice(v.id); showVoiceSheet = false },
                                onPreview = {
                                    v.previewPath?.let {
                                        if (state.playingPath == it) vm.stopPlayback() else vm.play(it)
                                    }
                                },
                                onDelete = { vm.deleteVoice(v) },
                            )
                        }
                    }
                    item {
                        SectionLabel("Emotion")
                        Row(
                            Modifier.fillMaxWidth()
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            MAGPIE_EMOTIONS.forEach { e ->
                                FilterChip(
                                    selected = state.emotion == e,
                                    onClick = { vm.setEmotion(e) },
                                    label = { Text(e.ifBlank { "Default" }, style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Purple40,
                                        selectedLabelColor = TextPrimary,
                                        containerColor = CardDark,
                                        labelColor = TextSecondary,
                                    ),
                                )
                            }
                        }
                    }
                    MAGPIE_LANGUAGES.forEach { (tag, _) ->
                        item { SectionLabel("Magpie · $tag") }
                        items(MAGPIE_VOICES.filter { it.language == tag }, key = { it.id() }) { v ->
                            val vid = v.id()
                            ListItem(
                                headlineContent = { Text(v.speaker, color = TextPrimary) },
                                supportingContent = { Text(tag, color = TextDisabled, style = MaterialTheme.typography.labelSmall) },
                                leadingContent = {
                                    Icon(Icons.Default.RecordVoiceOver, null, tint = Purple60, modifier = Modifier.size(19.dp))
                                },
                                trailingContent = {
                                    if (state.selectedVoiceId == null && state.selectedBuiltIn == vid) {
                                        Icon(Icons.Default.Check, null, tint = Cyan)
                                    }
                                },
                                modifier = Modifier.clickable {
                                    vm.selectBuiltIn(vid); showVoiceSheet = false
                                },
                                colors = ListItemDefaults.colors(containerColor = SurfDark),
                            )
                        }
                    }
                    item {
                        Text(
                            "Tap + in the composer to record a sample and clone your own voice.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDisabled,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }

    // ---- Name the cloned voice --------------------------------------------
    if (showNameDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = CardDark,
            title = { Text("Name this voice", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Saved voices can be reused any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("My voice", color = TextDisabled) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = BorderDark,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveClonedVoice(name.ifBlank { "My voice" })
                        showNameDialog = false
                    },
                ) { Text("Clone", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextDisabled,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ClonedVoiceRow(
    voice: VoiceEntity,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreview, modifier = Modifier.size(34.dp)) {
            Icon(
                if (playing) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                "Preview",
                tint = if (playing) Cyan else Purple60,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(voice.name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 1)
            Text("Cloned voice", style = MaterialTheme.typography.labelSmall, color = Cyan)
        }
        if (selected) {
            Icon(Icons.Default.Check, null, tint = Cyan, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.DeleteOutline, "Delete", tint = TextDisabled, modifier = Modifier.size(17.dp))
        }
    }
    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
}

private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent
