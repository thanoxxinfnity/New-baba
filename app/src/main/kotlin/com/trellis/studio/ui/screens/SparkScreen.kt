package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.SparkViewModel
import java.io.File

private val EXAMPLES = listOf(
    "a tip calculator with a split-between-people option",
    "a pomodoro timer with a circular progress ring",
    "a dice game where two players race to 50",
    "a colorful to-do list with checkboxes and progress",
    "a playable piano with 8 keys and sound",
    "a BMI calculator with a health gauge",
    "a memory match card game",
    "a neon countdown to New Year",
    "a random quote generator with share",
    "a simple drawing pad with color picker",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SparkScreen(vm: SparkViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var idea by remember { mutableStateOf("") }

    // Running app view (WebView) takes over the screen.
    if (s.html != null) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Surface(color = SurfDark) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.closeApp() }) {
                        Icon(Icons.Default.Close, "Close", tint = TextPrimary)
                    }
                    Text("Running", color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        s.currentPath?.let { FileExport.share(context, File(it), "text/html") }
                    }) { Icon(Icons.Default.IosShare, "Share", tint = Cyan) }
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.loadDataWithBaseURL(null, s.html!!, "text/html", "utf-8", null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Spark", style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.Bold)
                    Text("Describe an app — get a real one you can use & share",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Icon(Icons.Default.AutoAwesome, null, tint = Pink, modifier = Modifier.size(22.dp))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = idea, onValueChange = { idea = it },
                    label = { Text("What app do you want?", color = TextSecondary) },
                    placeholder = { Text("e.g. a workout timer with rounds and rest", color = TextDisabled) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedContainerColor = CardDark, unfocusedContainerColor = CardDark),
                    maxLines = 4,
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXAMPLES.take(6).forEach { ex ->
                        AssistChip(onClick = { idea = ex }, label = { Text(ex.take(22) + "…") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = CardHigh, labelColor = TextSecondary))
                    }
                }
            }
            item {
                Button(
                    onClick = { vm.build(idea) },
                    enabled = !s.busy && idea.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (s.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Building…", color = TextPrimary)
                    } else {
                        Icon(Icons.Default.Bolt, null, tint = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Build app", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                s.status?.let { Text(it, color = Cyan, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)) }
                s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)) }
            }

            if (apps.isNotEmpty()) {
                item {
                    Text("Your apps", style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp))
                }
                items(apps, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark)
                        .clickable { vm.openApp(a) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Purple40.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Widgets, null, tint = Pink, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(a.title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.openApp(a) }) {
                            Icon(Icons.Default.PlayArrow, "Open", tint = Cyan, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { vm.deleteApp(a) }) {
                            Icon(Icons.Default.DeleteOutline, "Delete", tint = TextDisabled, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
