package com.trellis.studio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.trellis.studio.data.model.NIM_IMAGE_MODELS
import com.trellis.studio.ui.components.*
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.ImageUtils
import kotlinx.coroutines.launch
import com.trellis.studio.network.TrellisClient
import com.trellis.studio.viewmodel.ModelJob
import com.trellis.studio.viewmodel.GenerateViewModel
import java.io.File

@Composable
fun GenerateScreen(
    vm: GenerateViewModel = viewModel(),
    onMenu: () -> Unit = {},
    onOpenModel: (path: String, name: String) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Image", "3D Model")

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Header
        Surface(color = SurfDark) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MenuButton(onMenu)
                    Text(
                        "Create",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                }
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SurfDark,
                    contentColor = Purple60,
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i; vm.clearError() },
                            text = { Text(title, color = if (selectedTab == i) Purple60 else TextSecondary) },
                        )
                    }
                }
            }
        }

        // Error banner
        state.error?.let { err ->
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Red, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::clearError, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> ImageGenerateTab(state = state, vm = vm)
            1 -> ThreeDGenerateTab(state = state, vm = vm, onOpenModel = onOpenModel)
        }
    }
}

@Composable
private fun ImageGenerateTab(
    state: com.trellis.studio.viewmodel.GenerateUiState,
    vm: GenerateViewModel,
) {
    val context = LocalContext.current
    var showModelSelector by remember { mutableStateOf(false) }
    val currentModel = NIM_IMAGE_MODELS.find { it.id == state.selectedImageModelId }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Model selector
        Card(
            modifier = Modifier.fillMaxWidth().clickable { showModelSelector = true },
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Amber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Image Model", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(currentModel?.displayName ?: state.selectedImageModelId, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                }
                Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
            }
        }

        // Prompt input
        OutlinedTextField(
            value = state.imagePrompt,
            onValueChange = vm::setImagePrompt,
            label = { Text("Prompt", color = TextSecondary) },
            placeholder = { Text("Describe what you want to create…", color = TextDisabled) },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                focusedLabelColor = Purple60,
            ),
            shape = RoundedCornerShape(14.dp),
            maxLines = 6,
        )

        // Negative prompt
        OutlinedTextField(
            value = state.negativePrompt,
            onValueChange = vm::setNegativePrompt,
            label = { Text("Negative Prompt (optional)", color = TextSecondary) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                focusedLabelColor = Purple60,
            ),
            shape = RoundedCornerShape(14.dp),
            maxLines = 2,
        )

        // Size picker row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sizes = listOf(512 to "512", 768 to "768", 1024 to "1024")
            Text("Width:", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
            sizes.forEach { (size, label) ->
                FilterChip(
                    selected = state.imageWidth == size,
                    onClick = { vm.setImageWidth(size) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40,
                        selectedLabelColor = TextPrimary,
                        containerColor = CardDark,
                        labelColor = TextSecondary,
                    ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sizes = listOf(512 to "512", 768 to "768", 1024 to "1024")
            Text("Height:", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
            sizes.forEach { (size, label) ->
                FilterChip(
                    selected = state.imageHeight == size,
                    onClick = { vm.setImageHeight(size) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40,
                        selectedLabelColor = TextPrimary,
                        containerColor = CardDark,
                        labelColor = TextSecondary,
                    ),
                )
            }
        }

        // Generate button
        Button(
            onClick = vm::generateImage,
            enabled = !state.isGeneratingImage && state.imagePrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = BorderDark),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isGeneratingImage) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.statusMessage ?: "Generating…", color = TextPrimary)
            } else {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate Image", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Generated image result
        state.generatedImagePath?.let { path ->
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generated!", style = MaterialTheme.typography.labelMedium, color = Teal)
                    }
                    AsyncImage(
                        model = path, contentDescription = "Generated image",
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).aspectRatio(1f),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }

    if (showModelSelector) {
        ImageModelSelector(
            currentModelId = state.selectedImageModelId,
            onSelect = { vm.selectImageModel(it.id) },
            onDismiss = { showModelSelector = false },
        )
    }
}

