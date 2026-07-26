package com.trellis.studio.ui.screens

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.DownloadHelper
import com.trellis.studio.data.toUserMessage
import kotlinx.coroutines.launch

class CanvasViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as App).container

    fun consumePendingContent(): Pair<String, String>? {
        val content = container.pendingCanvasContent
        container.pendingCanvasContent = null
        return content
    }

    fun saveToDownloads(language: String, code: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val ext = languageToExtension(language)
            val fileName = "snippet_${System.currentTimeMillis()}$ext"
            val result = runCatching { DownloadHelper.saveTextToDownloads(getApplication(), fileName, code) }
            onResult(result)
        }
    }

    private fun languageToExtension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> ".kt"
        "java" -> ".java"
        "python", "py" -> ".py"
        "javascript", "js" -> ".js"
        "typescript", "ts" -> ".ts"
        "html" -> ".html"
        "css" -> ".css"
        "json" -> ".json"
        "xml" -> ".xml"
        "bash", "sh", "shell" -> ".sh"
        "c" -> ".c"
        "cpp", "c++" -> ".cpp"
        "swift" -> ".swift"
        "go" -> ".go"
        "rust", "rs" -> ".rs"
        "sql" -> ".sql"
        else -> ".txt"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(onBack: () -> Unit, viewModel: CanvasViewModel = viewModel()) {
    var language by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.consumePendingContent()?.let { (lang, content) ->
            language = lang
            code = content
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (language.isNotBlank()) "Canvas · $language" else "Canvas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        viewModel.saveToDownloads(language, code) { result ->
                            scope.launch {
                                result.onSuccess { snackbarHostState.showSnackbar("Saved to $it") }
                                    .onFailure { snackbarHostState.showSnackbar(it.toUserMessage()) }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
