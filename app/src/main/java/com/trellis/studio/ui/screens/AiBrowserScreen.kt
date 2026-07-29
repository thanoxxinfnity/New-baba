package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.App
import com.trellis.studio.data.ChatHistoryEntry
import com.trellis.studio.data.ChatStreamEvent
import com.trellis.studio.data.toUserMessage
import com.trellis.studio.ui.theme.NimOrange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BrowserMessage(val role: String, val text: String)

private const val BROWSER_SYSTEM_PROMPT = """You are an AI web browsing assistant. You help users browse the internet.

When the user asks you to search or navigate, respond with ONE of these exact commands on its own line:
  [NAVIGATE:https://full-url-here]
  [SEARCH:search query here]
  [SCROLL:down]
  [SCROLL:up]
  [READ_PAGE]

After the command (or if no command is needed), respond naturally to the user.
When page content is shared with you, summarize and analyze it helpfully.
For searches, use DuckDuckGo: [NAVIGATE:https://duckduckgo.com/?q=search+term]"""

class AiBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _currentUrl = MutableStateFlow("https://duckduckgo.com")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _pageTitle = MutableStateFlow("New Tab")
    val pageTitle: StateFlow<String> = _pageTitle

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _messages = MutableStateFlow<List<BrowserMessage>>(emptyList())
    val messages: StateFlow<List<BrowserMessage>> = _messages

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _navigateCommand = MutableStateFlow<String?>(null)
    val navigateCommand: StateFlow<String?> = _navigateCommand

    private val _scrollCommand = MutableStateFlow<String?>(null)
    val scrollCommand: StateFlow<String?> = _scrollCommand

    private var pageText: String = ""
    private var pageUrl: String = "https://duckduckgo.com"

    fun onPageStarted(url: String) {
        _currentUrl.value = url
        pageUrl = url
        _isLoading.value = true
    }

    fun onPageFinished(url: String, title: String) {
        _currentUrl.value = url
        pageUrl = url
        _pageTitle.value = title
        _isLoading.value = false
    }

    fun onPageTextReceived(text: String) {
        pageText = text
    }

    fun clearNavigateCommand() { _navigateCommand.value = null }
    fun clearScrollCommand()   { _scrollCommand.value = null }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isSending.value) return
        val updatedMessages = _messages.value + BrowserMessage("user", userText)
        _messages.value = updatedMessages
        _isSending.value = true

        viewModelScope.launch {
            val history = mutableListOf(
                ChatHistoryEntry("system", BROWSER_SYSTEM_PROMPT)
            )
            updatedMessages.forEach { m ->
                history.add(ChatHistoryEntry(if (m.role == "user") "user" else "assistant", m.text))
            }

            val sb = StringBuilder()
            var error: String? = null

            container.chatRepository.streamChat(
                "meta/llama-3.3-70b-instruct",
                history
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.ContentDelta -> sb.append(event.text)
                    is ChatStreamEvent.Error        -> error = event.message
                    else                            -> Unit
                }
            }

            val reply = if (error != null && sb.isEmpty()) {
                "Sorry, I encountered an error: $error"
            } else {
                sb.toString()
            }

            // Parse commands from the AI reply
            val commandRegex = Regex("""\[NAVIGATE:(https?://[^\]]+)\]""")
            val searchRegex  = Regex("""\[SEARCH:([^\]]+)\]""")
            val scrollRegex  = Regex("""\[SCROLL:(up|down)\]""")
            val readRegex    = Regex("""\[READ_PAGE\]""")

            commandRegex.find(reply)?.let { _navigateCommand.value = it.groupValues[1] }
            searchRegex.find(reply)?.let {
                val q = it.groupValues[1].trim().replace(' ', '+')
                _navigateCommand.value = "https://duckduckgo.com/?q=$q"
            }
            scrollRegex.find(reply)?.let { _scrollCommand.value = it.groupValues[1] }

            val visibleReply = reply
                .replace(commandRegex, "")
                .replace(searchRegex, "")
                .replace(scrollRegex, "")
                .replace(readRegex, "")
                .trim()

            val finalReply = if (readRegex.containsMatchIn(reply) && pageText.isNotBlank()) {
                val pageSnippet = pageText.take(1500)
                val readResponse = StringBuilder()
                container.chatRepository.streamChat(
                    "meta/llama-3.3-70b-instruct",
                    history + listOf(
                        ChatHistoryEntry("assistant", reply),
                        ChatHistoryEntry("user", "Page content:\n$pageSnippet\n\nPlease summarize the key information on this page.")
                    )
                ).collect { event ->
                    if (event is ChatStreamEvent.ContentDelta) readResponse.append(event.text)
                }
                readResponse.toString().ifBlank { visibleReply }
            } else {
                visibleReply.ifBlank { "(Navigating…)" }
            }

            _messages.value = _messages.value + BrowserMessage("ai", finalReply)
            _isSending.value = false
        }
    }

    fun addPageContextMessage() {
        if (pageText.isBlank()) return
        val ctx = "Current page: $pageUrl\n\n${pageText.take(800)}"
        _messages.value = _messages.value + BrowserMessage("system_ctx", ctx)
    }
}

// ── JS Bridge ─────────────────────────────────────────────────────────────────

