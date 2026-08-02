package com.trellis.studio.viewmodel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.trellis.studio.network.AiAgentListener
import com.trellis.studio.service.AutomationService
import com.trellis.studio.service.FloatingOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One place that owns the agent's on/off lifecycle, so the screen, the floating
 * bubble and the socket all agree on a single state.
 *
 * The three pieces are otherwise independent — the overlay does not know about
 * the socket, the socket does not know about the UI — and this is the seam that
 * wires them together: the bubble's Start/Stop buttons call back into here, and
 * the socket's connection changes flow back out to whoever is watching [state].
 */
object AgentController {

    enum class Phase { OFF, CONNECTING, CONNECTED }

    data class State(
        val phase: Phase = Phase.OFF,
        /** The last thing that happened, for the status line. */
        val detail: String = "Not running",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var listener: AiAgentListener? = null

    val isRunning: Boolean get() = listener != null

    /**
     * Starts the overlay and connects to [url]. Returns a message to show when a
     * prerequisite is missing, or null on success — the caller routes the user to
     * the right Settings page for whatever it names.
     */
    fun start(context: Context, url: String): String? {
        val app = context.applicationContext
        if (url.isBlank()) return "Add the agent's WebSocket URL first."
        if (!Settings.canDrawOverlays(app)) return "overlay-permission"
        if (!AutomationService.isReady) return "accessibility-permission"

        // Bring the floating control up, and route its buttons back here.
        app.startService(Intent(app, FloatingOverlayService::class.java))
        FloatingOverlayService.onStart = { /* already running; ignore */ }
        FloatingOverlayService.onStop = { stop(app) }
        FloatingOverlayService.onStatus = { _state.value.detail }

        _state.value = State(Phase.CONNECTING, "Connecting to $url")
        FloatingOverlayService.status("Connecting")

        listener?.stop()
        listener = AiAgentListener(url).apply {
            onConnected = {
                _state.value = State(Phase.CONNECTED, "Connected to agent")
                FloatingOverlayService.status("Connected")
            }
            onDisconnected = { reason ->
                // The socket reconnects on its own; reflect the gap, do not clear
                // the listener — stop() is the only thing that turns it off.
                if (isRunning) {
                    _state.value = State(Phase.CONNECTING, "Reconnecting · $reason")
                    FloatingOverlayService.status("Reconnecting")
                }
            }
            start()
        }
        return null
    }

    fun stop(context: Context) {
        listener?.stop()
        listener = null
        _state.value = State(Phase.OFF, "Stopped")
        val app = context.applicationContext
        app.stopService(Intent(app, FloatingOverlayService::class.java))
    }
}
