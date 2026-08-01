package com.trellis.studio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.ModelExporter
import com.trellis.studio.viewmodel.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    vm: GalleryViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
    onOpenImage: (path: String, name: String) -> Unit = { _, _ -> },
    onOpenVoice: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val allItems by vm.allGenerations.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val exported by vm.exported.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showFormats by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tabs = listOf("All", "Images", "3D Models")

    val filtered = when (selectedTab) {
        1 -> allItems.filter { it.type == "image" }
        2 -> allItems.filter { it.type == "3d" }
        else -> allItems
    }
    val selecting = selection.isNotEmpty()
    val selectedModelCount = vm.selectedModels(allItems).size

    // Back should drop the selection before it leaves the screen.
    BackHandler(enabled = selecting) { vm.clearSelection() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(exported) {
        exported?.let {
            vm.consumeExported()
            FileExport.share(context, it, "application/zip")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgDark,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(BgDark)) {
            Surface(color = SurfDark) {
                Column {
                    if (selecting) {
                        SelectionBar(
                            count = selection.size,
                            modelCount = selectedModelCount,
                            busy = exporting != null,
                            onClose = { vm.clearSelection() },
                            onSelectAll = { vm.selectAll(filtered) },
                            onExport = { showFormats = true },
                            onDelete = { vm.deleteSelected(allItems) },
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MenuButton(onMenu)
                            Column(Modifier.weight(1f)) {
                                Text("Gallery", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                                Text(
                                    "Long-press to select several",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDisabled,
                                )
                            }
                            IconButton(onClick = onOpenVoice) {
                                Icon(Icons.Default.GraphicEq, "Voice", tint = TextSecondary)
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
                            }
                        }
                    }
                    TabRow(
                        selectedTabIndex = selectedTab, containerColor = SurfDark, contentColor = Purple60,
                    ) {
                        tabs.forEachIndexed { i, t ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                                text = { Text(t, color = if (selectedTab == i) Purple60 else TextSecondary) })
                        }
                    }
                }
            }

            exporting?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Collections, null, tint = TextDisabled, modifier = Modifier.size(52.dp))
                        Text("Nothing here yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text("Generate images or 3D models to see them here.", color = TextDisabled, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { item ->
                        GalleryCard(
                            item = item,
                            selected = item.id in selection,
                            selecting = selecting,
                            onDelete = { vm.delete(it) },
                            onToggle = { vm.toggleSelected(item.id) },
                            onOpen = {
                                if (item.type == "3d") {
                                    item.modelPath?.let { onOpenModel(it, item.prompt ?: "3D Model") }
                                } else {
                                    item.imagePath?.let { onOpenImage(it, item.prompt ?: "Image") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showFormats) {
        ModalBottomSheet(onDismissRequest = { showFormats = false }, containerColor = SurfDark) {
            BatchFormatSheet(
                modelCount = selectedModelCount,
                skipped = selection.size - selectedModelCount,
                onExport = { formats ->
                    showFormats = false
                    vm.exportSelected(allItems, formats)
                },
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    modelCount: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, "Cancel selection", tint = TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                // Only meshes can be exported, so say so before the user taps.
                if (modelCount == count) "$modelCount 3D model${if (modelCount == 1) "" else "s"}"
                else "$modelCount exportable · ${count - modelCount} image${if (count - modelCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (modelCount == 0) Amber else TextDisabled,
            )
        }
        TextButton(onClick = onSelectAll, enabled = !busy) {
            Text("All", color = Cyan, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = onExport, enabled = !busy && modelCount > 0) {
            Icon(
                Icons.Default.Download, "Export selected",
                tint = if (modelCount > 0) Cyan else TextDisabled,
            )
        }
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(Icons.Default.DeleteOutline, "Delete selected", tint = Red)
        }
    }
}

@Composable
private fun BatchFormatSheet(
    modelCount: Int,
    skipped: Int,
    onExport: (List<ModelExporter.Format>) -> Unit,
) {
    val chosen = remember { mutableStateListOf(ModelExporter.Format.GLB) }

    Column(Modifier.padding(bottom = 28.dp)) {
        Text(
            "Export $modelCount model${if (modelCount == 1) "" else "s"}",
            style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "One .zip, each model in its own folder." +
                if (skipped > 0) " $skipped image${if (skipped == 1) "" else "s"} will be skipped." else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (skipped > 0) Amber else TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(10.dp))

        ModelExporter.Format.entries.forEach { fmt ->
            val on = fmt in chosen
            ListItem(
                headlineContent = { Text(fmt.label, color = TextPrimary) },
                supportingContent = {
                    Text(fmt.note, color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                },
                leadingContent = {
                    Checkbox(
                        checked = on,
                        onCheckedChange = { if (on) chosen.remove(fmt) else chosen.add(fmt) },
                        colors = CheckboxDefaults.colors(checkedColor = Purple60),
                    )
                },
                modifier = Modifier.clickable { if (on) chosen.remove(fmt) else chosen.add(fmt) },
                colors = ListItemDefaults.colors(containerColor = SurfDark),
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onExport(chosen.toList()) },
            enabled = chosen.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Icon(Icons.Default.FolderZip, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export as .zip", color = TextPrimary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCard(
    item: GenerationEntity,
    selected: Boolean,
    selecting: Boolean,
    onDelete: (GenerationEntity) -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .then(if (selected) Modifier.neonBorder(RoundedCornerShape(20.dp)) else Modifier)
            .combinedClickable(
                // Once a selection is running, a plain tap extends it rather than
                // navigating away — the standard gallery behaviour.
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onToggle,
            ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (item.type == "image" && item.imagePath != null) {
                AsyncImage(
                    model = item.imagePath, contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else if (item.type == "3d") {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.ViewInAr, null, tint = Teal, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("3D Model", style = MaterialTheme.typography.bodySmall, color = Teal)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (selecting) "Tap to select" else "Tap to view 360°",
                        style = MaterialTheme.typography.labelSmall, color = TextDisabled,
                    )
                }
            }

            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) Cyan else TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(BgDark.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.prompt?.take(30)?.let { if (item.prompt.length > 30) "$it…" else it } ?: item.type.uppercase(),
                            style = MaterialTheme.typography.labelMedium, color = TextPrimary, maxLines = 1,
                        )
                    }
                    if (!selecting) {
                        IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.DeleteOutline, null, tint = Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
