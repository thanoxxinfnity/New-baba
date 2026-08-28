package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.util.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * The Vault: your own encrypted place to save secrets (phone unlock code, PINs,
 * passwords) behind a master password. The app can NEVER read your device lock
 * — Android hides that in hardware — but it can safely hold what you choose to
 * save here, so a forgotten code is one master password away.
 */
class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)

    data class Item(val id: Long, val title: String, val secret: String, val note: String)

    data class State(
        val hasVault: Boolean = false,
        val unlocked: Boolean = false,
        val items: List<Item> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var key: SecretKey? = null

    init {
        viewModelScope.launch {
            val has = prefs.vaultSalt.first().isNotBlank() && prefs.vaultCheck.first().isNotBlank()
            _state.update { it.copy(hasVault = has) }
        }
    }

    fun createMaster(pw: String) {
        if (pw.length < 4) { _state.update { it.copy(error = "Use at least 4 characters.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            withContext(Dispatchers.Default) {
                val salt = VaultCrypto.randomSalt()
                val k = VaultCrypto.deriveKey(pw.toCharArray(), salt)
                val check = VaultCrypto.encrypt(k, VaultCrypto.CHECK_TOKEN)
                prefs.setVault(salt, check)
                prefs.setVaultBlob(VaultCrypto.encrypt(k, "[]"))
                key = k
            }
            _state.update { it.copy(busy = false, hasVault = true, unlocked = true, items = emptyList()) }
        }
    }

    fun unlock(pw: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    val salt = prefs.vaultSalt.first()
                    val k = VaultCrypto.deriveKey(pw.toCharArray(), salt)
                    val token = VaultCrypto.decrypt(k, prefs.vaultCheck.first())
                    if (token != VaultCrypto.CHECK_TOKEN) return@runCatching null
                    key = k
                    parse(runCatching { VaultCrypto.decrypt(k, prefs.vaultBlob.first()) }.getOrDefault("[]"))
                }.getOrNull()
            }
            if (ok == null) _state.update { it.copy(busy = false, error = "Wrong master password.") }
            else _state.update { it.copy(busy = false, unlocked = true, items = ok, error = null) }
        }
    }

    fun add(title: String, secret: String, note: String) {
        if (title.isBlank() || secret.isBlank()) { _state.update { it.copy(error = "Title and secret are required.") }; return }
        val item = Item(System.currentTimeMillis(), title.trim(), secret, note.trim())
        val list = _state.value.items + item
        persist(list)
    }

    fun delete(item: Item) = persist(_state.value.items - item)

    private fun persist(list: List<Item>) {
        val k = key ?: return
        _state.update { it.copy(items = list) }
        viewModelScope.launch(Dispatchers.Default) {
            prefs.setVaultBlob(VaultCrypto.encrypt(k, toJson(list)))
        }
    }

    fun lock() {
        key = null
        _state.update { it.copy(unlocked = false, items = emptyList(), error = null) }
    }

    /** Erases the vault entirely so a new PIN can be set. Wipes all saved secrets. */
    fun reset() {
        key = null
        viewModelScope.launch { prefs.setVault("", ""); prefs.setVaultBlob("") }
        _state.update { State(hasVault = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun toJson(list: List<Item>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("secret", it.secret).put("note", it.note)) }
        return arr.toString()
    }

    private fun parse(json: String): List<Item> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.optLong("id"), o.optString("title"), o.optString("secret"), o.optString("note"))
        }
    }
}
