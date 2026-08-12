package com.trellis.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.FalImageClient
import com.trellis.studio.network.HfImageEditClient
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Full-screen image viewer with pinch-zoom, pan and double-tap.
 * Panning is clamped so the picture can't be dragged off screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imagePath: String,
    title: String = "Image",
    onBack: () -> Unit = {},
    /** Opens the freshly edited image (a new file) in the viewer. */
    onEdited: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val file = remember(imagePath) { File(imagePath) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showChrome by remember { mutableStateOf(true) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val animatedScale by animateFloatAsState(scale, label = "zoom")

    fun clampOffsets() {
        // At 1x there is nothing to pan; beyond that, allow half the overflow.
        val maxX = (boxSize.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(visible = showChrome) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1)
                            Text(
                                if (file.exists()) FileExport.humanSize(file.length()) else "missing",
                                style = MaterialTheme.typography.labelSmall,
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
                        IconButton(onClick = { if (file.exists()) showEdit = true }) {
                            Icon(Icons.Default.AutoFixHigh, "Edit with AI", tint = Cyan)
                        }
                        IconButton(onClick = {
                            if (file.exists()) scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                        android.app.WallpaperManager.getInstance(context).setBitmap(bmp)
                                    }.isSuccess
                                }
                                snackbar.showSnackbar(if (ok) "Set as wallpaper 🖼️" else "Couldn't set wallpaper.")
                            }
                        }) { Icon(Icons.Default.Wallpaper, "Set as wallpaper", tint = Pink) }
                        IconButton(onClick = {
                            if (file.exists()) FileExport.share(context, file, "image/*")
                        }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.75f),
                    ),
                )
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 50f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                        clampOffsets()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showChrome = !showChrome },
                        onDoubleTap = {
                            // Snap between fit and 2.5x, the usual gallery behaviour.
                            if (scale > 1.05f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imagePath,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = animatedScale,
                        scaleY = animatedScale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )

            if (editing) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(12.dp))
                    Text("Editing the image…", color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Zoom readout + reset, only once zoomed in
            AnimatedVisibility(
                visible = showChrome && abs(scale - 1f) > 0.02f,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier.padding(24.dp)
                        .glass(shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        String.format(java.util.Locale.US, "%.1f×", scale),
                        style = MaterialTheme.typography.labelLarge,
                        color = Cyan,
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(
                        onClick = { scale = 1f; offsetX = 0f; offsetY = 0f },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("Reset", color = TextSecondary, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }

    if (showEdit) {
        EditSheet(
            busy = editing,
            onDismiss = { if (!editing) showEdit = false },
            onEdit = { prompt ->
                editing = true
                scope.launch {
                    val prefs = AppPrefs(context)
                    val outDir = FileExport.outputDir(context)
                    // Free editing first: a Hugging Face FLUX.1-Kontext Space edits
                    // the real uploaded image at no cost. If it can't (busy/quota)
                    // and a fal.ai key is set, fall back to fal. This is what turns
                    // the old hard "needs a fal.ai key" error into a working edit.
                    val hfToken = prefs.hfToken.first()
                    val falKey = prefs.falKey.first()
                    val editResult = HfImageEditClient()
                        .edit(HfImageEditClient.DEFAULT_EDIT_SPACE, hfToken, file, prompt, outDir)
                        .recoverCatching { hfErr ->
                            if (falKey.isNotBlank())
                                FalImageClient().edit(falKey, file, prompt, outDir).getOrThrow()
                            else throw hfErr
                        }
                    editResult
                        .onSuccess { out ->
                            val name = "$title · edited"
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.get(context.applicationContext as android.app.Application)
                                        .generationDao()
                                        .insert(GenerationEntity(type = "image", prompt = name,
                                            modelPath = out.absolutePath))
                                }
                            }
                            editing = false
                            showEdit = false
                            onEdited(out.absolutePath, name)
                        }
                        .onFailure {
                            editing = false
                            snackbar.showSnackbar(it.message ?: "Couldn't edit the image.")
                        }
                }
            },
        )
    }
}

/** A prompt sheet for image-to-image editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfDark) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null, tint = Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit this image", style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Say what to change and the image is edited, keeping the rest — like " +
                    "\"make it night\", \"add a red hat\", \"turn the car blue\".",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                enabled = !busy,
                placeholder = { Text("make it snow", color = TextDisabled,
                    style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan, unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Cyan,
                ),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onEdit(prompt) },
                enabled = !busy && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple40, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp),
                        tint = if (prompt.isNotBlank()) TextPrimary else TextDisabled)
                    Spacer(Modifier.width(8.dp))
                    Text("Edit image",
                        color = if (prompt.isNotBlank()) TextPrimary else TextDisabled)
                }
            }
            Text(
                "Uses fal.ai FLUX Kontext — needs a fal.ai key in Settings (free starting " +
                    "credits). The free generators can't edit an uploaded image.",
                style = MaterialTheme.typography.labelSmall, color = TextDisabled,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
