package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.GalleryViewModel

@Composable
fun GalleryScreen(
    vm: GalleryViewModel = viewModel(),
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
    onOpenVoice: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val allItems by vm.allGenerations.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Images", "3D Models")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Gallery",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenVoice) {
                        Icon(Icons.Default.GraphicEq, "Voice", tint = TextSecondary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
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

        val filtered = when (selectedTab) {
            1 -> allItems.filter { it.type == "image" }
            2 -> allItems.filter { it.type == "3d" }
            else -> allItems
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
                        onDelete = { vm.delete(it) },
                        onOpen = {
                            if (item.type == "3d") {
                                item.modelPath?.let { onOpenModel(it, "3D Model") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(
    item: GenerationEntity,
    onDelete: (GenerationEntity) -> Unit,
    onOpen: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(0.85f)
            .clickable(enabled = item.type == "3d", onClick = onOpen),
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
                    Text("Tap to view 360°", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                }
            }
            // Overlay bottom bar
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
                    IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
