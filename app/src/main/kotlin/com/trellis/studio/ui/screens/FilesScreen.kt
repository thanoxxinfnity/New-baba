package com.trellis.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.FilesViewModel

@Composable
fun FilesScreen(vm: FilesViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.pickFolder(it) }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Header
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (s.hasFolder && (s.openName != null || s.breadcrumb.contains("/"))) {
                    IconButton(onClick = { vm.up() }) { Icon(Icons.Default.ArrowBack, "Up", tint = TextPrimary) }
                } else MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text(s.openName ?: "Code Folder", style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.openName?.let { "editing" } ?: s.breadcrumb.ifBlank { "Work on files directly" },
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.hasFolder && s.openName == null)
                    IconButton(onClick = { picker.launch(null) }) { Icon(Icons.Default.FolderOpen, "Change folder", tint = Cyan) }
                if (s.openName != null && s.dirty)
                    IconButton(onClick = { vm.save() }) { Icon(Icons.Default.Save, "Save", tint = Teal) }
            }
        }
        s.status?.let { Text(it, color = Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        when {
            !s.hasFolder -> EmptyFolder { picker.launch(null) }
            s.openName != null -> FileEditor(s, vm)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.entries, key = { it.uri }) { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardDark)
                        .clickable { vm.openEntry(e) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
                            tint = if (e.isDir) Cyan else TextSecondary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (s.entries.isEmpty()) item {
                    Text("Empty folder", color = TextDisabled, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }

    // AI output sheet
    if (s.aiOutput != null) {
        AlertDialog(
            onDismissRequest = { vm.closeAiOutput() },
            confirmButton = {
                Row {
                    TextButton(onClick = { clip.setText(AnnotatedString(s.aiOutput ?: "")) }) { Text("Copy") }
                    TextButton(onClick = { vm.applyAiOutput() }) { Text("Apply to file", color = Teal) }
                    TextButton(onClick = { vm.closeAiOutput() }) { Text("Close") }
                }
            },
            title = { Text("AI result", color = TextPrimary) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(s.aiOutput ?: "", color = TextSecondary, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            containerColor = CardDark,
        )
    }
}

@Composable
private fun EmptyFolder(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CreateNewFolder, null, tint = TextDisabled, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text("Open a project folder", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text("Browse your files and run AI review, docs, tests & fixes right on them — no copy-paste.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onPick, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
        }
    }
}

@Composable
private fun FileEditor(s: FilesViewModel.State, vm: FilesViewModel) {
    Column(Modifier.fillMaxSize()) {
        // AI action bar
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Review", "Explain", "Docs", "Tests", "Fix").forEach { a ->
                AssistChip(onClick = { vm.ai(a) }, enabled = !s.aiBusy, label = { Text(a) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) })
            }
        }
        if (s.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
        OutlinedTextField(
            value = s.content, onValueChange = { vm.edit(it) },
            modifier = Modifier.fillMaxSize().padding(10.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BorderDark, unfocusedBorderColor = BorderDark,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedContainerColor = Color(0xFF101018), unfocusedContainerColor = Color(0xFF101018)),
        )
    }
}
