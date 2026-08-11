package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import kotlin.random.Random

// ─────────────────────────── Password Generator ───────────────────────────
@Composable
fun PasswordGenScreen(onMenu: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var length by remember { mutableFloatStateOf(16f) }
    var symbols by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var pwd by remember { mutableStateOf("") }

    fun gen() {
        var pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (digits) pool += "0123456789"
        if (symbols) pool += "!@#\$%^&*-_=+?"
        pwd = (1..length.toInt()).map { pool[Random.nextInt(pool.length)] }.joinToString("")
    }
    LaunchedEffect(Unit) { gen() }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Password Gen", "Strong, random, yours", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(20.dp)) {
                Text(pwd, color = Cyan, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("Length: ${length.toInt()}", color = TextSecondary)
            Slider(value = length, onValueChange = { length = it }, valueRange = 6f..40f,
                colors = SliderDefaults.colors(thumbColor = Purple60, activeTrackColor = Purple60))
            ToggleRow("Include digits", digits) { digits = it }
            ToggleRow("Include symbols", symbols) { symbols = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { gen() }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Generate")
                }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(pwd)) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(alpha = 0.35f)))
    }
}

// ──────────────────────────── Unit Converter ──────────────────────────────
@Composable
fun UnitConverterScreen(onMenu: () -> Unit = {}) {
    val categories = listOf("Length", "Weight", "Temp")
    var cat by remember { mutableStateOf("Length") }
    var input by remember { mutableStateOf("1") }
    val value = input.toDoubleOrNull() ?: 0.0

    val results: List<Pair<String, String>> = when (cat) {
        "Length" -> listOf(
            "meters" to "%.3f".format(value),
            "feet" to "%.3f".format(value * 3.28084),
            "inches" to "%.2f".format(value * 39.3701),
            "km" to "%.4f".format(value / 1000),
            "miles" to "%.4f".format(value / 1609.34),
        )
        "Weight" -> listOf(
            "kg" to "%.3f".format(value),
            "pounds" to "%.3f".format(value * 2.20462),
            "grams" to "%.0f".format(value * 1000),
            "ounces" to "%.2f".format(value * 35.274),
        )
        else -> listOf(
            "°C" to "%.1f".format(value),
            "°F" to "%.1f".format(value * 9 / 5 + 32),
            "K" to "%.1f".format(value + 273.15),
        )
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Unit Converter", "Length · Weight · Temp", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach {
                    FilterChip(selected = cat == it, onClick = { cat = it }, label = { Text(it) })
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Value (${if (cat == "Temp") "°C" else results.first().first})", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
            results.drop(if (cat == "Temp") 1 else 1).forEach { (unit, v) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardDark).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(unit, color = TextSecondary)
                    Text(v, color = Cyan, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ───────────────────────── Fancy Text (font styles) ───────────────────────
@Composable
fun FancyTextScreen(onMenu: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var input by remember { mutableStateOf("VOID") }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Fancy Text", "Cool fonts for your bio & name", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Your text", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            fancyStyles(input).forEach { (name, styled) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark)
                    .clickable { clip.setText(AnnotatedString(styled)) }.padding(16.dp)) {
                    Text(name, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                    Text(styled, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text("Tap a style to copy it.", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun fancyStyles(s: String): List<Pair<String, String>> = listOf(
    "Bold" to mapChars(s, 0x1D400, 0x1D41A),
    "Italic" to mapChars(s, 0x1D434, 0x1D44E),
    "Script" to mapChars(s, 0x1D49C, 0x1D4B6),
    "Monospace" to mapChars(s, 0x1D670, 0x1D68A),
    "Double-struck" to mapChars(s, 0x1D538, 0x1D552),
    "Bubbles" to s.map { c -> if (c in 'a'..'z') (0x24D0 + (c - 'a')).toChar() else if (c in 'A'..'Z') (0x24B6 + (c - 'A')).toChar() else c }.joinToString(""),
)

private fun mapChars(s: String, upperBase: Int, lowerBase: Int): String = buildString {
    for (c in s) {
        when (c) {
            in 'A'..'Z' -> appendCodePoint(upperBase + (c - 'A'))
            in 'a'..'z' -> appendCodePoint(lowerBase + (c - 'a'))
            else -> append(c)
        }
    }
}

// ─────────────────────────── Color Palette ────────────────────────────────
@Composable
fun ColorPaletteScreen(onMenu: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var seed by remember { mutableIntStateOf(Random.nextInt(360)) }
    val palette = remember(seed) { paletteOf(seed) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Color Palette", "Fresh schemes for your designs", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            palette.forEach { (color, hex) ->
                Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(color)
                    .clickable { clip.setText(AnnotatedString(hex)) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(hex, color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ContentCopy, null, tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp))
                }
            }
            Button(onClick = { seed = Random.nextInt(360) },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("New palette")
            }
            Text("Tap any color to copy its hex.", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun paletteOf(baseHue: Int): List<Pair<Color, String>> {
    return (0..4).map { i ->
        val h = (baseHue + i * 30) % 360
        val s = 0.6f
        val l = 0.35f + i * 0.1f
        val c = hsl(h.toFloat(), s, l)
        c to "#%06X".format(0xFFFFFF and c.toArgb())
    }
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
