package com.trellis.studio.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.service.AutomationService
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.AgentController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The control panel for the on-device agent: point it at a server, hand it the
 * two permissions it needs, and turn it on. Once running it can tap, type and
 * open apps on command; the floating bubble carries the same controls over
 * every other app.
 */
@Composable
fun AgentScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()

    val state by AgentController.state.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        url = prefs.agentUrl.first()
        loaded = true
    }

    // Re-checked on every recomposition so returning from Settings updates the
    // chips without a manual refresh.
    val overlayOk = Settings.canDrawOverlays(context)
    val accessibilityOk = isAccessibilityEnabled(context)

    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
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
                        "Let the agent control the phone on command",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                StatusDot(state.phase)
            }

            Column(
                Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(state)

                // 1 — the two special permissions the agent cannot work without.
                Column(
                    Modifier.fillMaxWidth().glass().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("1 · Permissions", style = MaterialTheme.typography.labelLarge, color = Cyan)
                    PermissionRow(
                        label = "Draw over other apps",
                        note = "For the floating control bubble",
                        granted = overlayOk,
                        onGrant = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        },
                    )
                    PermissionRow(
                        label = "Accessibility service",
                        note = "The taps, typing and app launches run through it",
                        granted = accessibilityOk,
                        onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    )
                }

                // 2 — where the commands come from.
                Column(
                    Modifier.fillMaxWidth().glass().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("2 · Agent server", style = MaterialTheme.typography.labelLarge, color = Cyan)
                    Text(
                        "The WebSocket your Replit or remote agent serves. It sends " +
                            "JSON commands; the app runs them and reports back.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        enabled = loaded && state.phase == AgentController.Phase.OFF,
                        singleLine = true,
                        placeholder = {
                            Text("wss://your-agent.repl.co/ws", color = TextDisabled,
                                style = MaterialTheme.typography.bodySmall)
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Cyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3 — the switch.
                if (state.phase == AgentController.Phase.OFF) {
                    Button(
                        onClick = {
                            scope.launch {
                                prefs.setAgentUrl(url)
                                when (val problem = AgentController.start(context, url.trim())) {
                                    null -> Unit
                                    "overlay-permission" ->
                                        snackbar.showSnackbar("Grant \"Draw over other apps\" first.")
                                    "accessibility-permission" ->
                                        snackbar.showSnackbar("Turn on the Accessibility service first.")
                                    else -> snackbar.showSnackbar(problem)
                                }
                            }
                        },
                        enabled = loaded,
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start agent", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { AgentController.stop(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop agent", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                CommandReference()
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun StatusDot(phase: AgentController.Phase) {
    val color = when (phase) {
        AgentController.Phase.CONNECTED -> Teal
        AgentController.Phase.CONNECTING -> Amber
        AgentController.Phase.OFF -> TextDisabled
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun StatusCard(state: AgentController.State) {
    val glow = when (state.phase) {
        AgentController.Phase.CONNECTED -> Teal
        AgentController.Phase.CONNECTING -> Amber
        AgentController.Phase.OFF -> Purple40
    }
    Row(
        Modifier.fillMaxWidth().glass(glow = glow).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (state.phase) {
                AgentController.Phase.CONNECTED -> Icons.Default.SmartToy
                AgentController.Phase.CONNECTING -> Icons.Default.Sync
                AgentController.Phase.OFF -> Icons.Default.PowerSettingsNew
            },
            null, tint = glow, modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (state.phase) {
                    AgentController.Phase.CONNECTED -> "Agent connected"
                    AgentController.Phase.CONNECTING -> "Connecting…"
                    AgentController.Phase.OFF -> "Agent off"
                },
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.detail, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    note: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint = if (granted) Teal else Amber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(note, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
        if (!granted) {
            TextButton(onClick = onGrant) {
                Text("Grant", color = Cyan, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CommandReference() {
    Column(
        Modifier.fillMaxWidth().glass(fill = GlassFill.copy(alpha = 0.5f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Commands the agent can send", color = TextPrimary,
                style = MaterialTheme.typography.labelLarge)
        }
        Text(
            """
            |{"type":"click","x":500,"y":1000}
            |{"type":"click_text","text":"Login"}
            |{"type":"type_text","text":"Hello"}
            |{"type":"swipe","startX":..,"startY":..,"endX":..,"endY":..}
            |{"type":"scroll","dy":-800}
            |{"type":"launch","package":"com.whatsapp"}
            |{"type":"home"} {"type":"back"} {"type":"recents"}
            """.trimMargin(),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        )
        Text(
            "Each command is answered with " +
                "{\"type\":\"status\",\"ok\":true|false,\"detail\":\"…\"}.",
            color = TextDisabled,
            style = MaterialTheme.typography.labelSmall,
        )
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
