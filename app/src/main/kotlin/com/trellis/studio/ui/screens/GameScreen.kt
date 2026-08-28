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
import com.trellis.studio.viewmodel.GameHistoryItem
import com.trellis.studio.viewmodel.GameModel
import com.trellis.studio.viewmodel.GameViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val games by vm.games.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val built by vm.built.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val selected = remember { mutableStateListOf<Long>() }
    var idea by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }           // 0 = Create, 1 = History
    var autoModels by remember { mutableStateOf(true) } // AI makes the 3D models

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

            // Create / History switch
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = Cyan,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Create") },
                    selectedContentColor = Cyan, unselectedContentColor = TextSecondary)
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("History (${games.size})") },
                    selectedContentColor = Cyan, unselectedContentColor = TextSecondary)
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

            if (tab == 1) {
                GameHistoryList(
                    games = games,
                    onOpen = { vm.openGame(it) },
                    onDelete = { vm.deleteGame(it) },
                    onCreate = { tab = 0 },
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Describe your game", style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = idea,
                        onValueChange = { idea = it },
                        enabled = !busy,
                        placeholder = {
                            Text(
                                "e.g. a car that races around a track collecting coins; " +
                                    "each coin is 10 points, avoid the barrels",
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

                // AI-makes-the-models toggle (default on: no picking needed)
                item {
                    Row(
                        Modifier.fillMaxWidth().glass().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Pink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI creates the 3D models", color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "VOID designs and generates the characters and props for you — " +
                                    "you don't pick anything.",
                                color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = autoModels,
                            onCheckedChange = { autoModels = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Cyan,
                                checkedTrackColor = Cyan.copy(alpha = 0.35f),
                            ),
                        )
                    }
                }

                // Manual picker only when the user turns auto off
                if (!autoModels) {
                    item {
                        Text("Use your own 3D models", style = MaterialTheme.typography.labelLarge,
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
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (autoModels) vm.autoBuild(idea)
                            else vm.build(models.filter { selected.contains(it.id) }, idea)
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
                            if (autoModels) "Generate game" else "Build game",
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
private fun GameHistoryList(
    games: List<GameHistoryItem>,
    onOpen: (GameHistoryItem) -> Unit,
    onDelete: (GameHistoryItem) -> Unit,
    onCreate: () -> Unit,
) {
    if (games.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.SportsEsports, null, tint = TextDisabled, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No games yet", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            Text("Every game you generate is saved here, newest first.",
                color = TextDisabled, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = onCreate,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Purple40),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Add, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create a game", color = TextPrimary)
            }
        }
        return
    }
    val fmt = remember { SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(games, key = { it.id }) { g ->
            Row(
                Modifier.fillMaxWidth().glass().clickable { onOpen(g) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(Purple40.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.SportsEsports, null, tint = Pink, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(fmt.format(Date(g.createdAt)), color = TextDisabled,
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onOpen(g) }) {
                    Icon(Icons.Default.IosShare, "Open", tint = Cyan, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { onDelete(g) }) {
                    Icon(Icons.Default.DeleteOutline, "Delete", tint = TextDisabled, modifier = Modifier.size(20.dp))
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
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
                "VOID designs the 3D models, generates each one, rigs it with bones, " +
                    "and saves it to your Gallery — then writes the game as Godot 4 " +
                    "GDScript and packs it all into a project. You get a .zip: open its " +
                    "project.godot in Godot 4.3+, let it import, and press Play.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "You always get a real, runnable zip — if the AI code step can't " +
                    "complete, VOID drops in a ready-made playable game that still uses " +
                    "your models. Edit main.gd to change anything.",
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
