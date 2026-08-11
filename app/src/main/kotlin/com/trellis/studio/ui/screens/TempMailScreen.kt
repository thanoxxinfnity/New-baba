package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.TempMailViewModel

@Composable
fun TempMailScreen(vm: TempMailViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Temp Inbox", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Throwaway email — keep your real one private",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Cyan)
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            item {
                // Current address
                val addr = s.selected?.address
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(16.dp)) {
                    Text("Your address", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(addr ?: "No inbox yet — generate one", color = if (addr != null) Cyan else TextSecondary,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        if (addr != null) IconButton(onClick = { clip.setText(AnnotatedString(addr)) }) {
                            Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                        s.selected?.let { acc ->
                            IconButton(onClick = { vm.delete(acc) }) {
                                Icon(Icons.Default.DeleteOutline, "Delete inbox", tint = Pink, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            item {
                Button(onClick = { vm.generate() }, enabled = !s.creating,
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    if (s.creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Creating…", color = TextPrimary)
                    } else {
                        Icon(Icons.Default.Add, null, tint = TextPrimary); Spacer(Modifier.width(8.dp))
                        Text("Generate new inbox", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)) }
            }

            // Saved inboxes (switch between them)
            if (s.accounts.size > 1) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.accounts.forEach { a ->
                            FilterChip(selected = s.selected?.address == a.address,
                                onClick = { vm.select(a) },
                                label = { Text(a.address.substringBefore('@'), maxLines = 1) })
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Inbox", style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                        modifier = Modifier.weight(1f))
                    if (s.loading) CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                }
            }

            if (s.inbox.isEmpty() && !s.loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MarkEmailUnread, null, tint = TextDisabled, modifier = Modifier.size(44.dp))
                        Text("No mail yet", color = TextSecondary)
                        Text("Use this address to sign up somewhere, then tap Refresh.",
                            color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                items(s.inbox, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark)
                        .clickable { vm.open(m) }.padding(14.dp)) {
                        Row {
                            Text(m.from, color = Cyan, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f), maxLines = 1)
                            Text(m.date, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(m.subject, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(m.intro, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }

            item {
                Text("A privacy tool for throwaway signups — like Apple's Hide My Email. " +
                    "Mail arrives here; sending isn't supported.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    // Message reader
    if (s.openBody != null) {
        AlertDialog(
            onDismissRequest = { vm.closeMessage() },
            confirmButton = {
                val cm = LocalClipboardManager.current
                Row {
                    TextButton(onClick = { cm.setText(AnnotatedString(s.openBody ?: "")) }) { Text("Copy") }
                    TextButton(onClick = { vm.closeMessage() }) { Text("Close") }
                }
            },
            title = { Text("Message", color = TextPrimary) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(s.openBody ?: "", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            containerColor = CardDark,
        )
    }
}