@Composable
private fun ThreeDGenerateTab(
    state: com.trellis.studio.viewmodel.GenerateUiState,
    vm: GenerateViewModel,
    onOpenModel: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                ImageUtils.prepareForUpload(context, it, maxDimension = 1024)
                    .onSuccess { file -> vm.setSourceImagePath(file.absolutePath) }
                    .onFailure { e -> vm.showError(e.message ?: "Could not read that image") }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Info card
        Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null, tint = Teal, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Image → 3D Model", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Turn a single image into a textured 3D model using NVIDIA TRELLIS", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        // ---- Text -> 3D: the path that accepts user input on this account ----
        Text("Text to 3D", style = MaterialTheme.typography.labelMedium, color = Cyan)
        OutlinedTextField(
            value = state.text3dPrompt,
            onValueChange = vm::setTextTo3dPrompt,
            label = { Text("One object per line", color = TextSecondary) },
            placeholder = { Text("DOG\nCAR\nSpace Ship", color = TextDisabled) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = Cyan, unfocusedBorderColor = BorderDark,
                focusedContainerColor = CardDark, unfocusedContainerColor = CardDark,
                focusedLabelColor = Cyan,
            ),
            shape = RoundedCornerShape(14.dp),
            maxLines = 6,
        )
        // Mesh density
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrellisClient.Detail.entries.forEach { d ->
                FilterChip(
                    selected = state.detail == d,
                    onClick = { vm.setDetail(d) },
                    label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple40,
                        selectedLabelColor = TextPrimary,
                        containerColor = CardDark,
                        labelColor = TextSecondary,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val queued = state.text3dPrompt.split("\n").count { it.isBlank().not() }
        Button(
            onClick = vm::generate3dFromText,
            enabled = state.text3dPrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, disabledContainerColor = BorderDark),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(17.dp), tint = BgDark)
            Spacer(Modifier.width(8.dp))
            Text(
                if (queued > 1) "Queue $queued models" else "Generate 3D",
                style = MaterialTheme.typography.titleMedium,
                color = BgDark,
            )
        }

        // ---- Live queue ---------------------------------------------------
        val jobs by vm.queue.jobs.collectAsStateWithLifecycle()
        if (jobs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Queue",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.queue.clearFinished() }) {
                    Text("Clear done", color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                }
            }
            jobs.forEach { job ->
                val tint = when (job.status) {
                    ModelJob.Status.DONE -> Teal
                    ModelJob.Status.FAILED -> Red
                    ModelJob.Status.RUNNING -> Cyan
                    else -> TextDisabled
                }
                Row(
                    Modifier.fillMaxWidth()
                        .glass(
                            shape = RoundedCornerShape(14.dp),
                            glow = if (job.status == ModelJob.Status.RUNNING) Cyan else null,
                        )
                        .clickable(enabled = job.modelPath != null) {
                            job.modelPath?.let { onOpenModel(it, job.prompt) }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (job.status) {
                        ModelJob.Status.RUNNING ->
                            CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                        ModelJob.Status.DONE ->
                            Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(18.dp))
                        ModelJob.Status.FAILED ->
                            Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(18.dp))
                        else ->
                            Icon(Icons.Default.Schedule, null, tint = TextDisabled, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(job.prompt, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 1)
                        Text(job.progressLabel, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 2)
                    }
                    if (job.status == ModelJob.Status.DONE) {
                        Icon(Icons.Default.ChevronRight, null, tint = Teal, modifier = Modifier.size(18.dp))
                    }
                    if (job.status == ModelJob.Status.FAILED) {
                        TextButton(onClick = { vm.queue.retry(job.id) }) {
                            Text("Retry", color = Cyan, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

        Text(
            "Image to 3D",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Text(
            "NVIDIA's endpoint rejects uploaded photos on this account — it only " +
                "accepts its own sample. Text to 3D above works with anything you type.",
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
        )

        // Backend selector
        Text("3D Backend", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        val backends = listOf("nvidia" to "NVIDIA TRELLIS", "fal" to "fal.ai TRELLIS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            backends.forEach { (id, label) ->
                FilterChip(
                    selected = state.selected3dBackend == id,
                    onClick = { vm.select3dBackend(id) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Teal.copy(alpha = 0.3f),
                        selectedLabelColor = Teal,
                        containerColor = CardDark,
                        labelColor = TextSecondary,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Image picker
        Box(
            Modifier.fillMaxWidth().height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark)
                .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                .clickable { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center,
        ) {
            if (state.sourceImagePath != null) {
                AsyncImage(
                    model = state.sourceImagePath, contentDescription = "Source image",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(
                        onClick = { vm.setSourceImagePath(null) },
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(SurfDark.copy(alpha = 0.8f)),
                    ) { Icon(Icons.Default.Close, null, tint = TextPrimary, modifier = Modifier.size(16.dp)) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                    Text("Tap to select image", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Generate 3D button
        Button(
            onClick = vm::generate3d,
            enabled = !state.isGenerating3d && state.sourceImagePath != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, disabledContainerColor = BorderDark),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isGenerating3d) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.statusMessage ?: "Generating 3D model…", color = TextPrimary)
            } else {
                Icon(Icons.Default.ViewInAr, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate 3D", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Sample generation — the input NVIDIA's endpoint actually accepts.
        OutlinedButton(
            onClick = vm::generateSample3d,
            enabled = !state.isGenerating3d,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
            border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.Default.Science, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Generate sample 3D (always works)")
        }
        Text(
            "NVIDIA's TRELLIS endpoint currently accepts only its own sample image on " +
                "this account, so uploaded photos may be rejected. The sample returns a " +
                "real textured model you can view in 360°.",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled,
        )

        // Result
        state.generatedModelPath?.let { path ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Teal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("3D Model Ready", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text("Saved to Gallery", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                    Button(
                        onClick = { onOpenModel(path, File(path).name) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.RotateRight, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View in 360°", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
