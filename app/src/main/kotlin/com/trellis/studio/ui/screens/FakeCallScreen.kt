package com.trellis.studio.ui.screens

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.delay

/** Feature: Fake Call — schedule a realistic incoming call to prank a friend or
 *  escape an awkward moment. Rings and vibrates like the real thing. */
@Composable
fun FakeCallScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("Mom") }
    var number by remember { mutableStateOf("+91 98765 43210") }
    var delaySec by remember { mutableIntStateOf(5) }
    var phase by remember { mutableStateOf("setup") }  // setup | waiting | ringing | connected

    // Countdown then ring
    LaunchedEffect(phase) {
        if (phase == "waiting") { delay(delaySec * 1000L); phase = "ringing" }
    }
    // Connected call timer
    var callSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        if (phase == "connected") { callSec = 0; while (true) { delay(1000); callSec++ } }
    }
    // Ringtone + vibration while ringing
    DisposableEffect(phase) {
        var rt: Ringtone? = null
        var vib: Vibrator? = null
        if (phase == "ringing") {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                rt = RingtoneManager.getRingtone(context, uri).also { it.play() }
            }
            runCatching {
                vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val pattern = longArrayOf(0, 800, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                else @Suppress("DEPRECATION") vib?.vibrate(pattern, 0)
            }
        }
        onDispose { runCatching { rt?.stop() }; runCatching { vib?.cancel() } }
    }

    when (phase) {
        "ringing" -> IncomingCall(name, number,
            onAnswer = { phase = "connected" }, onDecline = { phase = "setup" })
        "connected" -> ConnectedCall(name, callSec) { phase = "setup" }
        else -> Column(Modifier.fillMaxSize().background(BgDark)) {
            ToolHeader("Fake Call", "Schedule a realistic incoming call", onMenu)
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Caller name", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
                OutlinedTextField(value = number, onValueChange = { number = it },
                    label = { Text("Number", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
                Text("Ring after", color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 30, 60).forEach { s ->
                        FilterChip(selected = delaySec == s, onClick = { delaySec = s }, label = { Text("${s}s") })
                    }
                }
                Spacer(Modifier.weight(1f))
                if (phase == "waiting") {
                    Text("Ringing in ${delaySec}s… lock your phone or hand it over 😏",
                        color = Cyan, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { phase = if (phase == "waiting") "setup" else "waiting" },
                    colors = ButtonDefaults.buttonColors(containerColor = if (phase == "waiting") Pink else Purple40),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.Call, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(if (phase == "waiting") "Cancel" else "Schedule call", color = Color.White,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun IncomingCall(name: String, number: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "ring")
    val pulse by infinite.animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0B12), Color(0xFF161228)))),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(80.dp))
        Text("Incoming call", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(30.dp))
        Box(Modifier.size(140.dp).scale(pulse).clip(CircleShape).background(NeonBrush),
            contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.displayMedium,
                color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(name, color = TextPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(number, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 60.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallButton(Icons.Default.CallEnd, Color(0xFFE53935), "Decline", onDecline)
            CallButton(Icons.Default.Call, Color(0xFF43A047), "Answer", onAnswer)
        }
    }
}

@Composable
private fun ConnectedCall(name: String, seconds: Int, onEnd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BgDark), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(100.dp))
        Box(Modifier.size(120.dp).clip(CircleShape).background(NeonBrush), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.displaySmall,
                color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(name, color = TextPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Teal, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        CallButton(Icons.Default.CallEnd, Color(0xFFE53935), "End", onEnd)
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(onClick = onClick, containerColor = color, modifier = Modifier.size(68.dp)) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}
