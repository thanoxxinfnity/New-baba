package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
            !s.unlocked -> UnlockPane(s.busy, s.error) { vm.unlock(it) }
            else -> UnlockedPane(vm, s, onAdd = { showAdd = true })
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onSave = { t, sec, n -> vm.add(t, sec, n); showAdd = false })
}

@Composable
private fun SetupPane(busy: Boolean, error: String?, onCreate: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.Shield, null, tint = Cyan, modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally))
        Text("Create a master password", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text("This one password unlocks your vault. It's never stored — remember it, " +
            "because it can't be recovered.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        pwField(pw, "Master password") { pw = it }
        pwField(pw2, "Confirm password") { pw2 = it }
        error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { if (pw == pw2) onCreate(pw) },
            enabled = !busy && pw.isNotBlank() && pw == pw2,
            colors = ButtonDefaults.buttonColors(containerColor = Purple40), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
            else Text("Create vault", fontWeight = FontWeight.Bold)
        }
        if (pw.isNotBlank() && pw2.isNotBlank() && pw != pw2)
            Text("Passwords don't match", color = Pink, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun UnlockPane(busy: Boolean, error: String?, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, tint = Cyan, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Enter master password", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        pwField(pw, "Master password") { pw = it }
        error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onUnlock(pw) }, enabled = !busy && pw.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Purple40), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
            else { Icon(Icons.Default.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("Unlock", fontWeight = FontWeight.Bold) }
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

@Composable
private fun pwField(value: String, label: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label, color = TextSecondary) },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        })
}
