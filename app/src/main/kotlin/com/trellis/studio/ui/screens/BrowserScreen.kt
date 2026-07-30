package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.trellis.studio.ui.components.copyToClipboard
import com.trellis.studio.ui.theme.*

private const val HOME = "https://www.google.com"

/** In-app browser with real Google search. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    var urlField by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(HOME) }
    var pageTitle by remember { mutableStateOf("Google") }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    /** Treats input as a URL if it looks like one, otherwise searches Google. */
    fun navigate(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        val looksLikeUrl = q.startsWith("http://") || q.startsWith("https://") ||
            (!q.contains(' ') && q.contains('.') && !q.endsWith("."))
        val target = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            looksLikeUrl -> "https://$q"
            else -> "$HOME/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        }
        loadError = null
        webView?.loadUrl(target)
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // Address bar
        Surface(color = SurfDark) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { webView?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = if (canGoBack) TextPrimary else TextDisabled,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { webView?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, "Forward",
                            tint = if (canGoForward) TextPrimary else TextDisabled,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    OutlinedTextField(
                        value = urlField,
                        onValueChange = { urlField = it },
                        placeholder = {
                            Text("Search Google or type a URL", color = TextDisabled, maxLines = 1)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Go,
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onGo = { navigate(urlField) }),
                        leadingIcon = {
                            Icon(
                                if (currentUrl.startsWith("https")) Icons.Default.Lock else Icons.Default.Public,
                                null,
                                tint = if (currentUrl.startsWith("https")) Teal else TextSecondary,
                                modifier = Modifier.size(15.dp),
                            )
                        },
                        trailingIcon = {
                            if (urlField.isNotEmpty()) {
                                IconButton(onClick = { urlField = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, "Clear", tint = TextSecondary, modifier = Modifier.size(15.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Purple60,
                            unfocusedBorderColor = BorderDark,
                            focusedContainerColor = CardDark,
                            unfocusedContainerColor = CardDark,
                        ),
                        shape = RoundedCornerShape(22.dp),
                    )

                    IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, "Reload", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Purple60,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            // Desktop-class UA gets fewer "open in app" interstitials.
                            userAgentString = userAgentString.replace("; wv", "")
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?, request: WebResourceRequest?,
                            ): Boolean = false  // keep navigation inside the app

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                progress = 5
                                url?.let { currentUrl = it; urlField = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                pageTitle = view?.title ?: ""
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                url?.let { currentUrl = it; urlField = it }
                            }

                            override fun onReceivedError(
                                view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loadError = "Could not load the page. Check your connection."
                                    progress = 100
                                }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(HOME)
                        webView = this
                    }
                },
            )

            loadError?.let {
                Card(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Red, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { loadError = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Bottom actions
        Surface(color = SurfDark) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { webView?.loadUrl(HOME) }) {
                    Icon(Icons.Default.Home, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Home", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { copyToClipboard(context, currentUrl) }) {
                    Icon(Icons.Default.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Copy URL", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(i) }
                }) {
                    Icon(Icons.Default.OpenInBrowser, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Open", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
