package com.trellis.studio.viewmodel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.trellis.studio.audio.VoiceIo
import com.trellis.studio.service.AutomationService
import com.trellis.studio.service.FloatingOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the floating-bubble side of the agent: showing it, and wiring its
 * buttons and microphone back to [AgentBrain].
 *
 * The bubble is what lets the agent be used while another app is open — VOID is
 * in the background then, so its own screen is gone, and the overlay carries the
 * controls and a voice button over the top of whatever is being operated. The
 * thinking itself is [AgentBrain]'s; this only connects the two.
 */
object AgentController {

    data class State(val overlayUp: Boolean = false, val listening: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the floating bubble can be shown yet. */
    fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun accessibilityReady(): Boolean = AutomationService.isReady

    /**
     * Brings the floating bubble up and points its controls at the brain. No-op
     * without the overlay permission — the caller checks [canShowOverlay] and
     * routes the user to Settings.
     */
    fun showOverlay(context: Context) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return

        VoiceIo.warmUp(app)
        app.startService(Intent(app, FloatingOverlayService::class.java))

        FloatingOverlayService.onStart = { startVoice(app) }
        FloatingOverlayService.onStop = { AgentBrain.stop() }
        FloatingOverlayService.onStatus = {
            if (AgentBrain.isRunning) "Working…" else "Idle · tap mic to speak"
        }
        _state.value = _state.value.copy(overlayUp = true)
        FloatingOverlayService.status("Idle · tap mic to speak")
    }

    fun hideOverlay(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, FloatingOverlayService::class.java))
        _state.value = _state.value.copy(overlayUp = false)
    }

    /**
     * Listens for one spoken instruction and hands it to the brain. Used by both
     * the bubble's mic and the in-app mic button.
     */
    fun startVoice(context: Context) {
        val app = context.applicationContext
        _state.value = _state.value.copy(listening = true)
        FloatingOverlayService.status("Listening…")
        VoiceIo.listen(
            context = app,
            onResult = { spoken ->
                _state.value = _state.value.copy(listening = false)
                FloatingOverlayService.log("heard: $spoken")
                AgentBrain.submit(app, spoken)
            },
            onPartial = { FloatingOverlayService.status("… $it") },
            onError = { err ->
                _state.value = _state.value.copy(listening = false)
                FloatingOverlayService.status("Mic: $err")
            },
        )
    }

    fun stopVoice() {
        VoiceIo.cancelListening()
        _state.value = _state.value.copy(listening = false)
    }
}
