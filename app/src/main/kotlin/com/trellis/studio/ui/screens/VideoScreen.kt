package com.trellis.studio.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.VideoViewModel
import java.io.File

/**
 * Free video from a text idea: the AI plans scenes, each scene's image is drawn,
 * and they are stitched into a real .mp4 with motion and captions. Nothing plays
 * until the whole file is done.
 */
@Composable
fun VideoScreen(
    vm: VideoViewModel = viewModel(),
    onMenu: () -> Unit = {},
) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val built by vm.built.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var idea by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("Anime") }
    var seconds by remember { mutableFloatStateOf(20f) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val busy = status != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Video Studio",
                        style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold)
                    Text("Text → a real video, scene by scene",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Icon(Icons.Default.Movie, null, tint = Pink, modifier = Modifier.size(22.dp))
            }

            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                built?.let { (path, title) ->
                    ResultCard(title = title, exists = File(path).exists(),
                        onPlay = { FileExport.share(context, File(path), "video/mp4") },
                        onDismiss = { vm.consumeBuilt() })
                }

                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().glass(glow = Cyan).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(status.orEmpty(), color = Cyan, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Text("1 · Describe the video", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                OutlinedTextField(
                    value = idea, onValueChange = { idea = it }, enabled = !busy,
                    placeholder = { Text("a lonely robot in a rainy city finds a flower and smiles",
                        color = TextDisabled, style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Cyan),
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                )

                Text("2 · Style", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.styles.forEach { s ->
                        FilterChip(
                            selected = style == s,
                            onClick = { style = s },
                            enabled = !busy,
                            label = { Text(s, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple40,
                                selectedLabelColor = TextPrimary,
                            ),
                        )
                    }
                }

                Text("3 · Length — ~${seconds.toInt()}s (${(seconds / 3.5f).toInt()} scenes)",
                    style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Slider(
                    value = seconds, onValueChange = { seconds = it },
                    valueRange = 10f..120f, enabled = !busy,
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan),
                )
                Text(
                    "Longer means more scenes to draw, so it takes longer. Each scene is a " +
                        "free AI image; the video is stitched on your phone.",
                    color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                )

                Button(
                    onClick = { vm.create(idea, style, seconds.toInt()) },
                    enabled = !busy && idea.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Movie, null, modifier = Modifier.size(18.dp),
                        tint = if (!busy && idea.isNotBlank()) TextPrimary else TextDisabled)
                    Spacer(Modifier.width(8.dp))
                    Text("Create video", color = if (!busy && idea.isNotBlank()) TextPrimary else TextDisabled)
                }

                Note()
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, exists: Boolean, onPlay: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(glow = Teal).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Video ready", color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Text(title, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, enabled = exists,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play / Share", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text("New", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun Note() {
    Column(
        Modifier.fillMaxWidth().glass(fill = GlassFill.copy(alpha = 0.5f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("How this works", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "This makes a real .mp4 for free: the AI writes a scene-by-scene story, " +
                "draws each scene as an image, and stitches them with a slow zoom and " +
                "captions. It is a moving picture-story — not AI-generated motion, " +
                "which only paid services do. Add a YouTube key in Settings and the " +
                "director takes style cues from popular videos.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}
