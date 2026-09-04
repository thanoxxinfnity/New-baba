package com.trellis.studio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.ZipConvertViewModel

/** Drop in a model archive, get a .glb the viewer and Rig Studio can open. */
@Composable
fun ZipConvertScreen(
    vm: ZipConvertViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { vm.convert(it) }
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri: Uri? -> uri?.let { vm.saveTo(it) } }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Zip → Model", "MMD (.pmx) and glTF archives to .glb", onMenu)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(140.dp)
                    .clip(RoundedCornerShape(16.dp)).background(CardDark)
                    .border(1.dp, if (s.sourceName != null) Teal.copy(alpha = 0.5f) else BorderDark,
                        RoundedCornerShape(16.dp))
                    .clickable(enabled = !s.busy) { picker.launch("*/*") },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.busy) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Cyan, strokeWidth = 3.dp)
                        Text(s.status ?: "Working…", color = Cyan)
                    } else {
                        Icon(Icons.Default.FolderZip, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                        Text(s.sourceName ?: "Tap to pick a .zip", color = TextSecondary, maxLines = 1)
                        Text("MMD .pmx pack · glTF archive · .glb", color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            s.contents?.let {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardHigh).padding(12.dp)) {
                    Text("Inside the archive", color = Cyan, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
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

            s.resultPath?.let { path ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.resultName ?: "Converted", color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            s.summary?.let {
                                Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Button(
                        onClick = { saver.launch(vm.suggestedFileName()) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Download, null, tint = BgDark, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save .glb to my device", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onOpenModel(path, s.resultName ?: "Model") },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.RotateRight, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("View 360°")
                    }
                    s.savedNote?.let {
                        Text(it, color = Teal, style = MaterialTheme.typography.labelSmall)
                    }
                    if (s.notes.isNotEmpty()) {
                        Text(s.notes.joinToString("\n") { "• $it" }, color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Also in Gallery, and ready for Rig Studio.",
                        color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                "MMD packs carry geometry, materials, textures and the full bone skeleton, " +
                    "and all of that comes across. What can't: toon and sphere maps, face " +
                    "morphs and the physics rig — glTF has no equivalent for them, so hair " +
                    "and skirt won't swing on their own.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}
