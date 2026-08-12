package com.trellis.studio.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.GameBooster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/** Registers a sensor listener for the composable's lifetime. */
@Composable
private fun sensorValues(type: Int): FloatArray? {
    val context = LocalContext.current
    var values by remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(type)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return values
}

// ───────────────────────────────── Compass ────────────────────────────────
@Composable
fun CompassScreen(onMenu: () -> Unit = {}) {
    val rot = sensorValues(Sensor.TYPE_ROTATION_VECTOR)
    var azimuth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rot) {
        rot?.let {
            val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, it)
            val o = FloatArray(3); SensorManager.getOrientation(r, o)
            azimuth = ((Math.toDegrees(o[0].toDouble()).toFloat()) + 360) % 360
        }
    }
    val anim by animateFloatAsState(-azimuth, label = "compass")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Compass", "Find your bearing", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().rotate(anim)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(color = Color(0xFF2B2B3D), radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(6f))
                    drawLine(Color(0xFFF472B6), Offset(c.x, 30f), c, strokeWidth = 10f) // N (pink)
                    drawLine(Color(0xFF38BDF8), c, Offset(c.x, size.height - 30f), strokeWidth = 6f)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${azimuth.toInt()}°", style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(direction(azimuth), color = Cyan, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Pink needle points North. Lay the phone flat.", color = TextDisabled,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun direction(a: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((a + 22.5f) / 45f).toInt() % 8]
}

// ─────────────────────────────── Bubble Level ─────────────────────────────
@Composable
fun LevelScreen(onMenu: () -> Unit = {}) {
    val acc = sensorValues(Sensor.TYPE_ACCELEROMETER)
    val x = acc?.getOrNull(0) ?: 0f
    val y = acc?.getOrNull(1) ?: 0f
    val ax by animateFloatAsState(-x, label = "lx")
    val ay by animateFloatAsState(y, label = "ly")
    val level = abs(x) < 0.3f && abs(y) < 0.3f

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Bubble Level", "Is it straight?", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(280.dp).clip(CircleShape).background(CardDark),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(90.dp).clip(CircleShape)
                    .background((if (level) Teal else Cyan).copy(alpha = 0.15f)))
                Box(Modifier.offset(x = (ax * 26).dp, y = (ay * 26).dp).size(70.dp).clip(CircleShape)
                    .background(if (level) Teal else Cyan))
            }
            Spacer(Modifier.height(20.dp))
            Text(if (level) "Level ✓" else "Tilted", color = if (level) Teal else TextSecondary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ─────────────────────────────── Sound Meter ──────────────────────────────
@Composable
fun SoundMeterScreen(onMenu: () -> Unit = {}) {
    var db by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        var rec: AudioRecord? = null
        var running = true
        val thread = Thread {
            try {
                val min = AudioRecord.getMinBufferSize(44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(2048))
                val buf = ShortArray(1024)
                rec?.startRecording()
                while (running) {
                    val n = rec?.read(buf, 0, buf.size) ?: 0
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                        val rms = sqrt(sum / n)
                        val d = (20 * Math.log10(rms.coerceAtLeast(1.0)) + 10).toFloat().coerceIn(0f, 120f)
                        db = d
                    }
                    Thread.sleep(60)
                }
            } catch (_: Exception) {}
        }
        thread.start()
        onDispose { running = false; runCatching { rec?.stop(); rec?.release() } }
    }
    val anim by animateFloatAsState((db / 120f).coerceIn(0f, 1f), label = "db")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Sound Meter", "How loud is it?", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("${db.toInt()}", style = MaterialTheme.typography.displayLarge.copy(brush = NeonBrush),
                fontWeight = FontWeight.Bold)
            Text("dB (approx)", color = TextSecondary)
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(progress = { anim },
                modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)),
                color = if (db > 85) Pink else Teal, trackColor = CardHigh)
            Spacer(Modifier.height(8.dp))
            Text(when { db > 85 -> "Loud — protect your ears"; db > 60 -> "Conversation level"; else -> "Quiet" },
                color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────────── Flashlight ───────────────────────────────
@Composable
fun FlashlightScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(false) }
    val cm = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val camId = remember { runCatching { cm.cameraIdList.firstOrNull { id ->
        cm.getCameraCharacteristics(id)
            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } }.getOrNull() }

    LaunchedEffect(on) { camId?.let { runCatching { cm.setTorchMode(it, on) } } }
    DisposableEffect(Unit) { onDispose { camId?.let { runCatching { cm.setTorchMode(it, false) } } } }

    Column(Modifier.fillMaxSize().background(if (on) Color(0xFF10131A) else BgDark)) {
        ToolHeader("Flashlight", "Tap to light up", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(180.dp).clip(CircleShape)
                .background(if (on) Cyan else CardDark)
                .clickable { on = !on }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FlashlightOn, null, tint = if (on) BgDark else TextSecondary,
                    modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(if (on) "ON" else "OFF", color = if (on) Cyan else TextSecondary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (camId == null) Text("No flash on this device", color = Pink,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────────── Shake to Boost ───────────────────────────
@Composable
fun ShakeBoostScreen(onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val acc = sensorValues(Sensor.TYPE_ACCELEROMETER)
    var lastBoost by remember { mutableLongStateOf(0L) }
    var freed by remember { mutableStateOf<Long?>(null) }
    var shakes by remember { mutableIntStateOf(0) }
    var boosting by remember { mutableStateOf(false) }

    // Detect the shake here, but run the boost on the screen's own scope so a
    // new accelerometer reading (which happens many times a second and restarts
    // this effect) can't cancel a boost half-way through.
    LaunchedEffect(acc) {
        val a = acc ?: return@LaunchedEffect
        val g = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) / SensorManager.GRAVITY_EARTH
        if (g > 2.2f && !boosting && System.currentTimeMillis() - lastBoost > 2500) {
            lastBoost = System.currentTimeMillis(); shakes++; boosting = true
            scope.launch {
                val r = withContext(Dispatchers.Default) { GameBooster.boost(context) }
                freed = r.freedMb; boosting = false
            }
        }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Shake to Boost", "Shake the phone to free RAM", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Vibration, null, tint = Cyan, modifier = Modifier.size(90.dp))
            Spacer(Modifier.height(20.dp))
            Text("Shake!", style = MaterialTheme.typography.headlineMedium.copy(brush = NeonBrush),
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            freed?.let { Text("Boosted — freed ${it} MB (shake #$shakes)", color = Teal) }
            Text("A firm shake runs a RAM boost. Great before launching a game.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}
