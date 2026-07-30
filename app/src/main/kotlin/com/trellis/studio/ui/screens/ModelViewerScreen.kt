package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
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
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * 360° viewer for generated .glb models.
 * Drag to orbit, pinch to zoom — SceneView's orbit manipulator handles gestures.
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

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRotate by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    val file = remember(modelPath) { File(modelPath) }

    LaunchedEffect(modelPath) {
        loading = true
        error = null
        nodes.clear()
        runCatching {
            if (!file.exists() || file.length() == 0L) error("Model file is missing or empty.")
            val buffer = ByteBuffer.wrap(file.readBytes())
            val instance = modelLoader.createModelInstance(buffer)
            ModelNode(modelInstance = instance, scaleToUnits = 1.5f)
        }.onSuccess {
            nodes += it
            loading = false
        }.onFailure {
            error = it.message ?: "Could not open this model."
            loading = false
        }
    }

    // Slow turntable so the model reads as 3D without any interaction.
    LaunchedEffect(autoRotate) {
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
                        Text(modelName, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1)
                        Text(
                            if (file.exists()) FileExport.humanSize(file.length()) else "missing",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
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
                    }
                }
            }

            if (!loading && error == null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
}
