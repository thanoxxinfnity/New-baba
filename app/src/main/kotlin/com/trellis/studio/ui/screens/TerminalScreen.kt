package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trellis.studio.terminal.ShellResult
import com.trellis.studio.terminal.ShellSession
import com.trellis.studio.ui.components.copyToClipboard
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import kotlinx.coroutines.launch

private data class TermLine(val prompt: String?, val text: String, val exitCode: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shell = remember { ShellSession(context) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<TermLine>() }
    val commandLog = remember { mutableStateListOf<String>() }
    var logIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        history += TermLine(null, BANNER)
    }
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) runCatching { listState.animateScrollToItem(history.lastIndex) }
    }

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        logIndex = -1
        if (cmd == "clear") {
            history.clear()
            return
        }
        commandLog += cmd
        val shownPrompt = shell.prompt()
        scope.launch {
            running = true
            history += TermLine(shownPrompt, cmd)
            val result: ShellResult = shell.run(cmd)
            if (result.output.isNotBlank()) {
                history += TermLine(null, result.output, result.exitCode)
            }
            running = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = TermBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            shell.prompt(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TermGreen,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = history.joinToString("\n") {
                            if (it.prompt != null) "${it.prompt} $ ${it.text}" else it.text
                        }
                        copyToClipboard(context, text)
                        scope.launch { snackbar.showSnackbar("Session copied") }
                    }) { Icon(Icons.Default.ContentCopy, "Copy session", tint = TextSecondary) }

                    IconButton(onClick = {
                        scope.launch {
                            runCatching {
                                FileExport.zipDirectory(context, shell.root, "workspace.zip")
                            }.onSuccess {
                                FileExport.share(context, it, "application/zip")
                            }.onFailure {
                                snackbar.showSnackbar(it.message ?: "Export failed")
                            }
                        }
                    }) { Icon(Icons.Default.FolderZip, "Export workspace", tint = TextSecondary) }

                    IconButton(onClick = { history.clear() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfDark),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(TermBg)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(history) { _, line ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        if (line.prompt != null) {
                            Text(
                                "${line.prompt} $ ",
                                color = TermGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                            )
                            Text(
                                line.text,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                softWrap = false,
                            )
                        } else {
                            Text(
                                line.text,
                                color = if (line.exitCode != 0) TermRed else TermDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (running) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp),
                                color = TermGreen,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "running…",
                                color = TermDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            // Quick commands
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ls -la", "pwd", "help", "df -h", "whoami", "ps", "date").forEach { q ->
                    SuggestionChip(
                        onClick = { input = q },
                        label = {
                            Text(q, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = CardDark,
                            labelColor = TermBlue,
                        ),
                        border = null,
                    )
                }
            }

            // Input line
            Surface(color = SurfDark, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // History up/down
                    Column {
                        IconButton(
                            onClick = {
                                if (commandLog.isNotEmpty()) {
                                    logIndex = if (logIndex < 0) commandLog.lastIndex
                                    else (logIndex - 1).coerceAtLeast(0)
                                    input = commandLog[logIndex]
                                }
                            },
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Default.KeyboardArrowUp, "Previous", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                        IconButton(
                            onClick = {
                                if (logIndex >= 0 && logIndex < commandLog.lastIndex) {
                                    logIndex++
                                    input = commandLog[logIndex]
                                } else {
                                    logIndex = -1
                                    input = ""
                                }
                            },
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Default.KeyboardArrowDown, "Next", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("$", color = TermGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    BasicTextFieldMono(
                        value = input,
                        onValueChange = { input = it },
                        onSubmit = ::submit,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::submit, enabled = input.isNotBlank() && !running) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            "Run",
                            tint = if (input.isNotBlank() && !running) TermGreen else TextDisabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicTextFieldMono(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        ),
        placeholder = {
            Text("type a command…", color = TextDisabled, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = CardDark,
            unfocusedContainerColor = CardDark,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = TermGreen,
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

private val BANNER = """
    Trellis Terminal
    Real shell, running as the app's own user inside its sandbox.
    Type "help" for what works and what Android does not allow.
""".trimIndent()
