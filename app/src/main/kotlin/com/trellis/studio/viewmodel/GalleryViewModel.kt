package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.ModelExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val allGenerations: StateFlow<List<GenerationEntity>> = db.generationDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val images: StateFlow<List<GenerationEntity>> = db.generationDao().getByType("image")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val models3d: StateFlow<List<GenerationEntity>> = db.generationDao().getByType("3d")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(item: GenerationEntity) = viewModelScope.launch {
        item.imagePath?.let { File(it).delete() }
        item.modelPath?.let { File(it).delete() }
        db.generationDao().delete(item.id)
    }

    // ------------------------------------------------------- batch selection

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _exporting = MutableStateFlow<String?>(null)
    val exporting: StateFlow<String?> = _exporting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Emitted when a batch .zip is ready to hand to the share sheet. */
    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selection
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSelected(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun selectAll(items: List<GenerationEntity>) {
        _selection.value = items.map { it.id }.toSet()
    }

    fun consumeMessage() { _message.value = null }
    fun consumeExported() { _exported.value = null }

    /** How many of the current selection are 3D models, which is what exports. */
    fun selectedModels(items: List<GenerationEntity>): List<GenerationEntity> =
        items.filter { it.id in _selection.value && it.type == "3d" && it.modelPath != null }

    fun deleteSelected(items: List<GenerationEntity>) = viewModelScope.launch {
        val chosen = items.filter { it.id in _selection.value }
        chosen.forEach { item ->
            item.imagePath?.let { File(it).delete() }
            item.modelPath?.let { File(it).delete() }
            db.generationDao().delete(item.id)
        }
        _selection.value = emptySet()
        _message.value = "Deleted ${chosen.size} item${if (chosen.size == 1) "" else "s"}."
    }

    /** Exports every selected 3D model into a single .zip. */
    fun exportSelected(items: List<GenerationEntity>, formats: List<ModelExporter.Format>) =
        viewModelScope.launch {
            if (_exporting.value != null) return@launch
            val chosen = selectedModels(items)
            if (chosen.isEmpty()) {
                _message.value = "Select some 3D models first — images don't export as meshes."
                return@launch
            }

            _exporting.value = "Preparing ${chosen.size} models…"
            ModelExporter.exportBatch(
                models = chosen.mapNotNull { it.modelPath?.let(::File) },
                formats = formats,
                outputDir = FileExport.outputDir(getApplication()),
                zipName = "void_models_${chosen.size}",
                onProgress = { p ->
                    _exporting.value = "Exporting ${p.done + 1} of ${p.total} · ${p.current}"
                },
            ).onSuccess { zip ->
                _exporting.value = null
                _selection.value = emptySet()
                _exported.value = zip
            }.onFailure {
                _exporting.value = null
                _message.value = it.message ?: "Export failed."
            }
        }
}
