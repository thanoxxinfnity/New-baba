package com.trellis.studio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import java.util.Calendar
import kotlin.random.Random

// ────────────────────────────── Calculator ────────────────────────────────
@Composable
fun CalculatorScreen(onMenu: () -> Unit = {}) {
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }

    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "0" }
            "⌫" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
            "=" -> result = runCatching { fmt(evalExpr(expr)) }.getOrDefault("Error")
            else -> expr += k
        }
    }
    val rows = listOf(
        listOf("C", "(", ")", "⌫"),
        listOf("7", "8", "9", "/"),
        listOf("4", "5", "6", "*"),
        listOf("1", "2", "3", "-"),
        listOf("0", ".", "=", "+"),
    )
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Calculator", null, onMenu)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(20.dp)) {
                Text(expr.ifBlank { "0" }, color = TextSecondary, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                Text(result, color = Cyan, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.displaySmall)
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        val op = k in listOf("/", "*", "-", "+", "=")
                        val ctrl = k in listOf("C", "⌫", "(", ")")
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                            .background(if (k == "=") Purple40 else if (op || ctrl) CardHigh else CardDark)
                            .clickable { press(k) }, contentAlignment = Alignment.Center) {
                            Text(k, color = if (k == "=") Color.White else if (op) Cyan else TextPrimary,
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')

/** Tiny recursive-descent evaluator: + - * / and parentheses. */
private fun evalExpr(s: String): Double {
    val t = s.replace(" ", "")
    var pos = 0
    fun peek() = if (pos < t.length) t[pos] else ' '
    fun number(): Double {
        val start = pos
        while (pos < t.length && (t[pos].isDigit() || t[pos] == '.')) pos++
        return t.substring(start, pos).toDouble()
    }
    lateinit var expr: () -> Double
    fun factor(): Double {
        if (peek() == '(') { pos++; val v = expr(); if (peek() == ')') pos++; return v }
        if (peek() == '-') { pos++; return -factor() }
        return number()
    }
    fun term(): Double {
        var v = factor()
        while (peek() == '*' || peek() == '/') { val op = t[pos]; pos++; val r = factor(); v = if (op == '*') v * r else v / r }
        return v
    }
    expr = {
        var v = term()
        while (peek() == '+' || peek() == '-') { val op = t[pos]; pos++; val r = term(); v = if (op == '+') v + r else v - r }
        v
    }
    return expr()
}

// ────────────────────────────── Text Tools ────────────────────────────────
@Composable
fun TextToolsScreen(onMenu: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    val chars = text.length
    val lines = if (text.isEmpty()) 0 else text.lines().size

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Text Tools", "Count & transform text", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Your text", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(160.dp), shape = RoundedCornerShape(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Words", words, Modifier.weight(1f)); Stat("Chars", chars, Modifier.weight(1f)); Stat("Lines", lines, Modifier.weight(1f))
            }
            val ops = listOf(
                "UPPER" to { text.uppercase() }, "lower" to { text.lowercase() },
                "Title" to { text.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } },
                "Reverse" to { text.reversed() },
                "No spaces" to { text.replace(Regex("\\s+"), "") },
                "Clear" to { "" },
            )
            ops.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, op) ->
                        FilledTonalButton(onClick = { text = op() }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardHigh),
                            shape = RoundedCornerShape(12.dp)) { Text(label, color = TextPrimary) }
                    }
                }
            }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(text)) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(CardDark).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

// ────────────────────────────── Age Calculator ────────────────────────────
@Composable
fun AgeCalculatorScreen(onMenu: () -> Unit = {}) {
    var d by remember { mutableStateOf("") }
    var m by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Age Calculator", "Exactly how old are you?", onMenu)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                num(d, "DD", Modifier.weight(1f)) { d = it }
                num(m, "MM", Modifier.weight(1f)) { m = it }
                num(y, "YYYY", Modifier.weight(1.4f)) { y = it }
            }
            Button(onClick = {
                val dd = d.toIntOrNull(); val mm = m.toIntOrNull(); val yy = y.toIntOrNull()
                out = if (dd != null && mm != null && yy != null) {
                    val birth = Calendar.getInstance().apply { set(yy, mm - 1, dd, 0, 0, 0) }
                    val now = Calendar.getInstance()
                    if (birth.after(now)) "That date is in the future 🙂"
                    else {
                        var years = now.get(Calendar.YEAR) - yy
                        var months = now.get(Calendar.MONTH) - (mm - 1)
                        var days = now.get(Calendar.DAY_OF_MONTH) - dd
                        if (days < 0) { months--; days += 30 }
                        if (months < 0) { years--; months += 12 }
                        val totalDays = (now.timeInMillis - birth.timeInMillis) / 86_400_000L
                        "$years years, $months months, $days days\n≈ $totalDays days old"
                    }
                } else "Enter a valid date."
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Cake, null); Spacer(Modifier.width(8.dp)); Text("Calculate age")
            }
            out?.let {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(20.dp),
                    contentAlignment = Alignment.Center) {
                    Text(it, color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun num(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.all(Char::isDigit)) onChange(it) },
        label = { Text(hint, color = TextSecondary) }, singleLine = true, modifier = modifier,
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

// ────────────────────────────── Magic 8-Ball ──────────────────────────────
@Composable
fun Magic8BallScreen(onMenu: () -> Unit = {}) {
    var answer by remember { mutableStateOf("Ask a question…") }
    var spin by remember { mutableStateOf(0f) }
    val rot by animateFloatAsState(spin, label = "8ball")
    val answers = listOf("Yes, definitely", "Absolutely", "It is certain", "Most likely", "Ask again later",
        "Cannot predict now", "Don't count on it", "My reply is no", "Very doubtful", "Signs point to yes",
        "Without a doubt", "Better not tell you now", "Outlook good", "Concentrate and ask again")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Magic 8-Ball", "Shake for wisdom", onMenu)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(240.dp).rotate(rot).clip(CircleShape).background(Color.Black),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(140.dp).clip(CircleShape).background(Purple40.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center) {
                    Text(answer, color = Cyan, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            Button(onClick = { answer = answers.random(); spin += 360f },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Casino, null); Spacer(Modifier.width(8.dp)); Text("Ask the 8-Ball")
            }
        }
    }
}
