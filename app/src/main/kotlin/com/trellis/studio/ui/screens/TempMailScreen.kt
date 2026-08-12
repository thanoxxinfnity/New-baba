package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.network.TempMailClient
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.TempMailViewModel

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TempMailScreen(vm: TempMailViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var showCustom by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var domainIdx by remember { mutableIntStateOf(0) }

    // Full-screen message reader with clickable links.
    if (s.openBody != null) {
        Column(Modifier.fillMaxSize().background(BgDark)) {
            Surface(color = SurfDark) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.closeMessage() }) {
                        Icon(Icons.Default.Close, "Back", tint = TextPrimary)
                    }
                    Text("Message", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        setBackgroundColor(android.graphics.Color.parseColor("#0B0B12"))
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                val u = req?.url ?: return false
                                if (u.scheme == "http" || u.scheme == "https") {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    return true
                                }
                                return false
                            }
                        }
                    }
                },
                update = { it.loadDataWithBaseURL(null, s.openBody!!, "text/html", "utf-8", null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Gradient header
        Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Purple40, Pink)))) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Temp Inbox", style = MaterialTheme.typography.titleLarge, color = Color.White,
                        fontWeight = FontWeight.Bold)
                    Text("Throwaway email — keep your real one private",
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                }
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            item {
                val addr = s.selected?.address
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(CardHigh, CardDark))).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(NeonBrush), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Email, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Your address", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                            Text(addr ?: "No inbox yet", color = if (addr != null) Cyan else TextSecondary,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (addr != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { clip.setText(AnnotatedString(addr)) },
                                label = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { s.selected?.let { vm.delete(it) } },
                                label = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = Pink, leadingIconContentColor = Pink))
                        }
                    }
                }
            }

            // Generate buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.generate() }, enabled = !s.creating,
                        colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                        if (s.creating) CircularProgressIndicator(Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                        else { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Random") }
                    }
                    OutlinedButton(onClick = { showCustom = !showCustom }, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Custom")
                    }
                }
                s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }

            // Custom address creator
            if (showCustom) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = customName, onValueChange = { customName = it },
                            label = { Text("Choose a name", color = TextSecondary) },
                            placeholder = { Text("e.g. tejas.cool", color = TextDisabled) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                        if (s.domains.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.domains.forEachIndexed { i, d ->
                                    FilterChip(selected = domainIdx == i, onClick = { domainIdx = i }, label = { Text("@$d") })
                                }
                            }
                        }
                        Button(onClick = {
                            vm.generate(customName.ifBlank { null }, s.domains.getOrNull(domainIdx))
                            showCustom = false; customName = ""
                        }, enabled = !s.creating, colors = ButtonDefaults.buttonColors(containerColor = Teal),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Check, null, tint = BgDark); Spacer(Modifier.width(6.dp))
                            Text("Create this address", color = BgDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Saved inboxes
            if (s.accounts.size > 1) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.accounts.forEach { a ->
                            FilterChip(selected = s.selected?.address == a.address, onClick = { vm.select(a) },
                                label = { Text(a.address.substringBefore('@'), maxLines = 1) })
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Inbox", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    if (s.loading) CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                }
            }

            if (s.inbox.isEmpty() && !s.loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MarkEmailUnread, null, tint = TextDisabled, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No mail yet", color = TextSecondary)
                        Text("Use this address somewhere, then tap Refresh 🔄",
                            color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                items(s.inbox, key = { it.id }) { m -> MailRow(m) { vm.open(m) } }
            }

            item {
                Text("Receive-only privacy tool (like Apple's Hide My Email). Sending isn't supported " +
                    "by free temp services, and big sites may reject temp domains.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MailRow(m: TempMailClient.Message, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top) {
        val letter = m.from.firstOrNull()?.uppercase() ?: "?"
        Box(Modifier.size(42.dp).clip(CircleShape).background(avatarColor(m.from)), contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(m.from, color = Cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.date, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
            }
            Text(m.subject, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (m.seen) FontWeight.Normal else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.intro, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!m.seen) Box(Modifier.padding(start = 6.dp, top = 4.dp).size(9.dp).clip(CircleShape).background(Pink))
    }
}

private val avatarPalette = listOf(
    Color(0xFF7C5CFF), Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFF2DD4BF), Color(0xFFFFB020))

private fun avatarColor(seed: String): Color =
    avatarPalette[(seed.sumOf { it.code } % avatarPalette.size + avatarPalette.size) % avatarPalette.size]
