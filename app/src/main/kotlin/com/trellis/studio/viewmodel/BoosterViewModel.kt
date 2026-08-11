package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.service.BoosterService
import com.trellis.studio.util.GameBooster
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BoosterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)

    data class State(
        val mem: GameBooster.Memory = GameBooster.Memory(0, 0, 0, false),
        val boosting: Boolean = false,
        val lastFreedMb: Long? = null,
        val lastTrimmed: Int? = null,
        val auto: Boolean = false,
        val intervalMin: Int = 5,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        _state.update { it.copy(mem = GameBooster.memory(getApplication())) }
        viewModelScope.launch {
            _state.update {
                it.copy(auto = prefs.boosterAuto.first(), intervalMin = prefs.boosterInterval.first())
            }
        }
        // Live RAM readout while the screen is open.
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(mem = GameBooster.memory(getApplication())) }
                delay(2_000)
            }
        }
    }

    fun boostNow() {
        if (_state.value.boosting) return
        viewModelScope.launch {
            _state.update { it.copy(boosting = true) }
            val result = GameBooster.boost(getApplication())
            _state.update {
                it.copy(
                    boosting = false,
                    mem = result.after,
                    lastFreedMb = result.freedMb,
                    lastTrimmed = result.appsTrimmed,
                )
            }
        }
    }

    fun setAuto(on: Boolean) {
        _state.update { it.copy(auto = on) }
        viewModelScope.launch {
            prefs.setBoosterAuto(on)
            if (on) BoosterService.start(getApplication(), _state.value.intervalMin)
            else BoosterService.stop(getApplication())
        }
    }

    fun setInterval(min: Int) {
        _state.update { it.copy(intervalMin = min) }
        viewModelScope.launch {
            prefs.setBoosterInterval(min)
            // Re-arm with the new interval if it's currently running.
            if (_state.value.auto) BoosterService.start(getApplication(), min)
        }
    }
}
