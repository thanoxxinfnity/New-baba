package com.trellis.studio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.trellis.studio.network.SketchfabClient
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.SketchfabViewModel

/**
 * Browse Sketchfab by name or link and pull downloadable models straight into
 * the app, packed as a .glb the viewer and Rig Studio can open.
 */
@Composable
fun SketchfabScreen(
    vm: SketchfabViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri: Uri? -> uri?.let { vm.saveTo(it) } }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Sketchfab", "Find a model by name or paste a link", onMenu)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                label = { Text("Name or Sketchfab link", color = TextSecondary) },
                placeholder = { Text("miku — or paste a model URL", color = TextDisabled) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan, unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                ),
                trailingIcon = {
                    if (s.query.isNotBlank()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, null, tint = TextDisabled)
                        }
                    }
                },
            )
            Button(
                onClick = { vm.go() },
                enabled = !s.busy && s.query.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (s.busy) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = BgDark, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(s.status ?: "Working…", color = BgDark)
                } else {
                    Icon(Icons.Default.Search, null, tint = BgDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find", color = BgDark, fontWeight = FontWeight.Bold)
                }
            }

            s.error?.let {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Red.copy(alpha = 0.15f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::clearError, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(15.dp))
                    }
                }
            }

            // Result of the last download.
            s.downloadedPath?.let { path ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.downloadedName ?: "Model ready", color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onOpenModel(path, s.downloadedName ?: "Model") },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.RotateRight, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("View 360°")
                        }
                        Button(
                            onClick = { saver.launch(vm.suggestedFileName()) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Download, null, tint = BgDark, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Save", color = BgDark)
                        }
                    }
                    s.savedNote?.let {
                        Text(it, color = Teal, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Also in Gallery, and ready to bone in Rig Studio.",
                        color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (s.searched && s.results.isEmpty() && s.error == null) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                Text("Nothing downloadable found for that.", color = TextSecondary)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(s.results, key = { it.uid }) { m ->
                ModelCard(
                    model = m,
                    downloading = s.downloadingUid == m.uid,
                    onDownload = { vm.download(m) },
                    onOpenPage = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.viewerUrl)))
                        }
                    },
                )
            }
            item {
                Text(
                    "Models here are the ones their authors published for download. " +
                        "Check the licence before you ship one in a game — most ask for " +
                        "credit, and some are non-commercial.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: SketchfabClient.Model,
    downloading: Boolean,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardDark).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(CardHigh),
            contentAlignment = Alignment.Center) {
            if (model.thumbnail != null) {
                AsyncImage(model = model.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.ViewInAr, null, tint = TextDisabled, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.name, color = TextPrimary, maxLines = 1,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (model.author.isNotBlank()) {
                Text("by ${model.author}", color = TextSecondary, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(model.license, color = Cyan, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            if (model.faceCount > 0) {
                Text("${model.faceCount} faces", color = TextDisabled,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (!model.downloadable) {
                Text("View only — not published for download", color = Amber,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onDownload, enabled = model.downloadable && !downloading) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(19.dp), color = Teal, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Download, "Download",
                        tint = if (model.downloadable) Teal else TextDisabled)
                }
            }
            IconButton(onClick = onOpenPage) {
                Icon(Icons.Default.OpenInNew, "Open on Sketchfab", tint = TextDisabled,
                    modifier = Modifier.size(17.dp))
            }
        }
    }
}
