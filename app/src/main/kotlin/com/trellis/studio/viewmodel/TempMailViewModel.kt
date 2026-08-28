package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.TempMailClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Backs the Temp Inbox tool: make disposable addresses and read incoming mail. */
class TempMailViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val client = TempMailClient()

    data class State(
        val accounts: List<TempMailClient.Account> = emptyList(),
        val selected: TempMailClient.Account? = null,
        val inbox: List<TempMailClient.Message> = emptyList(),
        val openBody: String? = null,
        val domains: List<String> = emptyList(),
        val creating: Boolean = false,
        val loading: Boolean = false,
        val status: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            client.domains().onSuccess { d -> _state.update { it.copy(domains = d) } }
        }
        viewModelScope.launch {
            prefs.tempMails.collect { raw ->
                val accts = raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 2) TempMailClient.Account(parts[0], parts[1]) else null
                }.toList()
                _state.update { s ->
                    s.copy(accounts = accts, selected = s.selected ?: accts.firstOrNull())
                }
                if (_state.value.inbox.isEmpty() && _state.value.selected != null) refresh()
            }
        }
    }

    fun generate(customName: String? = null, domain: String? = null) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null, status = "Creating a new inbox…") }
            client.create(customName, domain ?: _state.value.domains.firstOrNull())
                .onSuccess { acc ->
                    prefs.addTempMail(acc.address, acc.password)
                    _state.update { it.copy(creating = false, status = null, selected = acc, inbox = emptyList(), openBody = null) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(creating = false, status = null, error = e.message) } }
        }
    }

    fun select(acc: TempMailClient.Account) {
        _state.update { it.copy(selected = acc, inbox = emptyList(), openBody = null) }
        refresh()
    }

    fun refresh() {
        val acc = _state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            client.inbox(acc)
                .onSuccess { msgs -> _state.update { it.copy(loading = false, inbox = msgs) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun open(msg: TempMailClient.Message) {
        val acc = _state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(openBody = "…") }
            client.read(acc, msg.id)
                .onSuccess { body -> _state.update { it.copy(openBody = body) } }
                .onFailure { e -> _state.update { it.copy(openBody = null, error = e.message) } }
        }
    }

    fun closeMessage() = _state.update { it.copy(openBody = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun delete(acc: TempMailClient.Account) {
        viewModelScope.launch {
            prefs.removeTempMail(acc.address)
            if (_state.value.selected?.address == acc.address)
                _state.update { it.copy(selected = null, inbox = emptyList(), openBody = null) }
        }
    }
}
