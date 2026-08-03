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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.GameModel
import com.trellis.studio.viewmodel.GameViewModel

/**
 * Make a real game from your 3D models: pick the models, say what the game is,
 * and VOID writes the Godot code and hands back a project you open and Play.
 *
 * This is the reliable counterpart to driving Godot's UI by hand — the fragile
 * project files are templated, the AI only writes GDScript, and the result runs
 * on the first Play.
 */
@Composable
fun GameScreen(
    vm: GameViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onCreateModel: () -> Unit = {},
) {
    val context = LocalContext.current
    val models by vm.models.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val built by vm.built.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val selected = remember { mutableStateListOf<Long>() }
    var idea by remember { mutableStateOf("") }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(built) {
        built?.let { FileExport.share(context, it, "application/zip"); vm.consumeBuilt() }
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
                        "Game Studio",
                        style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Turn your 3D models into a playable Godot game",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Icon(Icons.Default.SportsEsports, null, tint = Pink, modifier = Modifier.size(22.dp))
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(status.orEmpty(), color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("1 · Pick your 3D models", style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary)
                }

                if (models.isEmpty()) {
                    item { NoModelsHint(onCreateModel) }
                } else {
                    items(models, key = { it.id }) { m ->
                        ModelPick(
                            model = m,
                            checked = selected.contains(m.id),
                            onToggle = {
                                if (selected.contains(m.id)) selected.remove(m.id) else selected.add(m.id)
                            },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    Text("2 · Describe the game", style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = idea,
                        onValueChange = { idea = it },
                        enabled = !busy,
                        placeholder = {
                            Text(
                                "e.g. the character runs around a field collecting the coins; " +
                                    "each coin is 10 points, avoid the trees",
                                color = TextDisabled, style = MaterialTheme.typography.bodySmall,
                            )
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
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    Button(
                        onClick = {
                            val chosen = models.filter { selected.contains(it.id) }
                            vm.build(chosen, idea)
                        },
                        enabled = !busy && idea.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple40, disabledContainerColor = CardHigh,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp),
                            tint = if (!busy && idea.isNotBlank()) TextPrimary else TextDisabled)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selected.isEmpty()) "Build game (no models)" else "Build game",
                            color = if (!busy && idea.isNotBlank()) TextPrimary else TextDisabled,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HowItWorks()
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelPick(model: GameModel, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (checked) Modifier.glass(glow = Cyan).neonBorder(RoundedCornerShape(20.dp))
                else Modifier.glass()
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (checked) Purple40.copy(alpha = 0.35f) else CardHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (checked) Icons.Default.Check else Icons.Default.ViewInAr,
                null, tint = if (checked) Cyan else Teal, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(model.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HowItWorks() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().glass(glow = Purple40),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("How this works", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "VOID writes the game as Godot 4 GDScript and packs it, with your " +
                    "models, into a project. You get a .zip — open its project.godot in " +
                    "Godot 4.3+ on your phone, let it import, and press Play.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "This is reliable because the AI only writes code — it never has to " +
                    "poke Godot's buttons. Edit main.gd to change the game.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NoModelsHint(onCreateModel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.ViewInAr, null, tint = TextDisabled, modifier = Modifier.size(40.dp))
        Text("No 3D models yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "You can still build a game from primitives, or generate some 3D models " +
                "first and use them as the characters and props.",
            color = TextDisabled, style = MaterialTheme.typography.bodySmall,
        )
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
