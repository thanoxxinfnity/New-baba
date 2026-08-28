package com.trellis.studio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.LiveChatViewModel

/**
 * Hands-free voice conversation with the AI. Tap once and just talk — it listens,
 * answers out loud, and listens again, in your cloned voice if you have one.
 */
@Composable
fun LiveVoiceScreen(
    vm: LiveChatViewModel = viewModel(),
    onMenu: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.start() }

    fun toggle() {
        if (state.running) { vm.stop(); return }
        val has = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (has) vm.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Live Voice", style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold)
                    // The reply voice — tap to change.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !state.running) { menu = true }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.RecordVoiceOver, null, tint = Cyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(state.voiceLabel, style = MaterialTheme.typography.labelSmall, color = Cyan)
                            Icon(Icons.Default.ArrowDropDown, null, tint = Cyan, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            state.voices.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label) },
                                    onClick = { vm.setVoice(v.key); menu = false },
                                    trailingIcon = {
                                        if (v.key == state.voiceKey)
                                            Icon(Icons.Default.Check, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.lines.isNotEmpty()) {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Default.Delete, "Clear", tint = TextDisabled)
                    }
                }
            }

            if (state.lines.isEmpty()) {
                EmptyLive(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.lines) { line -> Bubble(line) }
                }
            }

            // Live status line (partial speech / thinking / speaking).
            val status = when (state.phase) {
                LiveChatViewModel.Phase.LISTENING -> state.partial.ifBlank { "Listening…" }
                LiveChatViewModel.Phase.THINKING -> "Thinking…"
                LiveChatViewModel.Phase.SPEAKING -> "Speaking…"
                LiveChatViewModel.Phase.IDLE -> if (state.running) "…" else "Tap the mic and start talking"
            }
            state.error?.let {
                Text(it, color = Amber, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            Text(status, color = if (state.running) Cyan else TextDisabled,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth())

            // The big talk button, pulsing while active.
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val s by pulse.animateFloat(
                    initialValue = 1f, targetValue = if (state.running) 1.12f else 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s",
                )
                val color = when (state.phase) {
                    LiveChatViewModel.Phase.LISTENING -> Teal
                    LiveChatViewModel.Phase.THINKING -> Amber
                    LiveChatViewModel.Phase.SPEAKING -> Pink
                    else -> Purple40
                }
                Box(
                    Modifier.size(96.dp).scale(if (state.running) s else 1f).clip(CircleShape)
                        .background(if (state.running) color else Purple40)
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.running) Icons.Default.Stop else Icons.Default.Mic,
                        if (state.running) "Stop" else "Start talking",
                        tint = Color.White, modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(line: LiveChatViewModel.Line) {
    val you = line.role == LiveChatViewModel.Role.YOU
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (you) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(
                    if (you) 18.dp else 18.dp, 18.dp,
                    if (you) 4.dp else 18.dp, if (you) 18.dp else 4.dp))
                .background(if (you) Purple40 else CardHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(line.text, color = if (you) Color.White else TextPrimary,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyLive(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.RecordVoiceOver, null, tint = TextDisabled, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text("Talk to the AI", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the mic and just speak — it answers out loud and keeps the " +
                "conversation going. Clone a voice in the Voice tab first and it will " +
                "reply in that voice.",
            color = TextDisabled, style = MaterialTheme.typography.bodySmall,
        )
    }
}
