package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.VaultViewModel

@Composable
fun VaultScreen(vm: VaultViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Vault", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Your encrypted secrets locker", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                if (s.unlocked) IconButton(onClick = { vm.lock() }) { Icon(Icons.Default.Lock, "Lock", tint = Cyan) }
            }
        }

        when {
            !s.hasVault -> SetupPane(s.busy, s.error) { vm.createMaster(it) }
            !s.unlocked -> UnlockPane(s.busy, s.error, onUnlock = { vm.unlock(it) }, onReset = { showReset = true })
            else -> UnlockedPane(vm, s, onAdd = { showAdd = true })
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onSave = { t, sec, n -> vm.add(t, sec, n); showAdd = false })
    if (showReset) AlertDialog(
        onDismissRequest = { showReset = false },
        confirmButton = { TextButton(onClick = { vm.reset(); showReset = false }) { Text("Erase & reset", color = Pink) } },
        dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        title = { Text("Reset vault?", color = TextPrimary) },
        text = { Text("This erases the vault and everything saved in it, so you can set a new PIN. " +
            "This can't be undone.", color = TextSecondary) },
        containerColor = CardDark,
    )
}

@Composable
private fun SetupPane(busy: Boolean, error: String?, onCreate: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    LockPad(
        title = if (first == null) "Set a PIN" else "Confirm your PIN",
        subtitle = if (first == null) "This unlocks your vault. It's never stored — remember it."
        else "Enter the same PIN again",
        pin = pin, onPin = { pin = it; mismatch = false }, busy = busy,
        error = if (mismatch) "PINs don't match — try again" else error,
        onSubmit = {
            if (pin.length < 4) return@LockPad
            if (first == null) { first = pin; pin = "" }
            else if (pin == first) onCreate(pin)
            else { mismatch = true; pin = ""; first = null }
        },
    )
}

@Composable
private fun UnlockPane(busy: Boolean, error: String?, onUnlock: (String) -> Unit, onReset: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        LockPad(
            title = "Enter your PIN", subtitle = "Unlock your vault",
            pin = pin, onPin = { pin = it }, busy = busy, error = error,
            onSubmit = { if (pin.length >= 4) onUnlock(pin) },
        )
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
            Text("Forgot PIN? Reset vault", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A lock-screen-style PIN pad: dots + a numeric keypad. */
@Composable
private fun LockPad(
    title: String, subtitle: String, pin: String, onPin: (String) -> Unit,
    busy: Boolean, error: String?, onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, tint = Cyan, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        // dots
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(8) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Cyan else CardHigh))
            }
        }
        error?.let { Text(it, color = Pink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(28.dp))
        // keypad
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape)
                        .background(if (k == "✓") Purple40 else CardDark)
                        .clickable(enabled = !busy) {
                            when (k) {
                                "⌫" -> if (pin.isNotEmpty()) onPin(pin.dropLast(1))
                                "✓" -> onSubmit()
                                else -> if (pin.length < 8) onPin(pin + k)
                            }
                        }, contentAlignment = Alignment.Center) {
                        if (k == "✓" && busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(k, color = if (k == "✓") Color.White else TextPrimary,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockedPane(vm: VaultViewModel, s: VaultViewModel.State, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardHigh).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save your phone unlock code here so you never lose it. (No app can read the " +
                        "real device lock — Android hides it in hardware.)", color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (s.items.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Key, null, tint = TextDisabled, modifier = Modifier.size(44.dp))
                    Text("No secrets saved yet", color = TextSecondary)
                    Text("Tap + to add your phone PIN, passwords, notes.", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(s.items, key = { it.id }) { VaultRow(it) { vm.delete(it) } }
        }
        FloatingActionButton(onClick = onAdd, containerColor = Purple40,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Default.Add, "Add", tint = TextPrimary)
        }
    }
}

@Composable
private fun VaultRow(item: VaultViewModel.Item, onDelete: () -> Unit) {
    val clip = LocalClipboardManager.current
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VpnKey, null, tint = Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(item.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Reveal",
                    tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { clip.setText(AnnotatedString(item.secret)) }) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Delete", tint = Pink, modifier = Modifier.size(18.dp)) }
        }
        Text(if (reveal) item.secret else "•".repeat(item.secret.length.coerceIn(4, 12)),
            color = Cyan, style = MaterialTheme.typography.bodyLarge)
        if (item.note.isNotBlank()) Text(item.note, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(title, secret, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a secret", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title (e.g. Phone PIN)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret / code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        containerColor = CardDark,
    )
}

