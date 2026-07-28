package com.trellis.studio.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TERMINAL_URL    = "https://zeppelin-untold-hazily.ngrok-free.dev"
private const val TERM_USER       = "admin"
private const val TERM_PASS       = "123456"

/**
 * Full-screen embedded Linux terminal via WebView.
 * Auto-fills the ngrok-hosted terminal's login form with the configured credentials.
 * A custom header bypasses ngrok's browser-warning interstitial page.
 */
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF161B22)
            )
        )

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
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
                    databaseEnabled = true
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
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }

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

                // Add ngrok bypass header + load the terminal
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
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    nativeInputValueSetter.call(userField, '$TERM_USER');
                    userField.dispatchEvent(new Event('input', { bubbles: true }));
                    nativeInputValueSetter.call(passField, '$TERM_PASS');
                    passField.dispatchEvent(new Event('input', { bubbles: true }));
                    var form = userField.closest('form') || passField.closest('form');
                    var btn = document.querySelector('button[type=submit], input[type=submit], button.login, .btn-login, #login-btn');
                    if (btn) { btn.click(); return; }
                    if (form) { form.submit(); return; }
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
