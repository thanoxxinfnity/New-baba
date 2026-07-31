package com.trellis.studio.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Client for a ttyd web terminal (https://github.com/tsl0922/ttyd), the kind
 * `ttyd bash` + `ngrok http` exposes.
 *
 * ttyd is a raw TTY over WebSocket rather than a REST API, so:
 *  - the socket negotiates the "tty" subprotocol
 *  - the first frame is a JSON handshake
 *  - client frames are '0' + input bytes
 *  - server frames are '0' + output bytes, '1' title, '2' preferences
 *
 * A TTY has no notion of "command finished", so [run] appends a sentinel echo
 * and waits for it to come back. Verified against a live ttyd 1.7.4 session.
 */
class TtydClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived socket
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Turns an https:// page URL into the wss:// endpoint ttyd listens on. */
    private fun socketUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://")
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://")
            else -> "wss://$trimmed"
        }
        return if (ws.endsWith("/ws")) ws else "$ws/ws"
    }

    /**
     * Runs [command] and returns everything it printed.
     *
     * @param timeoutMs how long to wait for the sentinel before giving up
     * @param onOutput streams output as it arrives, for live terminal display
     */
    suspend fun run(
        baseUrl: String,
        command: String,
        timeoutMs: Long = 120_000,
        onOutput: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(Exception("No terminal URL set. Add it in Settings."))
        }

        val sentinel = "__TRELLIS_${System.nanoTime()}__"
        val done = CompletableDeferred<String>()
        val buffer = StringBuilder()

        val request = Request.Builder()
            .url(socketUrl(baseUrl))
            .header("Sec-WebSocket-Protocol", "tty")
            .header("ngrok-skip-browser-warning", "true")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // ttyd expects the auth/geometry handshake before any input.
                webSocket.send("""{"AuthToken":"","columns":220,"rows":50}""")
                // stty -echo keeps the command itself out of the captured output.
                webSocket.send("0stty -echo 2>/dev/null; $command; echo $sentinel\$?\r")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text, webSocket)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handle(bytes.utf8(), webSocket)

            private fun handle(frame: String, webSocket: WebSocket) {
                if (frame.isEmpty()) return
                // '0' = output; other opcodes are title/prefs and can be ignored.
                if (frame[0] != '0') return
                val chunk = frame.substring(1)
                buffer.append(chunk)
                onOutput(chunk)

                val marker = buffer.indexOf(sentinel)
                if (marker >= 0) {
                    val body = buffer.substring(0, marker)
                    webSocket.close(1000, null)
                    done.complete(clean(body))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done.isCompleted) {
                    done.completeExceptionally(
                        Exception(describe(t, response))
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(clean(buffer.toString()))
            }
        }

        val socket = client.newWebSocket(request, listener)
        val output = withTimeoutOrNull(timeoutMs) {
            runCatching { done.await() }
        }
        socket.cancel()

        when {
            output == null -> Result.failure(
                Exception("Command timed out after ${timeoutMs / 1000}s on the remote terminal.")
            )
            output.isFailure -> Result.failure(output.exceptionOrNull() ?: Exception("Terminal error"))
            else -> Result.success(output.getOrDefault(""))
        }
    }

    /** Quick reachability + toolchain probe. */
    suspend fun probe(baseUrl: String): Result<RemoteToolchain> {
        val script = "echo J:\$(java -version 2>&1|head -1); " +
            "echo S:\$ANDROID_HOME; " +
            "echo G:\$(command -v gradle); " +
            "echo H:\$(uname -o)"
        return run(baseUrl, script, timeoutMs = 45_000).map { out ->
            RemoteToolchain(
                java = out.lineValue("J:").isNotBlank(),
                javaVersion = out.lineValue("J:"),
                sdkPath = out.lineValue("S:"),
                gradlePath = out.lineValue("G:"),
                os = out.lineValue("H:"),
            )
        }
    }

    private fun String.lineValue(prefix: String): String =
        lines().firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)?.trim().orEmpty()

    /** Strips ANSI escapes and bracketed-paste markers a TTY interleaves. */
    private fun clean(raw: String): String = raw
        .replace(ANSI, "")
        .replace("\u001B]0;", "")
        .replace("\u0007", "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .filterNot { it.contains("bracketed") || it.trim() == "stty -echo" }
        .joinToString("\n")
        .trim()

    private fun describe(t: Throwable, response: Response?): String {
        val ngrok = response?.header("ngrok-error-code")
        return when {
            ngrok == "ERR_NGROK_3200" ->
                "Terminal is offline. Start ttyd and ngrok on your machine, then retry."
            ngrok == "ERR_NGROK_8012" ->
                "Tunnel is up but ttyd isn't listening on that port."
            response?.code == 403 || response?.code == 401 ->
                "Terminal rejected the connection — it may require a login."
            t is java.net.UnknownHostException -> "Cannot reach the terminal URL."
            else -> t.message ?: "Terminal connection failed."
        }
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
    }
}

data class RemoteToolchain(
    val java: Boolean = false,
    val javaVersion: String = "",
    val sdkPath: String = "",
    val gradlePath: String = "",
    val os: String = "",
) {
    val canBuildApk: Boolean get() = java && sdkPath.isNotBlank() && gradlePath.isNotBlank()
}
