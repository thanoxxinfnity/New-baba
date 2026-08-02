package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.trellis.studio.util.AutoRigger
import com.trellis.studio.util.MeshAnalyzer
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

    // The selection is an id, not a copy of the row. Holding the object meant it
    // had to be re-synced whenever the list re-emitted, and any gap in that
    // re-sync shows up as the wrong model being acted on.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = models.firstOrNull { it.id == selectedId }

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
                        selected = selectedId == model.id,
                        onClick = { selectedId = if (selectedId == model.id) null else model.id },
                        onView = { onOpenModel(model.path, model.name) },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    PromptBox(
                        enabled = selected != null && busy == null,
                        subject = selected?.name,
                        onGenerate = { text -> selected?.let { vm.animateFromPrompt(it, text) } },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                item {
                    Text(
                        if (selected == null) "Or pick a ready-made motion"
                        else "Or pick a ready-made motion",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected == null) TextDisabled else TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SectionHeading(
                        "Skeleton",
                        "Builds bones and skin weights, then deforms the mesh — walking, wheels",
                        enabled = selected != null,
                    )
                }

                if (selected?.shape == MeshAnalyzer.Shape.SOLID) {
                    item { NoLimbsNotice() }
                }

                items(AutoRigger.Rig.entries, key = { "rig_${it.name}" }) { rig ->
                    val model = selected
                    // A skeleton needs limbs to attach to. Offering one for a model
                    // that measured as a single solid shape is how the rig ended up
                    // bending the whole thing — so it is not offered.
                    val fits = model != null && model.shape != MeshAnalyzer.Shape.SOLID
                    RigRow(
                        rig = rig,
                        enabled = fits && busy == null,
                        suggested = model?.suggestedRig == rig,
                        onRig = { model?.let { vm.rig(it, rig) } },
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    SectionHeading(
                        "Whole-object clips",
                        "Moves the model as one piece — no bones, works on anything",
                        enabled = selected != null,
                    )
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
                                "Generated models arrive as one fused shell — no bones, no separate " +
                                    "parts. \"Skeleton\" builds a rig from the model's own proportions " +
                                    "and weights every vertex to it, so legs and wheels move on their " +
                                    "own. \"Whole-object\" moves the model as one piece.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Either way it is real glTF animation — not a video, not a preview. " +
                                    "Unity, Unreal, Godot, Blender and three.js play it straight from " +
                                    "the .glb, and so does the viewer here.",
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
            Text(
                if (model.existingClips.isEmpty()) model.sizeLabel
                else "${model.sizeLabel} · plays ${model.existingClips.joinToString(", ")}",
                color = if (model.existingClips.isEmpty()) TextDisabled else Teal,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            // What the mesh was measured to be. Bones are placed on this, so it is
            // the honest predictor of whether a rig will look right.
            Text(
                model.shapeSummary,
                color = when (model.shape) {
                    MeshAnalyzer.Shape.SOLID -> Amber
                    else -> Cyan
                },
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

/** Describe the motion in words; the LLM turns it into bone keyframes. */
@Composable
private fun PromptBox(
    enabled: Boolean,
    subject: String?,
    onGenerate: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.glass(glow = Cyan) else Modifier.glass(fill = GlassFill.copy(alpha = 0.4f)))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = if (enabled) Cyan else TextDisabled,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "2 · Describe the motion",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) TextPrimary else TextDisabled,
            )
        }
        Text(
            if (subject == null) "Pick a model above first."
            else "Say what \"$subject\" should do — the AI works out which bones move.",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = {
                Text("walk forward and wag its tail", color = TextDisabled,
                    style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = BorderDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Cyan,
            ),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        // The chips and the button fought for the same row, which squeezed the
        // button until its label wrapped one letter per line. They get a row each,
        // and the chips scroll rather than shrink.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("walk", "run and wave", "spin the wheels", "jump", "wag tail").forEach { hint ->
                AssistChip(
                    onClick = { text = hint },
                    enabled = enabled,
                    label = {
                        Text(hint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    },
                    colors = AssistChipDefaults.assistChipColors(labelColor = TextSecondary),
                )
            }
        }
        Button(
            onClick = { onGenerate(text); text = "" },
            enabled = enabled && text.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Purple40,
                disabledContainerColor = CardHigh,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp),
                tint = if (enabled && text.isNotBlank()) TextPrimary else TextDisabled)
            Spacer(Modifier.width(8.dp))
            Text(
                "Animate",
                color = if (enabled && text.isNotBlank()) TextPrimary else TextDisabled,
                maxLines = 1,
            )
        }
    }
}

/** Shown when the measurement found nothing to attach bones to. */
@Composable
private fun NoLimbsNotice() {
    Row(
        Modifier.fillMaxWidth().glass(glow = Amber).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "This model measured as one solid shape — no separate legs or wheels " +
                "to move. A skeleton would just bend the whole thing, so the " +
                "whole-object clips below are the ones that will look right.",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SectionHeading(title: String, note: String, enabled: Boolean) {
    Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Cyan else TextDisabled,
        )
        Text(note, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun RigRow(
    rig: AutoRigger.Rig,
    enabled: Boolean,
    suggested: Boolean,
    onRig: () -> Unit,
) {
    var showCaveat by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (suggested && enabled) Modifier.glass(glow = Pink).neonBorder(RoundedCornerShape(20.dp))
                else Modifier.glass(fill = if (enabled) GlassFill else GlassFill.copy(alpha = 0.4f))
            )
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                rigIcon(rig), null,
                tint = if (enabled) Cyan else TextDisabled,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rig.label,
                        color = if (enabled) TextPrimary else TextDisabled,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (suggested) {
                        Spacer(Modifier.width(8.dp))
                        Text("best fit", color = Pink, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    rig.note,
                    color = TextDisabled,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { showCaveat = !showCaveat }) {
                Icon(
                    Icons.Default.Info, "What this fits",
                    tint = if (enabled) TextSecondary else TextDisabled,
                    modifier = Modifier.size(18.dp),
                )
            }
            FilledTonalButton(
                onClick = onRig,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Purple40),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Rig", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(6.dp))
        }
        // The rig is fitted from proportions, not understood, so the limits are
        // one tap away rather than discovered after a bad result.
        AnimatedVisibility(visible = showCaveat) {
            Text(
                rig.caveat,
                color = Amber,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 32.dp, end = 12.dp, top = 6.dp),
            )
        }
    }
}

private fun rigIcon(rig: AutoRigger.Rig) = when (rig) {
    AutoRigger.Rig.HUMANOID_WALK -> Icons.Default.DirectionsWalk
    AutoRigger.Rig.HUMANOID_RUN -> Icons.Default.DirectionsRun
    AutoRigger.Rig.QUADRUPED_WALK -> Icons.Default.Pets
    AutoRigger.Rig.VEHICLE_WHEELS -> Icons.Default.DirectionsCar
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