class BrowserBridge(private val onText: (String) -> Unit) {
    @JavascriptInterface
    fun receiveText(text: String) { onText(text) }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AiBrowserScreen(onBack: () -> Unit = {}, viewModel: AiBrowserViewModel = viewModel()) {
    val currentUrl   by viewModel.currentUrl.collectAsState()
    val pageTitle    by viewModel.pageTitle.collectAsState()
    val isLoading    by viewModel.isLoading.collectAsState()
    val messages     by viewModel.messages.collectAsState()
    val isSending    by viewModel.isSending.collectAsState()
    val navigateCmd  by viewModel.navigateCommand.collectAsState()
    val scrollCmd    by viewModel.scrollCommand.collectAsState()

    var urlBarText   by rememberSaveable { mutableStateOf("") }
    var chatInput    by remember { mutableStateOf("") }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    val listState    = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val scope        = rememberCoroutineScope()

    // Execute navigate commands
    LaunchedEffect(navigateCmd) {
        navigateCmd?.let { url ->
            webViewRef?.loadUrl(url)
            urlBarText = url
            viewModel.clearNavigateCommand()
        }
    }

    // Execute scroll commands
    LaunchedEffect(scrollCmd) {
        scrollCmd?.let { dir ->
            val js = if (dir == "down") "window.scrollBy(0, 600);" else "window.scrollBy(0, -600);"
            webViewRef?.evaluateJavascript(js, null)
            viewModel.clearScrollCommand()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {

        // ── Navigation bar ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick  = { webViewRef?.goBack() },
                modifier = Modifier.size(36.dp),
                enabled  = webViewRef?.canGoBack() == true
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Browser back",
                    modifier = Modifier.size(18.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick  = { webViewRef?.goForward() },
                modifier = Modifier.size(36.dp),
                enabled  = webViewRef?.canGoForward() == true
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward",
                    modifier = Modifier.size(18.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // URL bar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value         = urlBarText.ifEmpty { currentUrl },
                    onValueChange = { urlBarText = it },
                    modifier      = Modifier.fillMaxWidth(),
                    textStyle     = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush   = SolidColor(NimOrange),
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val raw = urlBarText.trim()
                        val url = if (raw.startsWith("http")) raw
                                  else if (raw.contains(".") && !raw.contains(" ")) "https://$raw"
                                  else "https://duckduckgo.com/?q=${raw.replace(' ', '+')}"
                        webViewRef?.loadUrl(url)
                        urlBarText = url
                        focusManager.clearFocus()
                    }),
                    decorationBox = { inner ->
                        if (urlBarText.isEmpty() && currentUrl.isEmpty()) {
                            Text("Search or enter URL…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                )
            }

            if (isLoading) {
                IconButton(onClick = { webViewRef?.stopLoading() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Stop", modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "Reload", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier    = Modifier.fillMaxWidth().height(2.dp),
                color       = NimOrange,
                strokeWidth = 2.dp
            )
        }

        // ── WebView (60% height) ──────────────────────────────────────────
        AndroidView(
            factory  = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled     = true
                    settings.domStorageEnabled      = true
                    settings.loadWithOverviewMode   = true
                    settings.useWideViewPort        = true
                    settings.builtInZoomControls    = true
                    settings.displayZoomControls    = false

                    addJavascriptInterface(
                        BrowserBridge { text -> viewModel.onPageTextReceived(text) },
                        "AndroidBridge"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            viewModel.onPageStarted(url)
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            return false
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            val title = view.title ?: url
                            viewModel.onPageFinished(url, title)
                            // Extract page text via JS
                            view.evaluateJavascript(
                                "(function(){ return document.body ? document.body.innerText.substring(0,3000) : ''; })()"
                            ) { result ->
                                val clean = result?.trim('"')?.replace("\\n", "\n")?.replace("\\t", " ") ?: ""
                                viewModel.onPageTextReceived(clean)
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {}
                    loadUrl("https://duckduckgo.com")
                    webViewRef = this
                }
            },
            update   = { wv -> webViewRef = wv },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
        )

        // ── AI Chat panel (40% height) ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Chat header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI Browser Agent",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = NimOrange,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    pageTitle.take(30),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // Messages
            LazyColumn(
                state          = listState,
                modifier       = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Ask me to search, navigate, or analyze the current page.\nExample: \"Search for Android 15 features\"",
                            style  = MaterialTheme.typography.bodySmall,
                            color  = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(messages.filter { it.role != "system_ctx" }) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) NimOrange.copy(alpha = 0.2f)
                                                 else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(
                                topStart = 12.dp, topEnd = 12.dp,
                                bottomStart = if (isUser) 12.dp else 2.dp,
                                bottomEnd   = if (isUser) 2.dp  else 12.dp
                            )
                        ) {
                            Text(
                                msg.text,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                                color    = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (isSending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp),
                                color = NimOrange, strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Read page button
                IconButton(
                    onClick  = {
                        scope.launch {
                            webViewRef?.evaluateJavascript(
                                "(function(){ return document.body ? document.body.innerText.substring(0,3000) : ''; })()"
                            ) { result ->
                                val clean = result?.trim('"')?.replace("\\n", "\n") ?: ""
                                viewModel.onPageTextReceived(clean)
                                viewModel.sendMessage("Read and summarize this page for me.")
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Search, "Read page",
                        modifier = Modifier.size(16.dp),
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value         = chatInput,
                        onValueChange = { chatInput = it },
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush   = SolidColor(NimOrange),
                        maxLines      = 3,
                        decorationBox = { inner ->
                            if (chatInput.isEmpty()) {
                                Text("Ask AI to search, navigate…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    )
                }

                val canSend = chatInput.isNotBlank() && !isSending
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (canSend) NimOrange else MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (canSend) Modifier.let { it } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp),
                            color = Color.White, strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick  = { viewModel.sendMessage(chatInput); chatInput = "" },
                            enabled  = canSend,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send",
                                modifier = Modifier.size(14.dp),
                                tint     = if (canSend) Color.White
                                           else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
