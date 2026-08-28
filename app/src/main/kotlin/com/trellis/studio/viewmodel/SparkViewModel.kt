package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.SparkDirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A Spark mini-app in the history. */
data class SparkApp(val id: Long, val title: String, val htmlPath: String, val createdAt: Long)

/** Backs the Spark screen: describe an app → a live, self-contained HTML app. */
class SparkViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val director = SparkDirector()

    data class State(
        val busy: Boolean = false,
        val status: String? = null,
        val html: String? = null,
        val currentPath: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val apps: StateFlow<List<SparkApp>> = db.generationDao().getByType("spark")
        .map { rows ->
            rows.mapNotNull { r ->
                val p = r.modelPath ?: return@mapNotNull null
                if (!File(p).exists()) return@mapNotNull null
                SparkApp(r.id, r.prompt?.takeIf { it.isNotBlank() } ?: "App", p, r.createdAt)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearError() = _state.update { it.copy(error = null) }
    fun closeApp() = _state.update { it.copy(html = null, currentPath = null) }

    fun build(idea: String) {
        if (_state.value.busy || idea.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Building your app… (up to ~3 min)", error = null) }
            val apiKey = prefs.nvidiaKey.first()
            val model = prefs.gameModel.first()   // GLM: strong at self-contained code
            director.build(apiKey, model, idea)
                .onSuccess { html ->
                    val path = withContext(Dispatchers.IO) {
                        val dir = File(getApplication<Application>().filesDir, "spark").apply { mkdirs() }
                        File(dir, "spark_${System.currentTimeMillis()}.html").apply { writeText(html) }.absolutePath
                    }
                    runCatching {
                        db.generationDao().insert(GenerationEntity(type = "spark", prompt = idea.take(80), modelPath = path))
                    }
                    _state.update { it.copy(busy = false, status = null, html = html, currentPath = path) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, status = null, error = e.message) } }
        }
    }

    fun openApp(app: SparkApp) {
        viewModelScope.launch {
            val html = withContext(Dispatchers.IO) { runCatching { File(app.htmlPath).readText() }.getOrNull() }
            if (html != null) _state.update { it.copy(html = html, currentPath = app.htmlPath) }
            else _state.update { it.copy(error = "That app file is missing.") }
        }
    }

    fun deleteApp(app: SparkApp) = viewModelScope.launch {
        runCatching { File(app.htmlPath).delete(); db.generationDao().delete(app.id) }
    }
}
