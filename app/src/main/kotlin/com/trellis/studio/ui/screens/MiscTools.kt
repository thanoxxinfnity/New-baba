package com.trellis.studio.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.random.Random

// ─────────────────────────────── TTS Reader ───────────────────────────────
@Composable
fun TtsReaderScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Read Aloud", "Paste text, hear it spoken", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Text to read", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    tts.language = Locale.getDefault()
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "read")
                }, enabled = ready && text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Read")
                }
                OutlinedButton(onClick = { tts.stop() }, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            }
        }
    }
}

// ───────────────────────── Gradient Wallpaper Studio ──────────────────────
@Composable
fun GradientWallpaperScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var seed by remember { mutableIntStateOf(Random.nextInt()) }
    val colors = remember(seed) { randomGradient(seed) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = BgDark) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(BgDark)) {
            ToolHeader("Wallpaper Studio", "Make & set neon gradients", onMenu)
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(colors)))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { seed = Random.nextInt() },
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle")
                    }
                    Button(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bmp = gradientBitmap(colors, 1080, 1920)
                                    android.app.WallpaperManager.getInstance(context).setBitmap(bmp)
                                }.isSuccess
                            }
                            snackbar.showSnackbar(if (ok) "Wallpaper set 🖼️" else "Couldn't set wallpaper.")
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Wallpaper, null); Spacer(Modifier.width(8.dp)); Text("Set")
                    }
                }
            }
        }
    }
}

private fun randomGradient(seed: Int): List<Color> {
    val r = Random(seed)
    fun c() = Color(r.nextInt(60, 220), r.nextInt(60, 220), r.nextInt(120, 255))
    return listOf(c(), c(), c())
}

private fun gradientBitmap(colors: List<Color>, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val ints = colors.map { android.graphics.Color.rgb((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }.toIntArray()
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), ints, null, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return bmp
}

// ───────────────────────────── Charging Display ───────────────────────────
@Composable
fun ChargingDisplayScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    var pct by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    var temp by remember { mutableFloatStateOf(0f) }
    var voltage by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            pct = lvl * 100 / scale
            val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            voltage = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "OK"
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    val infinite = rememberInfiniteTransition(label = "charge")
    val glow by infinite.animateFloat(0.4f, 1f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Charging", "Bedside battery display", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(if (charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd, null,
                tint = if (charging) Teal else Cyan, modifier = Modifier.size(90.dp)
                    .alpha(if (charging) glow else 1f))
            Text("$pct%", style = MaterialTheme.typography.displayLarge.copy(brush = NeonBrush),
                fontWeight = FontWeight.Bold)
            Text(if (charging) "Charging" else "On battery", color = if (charging) Teal else TextSecondary,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Info("Temp", "${"%.1f".format(temp)}°C")
                Info("Voltage", "${voltage / 1000f}V")
                Info("Health", health)
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
    }
}

// ───────────────────────────────── Screen Test ────────────────────────────
@Composable
fun ScreenTestScreen(onMenu: () -> Unit = {}) {
    val palette = listOf(Color.White, Color.Red, Color.Green, Color.Blue, Color.Black, Color(0xFF808080))
    var i by remember { mutableIntStateOf(-1) }

    if (i >= 0) {
        Box(Modifier.fillMaxSize().background(palette[i % palette.size])
            .clickable { i = if (i + 1 >= palette.size) -1 else i + 1 })
        return
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Screen Test", "Check for dead pixels", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Smartphone, null, tint = Cyan, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(20.dp))
            Text("Tap start, then tap the screen to cycle through solid colors. Look for spots or " +
                "stuck pixels. Tap on the last color to exit.",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { i = 0 }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp)) { Text("Start test") }
        }
    }
}
