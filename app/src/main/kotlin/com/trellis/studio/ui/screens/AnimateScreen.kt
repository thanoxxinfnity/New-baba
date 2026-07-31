package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.AnimationBaker
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.AnimatableModel
import com.trellis.studio.viewmodel.AnimateViewModel

/**
 * Dedicated screen for putting motion into a generated model.
 *
 * Pick a model, pick a clip, and the app writes real glTF animation channels
 * into a new .glb. The result plays in the in-app viewer and in Unity, Unreal,
 * Godot, Blender and three.js without any extra setup.
 */
@Composable
fun AnimateScreen(
    vm: AnimateViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
    onCreateModel: () -> Unit = {},
) {
    val context = LocalContext.current
    val models by vm.models.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val baked by vm.baked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var selected by remember { mutableStateOf<AnimatableModel?>(null) }

    // The selected model is held by value, so refresh it when the list changes
    // (a bake inserts a new row and would otherwise leave a stale selection).
    LaunchedEffect(models) {
        selected = selected?.let { current -> models.firstOrNull { it.id == current.id } }
    }

    LaunchedEffect(baked) {
        baked?.let { (path, name) ->
            vm.consumeBaked()
            onOpenModel(path, name)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text(
                        "Animate",
                        style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Add real motion to a 3D model",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Icon(Icons.Default.Animation, null, tint = Pink, modifier = Modifier.size(22.dp))
            }

            AnimatedVisibility(visible = busy != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(busy.orEmpty(), color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (models.isEmpty()) {
                EmptyAnimateHint(onCreateModel)
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        "1 · Choose a model",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }

                items(models, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        selected = selected?.id == model.id,
                        onClick = { selected = if (selected?.id == model.id) null else model },
                        onView = { onOpenModel(model.path, model.name) },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (selected == null) "2 · Pick a model above to unlock the clips"
                        else "2 · Choose a motion clip",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected == null) TextDisabled else TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                items(AnimationBaker.Clip.entries, key = { it.name }) { clip ->
                    val model = selected
                    ClipRow(
                        clip = clip,
                        enabled = model != null && busy == null,
                        alreadyOn = model?.existingClips?.contains(clip.label) == true,
                        onBake = { model?.let { vm.animate(it, clip) } },
                        onExport = {
                            model?.let {
                                vm.animateAndExport(it, clip, null) { file, mime ->
                                    FileExport.share(context, file, mime)
                                }
                            }
                        },
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().glass(glow = Purple40),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("How this works", color = TextPrimary,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                "The clip is written into the file as real glTF animation — not a " +
                                    "video, not a preview. Unity, Unreal, Godot, Blender and three.js " +
                                    "play it straight from the .glb, and so does the viewer here.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Animation lives in .glb only. OBJ, STL and PLY store geometry, so " +
                                    "export those for static props.",
                                color = TextDisabled,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: AnimatableModel,
    selected: Boolean,
    onClick: () -> Unit,
    onView: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.glass(glow = Cyan).neonBorder(RoundedCornerShape(20.dp))
                else Modifier.glass()
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (selected) Purple40.copy(alpha = 0.35f) else CardHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Default.Check else Icons.Default.ViewInAr,
                null,
                tint = if (selected) Cyan else Teal,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.name,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val subtitle = if (model.existingClips.isEmpty()) model.sizeLabel
            else "${model.sizeLabel} · plays ${model.existingClips.joinToString(", ")}"
            Text(
                subtitle,
                color = if (model.existingClips.isEmpty()) TextDisabled else Teal,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onView) {
            Icon(Icons.Default.PlayCircleOutline, "Preview", tint = TextSecondary,
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ClipRow(
    clip: AnimationBaker.Clip,
    enabled: Boolean,
    alreadyOn: Boolean,
    onBake: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(fill = if (enabled) GlassFill else GlassFill.copy(alpha = 0.4f))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            clipIcon(clip), null,
            tint = if (enabled) Pink else TextDisabled,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.label,
                    color = if (enabled) TextPrimary else TextDisabled,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (alreadyOn) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "already added",
                        color = Teal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                "${clip.note} · ${clip.seconds}s loop",
                color = TextDisabled,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        }
        IconButton(onClick = onExport, enabled = enabled) {
            Icon(Icons.Default.Download, "Export with this clip",
                tint = if (enabled) TextSecondary else TextDisabled, modifier = Modifier.size(18.dp))
        }
        FilledTonalButton(
            onClick = onBake,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Purple40),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Add", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(6.dp))
    }
}

private fun clipIcon(clip: AnimationBaker.Clip) = when (clip) {
    AnimationBaker.Clip.SPIN -> Icons.Default.Rotate90DegreesCcw
    AnimationBaker.Clip.FLOAT -> Icons.Default.Air
    AnimationBaker.Clip.SPIN_FLOAT -> Icons.Default.AllInclusive
    AnimationBaker.Clip.PULSE -> Icons.Default.Bolt
    AnimationBaker.Clip.TUMBLE -> Icons.Default.Cyclone
    AnimationBaker.Clip.SWAY -> Icons.Default.Waves
    AnimationBaker.Clip.BOUNCE -> Icons.Default.SportsBasketball
}

@Composable
private fun ColumnScope.EmptyAnimateHint(onCreateModel: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Animation, null, tint = TextDisabled, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text("No 3D models yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Generate a model first, then come back to give it motion.",
            color = TextDisabled,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onCreateModel,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Purple40),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = TextPrimary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create a 3D model", color = TextPrimary)
        }
    }
}
