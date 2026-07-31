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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
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
) {
    val context = LocalContext.current
    val file = remember(imagePath) { File(imagePath) }

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
                        scale = (scale * zoom).coerceIn(1f, 6f)
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
                        String.format("%.1f×", scale),
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
}
