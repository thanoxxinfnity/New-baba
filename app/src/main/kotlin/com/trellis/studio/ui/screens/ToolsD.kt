package com.trellis.studio.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale

// ─────────────────────────── Base64 & Hash (dev) ──────────────────────────
@Composable
fun DevToolsScreen(onMenu: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    fun out(v: String) { output = v }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Dev Tools", "Base64 · Hash", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Input", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(120.dp), shape = RoundedCornerShape(14.dp))
            val ops = listOf<Pair<String, () -> String>>(
                "Base64 encode" to { android.util.Base64.encodeToString(input.toByteArray(), android.util.Base64.NO_WRAP) },
                "Base64 decode" to { runCatching { String(android.util.Base64.decode(input, android.util.Base64.DEFAULT)) }.getOrDefault("Invalid Base64") },
                "MD5" to { hashOf(input, "MD5") },
                "SHA-1" to { hashOf(input, "SHA-1") },
                "SHA-256" to { hashOf(input, "SHA-256") },
                "URL encode" to { java.net.URLEncoder.encode(input, "UTF-8") },
            )
            ops.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, op) ->
                        FilledTonalButton(onClick = { out(op()) }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardHigh),
                            shape = RoundedCornerShape(12.dp)) { Text(label, color = TextPrimary, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (output.isNotBlank()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark)
                    .clickable { clip.setText(AnnotatedString(output)) }.padding(16.dp)) {
                    Text(output, color = Cyan, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Tap the result to copy.", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun hashOf(s: String, algo: String): String =
    MessageDigest.getInstance(algo).digest(s.toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) }

// ────────────────────────── Breathing Exercise ────────────────────────────
@Composable
fun BreathingScreen(onMenu: () -> Unit = {}) {
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Breathe in") }
    val scale = remember { Animatable(0.5f) }

    LaunchedEffect(running) {
        while (running) {
            phase = "Breathe in"; scale.animateTo(1f, tween(4000))
            phase = "Hold"; delay(2000)
            phase = "Breathe out"; scale.animateTo(0.5f, tween(4000))
            phase = "Hold"; delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Breathe", "Calm down in 60 seconds", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(240.dp).scale(scale.value).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Cyan, Purple40))))
                Text(if (running) phase else "Tap start", color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(40.dp))
            Button(onClick = { running = !running },
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Pink else Teal),
                shape = RoundedCornerShape(16.dp)) {
                Text(if (running) "Stop" else "Start", fontWeight = FontWeight.Bold, color = BgDark)
            }
        }
    }
}

// ────────────────────────────── Tic-Tac-Toe ───────────────────────────────
@Composable
fun TicTacToeScreen(onMenu: () -> Unit = {}) {
    val board = remember { mutableStateListOf(*Array(9) { "" }) }
    var turn by remember { mutableStateOf("X") }
    var status by remember { mutableStateOf("X's turn") }
    var over by remember { mutableStateOf(false) }

    fun winner(): String? {
        val w = listOf(intArrayOf(0,1,2),intArrayOf(3,4,5),intArrayOf(6,7,8),intArrayOf(0,3,6),
            intArrayOf(1,4,7),intArrayOf(2,5,8),intArrayOf(0,4,8),intArrayOf(2,4,6))
        for (l in w) if (board[l[0]].isNotEmpty() && board[l[0]] == board[l[1]] && board[l[1]] == board[l[2]]) return board[l[0]]
        return null
    }
    fun reset() { for (i in 0..8) board[i] = ""; turn = "X"; status = "X's turn"; over = false }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Tic-Tac-Toe", "Two players, pass & play", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(status, color = if (over) Teal else TextPrimary, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(9) { i ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(CardDark)
                        .clickable {
                            if (board[i].isEmpty() && !over) {
                                board[i] = turn
                                val win = winner()
                                when {
                                    win != null -> { status = "$win wins! 🎉"; over = true }
                                    board.none { it.isEmpty() } -> { status = "Draw!"; over = true }
                                    else -> { turn = if (turn == "X") "O" else "X"; status = "$turn's turn" }
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text(board[i], color = if (board[i] == "X") Cyan else Pink, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
            Button(onClick = { reset() }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("New game")
            }
        }
    }
}

// ─────────────────────────────── Whiteboard ───────────────────────────────
@Composable
fun WhiteboardScreen(onMenu: () -> Unit = {}) {
    val paths = remember { mutableStateListOf<Pair<Path, Color>>() }
    var current by remember { mutableStateOf<Path?>(null) }
    var color by remember { mutableStateOf(Cyan) }
    val palette = listOf(Cyan, Pink, Teal, Purple60, Color.White, Color(0xFFFFB020))

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Whiteboard", "Doodle away", onMenu)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF14141F))
            .pointerInput(color) {
                detectDragGestures(
                    onDragStart = { off -> current = Path().apply { moveTo(off.x, off.y) } },
                    onDrag = { change, _ ->
                        current?.lineTo(change.position.x, change.position.y)
                        // force redraw by re-adding
                        current?.let { p -> if (paths.isEmpty() || paths.last().first !== p) paths.add(p to color) }
                    },
                    onDragEnd = { current = null },
                )
            }) {
            paths.forEach { (p, c) -> drawPath(p, c, style = Stroke(width = 8f, cap = StrokeCap.Round)) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            palette.forEach { c ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(c)
                    .clickable { color = c }.then(if (c == color) Modifier.scale(1.2f) else Modifier))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { paths.clear() }) { Icon(Icons.Default.Delete, "Clear", tint = Pink) }
        }
    }
}

// ─────────────────────────────── Metronome ────────────────────────────────
@Composable
fun MetronomeScreen(onMenu: () -> Unit = {}) {
    var bpm by remember { mutableIntStateOf(100) }
    var running by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }
    DisposableEffect(Unit) { onDispose { runCatching { tone.release() } } }

    LaunchedEffect(running, bpm) {
        while (running) {
            beat = (beat % 4) + 1
            runCatching { tone.startTone(if (beat == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 60) }
            delay((60000L / bpm).coerceAtLeast(120))
        }
    }
    val pulse by animateFloatAsState(if (running && beat > 0) 1.15f else 1f, label = "tick")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Metronome", "Keep the beat", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(160.dp).scale(pulse).clip(CircleShape)
                .background(if (running) NeonBrush else androidx.compose.ui.graphics.SolidColor(CardDark)),
                contentAlignment = Alignment.Center) {
                Text("$bpm", color = if (running) Color.White else TextPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall)
            }
            Text("BPM", color = TextSecondary)
            Spacer(Modifier.height(20.dp))
            Slider(value = bpm.toFloat(), onValueChange = { bpm = it.toInt() }, valueRange = 40f..220f,
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan))
            Button(onClick = { running = !running; if (!running) beat = 0 },
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Pink else Teal),
                shape = RoundedCornerShape(16.dp)) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = BgDark)
                Spacer(Modifier.width(8.dp)); Text(if (running) "Stop" else "Start", color = BgDark, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────── Quick Notes ──────────────────────────────
@Composable
fun QuickNotesScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { text = prefs.quickNotes.first() }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Quick Notes", "Auto-saved scratchpad", onMenu)
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = text ?: "", onValueChange = { v -> text = v; scope.launch { prefs.setQuickNotes(v) } },
                placeholder = { Text("Jot anything… it saves automatically.", color = TextDisabled) },
                modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark),
            )
        }
    }
}
