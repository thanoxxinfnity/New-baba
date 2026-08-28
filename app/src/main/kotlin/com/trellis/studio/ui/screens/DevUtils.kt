package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Reusable in→out layout: an input box, action buttons, a monospace result. */
@Composable
private fun DevScaffold(
    title: String, subtitle: String?, onMenu: () -> Unit,
    input: String, onInput: (String) -> Unit, inputLabel: String,
    actions: List<Pair<String, () -> String>>, output: String, onOutput: (String) -> Unit,
    numeric: Boolean = false, extra: (@Composable () -> Unit)? = null,
) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader(title, subtitle, onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = input, onValueChange = onInput,
                label = { Text(inputLabel, color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(130.dp), shape = RoundedCornerShape(14.dp),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default)
            extra?.invoke()
            actions.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, op) ->
                        FilledTonalButton(onClick = { onOutput(runCatching { op() }.getOrElse { "Error: ${it.message}" }) },
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardHigh),
                            shape = RoundedCornerShape(12.dp)) { Text(label, color = TextPrimary, style = MaterialTheme.typography.labelMedium) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (output.isNotBlank()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark)
                    .clickable { clip.setText(AnnotatedString(output)) }.padding(16.dp)) {
                    Text(output, color = Cyan, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Tap result to copy", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─────────────────────────── JSON Formatter ───────────────────────────────
@Composable
fun JsonFormatterScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }
    DevScaffold("JSON Formatter", "Pretty-print, minify, validate", onMenu, input, { input = it }, "Paste JSON",
        listOf(
            "Pretty" to { prettyJson(input, 2) },
            "Minify" to { minifyJson(input) },
            "Validate" to { prettyJson(input, 2).let { "✓ Valid JSON" } },
        ), output, { output = it })
}

private fun prettyJson(s: String, indent: Int): String {
    val t = s.trim()
    return if (t.startsWith("[")) JSONArray(t).toString(indent) else JSONObject(t).toString(indent)
}
private fun minifyJson(s: String): String {
    val t = s.trim()
    return if (t.startsWith("[")) JSONArray(t).toString() else JSONObject(t).toString()
}

// ─────────────────────────── JWT Decoder ──────────────────────────────────
@Composable
fun JwtDecoderScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }
    DevScaffold("JWT Decoder", "Decode header & payload", onMenu, input, { input = it }, "Paste a JWT token",
        listOf("Decode" to { decodeJwt(input) }), output, { output = it })
}

private fun decodeJwt(token: String): String {
    val parts = token.trim().split(".")
    require(parts.size >= 2) { "Not a JWT (needs header.payload.signature)" }
    fun dec(p: String): String {
        val bytes = android.util.Base64.decode(p, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val json = String(bytes)
        return runCatching { JSONObject(json).toString(2) }.getOrDefault(json)
    }
    return "── HEADER ──\n${dec(parts[0])}\n\n── PAYLOAD ──\n${dec(parts[1])}"
}

// ─────────────────────────── UUID Generator ───────────────────────────────
@Composable
fun UuidScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("5") }; var output by remember { mutableStateOf(UUID.randomUUID().toString()) }
    DevScaffold("UUID Generator", "Random v4 IDs", onMenu, input, { input = it }, "How many? (for bulk)",
        listOf(
            "One" to { UUID.randomUUID().toString() },
            "Bulk" to { (1..(input.toIntOrNull() ?: 5).coerceIn(1, 100)).joinToString("\n") { UUID.randomUUID().toString() } },
        ), output, { output = it }, numeric = true)
}

// ─────────────────────────── Timestamp Converter ──────────────────────────
@Composable
fun TimestampScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf((System.currentTimeMillis() / 1000).toString()) }
    var output by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US) }
    DevScaffold("Timestamp", "Unix epoch ⇄ date", onMenu, input, { input = it }, "Unix seconds or millis",
        listOf(
            "→ Date" to {
                val n = input.trim().toLong()
                val ms = if (input.trim().length > 10) n else n * 1000
                fmt.format(Date(ms))
            },
            "Now (epoch)" to { (System.currentTimeMillis() / 1000).toString() },
        ), output, { output = it }, numeric = true)
}

// ─────────────────────────── Base / Number Converter ──────────────────────
@Composable
fun BaseConverterScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("255") }; var output by remember { mutableStateOf("") }
    var fromBase by remember { mutableIntStateOf(10) }
    val bases = listOf(2, 8, 10, 16)
    DevScaffold("Base Converter", "Bin · Oct · Dec · Hex", onMenu, input, { input = it }, "Number",
        listOf("Convert" to {
            val v = input.trim().toLong(fromBase)
            "BIN: ${v.toString(2)}\nOCT: ${v.toString(8)}\nDEC: $v\nHEX: ${v.toString(16).uppercase()}"
        }), output, { output = it },
        extra = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bases.forEach { b ->
                    FilterChip(selected = fromBase == b, onClick = { fromBase = b },
                        label = { Text("from base $b") })
                }
            }
        })
}

