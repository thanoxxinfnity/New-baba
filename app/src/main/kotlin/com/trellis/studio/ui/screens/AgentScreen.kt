package com.trellis.studio.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.service.AutomationService
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.AgentBrain
import com.trellis.studio.viewmodel.AgentController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Talk to VOID's agent the way you talk to the chat: say what you want done —
 * "open Godot and make a new 2D scene", "reply to the last WhatsApp" — and it
 * drives the phone to do it, speaking back as it goes. Voice in, voice out, and
 * a floating bubble so it keeps working over other apps.
 */
@Composable
fun AgentScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()

    val transcript by AgentBrain.transcript.collectAsStateWithLifecycle()
    val busy by AgentBrain.busy.collectAsStateWithLifecycle()
    val controllerState by AgentController.state.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var ttsOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        ttsOn = prefs.agentTts.first()
        AgentBrain.setSpeaking(ttsOn)
    }

    // Re-read on each recomposition, so returning from Settings updates the state.
    val overlayOk = Settings.canDrawOverlays(context)
    val accessibilityOk = isAccessibilityEnabled(context)
    val ready = accessibilityOk

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) AgentController.startVoice(context) }

    fun talk() {
        val has = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (has) AgentController.startVoice(context) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        AgentBrain.submit(context, text)
        input = ""
    }

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text(
                        "AI Agent",
                        style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (ready) "Tell me what to do on your phone"
                        else "Needs the accessibility service",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) TextSecondary else Amber,
                    )
                }
                // Speak-aloud toggle.
                IconButton(onClick = {
                    ttsOn = !ttsOn
                    AgentBrain.setSpeaking(ttsOn)
                    scope.launch { prefs.setAgentTts(ttsOn) }
                }) {
                    Icon(
                        if (ttsOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        "Speak replies",
                        tint = if (ttsOn) Cyan else TextDisabled,
                    )
                }
                // Floating-bubble toggle for background use over other apps.
                IconButton(onClick = {
                    if (controllerState.overlayUp) AgentController.hideOverlay(context)
                    else if (overlayOk) AgentController.showOverlay(context)
                    else context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) {
                    Icon(
                        Icons.Default.BubbleChart,
                        "Floating bubble",
                        tint = if (controllerState.overlayUp) Pink else TextDisabled,
                    )
                }
            }

            // Master setup: shown until all three are granted, then it collapses
            // to a single line so it stays out of the way once the agent is ready.
            val micOk = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            SetupCard(
                accessibilityOk = accessibilityOk,
                overlayOk = overlayOk,
                micOk = micOk,
                onAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOverlay = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                onMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onAppInfo = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
            )

            // Conversation
            if (transcript.isEmpty()) {
                EmptyAgentHint(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(transcript) { m -> MessageBubble(m) }
                }
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(13.dp), color = Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Working…", color = Cyan, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AgentBrain.stop() }) {
                        Text("Stop", color = Color(0xFFDC2626), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Input row: mic + text + send
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val listening = controllerState.listening
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (listening) Pink else Purple40)
                        .clickable(enabled = ready) { if (listening) AgentController.stopVoice() else talk() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (listening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        "Speak",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = ready,
                    placeholder = {
                        Text(
                            "Kya karna hai? e.g. open Chrome",
                            color = TextDisabled,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Cyan,
                    ),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )

                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (input.isBlank()) CardHigh else Cyan)
                        .clickable(enabled = ready && input.isNotBlank()) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Send, "Send",
                        tint = if (input.isBlank()) TextDisabled else Color.Black,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: AgentBrain.Message) {
    when (m.role) {
        AgentBrain.Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Purple40)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(m.text, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        }

        AgentBrain.Role.AGENT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                    .background(CardHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(m.text, color = TextPrimary, style = MaterialTheme.typography.bodyMedium) }
        }

        AgentBrain.Role.ACTION -> Row(
            Modifier.fillMaxWidth().padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.TouchApp, null, tint = Teal, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.text, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }

        AgentBrain.Role.ERROR -> Row(
            Modifier.fillMaxWidth().padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = Amber, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.text, color = Amber, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The one place to switch everything on. Each capability shows its live state
 * and a button that opens the exact setting for it. Once all three are on it
 * shrinks to a single "ready" line, and it always carries the MIUI/Xiaomi note
 * because "Not working. Tap for info." is the failure most users hit there.
 */
@Composable
private fun SetupCard(
    accessibilityOk: Boolean,
    overlayOk: Boolean,
    micOk: Boolean,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onMic: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val allOk = accessibilityOk && overlayOk && micOk
    var showXiaomi by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(glow = if (allOk) Teal else Amber).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (allOk) Icons.Default.CheckCircle else Icons.Default.PowerSettingsNew,
                null, tint = if (allOk) Teal else Amber, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (allOk) "Agent ready" else "Turn the agent on",
                color = TextPrimary, style = MaterialTheme.typography.labelLarge,
            )
        }

        if (!allOk) {
            SetupRow(
                "Accessibility service", "Lets the agent tap, type and scroll — required",
                accessibilityOk, onAccessibility,
            )
            SetupRow(
                "Draw over other apps", "The floating bubble for use over other apps",
                overlayOk, onOverlay,
            )
            SetupRow(
                "Microphone", "So you can speak commands",
                micOk, onMic,
            )

            // The Xiaomi case, folded away so it does not shout at everyone.
            TextButton(
                onClick = { showXiaomi = !showXiaomi },
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    if (showXiaomi) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = Cyan, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "It says \"Not working\" / Xiaomi, Oppo, Vivo?",
                    color = Cyan, style = MaterialTheme.typography.labelSmall,
                )
            }
            if (showXiaomi) {
                Text(
                    "Some phones block accessibility for sideloaded apps. Fix it once:\n" +
                        "1. Open App info below → tap the ⋮ menu → \"Allow restricted settings\".\n" +
                        "2. In Autostart / Startup, allow VOID to start.\n" +
                        "3. Back in Accessibility, if VOID shows \"Not working\", turn it OFF " +
                        "then ON again — a reinstall leaves the old entry stale.",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(
                    onClick = onAppInfo,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(15.dp), tint = Cyan)
                    Spacer(Modifier.width(6.dp))
                    Text("Open App info", color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, note: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null, tint = if (granted) Teal else Amber, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(note, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
        if (!granted) {
            FilledTonalButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Purple40),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Turn on", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyAgentHint(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.SmartToy, null, tint = TextDisabled, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text("Tell me what to do", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Type or tap the mic. I'll open apps, tap, type and scroll for you — " +
                "and talk you through it. Turn on the bubble to keep me working over " +
                "other apps.",
            color = TextDisabled,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Open Chrome and search for cats",
            "Open WhatsApp",
            "Go to home screen",
        ).forEach {
            Text("· $it", color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

/** Whether the user has switched our AccessibilityService on in Settings. */
private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val id = "${context.packageName}/${AutomationService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
    return splitter.any { it.equals(id, ignoreCase = true) }
}
