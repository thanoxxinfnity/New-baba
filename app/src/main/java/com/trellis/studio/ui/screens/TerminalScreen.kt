package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TERMINAL_URL = "https://zeppelin-untold-hazily.ngrok-free.dev"
private const val TERM_USER    = "admin"
private const val TERM_PASS    = "123456"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    var loading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Linux Terminal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        TERMINAL_URL,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload terminal")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
        )

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Quick-action toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TerminalActionChip(
                icon = Icons.Default.Android,
                label = "Build APK",
                onClick = {
                    webViewRef?.injectCommand("./gradlew assembleDebug 2>&1 | tail -30")
                }
            )
            TerminalActionChip(
                icon = Icons.Default.FolderZip,
                label = "ZIP Project",
                onClick = {
                    webViewRef?.injectCommand("zip -r project_\$(date +%Y%m%d_%H%M%S).zip . -x '*.git*' -x 'build/*' -x '.gradle/*'")
                }
            )
            TerminalActionChip(
                icon = Icons.Default.SportsEsports,
                label = "Build Game",
                onClick = {
                    webViewRef?.injectCommand("ls -la *.html 2>/dev/null || echo 'No HTML game files found. Ask the AI to generate a game first.'")
                }
            )
            TerminalActionChip(
                icon = Icons.Default.Folder,
                label = "List Files",
                onClick = { webViewRef?.injectCommand("ls -la") }
            )
        }

        Box(Modifier.weight(1f)) {
            TerminalWebView(
                onLoadingChanged = { loading = it },
                onWebViewCreated = { webViewRef = it }
            )
        }
    }
}

@Composable
private fun TerminalActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(
    onLoadingChanged: (Boolean) -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                }

                webChromeClient = WebChromeClient()

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChanged(false)
                        injectAutoLogin(view)
                    }
                }

                loadUrl(
                    TERMINAL_URL,
                    mapOf(
                        "ngrok-skip-browser-warning" to "true",
                        "User-Agent" to "Mozilla/5.0 NIMAgent"
                    )
                )
                onWebViewCreated(this)
            }
        }
    )
}

private fun WebView.injectCommand(cmd: String) {
    val escaped = cmd.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
    val js = """
        (function() {
            var cmd = '$escaped\n';
            // ttyd / xterm.js: try window.term first
            if (window.term && window.term.paste) {
                window.term.paste(cmd);
                return;
            }
            // Try xterm textarea paste event
            var textarea = document.querySelector('.xterm-helper-textarea');
            if (textarea) {
                textarea.focus();
                try {
                    var dt = new DataTransfer();
                    dt.setData('text/plain', cmd);
                    textarea.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true }));
                    return;
                } catch(e) {}
            }
            // Fallback: send via WebSocket if ttyd exposes it
            if (window.ws && window.ws.readyState === 1) {
                window.ws.send('0' + cmd);
            }
        })();
    """.trimIndent()
    evaluateJavascript(js, null)
}

private fun injectAutoLogin(view: WebView?) {
    val js = """
        (function() {
            var attempts = 0;
            function tryLogin() {
                attempts++;
                var inputs = document.querySelectorAll('input');
                var userField = null;
                var passField = null;
                for (var i = 0; i < inputs.length; i++) {
                    var inp = inputs[i];
                    var t = (inp.type || '').toLowerCase();
                    var n = (inp.name || inp.id || inp.placeholder || '').toLowerCase();
                    if (t === 'password') { passField = inp; }
                    else if (t === 'text' || t === 'email' || n.includes('user') || n.includes('name') || n.includes('login')) {
                        userField = inp;
                    }
                }
                if (userField && passField) {
                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(userField, '$TERM_USER');
                    userField.dispatchEvent(new Event('input', { bubbles: true }));
                    setter.call(passField, '$TERM_PASS');
                    passField.dispatchEvent(new Event('input', { bubbles: true }));
                    var btn = document.querySelector('button[type=submit], input[type=submit]');
                    var form = userField.closest('form') || passField.closest('form');
                    if (btn) { btn.click(); } else if (form) { form.submit(); }
                } else if (attempts < 10) {
                    setTimeout(tryLogin, 500);
                }
            }
            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                tryLogin();
            } else {
                document.addEventListener('DOMContentLoaded', tryLogin);
            }
        })();
    """.trimIndent()
    view?.evaluateJavascript(js, null)
}
