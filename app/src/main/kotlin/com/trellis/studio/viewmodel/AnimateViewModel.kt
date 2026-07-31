package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.util.AnimationBaker
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.ModelExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A 3D model the user can animate, plus whatever clips it already carries. */
data class AnimatableModel(
    val id: Long,
    val name: String,
    val path: String,
    val sizeLabel: String,
    val existingClips: List<String>,
)

/**
 * Backs the Animate screen: pick a generated model, bake a motion clip into it,
 * and get a file that plays in-app and in any game engine.
 */
class AnimateViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    // Each row is stat'ed and its glTF header parsed, so the mapping is kept off
    // the main thread — Room's flow would otherwise hand it straight to the UI.
    val models: StateFlow<List<AnimatableModel>> = db.generationDao().getByType("3d")
        .map { rows -> rows.mapNotNull { it.toAnimatable() } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Emits the path of a freshly baked file so the screen can open the viewer. */
    private val _baked = MutableStateFlow<Pair<String, String>?>(null)
    val baked: StateFlow<Pair<String, String>?> = _baked.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun consumeBaked() { _baked.value = null }

    private fun GenerationEntity.toAnimatable(): AnimatableModel? {
        val path = modelPath ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        return AnimatableModel(
            id = id,
            name = prompt?.takeIf { it.isNotBlank() } ?: file.name,
            path = path,
            sizeLabel = FileExport.humanSize(file.length()),
            existingClips = AnimationBaker.clipNames(file).filter { it.isNotBlank() },
        )
    }

    /**
     * Bakes [clip] into [model] and registers the result as its own gallery entry,
     * so the animated version sits alongside the original instead of replacing it.
     */
    fun animate(model: AnimatableModel, clip: AnimationBaker.Clip) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Baking ${clip.label}…"

        val source = File(model.path)
        // Keep it next to the other models so the viewer, gallery and exporter
        // all find it without a special case.
        val dir = File(getApplication<Application>().filesDir, "models3d").apply { mkdirs() }

        AnimationBaker.bake(source, clip, dir)
            .onSuccess { file ->
                val name = "${model.name} · ${clip.label}"
                runCatching {
                    db.generationDao().insert(
                        GenerationEntity(type = "3d", prompt = name, modelPath = file.absolutePath)
                    )
                }
                _busy.value = null
                _baked.value = file.absolutePath to name
            }
            .onFailure {
                _busy.value = null
                _message.value = it.message ?: "Could not add the animation."
            }
    }

    /** Bakes then immediately hands the file to the share sheet. */
    fun animateAndExport(
        model: AnimatableModel,
        clip: AnimationBaker.Clip,
        format: ModelExporter.Format?,
        onReady: (File, String) -> Unit,
    ) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Baking ${clip.label}…"
        val context = getApplication<Application>()

        AnimationBaker.bake(File(model.path), clip, FileExport.outputDir(context))
            .onSuccess { glb ->
                if (format == null || format == ModelExporter.Format.GLB) {
                    _busy.value = null
                    onReady(glb, "model/gltf-binary")
                    return@launch
                }
                // OBJ/STL/PLY carry geometry only — animation does not survive them,
                // so say so rather than handing over a file that silently sits still.
                _busy.value = "Converting to ${format.label}…"
                ModelExporter.export(glb, format, FileExport.outputDir(context))
                    .onSuccess { out ->
                        _busy.value = null
                        _message.value =
                            "${format.label} stores geometry only — the animation is in the .glb."
                        onReady(out, "application/octet-stream")
                    }
                    .onFailure {
                        _busy.value = null
                        _message.value = it.message ?: "Export failed."
                    }
            }
            .onFailure {
                _busy.value = null
                _message.value = it.message ?: "Could not add the animation."
            }
    }
}
