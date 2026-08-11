package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Backs the small AI fun tools (gamertags, meme captions, roast/joke/fortune). */
class AiFunViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val nim = NimClient()

    data class State(val busy: Boolean = false, val output: String = "", val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Runs [system] over [user] and puts the reply in [State.output]. */
    fun run(system: String, user: String, temperature: Double = 1.0) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val apiKey = prefs.nvidiaKey.first()
            val model = prefs.selectedLlm.first()
            nim.chat(
                apiKey = apiKey, model = model,
                turns = listOf(ChatTurn("system", system), ChatTurn("user", user)),
                maxTokens = 260, temperature = temperature,
            ).map { it.content.ifBlank { it.reasoning.orEmpty() }.trim() }
                .onSuccess { out -> _state.update { it.copy(busy = false, output = out) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Failed. Try again.") } }
        }
    }

    fun clear() = _state.update { State() }
}
