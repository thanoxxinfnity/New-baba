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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.AutoRigger
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.RigStudioViewModel
import java.io.File

/**
 * Rigs a model the user brings in themselves: import a .glb, say how it is
 * posed, get a real skeleton back, then preview or download it.
 */
@Composable
fun RigStudioScreen(
    vm: RigStudioViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { vm.importModel(it) }
    }
    // A real save: the user chooses the folder, the file lands in their storage.
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri: Uri? -> uri?.let { vm.saveTo(it) } }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Rig Studio", "Add a real skeleton to your own 3D model", onMenu)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 1. Bring in a model ----------------------------------------
            Text("1 · Pick your model", style = MaterialTheme.typography.labelMedium, color = Cyan)
            Box(
                Modifier.fillMaxWidth().height(if (s.sourceName == null) 130.dp else 92.dp)
                    .clip(RoundedCornerShape(16.dp)).background(CardDark)
                    .border(1.dp, if (s.sourceName != null) Teal.copy(alpha = 0.5f) else BorderDark,
                        RoundedCornerShape(16.dp))
                    .clickable(enabled = !s.busy) { picker.launch("*/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (s.sourceName != null) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ViewInAr, null, tint = Teal, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.sourceName!!, color = TextPrimary, maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Tap to choose a different file", color = TextDisabled,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.UploadFile, null, tint = TextSecondary, modifier = Modifier.size(38.dp))
                        Text("Tap to pick a .glb file", color = TextSecondary)
                        Text("From your phone, Drive, anywhere", color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // What the mesh actually measured as.
            s.analysis?.let { info ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardHigh).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, null, tint = Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Measured from the mesh", color = Cyan,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(info.shapeSummary, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(info.poseSummary, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (s.sourceName != null) {
                // ---- 2. Skeleton shape --------------------------------------
                Text("2 · Skeleton", style = MaterialTheme.typography.labelMedium, color = Cyan)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoRigger.Frame.entries.forEach { f ->
                        FilterChip(
                            selected = s.frame == f,
                            onClick = { vm.setFrame(f) },
                            label = { Text(f.label, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple40, selectedLabelColor = TextPrimary,
                                containerColor = CardDark, labelColor = TextSecondary,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ---- 3. Pose (only meaningful for arms) ----------------------
                if (s.frame == AutoRigger.Frame.HUMANOID) {
                    Text("3 · How the arms are held", style = MaterialTheme.typography.labelMedium, color = Cyan)
                    AutoRigger.Pose.entries.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (s.pose == p) Purple40.copy(alpha = 0.30f) else CardDark)
                                .border(1.dp, if (s.pose == p) Purple60 else BorderDark, RoundedCornerShape(12.dp))
                                .clickable { vm.setPose(p) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (s.pose == p) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                null, tint = if (s.pose == p) Purple60 else TextDisabled,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(p.note, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // ---- Rig ----------------------------------------------------
                Button(
                    onClick = { vm.rig() },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, disabledContainerColor = CardHigh),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (s.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BgDark, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s.status ?: "Working…", color = BgDark)
                    } else {
                        Icon(Icons.Default.Accessibility, null, tint = BgDark, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add real bones", color = BgDark, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            s.error?.let {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Red.copy(alpha = 0.15f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::clearError, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(15.dp))
                    }
                }
            }

            // ---- Result ------------------------------------------------------
            s.result?.let { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardDark).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Rigged — ${r.bones.size} bones", color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium)
                            Text("${r.frame.label} · ${r.pose.label}", color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(r.bones.joinToString(", "), color = TextDisabled,
                        style = MaterialTheme.typography.labelSmall)

                    // Saving to the user's own storage is the primary action —
                    // this is the file they take into Blender or their engine.
                    Button(
                        onClick = { saver.launch(vm.suggestedFileName()) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Download, null, tint = BgDark, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save .glb to my device", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onOpenModel(r.file.absolutePath, "Rigged model") },
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.RotateRight, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp)); Text("View 360°")
                        }
                        OutlinedButton(
                            onClick = { FileExport.share(context, r.file, "model/gltf-binary") },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.IosShare, null, tint = Cyan, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp)); Text("Share", color = Cyan)
                        }
                    }
                    s.savedNote?.let { note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(note, color = Teal, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("${r.file.name} · ${FileExport.humanSize(r.file.length())}",
                        color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                "The skeleton is written into the .glb itself — joints plus skin weights — so " +
                    "Blender, Unity, Godot and three.js can all animate it. Pose matters: bones " +
                    "are laid along the arms, so telling it T-pose when the model is A-posed puts " +
                    "them in the wrong place.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}
