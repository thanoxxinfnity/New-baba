package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A mini REST client (Postman-lite): build a request, send it, read the response. */
class ApiTesterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val nim = NimClient()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    data class Header(val id: Long, val key: String, val value: String)
    data class HistoryItem(val method: String, val url: String)

    data class State(
        val method: String = "GET",
        val url: String = "",
        val headers: List<Header> = listOf(Header(0, "", "")),
        val body: String = "",
        val sending: Boolean = false,
        val status: Int? = null,
        val statusText: String = "",
        val timeMs: Long? = null,
        val sizeBytes: Int? = null,
        val respBody: String = "",
        val respHeaders: String = "",
        val error: String? = null,
        val history: List<HistoryItem> = emptyList(),
        val aiBusy: Boolean = false,
        val aiOutput: String? = null,
    )

    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(history = loadHistory()) } }
    }

    fun setMethod(m: String) = _state.update { it.copy(method = m) }
    fun setUrl(u: String) = _state.update { it.copy(url = u) }
    fun setBody(b: String) = _state.update { it.copy(body = b) }

    fun addHeader() = _state.update { it.copy(headers = it.headers + Header(System.nanoTime(), "", "")) }
    fun updateHeader(id: Long, key: String, value: String) = _state.update { s ->
        s.copy(headers = s.headers.map { if (it.id == id) it.copy(key = key, value = value) else it })
    }
    fun removeHeader(id: Long) = _state.update { s -> s.copy(headers = s.headers.filter { it.id != id }.ifEmpty { listOf(Header(0, "", "")) }) }

    fun loadHistory(item: HistoryItem) = _state.update { it.copy(method = item.method, url = item.url) }

    fun send() {
        val s = _state.value
        if (s.url.isBlank() || s.sending) return
        var url = s.url.trim()
        if (!url.startsWith("http")) url = "https://$url"
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null, status = null, respBody = "", aiOutput = null) }
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val hdrMap = s.headers.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value.trim() }
                    val builder = Request.Builder().url(url).headers(hdrMap.toHeaders())
                    val ct = (hdrMap.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/json").toMediaType()
                    when (s.method) {
                        "GET" -> builder.get()
                        "DELETE" -> if (s.body.isBlank()) builder.delete() else builder.delete(s.body.toRequestBody(ct))
                        else -> builder.method(s.method, s.body.toRequestBody(ct))
                    }
                    http.newCall(builder.build()).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        val hdrs = resp.headers.joinToString("\n") { "${it.first}: ${it.second}" }
                        arrayOf(resp.code, resp.message, prettyIfJson(raw), hdrs, raw.length)
                    }
                }
            }
            val dt = System.currentTimeMillis() - t0
            result.onSuccess { r ->
                @Suppress("UNCHECKED_CAST")
                _state.update { it.copy(sending = false, timeMs = dt, status = r[0] as Int,
                    statusText = r[1] as String, respBody = r[2] as String, respHeaders = r[3] as String, sizeBytes = r[4] as Int) }
                saveHistory(HistoryItem(s.method, url))
            }.onFailure { e ->
                _state.update { it.copy(sending = false, timeMs = dt, error = e.message ?: "Request failed.") }
            }
        }
    }

    fun explain() {
        val s = _state.value
        if (s.aiBusy || s.respBody.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(aiBusy = true, aiOutput = null) }
            val apiKey = prefs.nvidiaKey.first(); val model = prefs.selectedLlm.first()
            nim.chat(apiKey, model, listOf(
                ChatTurn("system", "You are an API expert. Briefly explain this HTTP response: what it means, key fields, and any error or next step."),
                ChatTurn("user", "Status ${s.status}. Body:\n${s.respBody.take(4000)}")
            ), maxTokens = 400, temperature = 0.3)
                .map { it.content.ifBlank { it.reasoning.orEmpty() } }
                .onSuccess { o -> _state.update { it.copy(aiBusy = false, aiOutput = o) } }
                .onFailure { e -> _state.update { it.copy(aiBusy = false, error = e.message) } }
        }
    }
    fun closeAi() = _state.update { it.copy(aiOutput = null) }

    private fun prettyIfJson(s: String): String = runCatching {
        val t = s.trim()
        if (t.startsWith("{")) JSONObject(t).toString(2) else if (t.startsWith("[")) JSONArray(t).toString(2) else s
    }.getOrDefault(s)

    private suspend fun loadHistory(): List<HistoryItem> = runCatching {
        val arr = JSONArray(prefs.apiHistory.first().ifBlank { "[]" })
        (0 until arr.length()).map { val o = arr.getJSONObject(it); HistoryItem(o.optString("m"), o.optString("u")) }
    }.getOrDefault(emptyList())

    private fun saveHistory(item: HistoryItem) {
        val list = (listOf(item) + _state.value.history.filter { it.url != item.url }).take(15)
        _state.update { it.copy(history = list) }
        viewModelScope.launch {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("m", it.method).put("u", it.url)) }
            prefs.setApiHistory(arr.toString())
        }
    }
}
