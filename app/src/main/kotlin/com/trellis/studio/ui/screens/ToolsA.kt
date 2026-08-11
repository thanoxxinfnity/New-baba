package com.trellis.studio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.random.Random

// ───────────────────────── Focus Timer (Pomodoro) ─────────────────────────
@Composable
fun FocusTimerScreen(onMenu: () -> Unit = {}) {
    var totalSec by remember { mutableIntStateOf(25 * 60) }
    var leftSec by remember { mutableIntStateOf(25 * 60) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(running) {
        while (running && leftSec > 0) { delay(1000); leftSec-- }
        if (leftSec == 0) running = false
    }
    val progress by animateFloatAsState(
        if (totalSec > 0) leftSec.toFloat() / totalSec else 0f, label = "focus")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Focus Timer", "Lock in for a gaming or study session", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(),
                    color = CardHigh, strokeWidth = 14.dp)
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(),
                    color = if (leftSec < 60) Pink else Cyan, strokeWidth = 14.dp)
                Text("%02d:%02d".format(leftSec / 60, leftSec % 60),
                    style = MaterialTheme.typography.displayMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(5, 15, 25, 45).forEach { m ->
                    FilterChip(selected = totalSec == m * 60,
                        onClick = { totalSec = m * 60; leftSec = m * 60; running = false },
                        label = { Text("${m}m") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { running = !running },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp)); Text(if (running) "Pause" else "Start")
                }
                OutlinedButton(onClick = { leftSec = totalSec; running = false },
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Reset")
                }
            }
        }
    }
}

// ───────────────────────────── Stopwatch ──────────────────────────────────
@Composable
fun StopwatchScreen(onMenu: () -> Unit = {}) {
    var ms by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    val laps = remember { mutableStateListOf<Long>() }

    LaunchedEffect(running) {
        var last = System.currentTimeMillis()
        while (running) { delay(30); val now = System.currentTimeMillis(); ms += now - last; last = now }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Stopwatch", "With laps", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(fmtMs(ms), style = MaterialTheme.typography.displayMedium.copy(brush = NeonBrush),
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { running = !running },
                    colors = ButtonDefaults.buttonColors(containerColor = if (running) Pink else Teal),
                    shape = RoundedCornerShape(16.dp)) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(onClick = {
                    if (running) laps.add(0, ms) else { ms = 0; laps.clear() }
                }, shape = RoundedCornerShape(16.dp)) { Text(if (running) "Lap" else "Reset") }
            }
            laps.forEachIndexed { i, l ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardDark)
                    .padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lap ${laps.size - i}", color = TextSecondary)
                    Text(fmtMs(l), color = TextPrimary)
                }
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val m = ms / 60000; val s = (ms / 1000) % 60; val cs = (ms % 1000) / 10
    return "%02d:%02d.%02d".format(m, s, cs)
}

// ───────────────────────────── Dice & Coin ────────────────────────────────
@Composable
fun DiceCoinScreen(onMenu: () -> Unit = {}) {
    var dice by remember { mutableIntStateOf(1) }
    var coin by remember { mutableStateOf("Heads") }
    val spin by animateFloatAsState(dice.toFloat() * 60f, label = "dice")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Dice & Coin", "Settle it fair", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(120.dp).rotate(spin).clip(RoundedCornerShape(24.dp))
                .background(NeonBrush), contentAlignment = Alignment.Center) {
                Text("$dice", style = MaterialTheme.typography.displayLarge, color = BgDark, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { dice = Random.nextInt(1, 7) },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Casino, null); Spacer(Modifier.width(6.dp)); Text("Roll dice")
            }
            Divider(color = BorderDark)
            Box(Modifier.size(110.dp).clip(CircleShape).background(if (coin == "Heads") Cyan else Pink),
                contentAlignment = Alignment.Center) {
                Text(coin, color = BgDark, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { coin = if (Random.nextBoolean()) "Heads" else "Tails" },
                shape = RoundedCornerShape(16.dp)) { Text("Flip coin") }
        }
    }
}

// ─────────────────────────── Random Picker ────────────────────────────────
@Composable
fun RandomPickerScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Random Picker", "Let fate decide", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Options, one per line", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            Button(onClick = {
                val opts = input.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                picked = opts.randomOrNull()
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Pick one")
            }
            picked?.let {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardDark).padding(28.dp),
                    contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.headlineSmall.copy(brush = NeonBrush),
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
