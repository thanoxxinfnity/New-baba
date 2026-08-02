package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.trellis.studio.util.AutoRigger
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.AndroidTextureScaler
import com.trellis.studio.util.MeshSimplifier
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
    // Asked before every export, the way Meshy and Tripo do it.
    var detail by remember { mutableStateOf(MeshSimplifier.Detail.FULL) }
    var size by remember { mutableStateOf<MeshSimplifier.Size?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    // Null until the user opens Advanced; then it overrides the preset entirely.
    var advanced by remember { mutableStateOf<MeshSimplifier.Options?>(null) }
    var clipNames by remember { mutableStateOf<List<String>>(emptyList()) }

    val file = remember(currentPath) { File(currentPath) }
    val hasAnimation = clipNames.isNotEmpty()

    LaunchedEffect(currentPath) {
        size = withContext(Dispatchers.IO) { MeshSimplifier.measure(file) }
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

    // ---- Advanced quality --------------------------------------------------
    if (showAdvanced) {
        ModalBottomSheet(onDismissRequest = { showAdvanced = false }, containerColor = SurfDark) {
            AdvancedSheet(
                original = size,
                start = advanced ?: MeshSimplifier.Options.of(detail),
                onApply = { advanced = it; showAdvanced = false },
                onReset = { advanced = null; showAdvanced = false },
            )
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

                // Bones first: a clip moves the model as one piece, but only a
                // skeleton lets anything else animate it afterwards.
                ListItem(
                    headlineContent = { Text("Add bones (rig only)", color = TextPrimary) },
                    supportingContent = {
                        Text(
                            "Measures the mesh, fits a skeleton to its limbs and skins it. " +
                                "No clip — drive the joints from code.",
                            color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Accessibility, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable(enabled = exporting == null) {
                        scope.launch {
                            exporting = "Measuring the model and placing bones…"
                            val dir = File(context.filesDir, "models3d").apply { mkdirs() }
                            AutoRigger.addBones(file, dir)
                                .onSuccess { rigged ->
                                    val named = "${currentName.substringBefore(" · ")} · rigged"
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            AppDatabase
                                                .get(context.applicationContext as android.app.Application)
                                                .generationDao()
                                                .insert(
                                                    GenerationEntity(
                                                        type = "3d",
                                                        prompt = named,
                                                        modelPath = rigged.file.absolutePath,
                                                    )
                                                )
                                        }
                                    }
                                    exporting = null
                                    showAnimate = false
                                    currentName = named
                                    currentPath = rigged.file.absolutePath
                                    snackbar.showSnackbar(
                                        "${rigged.bones.size} bones added: " +
                                            rigged.bones.take(3).joinToString(", ") + "…"
                                    )
                                }
                                .onFailure { e ->
                                    exporting = null
                                    snackbar.showSnackbar(e.message ?: "Could not add bones")
                                }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = SurfDark),
                )
                HorizontalDivider(color = BorderDark)

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
                Spacer(Modifier.height(6.dp))
                DetailPicker(
                    current = detail,
                    original = size,
                    advanced = advanced,
                    onPick = { detail = it; advanced = null },
                    onAdvanced = { showAdvanced = true },
                )
                HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))

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
                                val dir = FileExport.outputDir(context)
                                val options = advanced ?: MeshSimplifier.Options.of(detail)
                                val source = if (options.changesNothing) file else {
                                    exporting = "Preparing the mesh…"
                                    // A separate folder, so the intermediate can keep
                                    // the model's own name without overwriting it.
                                    MeshSimplifier.process(
                                        file, options, File(dir, "prepared"), AndroidTextureScaler, suffix = "",
                                    )
                                        .getOrElse { e ->
                                            exporting = null
                                            snackbar.showSnackbar(e.message ?: "Could not prepare the model")
                                            return@launch
                                        }
                                }
                                exporting = "Writing ${fmt.label}…"
                                ModelExporter.export(source, fmt, dir)
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
                            val dir = FileExport.outputDir(context)
                            val options = advanced ?: MeshSimplifier.Options.of(detail)
                            val source = if (options.changesNothing) file else {
                                exporting = "Preparing the mesh…"
                                MeshSimplifier.process(
                                    file, options, File(dir, "prepared"), AndroidTextureScaler, suffix = "",
                                )
                                    .getOrElse { e ->
                                        exporting = null
                                        snackbar.showSnackbar(e.message ?: "Could not prepare the model")
                                        return@launch
                                    }
                            }
                            exporting = "Packing every format…"
                            ModelExporter.exportAll(source, dir)
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

/**
 * Triangle-and-texture budget, chosen before the format.
 *
 * The current cost is spelled out because "Low" means nothing without knowing
 * what the model actually is — a 2k-triangle prop is already below every
 * preset, and reducing it further would only make it worse.
 */
@Composable
private fun DetailPicker(
    current: MeshSimplifier.Detail,
    original: MeshSimplifier.Size?,
    advanced: MeshSimplifier.Options?,
    onPick: (MeshSimplifier.Detail) -> Unit,
    onAdvanced: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = MaterialTheme.typography.labelLarge, color = Cyan)
            Spacer(Modifier.width(8.dp))
            original?.let {
                Text(
                    "now ${"%,d".format(it.triangles)} triangles · ${FileExport.humanSize(it.bytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MeshSimplifier.Detail.entries.forEach { d ->
                // A preset above what the model already has would do nothing, so
                // it is shown as unavailable rather than silently no-op.
                val pointless = original != null && !d.isFull &&
                    original.triangles <= d.targetTriangles && d.textureSize >= 1024
                FilterChip(
                    selected = current == d,
                    enabled = !pointless,
                    onClick = { onPick(d) },
                    label = { Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40,
                        selectedLabelColor = TextPrimary,
                        containerColor = CardDark,
                        labelColor = TextSecondary,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                advanced?.let { o ->
                    buildString {
                        append(if (o.targetTriangles > 0) "%,d tris".format(o.targetTriangles) else "all tris")
                        if (o.textureSize > 0) append(" · ${o.textureSize}px")
                        if (o.smoothNormals) append(" · smooth")
                        if (o.tangents) append(" · tangents")
                    }
                } ?: current.note,
                style = MaterialTheme.typography.labelSmall,
                color = if (advanced != null) Cyan else TextDisabled,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdvanced) {
                Icon(Icons.Default.Tune, null, tint = Pink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Advanced", color = Pink, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Full control over the export, for people who want to tune it themselves.
 *
 * The texture options run past the source resolution on purpose — a game
 * pipeline sometimes wants a fixed size — but going above what the model
 * actually has is labelled as upscaling, because it cannot invent detail that
 * was never generated.
 */
@Composable
private fun AdvancedSheet(
    original: MeshSimplifier.Size?,
    start: MeshSimplifier.Options,
    onApply: (MeshSimplifier.Options) -> Unit,
    onReset: () -> Unit,
) {
    var triangles by remember { mutableIntStateOf(start.targetTriangles) }
    var texture by remember { mutableIntStateOf(start.textureSize) }
    var smooth by remember { mutableStateOf(start.smoothNormals) }
    var tangents by remember { mutableStateOf(start.tangents) }

    val sourceTriangles = original?.triangles ?: 0

    Column(Modifier.padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Text(
            "Advanced",
            style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "Everything the export can change. Set it once and it overrides the preset.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        // ---- geometry ------------------------------------------------------
        Spacer(Modifier.height(14.dp))
        SheetLabel("Triangles", if (sourceTriangles > 0) "model has %,d".format(sourceTriangles) else null)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0 to "Keep all", 100_000 to "100k", 50_000 to "50k", 25_000 to "25k",
                   10_000 to "10k", 5_000 to "5k", 2_000 to "2k", 1_000 to "1k").forEach { (value, label) ->
                FilterChip(
                    selected = triangles == value,
                    onClick = { triangles = value },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40, selectedLabelColor = TextPrimary,
                        containerColor = CardDark, labelColor = TextSecondary,
                    ),
                )
            }
        }

        // ---- texture -------------------------------------------------------
        Spacer(Modifier.height(10.dp))
        SheetLabel("Texture", "generated at 1024px")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0 to "Original", 4096 to "4K", 2048 to "2K", 1024 to "1K",
                   512 to "512", 256 to "256").forEach { (value, label) ->
                FilterChip(
                    selected = texture == value,
                    onClick = { texture = value },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40, selectedLabelColor = TextPrimary,
                        containerColor = CardDark, labelColor = TextSecondary,
                    ),
                )
            }
        }
        if (texture > SOURCE_TEXTURE) {
            Text(
                "${texture}px is above the ${SOURCE_TEXTURE}px the model was generated at. " +
                    "It will be upscaled — the file grows but no new detail appears.",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // ---- shading -------------------------------------------------------
        Spacer(Modifier.height(10.dp))
        SheetLabel("Shading", null)
        ListItem(
            headlineContent = { Text("Smooth normals", color = TextPrimary) },
            supportingContent = {
                Text(
                    "Generated models ship without normals, so engines light every " +
                        "triangle as a separate facet. This is the biggest visual change.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingContent = {
                Switch(
                    checked = smooth,
                    onCheckedChange = { smooth = it; if (!it) tangents = false },
                    colors = SwitchDefaults.colors(checkedTrackColor = Purple40),
                )
            },
            colors = ListItemDefaults.colors(containerColor = SurfDark),
        )
        ListItem(
            headlineContent = {
                Text("Tangents", color = if (smooth) TextPrimary else TextDisabled)
            },
            supportingContent = {
                Text(
                    "Needed before Unity or Unreal can light the model with a normal map.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingContent = {
                Switch(
                    checked = tangents,
                    enabled = smooth,
                    onCheckedChange = { tangents = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Purple40),
                )
            },
            colors = ListItemDefaults.colors(containerColor = SurfDark),
        )

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) { Text("Use preset", color = TextSecondary) }
            Button(
                onClick = {
                    onApply(
                        MeshSimplifier.Options(
                            targetTriangles = triangles,
                            textureSize = texture,
                            smoothNormals = smooth,
                            tangents = tangents,
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) { Text("Apply", color = TextPrimary) }
        }
    }
}

@Composable
private fun SheetLabel(title: String, note: String?) {
    Row(
        Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Cyan)
        note?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
        }
    }
}

/** What TRELLIS actually generates, so upscaling can be called out honestly. */
private const val SOURCE_TEXTURE = 1024

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
