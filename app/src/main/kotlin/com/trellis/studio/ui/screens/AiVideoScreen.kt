package com.trellis.studio.ui.screens

import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.AiVideoViewModel
import java.io.File

@Composable
fun AiVideoScreen(vm: AiVideoViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.pickImage(it) }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("AI Video", "Text → video · Image → video (free)", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            OutlinedTextField(value = s.prompt, onValueChange = vm::setPrompt,
                label = { Text("Describe the video", color = TextSecondary) },
                placeholder = { Text("e.g. a car driving in the rain at night, cinematic", color = TextDisabled) },
                modifier = Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark))

            // Optional start image (image-to-video)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (s.imagePath != null) "Image ✓" else "Start image (optional)")
                }
                if (s.imagePath != null) TextButton(onClick = { vm.clearImage() }) { Text("Remove", color = Pink) }
            }
            Text(if (s.imagePath != null) "Mode: Image → Video" else "Mode: Text → Video",
                color = Cyan, style = MaterialTheme.typography.labelMedium)

            // Duration
            Text("Duration: ${s.durationSec}s", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Slider(value = s.durationSec.toFloat(), onValueChange = { vm.setDuration(it.toInt()) },
                valueRange = 1f..5f, steps = 3,
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = BorderDark))

            Button(onClick = { vm.generate() }, enabled = !s.busy && (s.prompt.isNotBlank() || s.imagePath != null),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (s.busy) { CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Generating…", color = TextPrimary) }
                else { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text("Generate video", fontWeight = FontWeight.Bold) }
            }
            s.status?.let { Text(it, color = Cyan, style = MaterialTheme.typography.labelSmall) }
            s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall) }

            s.videoPath?.let { path ->
                Box(Modifier.fillMaxWidth().height(260.dp).background(Color.Black, RoundedCornerShape(14.dp))) {
                    AndroidView(factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(path)
                            setOnPreparedListener { it.isLooping = true; start() }
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { FileExport.share(context, File(path), "video/mp4") },
                        colors = ButtonDefaults.buttonColors(containerColor = Teal), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, tint = BgDark); Spacer(Modifier.width(6.dp)); Text("Share", color = BgDark)
                    }
                }
            }

            Text("Free AI video runs on a shared GPU (Hugging Face). It's genuinely free but " +
                "can be briefly busy — needs your HF token in Settings. Short clips generate in " +
                "seconds; long ones aren't supported by the free model.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