// ─────────────────────────── Case Converter ───────────────────────────────
@Composable
fun CaseConverterScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }
    DevScaffold("Case Converter", "camelCase, snake_case…", onMenu, input, { input = it }, "An identifier or phrase",
        listOf(
            "camelCase" to { toCamel(input, false) },
            "PascalCase" to { toCamel(input, true) },
            "snake_case" to { words(input).joinToString("_") { it.lowercase() } },
            "kebab-case" to { words(input).joinToString("-") { it.lowercase() } },
            "CONSTANT" to { words(input).joinToString("_") { it.uppercase() } },
            "Title Case" to { words(input).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } },
        ), output, { output = it })
}

private fun words(s: String): List<String> =
    s.trim().split(Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")).filter { it.isNotBlank() }
private fun toCamel(s: String, pascal: Boolean): String = words(s).mapIndexed { i, w ->
    if (i == 0 && !pascal) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() }
}.joinToString("")

// ─────────────────────────── Regex Tester ─────────────────────────────────
@Composable
fun RegexTesterScreen(onMenu: () -> Unit = {}) {
    var pattern by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val result = remember(pattern, text) {
        if (pattern.isBlank()) "" else runCatching {
            val re = Regex(pattern)
            val matches = re.findAll(text).map { it.value }.toList()
            if (matches.isEmpty()) "No matches" else "${matches.size} match(es):\n" + matches.joinToString("\n") { "• $it" }
        }.getOrElse { "Invalid regex: ${it.message}" }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Regex Tester", "Live match testing", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = pattern, onValueChange = { pattern = it },
                label = { Text("Pattern", color = TextSecondary) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Test text", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(14.dp))
            if (result.isNotBlank()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).padding(16.dp)) {
                    Text(result, color = if (result.startsWith("Invalid")) Pink else Cyan,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ─────────────────────────── Color Converter ──────────────────────────────
@Composable
fun ColorConverterScreen(onMenu: () -> Unit = {}) {
    var hex by remember { mutableStateOf("#38BDF8") }
    val parsed = remember(hex) { runCatching { android.graphics.Color.parseColor(hex.trim()) }.getOrNull() }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Color Converter", "HEX ⇄ RGB ⇄ HSL", onMenu)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = hex, onValueChange = { hex = it }, singleLine = true,
                label = { Text("Hex color (#RRGGBB)", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            if (parsed != null) {
                val r = android.graphics.Color.red(parsed); val g = android.graphics.Color.green(parsed); val b = android.graphics.Color.blue(parsed)
                Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(16.dp)).background(Color(parsed)))
                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(parsed, hsv)
                InfoLine("RGB", "rgb($r, $g, $b)")
                InfoLine("HSL", "hsl(${hsv[0].toInt()}, ${(hsv[1] * 100).toInt()}%, ${(hsv[2] * 100).toInt()}%)")
                InfoLine("HEX", String.format(Locale.US, "#%06X", 0xFFFFFF and parsed))
            } else Text("Enter a valid hex like #38BDF8", color = Pink, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val clip = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardDark)
        .clickable { clip.setText(AnnotatedString(value)) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, modifier = Modifier.width(56.dp))
        Text(value, color = Cyan, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ContentCopy, null, tint = TextDisabled, modifier = Modifier.size(16.dp))
    }
}

// ─────────────────────────── Diff Checker ─────────────────────────────────
@Composable
fun DiffScreen(onMenu: () -> Unit = {}) {
    var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    val diff = remember(a, b) {
        val la = a.lines(); val lb = b.lines()
        val setA = la.toSet(); val setB = lb.toSet()
        buildString {
            lb.filter { it !in setA }.forEach { appendLine("＋ $it") }
            la.filter { it !in setB }.forEach { appendLine("－ $it") }
        }.ifBlank { "No differences" }
    }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Diff Checker", "Compare two texts", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = a, onValueChange = { a = it }, label = { Text("Original", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(110.dp), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(value = b, onValueChange = { b = it }, label = { Text("Changed", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(110.dp), shape = RoundedCornerShape(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).padding(16.dp)) {
                Text(diff, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary)
            }
            Text("＋ added lines · － removed lines", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────── Lorem Ipsum ──────────────────────────────────
@Composable
fun LoremScreen(onMenu: () -> Unit = {}) {
    var input by remember { mutableStateOf("3") }; var output by remember { mutableStateOf("") }
    DevScaffold("Lorem Ipsum", "Placeholder text", onMenu, input, { input = it }, "How many paragraphs?",
        listOf("Generate" to {
            val n = (input.toIntOrNull() ?: 3).coerceIn(1, 20)
            (1..n).joinToString("\n\n") { LOREM }
        }), output, { output = it }, numeric = true)
}

private const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
    "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco " +
    "laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit."
