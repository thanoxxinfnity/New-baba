package com.trellis.studio.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A folder workspace: open a project folder once (Storage Access Framework),
 * browse it, open a file, and run AI dev actions on it — no copy-paste.
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val nim = NimClient()

    data class Entry(val name: String, val isDir: Boolean, val uri: String)

    data class State(
        val hasFolder: Boolean = false,
        val breadcrumb: String = "",
        val entries: List<Entry> = emptyList(),
        val openName: String? = null,
        val content: String = "",
        val dirty: Boolean = false,
        val aiBusy: Boolean = false,
        val aiOutput: String? = null,
        val status: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val stack = ArrayDeque<DocumentFile>()
    private var currentDocs: List<DocumentFile> = emptyList()
    private var openDoc: DocumentFile? = null

    init {
        viewModelScope.launch {
            val saved = prefs.codeFolder.first()
            if (saved.isNotBlank()) runCatching {
                DocumentFile.fromTreeUri(getApplication(), Uri.parse(saved))?.let { openRoot(it) }
            }
        }
    }

    fun pickFolder(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        viewModelScope.launch { prefs.setCodeFolder(uri.toString()) }
        DocumentFile.fromTreeUri(getApplication(), uri)?.let { openRoot(it) }
    }

    private fun openRoot(root: DocumentFile) {
        stack.clear(); stack.addLast(root)
        _state.update { it.copy(hasFolder = true, openName = null) }
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(status = "Loading…") }
            val docs = withContext(Dispatchers.IO) {
                (stack.lastOrNull()?.listFiles()?.toList() ?: emptyList())
                    .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() })
            }
            currentDocs = docs
            _state.update {
                it.copy(
                    status = null,
                    breadcrumb = stack.mapNotNull { d -> d.name }.joinToString(" / ").ifBlank { "/" },
                    entries = docs.map { d -> Entry(d.name ?: "?", d.isDirectory, d.uri.toString()) },
                )
            }
        }
    }

    fun openEntry(entry: Entry) {
        val doc = currentDocs.firstOrNull { it.uri.toString() == entry.uri } ?: return
        if (doc.isDirectory) { stack.addLast(doc); _state.update { it.copy(openName = null) }; refresh() }
        else openFile(doc)
    }

    fun up(): Boolean {
        if (_state.value.openName != null) { _state.update { it.copy(openName = null, aiOutput = null) }; return true }
        if (stack.size > 1) { stack.removeLast(); refresh(); return true }
        return false
    }

    private fun openFile(doc: DocumentFile) {
        viewModelScope.launch {
            _state.update { it.copy(status = "Opening…") }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(doc.uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (text == null) { _state.update { it.copy(status = null, error = "Can't open this file as text.") }; return@launch }
            openDoc = doc
            _state.update { it.copy(status = null, openName = doc.name, content = text, dirty = false, aiOutput = null, error = null) }
        }
    }

    fun edit(v: String) = _state.update { it.copy(content = v, dirty = true) }

    fun save() {
        val doc = openDoc ?: return
        viewModelScope.launch {
            _state.update { it.copy(status = "Saving…") }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(doc.uri, "wt")?.use {
                        it.write(_state.value.content.toByteArray())
                    }
                    true
                }.getOrDefault(false)
            }
            _state.update { it.copy(status = if (ok) "Saved ✓" else null, dirty = !ok, error = if (ok) null else "Couldn't save.") }
        }
    }

    /** Runs a dev AI action on the open file and shows the result. */
    fun ai(kind: String) {
        if (_state.value.aiBusy || _state.value.content.isBlank()) return
        val system = when (kind) {
            "Review" -> "You are a senior engineer. Review this file: list concrete bugs, risks and improvements as short bullets, most important first."
            "Explain" -> "Explain what this file/code does, clearly and concisely, section by section."
            "Docs" -> "Add clear doc comments to this code (the idiomatic style for its language). Output the full documented code in a code block."
            "Tests" -> "Write thorough unit tests for this code using the idiomatic framework. Output only the test code."
            "Fix" -> "Find and fix bugs in this code. Output the corrected full file in a code block, then a short bullet list of what you changed."
            else -> "Improve this code and explain the changes."
        }
        viewModelScope.launch {
            _state.update { it.copy(aiBusy = true, aiOutput = null, error = null) }
            val apiKey = prefs.nvidiaKey.first()
            val model = prefs.gameModel.first()   // GLM: strong at code
            nim.chat(apiKey, model,
                listOf(ChatTurn("system", system), ChatTurn("user", "File: ${_state.value.openName}\n\n${_state.value.content.take(12000)}")),
                maxTokens = 3000, temperature = 0.3, readTimeoutSeconds = 180,
            ).map { it.content.ifBlank { it.reasoning.orEmpty() } }
                .onSuccess { out -> _state.update { it.copy(aiBusy = false, aiOutput = out) } }
                .onFailure { e -> _state.update { it.copy(aiBusy = false, error = e.message) } }
        }
    }

    fun closeAiOutput() = _state.update { it.copy(aiOutput = null) }
    /** Replace the editor content with the AI output (e.g. after Fix/Docs). */
    fun applyAiOutput() {
        val out = _state.value.aiOutput ?: return
        val code = stripFence(out)
        _state.update { it.copy(content = code, dirty = true, aiOutput = null) }
    }
    private fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n', t).substringBeforeLast("```").trim()
    }

    fun clearFolder() {
        stack.clear(); currentDocs = emptyList(); openDoc = null
        viewModelScope.launch { prefs.setCodeFolder("") }
        _state.update { State() }
    }
}
