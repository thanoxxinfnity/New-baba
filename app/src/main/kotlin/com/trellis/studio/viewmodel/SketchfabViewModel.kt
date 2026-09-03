package com.trellis.studio.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.SketchfabClient
import com.trellis.studio.util.GltfPacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Finds Sketchfab models by name or link and brings the downloadable ones in. */
class SketchfabViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val client = SketchfabClient()
    private val db = AppDatabase.get(app)

    data class State(
        val query: String = "",
        val results: List<SketchfabClient.Model> = emptyList(),
        val searched: Boolean = false,
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
        /** The model currently being fetched, so its card can show a spinner. */
        val downloadingUid: String? = null,
        val downloadedPath: String? = null,
        val downloadedName: String? = null,
        val savedNote: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setQuery(v: String) = _state.update { it.copy(query = v) }
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * One box for both jobs: a pasted Sketchfab link resolves to that exact
     * model, anything else is treated as a search term.
     */
    fun go() {
        val q = _state.value.query.trim()
        if (q.isBlank() || _state.value.busy) return
        val uid = SketchfabClient.extractUid(q)

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true, error = null, searched = false, results = emptyList(),
                    status = if (uid != null) "Looking up that model…" else "Searching Sketchfab…",
                )
            }
            val outcome =
                if (uid != null) client.detail(uid).map { listOf(it) }
                else client.search(q)

            outcome
                .onSuccess { list ->
                    _state.update {
                        it.copy(busy = false, status = null, results = list, searched = true)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, status = null, error = e.message, searched = true) }
                }
        }
    }

    /**
     * Downloads a model and packs it into a single .glb so the viewer and Rig
     * Studio can both open it.
     */
    fun download(model: SketchfabClient.Model) {
        if (_state.value.downloadingUid != null) return
        if (!model.downloadable) {
            _state.update {
                it.copy(
                    error = "\"${model.name}\" isn't downloadable — its author kept it " +
                        "view-only or it's a paid store model. Try one marked Downloadable."
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(downloadingUid = model.uid, error = null, status = "Asking Sketchfab…",
                    downloadedPath = null, savedNote = null)
            }
            val token = prefs.sketchfabToken.first()

            val result = client.downloadUrl(model.uid, token)
                .also { _state.update { s -> s.copy(status = "Downloading…") } }
                .mapCatching { url -> client.fetch(url).getOrThrow() }
                .also { _state.update { s -> s.copy(status = "Packing into a .glb…") } }
                .mapCatching { zip ->
                    GltfPacker.packZip(
                        zip,
                        File(getApplication<Application>().filesDir, "models3d"),
                        model.name,
                    ).getOrThrow()
                }

            result
                .onSuccess { packed ->
                    runCatching {
                        db.generationDao().insert(
                            GenerationEntity(
                                type = "3d",
                                prompt = "${model.name} — ${model.author} (${model.license})",
                                modelPath = packed.file.absolutePath,
                            )
                        )
                    }
                    _state.update {
                        it.copy(
                            downloadingUid = null, status = null,
                            downloadedPath = packed.file.absolutePath,
                            downloadedName = model.name,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(downloadingUid = null, status = null, error = e.message) }
                }
        }
    }

    /** Copies the packed .glb wherever the user chose. */
    fun saveTo(destination: Uri) {
        val path = _state.value.downloadedPath ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(destination)
                        ?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
                        ?: error("Could not write to that location.")
                }
            }
                .onSuccess { _state.update { it.copy(savedNote = "Saved to your device") } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun suggestedFileName(): String {
        val base = (_state.value.downloadedName ?: "model")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(48).ifBlank { "model" }
        return "$base.glb"
    }
}
