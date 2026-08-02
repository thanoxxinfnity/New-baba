package com.trellis.studio.network

import com.trellis.studio.service.AutomationService
import com.trellis.studio.service.FloatingOverlayService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the remote AI agent over a WebSocket and turns the JSON it sends
 * into device actions through [AutomationService].
 *
 * The agent speaks one message per command, e.g.
 *   {"type":"click","x":500,"y":1000}
 *   {"type":"type_text","text":"Hello"}
 *   {"type":"launch","package":"com.example.app"}
 * and every command is answered with a status message
 *   {"type":"status","id":<echoed>,"ok":true|false,"detail":"..."}
 * so the server knows whether the tap landed.
 *
 * Parsing is defensive on purpose: a command with a missing field or an unknown
 * type is reported back as a failure, never allowed to crash the socket. If the
 * connection drops it reconnects with a growing backoff until [stop] is called.
 */
class AiAgentListener(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val running = AtomicBoolean(false)
    private val reconnect = android.os.Handler(android.os.Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var attempt = 0

    /** Optional hook so the UI can react to connection state changes. */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null

    val isConnected: Boolean get() = socket != null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connect()
    }

    fun stop() {
        running.set(false)
        reconnect.removeCallbacksAndMessages(null)
        socket?.close(NORMAL_CLOSURE, "client stop")
        socket = null
        FloatingOverlayService.status(FloatingOverlayService.STATUS_IDLE)
    }

    private fun connect() {
        if (!running.get()) return
        FloatingOverlayService.log("Connecting to agent…")
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, Handler())
    }

    private inner class Handler : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            attempt = 0
            FloatingOverlayService.log("Agent connected.")
            FloatingOverlayService.status("Connected")
            onConnected?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text, webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            val reason = t.message ?: "connection failed"
            FloatingOverlayService.log("Agent error: $reason")
            FloatingOverlayService.status("Disconnected")
            onDisconnected?.invoke(reason)
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (running.get()) scheduleReconnect()
        }
    }

    /** Backs off up to a ceiling so a dead server is not hammered. */
    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = (BASE_BACKOFF_MS * (1L shl attempt.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)
        attempt++
        reconnect.postDelayed({ connect() }, delay)
    }

    // ------------------------------------------------------------- dispatch

    private fun handle(text: String, webSocket: WebSocket) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (obj == null) {
            reply(webSocket, null, false, "unparseable JSON")
            return
        }
        val id = obj["id"]?.jsonPrimitive?.content
        val type = obj["type"]?.jsonPrimitive?.content

        val service = AutomationService.instance
        if (service == null) {
            reply(webSocket, id, false, "accessibility service not enabled")
            FloatingOverlayService.log("Command ignored: service off")
            return
        }

        FloatingOverlayService.log("→ $type")
        runCatching {
            when (type) {
                "click" -> {
                    val x = obj.float("x"); val y = obj.float("y")
                    service.clickAt(x, y) { ok -> reply(webSocket, id, ok, "click ($x,$y)") }
                }
                "long_click" -> {
                    val x = obj.float("x"); val y = obj.float("y")
                    service.longClickAt(x, y) { ok -> reply(webSocket, id, ok, "long_click") }
                }
                "click_text" -> {
                    val ok = service.clickText(obj.str("text"))
                    reply(webSocket, id, ok, "click_text")
                }
                "swipe" -> {
                    service.swipe(
                        obj.float("startX"), obj.float("startY"),
                        obj.float("endX"), obj.float("endY"),
                    ) { ok -> reply(webSocket, id, ok, "swipe") }
                }
                "scroll" -> {
                    service.scroll(obj.float("dy")) { ok -> reply(webSocket, id, ok, "scroll") }
                }
                "type_text" -> {
                    val ok = service.typeText(obj.str("text"))
                    reply(webSocket, id, ok, "type_text")
                }
                "launch" -> {
                    val ok = service.launchApp(obj.str("package"))
                    reply(webSocket, id, ok, "launch")
                }
                "home" -> reply(webSocket, id, service.goHome(), "home")
                "back" -> reply(webSocket, id, service.goBack(), "back")
                "recents" -> reply(webSocket, id, service.openRecents(), "recents")
                "notifications" -> reply(webSocket, id, service.openNotifications(), "notifications")
                else -> reply(webSocket, id, false, "unknown type: $type")
            }
        }.onFailure { e ->
            reply(webSocket, id, false, e.message ?: "bad arguments for $type")
        }
    }

    private fun reply(webSocket: WebSocket, id: String?, ok: Boolean, detail: String) {
        val payload = buildJsonObject {
            put("type", "status")
            if (id != null) put("id", id)
            put("ok", ok)
            put("detail", detail)
        }
        webSocket.send(payload.toString())
        FloatingOverlayService.log(if (ok) "✓ $detail" else "✗ $detail")
    }

    // Small typed accessors so a missing field throws a clear message rather than
    // silently defaulting to zero and tapping the corner of the screen.
    private fun JsonObject.float(key: String): Float =
        this[key]?.jsonPrimitive?.float ?: error("missing number '$key'")

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("missing string '$key'")

    /** Optional strongly-typed command shape, for callers that prefer it. */
    @Serializable
    data class Command(
        val type: String,
        val id: String? = null,
        val x: Float? = null,
        val y: Float? = null,
        val text: String? = null,
        @SerialName("package") val packageName: String? = null,
    )

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** A client tuned for a long-lived socket: no read timeout, keep pinging. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
