package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.ApiTesterViewModel

@Composable
fun ApiTesterScreen(vm: ApiTesterViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    var tab by remember { mutableIntStateOf(0) }   // 0 body, 1 headers

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("API Tester", "A REST client in your pocket", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Method + URL
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    AssistChip(onClick = { menu = true }, label = { Text(s.method, fontWeight = FontWeight.Bold) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = methodColor(s.method).copy(alpha = 0.25f), labelColor = methodColor(s.method)))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        vm.methods.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { vm.setMethod(m); menu = false }) }
                    }
                }
                OutlinedTextField(value = s.url, onValueChange = vm::setUrl, singleLine = true,
                    placeholder = { Text("https://api.example.com/…", color = TextDisabled) },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
            }

            Button(onClick = { vm.send() }, enabled = !s.sending && s.url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (s.sending) CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Send", fontWeight = FontWeight.Bold) }
            }

            // Body / Headers tabs
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Cyan) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Body") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Headers (${s.headers.count { it.key.isNotBlank() }})") })
            }
            if (tab == 0) {
                OutlinedTextField(value = s.body, onValueChange = vm::setBody,
                    placeholder = { Text("Request body (JSON)…", color = TextDisabled) },
                    modifier = Modifier.fillMaxWidth().height(120.dp), shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            } else {
                s.headers.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(h.key, { vm.updateHeader(h.id, it, h.value) }, placeholder = { Text("Key") },
                            singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp))
                        OutlinedTextField(h.value, { vm.updateHeader(h.id, h.key, it) }, placeholder = { Text("Value") },
                            singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp))
                        IconButton(onClick = { vm.removeHeader(h.id) }) { Icon(Icons.Default.Close, "Remove", tint = TextDisabled, modifier = Modifier.size(18.dp)) }
                    }
                }
                TextButton(onClick = { vm.addHeader() }) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add header") }
            }

            // Response
            s.error?.let {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pink.copy(alpha = 0.15f)).padding(14.dp)) {
                    Text(it, color = Pink, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (s.status != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val col = if (s.status!! < 300) Teal else if (s.status!! < 400) Cyan else Pink
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("${s.status} ${s.statusText}", color = col, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    Text("${s.timeMs}ms · ${s.sizeBytes}B", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { clip.setText(AnnotatedString(s.respBody)) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.explain() }, enabled = !s.aiBusy) { Icon(Icons.Default.AutoAwesome, "Explain", tint = Pink, modifier = Modifier.size(18.dp)) }
                }
                if (s.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pink)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF101018)).padding(14.dp)) {
                    Text(s.respBody.ifBlank { "(empty response)" }, color = Cyan, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // History
            if (s.history.isNotEmpty()) {
                Text("Recent", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                s.history.forEach { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardDark)
                        .clickable { vm.loadHistory(h) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.method, color = methodColor(h.method), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
                        Text(h.url, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (s.aiOutput != null) {
        AlertDialog(onDismissRequest = { vm.closeAi() },
            confirmButton = { TextButton(onClick = { vm.closeAi() }) { Text("Close") } },
            title = { Text("Response explained", color = TextPrimary) },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(s.aiOutput ?: "", color = TextSecondary, style = MaterialTheme.typography.bodyMedium) } },
            containerColor = CardDark)
    }
}

private fun methodColor(m: String): Color = when (m) {
    "GET" -> Teal; "POST" -> Cyan; "PUT", "PATCH" -> Color(0xFFFFB020); "DELETE" -> Pink; else -> Purple60
}
