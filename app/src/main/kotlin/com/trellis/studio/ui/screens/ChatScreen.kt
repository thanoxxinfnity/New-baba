package com.trellis.studio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.data.model.NIM_LLM_MODELS
import com.trellis.studio.ui.components.*
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
    }

    // Scroll to bottom when messages arrive or the typing indicator appears.
    LaunchedEffect(state.messages.size, state.isLoading) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) runCatching { listState.animateScrollToItem(last) }
    }

    val currentModel = NIM_LLM_MODELS.find { it.id == state.selectedModel }

    // Drop a staged image if the user switches to a model that cannot see it.
    LaunchedEffect(currentModel?.isVision) {
        if (currentModel?.isVision != true) selectedImageUri = null
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Top bar
        TopAppBar(
            title = {
                Column {
                    Text("Chat", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    if (currentModel != null) {
                        Text(currentModel.displayName, style = MaterialTheme.typography.bodySmall, color = Purple60)
                    }
                }
            },
            actions = {
                // Model selector button
                FilledTonalButton(
                    onClick = { showModelSelector = true },
                    modifier = Modifier.padding(end = 4.dp).height(34.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardDark),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Psychology, null, tint = Purple60, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(currentModel?.displayName?.take(12) ?: "Model", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                    Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                // History
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Default.History, "History", tint = TextSecondary)
                }
                // New chat
                IconButton(onClick = { vm.newSession(); inputText = ""; selectedImageUri = null }) {
                    Icon(Icons.Default.Add, "New Chat", tint = TextSecondary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfDark),
        )

        // Error snackbar
        state.error?.let { err ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Red, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.clearError() }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Messages
        if (state.messages.isEmpty() && !state.isLoading) {
            // weight(1f), not fillMaxSize(): an unweighted fillMaxSize child eats all
            // remaining height and pushes the input bar off the bottom of the screen.
            EmptyChatHint(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        role = msg.role,
                        content = msg.content,
                        reasoning = msg.reasoningContent,
                        imagePath = msg.imagePath,
                    )
                }
                if (state.isLoading) {
                    item { TypingIndicator() }
                }
            }
        }

        // Input area
        Surface(color = SurfDark, shadowElevation = 8.dp) {
            Column {
                // Selected image preview
                selectedImageUri?.let { uri ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        coil.compose.AsyncImage(
                            model = uri, contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Image attached", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { selectedImageUri = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    // Attach image (vision models)
                    if (currentModel?.isVision == true) {
                        IconButton(onClick = { imagePicker.launch("image/*") }) {
                            Icon(Icons.Default.AttachFile, "Attach Image", tint = TextSecondary)
                        }
                    }
                    // Text input
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message…", color = TextDisabled) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Purple60,
                            unfocusedBorderColor = BorderDark,
                            focusedContainerColor = CardDark,
                            unfocusedContainerColor = CardDark,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        maxLines = 5,
                    )
                    Spacer(Modifier.width(8.dp))
                    // Send button
                    val canSend = inputText.isNotBlank() && !state.isLoading
                    FloatingActionButton(
                        onClick = {
                            if (canSend) {
                                val imgPath = selectedImageUri?.let { uri ->
                                    val input: InputStream? = context.contentResolver.openInputStream(uri)
                                    val file = File(context.cacheDir, "chat_img_${System.currentTimeMillis()}.jpg")
                                    input?.use { ins -> file.outputStream().use { out -> ins.copyTo(out) } }
                                    file.absolutePath
                                }
                                vm.sendMessage(inputText, imgPath)
                                inputText = ""
                                selectedImageUri = null
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = if (canSend) Purple60 else BorderDark,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        elevation = FloatingActionButtonDefaults.elevation(0.dp),
                    ) {
                        Icon(if (state.isLoading) Icons.Default.HourglassEmpty else Icons.Default.Send, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    // Model selector sheet
    if (showModelSelector) {
        LlmModelSelector(
            currentModel = state.selectedModel,
            onSelect = { vm.selectModel(it.id) },
            onDismiss = { showModelSelector = false },
        )
    }

    // History drawer
    if (showHistory) {
        ChatHistorySheet(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            onSelect = { vm.openSession(it.id); showHistory = false },
            onDelete = { vm.deleteSession(it.id) },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.AutoAwesome, null, tint = Purple60, modifier = Modifier.size(48.dp))
            Text("Trellis Studio AI", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text("Select a model and start chatting", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistorySheet(
    sessions: List<com.trellis.studio.data.entity.ChatSessionEntity>,
    currentSessionId: Long?,
    onSelect: (com.trellis.studio.data.entity.ChatSessionEntity) -> Unit,
    onDelete: (com.trellis.studio.data.entity.ChatSessionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfDark) {
        Column(Modifier.fillMaxHeight(0.7f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Chat History", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = TextSecondary) }
            }
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No history yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        ListItem(
                            headlineContent = { Text(session.title, color = TextPrimary, maxLines = 1) },
                            supportingContent = { Text(session.model, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            leadingContent = {
                                Icon(Icons.Default.Chat, null,
                                    tint = if (session.id == currentSessionId) Purple60 else TextDisabled)
                            },
                            trailingContent = {
                                IconButton(onClick = { onDelete(session) }) {
                                    Icon(Icons.Default.DeleteOutline, null, tint = TextDisabled)
                                }
                            },
                            modifier = Modifier.clickable { onSelect(session) },
                            colors = ListItemDefaults.colors(containerColor = if (session.id == currentSessionId) CardDark else Color.Transparent),
                        )
                        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

