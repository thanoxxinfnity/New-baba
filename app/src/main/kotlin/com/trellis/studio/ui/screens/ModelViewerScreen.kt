package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.AnimationBaker
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.ModelExporter
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 360° viewer for generated .glb models.
 * Drag to orbit, pinch to zoom — SceneView's orbit manipulator handles gestures.
 *
 * A file that carries baked glTF animation plays it for real; the manual
 * turntable is only used for models that have no animation of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelViewerScreen(
    modelPath: String,
    modelName: String = "3D Model",
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader)
    val cameraNode = rememberCameraNode(engine) { position = Float3(0f, 0f, 4f) }
    val nodes = rememberNodes()

    // Baking writes a new file and swaps the viewer onto it, so the path is state.
    var currentPath by remember(modelPath) { mutableStateOf(modelPath) }
    var currentName by remember(modelName) { mutableStateOf(modelName) }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRotate by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showAnimate by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var clipNames by remember { mutableStateOf<List<String>>(emptyList()) }

    val file = remember(currentPath) { File(currentPath) }
    val hasAnimation = clipNames.isNotEmpty()

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        nodes.clear()
        clipNames = emptyList()
        runCatching {
            if (!file.exists() || file.length() == 0L) error("Model file is missing or empty.")
            val buffer = ByteBuffer.wrap(file.readBytes())
            val instance = modelLoader.createModelInstance(buffer)
            ModelNode(modelInstance = instance, scaleToUnits = 1.5f)
        }.onSuccess { node ->
            nodes += node
            // Baked clips play through Filament's animator, which SceneView ticks
            // every frame — the manual turntable would fight it, so it stands down.
            if (node.animationCount > 0) {
                node.playAnimation(0, 1f, true)
                clipNames = withContext(Dispatchers.IO) {
                    AnimationBaker.clipNames(file)
                }.ifEmpty { List(node.animationCount) { "Clip ${it + 1}" } }
                autoRotate = false
            }
            loading = false
        }.onFailure {
            error = it.message ?: "Could not open this model."
            loading = false
        }
    }

    // Slow turntable so a static model still reads as 3D without any interaction.
    LaunchedEffect(autoRotate, currentPath) {
        var last = 0L
        while (autoRotate) {
            withFrameNanos { now ->
                val delta = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                nodes.firstOrNull()?.let { node ->
                    val r = node.rotation
                    node.rotation = Float3(r.x, r.y + delta * 24f, r.z)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(currentName, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1)
                        Text(
                            buildString {
                                append(if (file.exists()) FileExport.humanSize(file.length()) else "missing")
                                if (hasAnimation) append(" · ${clipNames.joinToString(", ")}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasAnimation) Teal else TextSecondary,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showAnimate = true }) {
                        Icon(Icons.Default.Animation, "Animate", tint = Pink)
                    }
                    IconButton(onClick = { showExport = true }) {
                        Icon(Icons.Default.Download, "Export", tint = Cyan)
                    }
                    IconButton(onClick = { showInfo = !showInfo }) {
                        Icon(Icons.Default.Info, "Info", tint = TextSecondary)
                    }
                    IconButton(onClick = {
                        if (file.exists()) FileExport.share(context, file, "model/gltf-binary")
                        else scope.launch { snackbar.showSnackbar("Model file is missing") }
                    }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfDark),
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(BgDark)) {

            if (error == null) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    cameraNode = cameraNode,
                    cameraManipulator = rememberCameraManipulator(
                        orbitHomePosition = cameraNode.worldPosition,
                        targetPosition = Float3(0f, 0f, 0f),
                    ),
                    childNodes = nodes,
                    environment = environment,
                )
            }

            if (loading) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Purple60)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading model…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            error?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = Red, style = MaterialTheme.typography.bodyMedium)
                }
            }

            AnimatedVisibility(visible = showInfo, modifier = Modifier.align(Alignment.TopCenter)) {
                Card(
                    Modifier.padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Drag to orbit · pinch to zoom", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                        Text(file.name, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Text("glTF binary (.glb)", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        if (hasAnimation) {
                            Text(
                                "Animation baked in — plays in Unity, Unreal, Godot and Blender too",
                                color = Teal,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (!loading && error == null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasAnimation) {
                        // Playback is driven by the file's own clip; the only useful
                        // control here is the extra turntable on top of it.
                        FilledTonalButton(
                            onClick = { autoRotate = !autoRotate },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (autoRotate) Purple40 else CardDark,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Animation, null, modifier = Modifier.size(17.dp), tint = Teal)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (autoRotate) "Playing + orbit" else "Playing ${clipNames.first()}",
                                color = TextPrimary,
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { autoRotate = !autoRotate },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (autoRotate) Purple40 else CardDark,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                if (autoRotate) Icons.Default.Pause else Icons.Default.RotateRight,
                                null, modifier = Modifier.size(17.dp), tint = TextPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (autoRotate) "Stop" else "Spin 360°", color = TextPrimary)
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            nodes.firstOrNull()?.let { it.rotation = Float3(0f, 0f, 0f) }
                            cameraNode.position = Float3(0f, 0f, 4f)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardDark),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(17.dp), tint = TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Reset", color = TextPrimary)
                    }
                }
            }
        }
    }

    // ---- Bake motion into the model ---------------------------------------
    if (showAnimate) {
        ModalBottomSheet(onDismissRequest = { showAnimate = false }, containerColor = SurfDark) {
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(
                    "Animate this model",
                    style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Text(
                    "Writes a real glTF animation into a new .glb. Game engines play it natively.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(10.dp))

                exporting?.let { BusyRow(it) }

                AnimationBaker.Clip.entries.forEach { clip ->
                    ListItem(
                        headlineContent = { Text(clip.label, color = TextPrimary) },
                        supportingContent = {
                            Text("${clip.note} · ${clip.seconds}s loop", color = TextDisabled,
                                style = MaterialTheme.typography.labelSmall)
                        },
                        leadingContent = {
                            Icon(Icons.Default.Animation, null, tint = Pink, modifier = Modifier.size(20.dp))
                        },
                        trailingContent = {
                            if (clipNames.contains(clip.label)) {
                                Text("added", color = Teal, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.clickable(enabled = exporting == null) {
                            scope.launch {
                                exporting = "Baking ${clip.label}…"
                                val dir = File(context.filesDir, "models3d").apply { mkdirs() }
                                AnimationBaker.bake(file, clip, dir)
                                    .onSuccess { out ->
                                        val named = "${currentName.substringBefore(" · ")} · ${clip.label}"
                                        // Register it so the animated copy shows up in the
                                        // gallery and the Animate screen, not just here.
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                AppDatabase
                                                    .get(context.applicationContext as android.app.Application)
                                                    .generationDao()
                                                    .insert(
                                                        GenerationEntity(
                                                            type = "3d",
                                                            prompt = named,
                                                            modelPath = out.absolutePath,
                                                        )
                                                    )
                                            }
                                        }
                                        exporting = null
                                        showAnimate = false
                                        currentName = named
                                        currentPath = out.absolutePath   // reloads the scene
                                    }
                                    .onFailure { e ->
                                        exporting = null
                                        snackbar.showSnackbar(e.message ?: "Could not add the animation")
                                    }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = SurfDark),
                    )
                }
            }
        }
    }

    // ---- Export for game engines -----------------------------------------
    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }, containerColor = SurfDark) {
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(
                    "Export model",
                    style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Text(
                    "Drop these straight into Unity, Unreal, Godot or Blender.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(10.dp))

                exporting?.let { BusyRow(it) }

                ModelExporter.Format.entries.forEach { fmt ->
                    ListItem(
                        headlineContent = { Text(fmt.label, color = TextPrimary) },
                        supportingContent = {
                            Text(
                                // Only glTF carries animation; saying so up front beats
                                // handing over a file that quietly sits still.
                                if (hasAnimation && fmt != ModelExporter.Format.GLB)
                                    "${fmt.note} — geometry only, no animation"
                                else fmt.note,
                                color = TextDisabled,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.InsertDriveFile, null, tint = Purple60, modifier = Modifier.size(20.dp))
                        },
                        modifier = Modifier.clickable(enabled = exporting == null) {
                            scope.launch {
                                exporting = "Writing ${fmt.label}…"
                                ModelExporter.export(file, fmt, FileExport.outputDir(context))
                                    .onSuccess { out ->
                                        exporting = null
                                        showExport = false
                                        FileExport.share(context, out)
                                    }
                                    .onFailure { e ->
                                        exporting = null
                                        snackbar.showSnackbar(e.message ?: "Export failed")
                                    }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = SurfDark),
                    )
                }

                HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                ListItem(
                    headlineContent = { Text("All formats (.zip)", color = Cyan) },
                    supportingContent = {
                        Text("GLB + OBJ + MTL + STL + PLY + texture", color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.FolderZip, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable(enabled = exporting == null) {
                        scope.launch {
                            exporting = "Packing every format…"
                            ModelExporter.exportAll(file, FileExport.outputDir(context))
                                .onSuccess { zip ->
                                    exporting = null
                                    showExport = false
                                    FileExport.share(context, zip, "application/zip")
                                }
                                .onFailure { e ->
                                    exporting = null
                                    snackbar.showSnackbar(e.message ?: "Export failed")
                                }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = SurfDark),
                )
            }
        }
    }
}

@Composable
private fun BusyRow(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = Cyan, style = MaterialTheme.typography.labelMedium)
    }
}
