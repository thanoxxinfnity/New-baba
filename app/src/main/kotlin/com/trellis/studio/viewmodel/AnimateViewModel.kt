package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.AnimationDirector
import com.trellis.studio.util.AnimationBaker
import com.trellis.studio.util.AutoRigger
import com.trellis.studio.util.MeshAnalyzer
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.ModelExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
    /** The rig its proportions suggest, so the right tab opens first. */
    val suggestedRig: AutoRigger.Rig,
    /** What the model actually is, measured — not guessed from the bounding box. */
    val shape: MeshAnalyzer.Shape,
    val shapeSummary: String,
    val confidence: Float,
)

/**
 * Backs the Animate screen: pick a generated model, bake a motion clip into it,
 * and get a file that plays in-app and in any game engine.
 */
class AnimateViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val director = AnimationDirector()

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

    /** The skeleton just added, so the screen can list the bones to drive in code. */
    private val _rigged = MutableStateFlow<AutoRigger.Rigged?>(null)
    val rigged: StateFlow<AutoRigger.Rigged?> = _rigged.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun consumeBaked() { _baked.value = null }
    fun consumeRigged() { _rigged.value = null }

    /**
     * Inspecting a model means reading its glTF header and, for the suggestion,
     * its vertex positions. The list re-emits on every database write, so the
     * results are cached per file — otherwise every save re-read every model.
     */
    private val inspected = mutableMapOf<String, Pair<Long, AnimatableModel>>()

    private fun GenerationEntity.toAnimatable(): AnimatableModel? {
        val path = modelPath ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null

        val stamp = file.lastModified()
        inspected[path]?.let { (cachedStamp, cached) ->
            if (cachedStamp == stamp) return cached.copy(id = id, name = displayName(file))
        }

        val analysis = runCatching { MeshAnalyzer.analyse(ModelExporter.parseGlb(file)) }.getOrNull()
        val fresh = AnimatableModel(
            id = id,
            name = displayName(file),
            path = path,
            sizeLabel = FileExport.humanSize(file.length()),
            existingClips = AnimationBaker.clipNames(file).filter { it.isNotBlank() },
            suggestedRig = AutoRigger.suggest(file),
            shape = analysis?.shape ?: MeshAnalyzer.Shape.SOLID,
            shapeSummary = analysis?.summary ?: "Could not read this model",
            confidence = analysis?.confidence ?: 0f,
        )
        inspected[path] = stamp to fresh
        return fresh
    }

    private fun GenerationEntity.displayName(file: File) =
        prompt?.takeIf { it.isNotBlank() } ?: file.name

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

    /**
     * Animates the model from a written description: the LLM choreographs which
     * bones move and how, and the rigger plays it against a skeleton fitted to
     * this particular mesh.
     */
    fun animateFromPrompt(model: AnimatableModel, prompt: String) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        val apiKey = prefs.nvidiaKey.first()
        if (apiKey.isBlank()) {
            _message.value = "Add your NVIDIA API key in Settings."
            return@launch
        }

        _busy.value = "Working out the motion…"
        val llm = prefs.selectedLlm.first()
        director.choreograph(apiKey, llm, prompt, subject = model.name)
            .onSuccess { spec ->
                _busy.value = "Building the skeleton…"
                val dir = File(getApplication<Application>().filesDir, "models3d").apply { mkdirs() }
                // The preset is only a fallback shape here; the spec picks the frame.
                AutoRigger.rig(File(model.path), AutoRigger.Rig.HUMANOID_WALK, dir, spec)
                    .onSuccess { file ->
                        val name = "${model.name} · ${spec.name}"
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
                        _message.value = it.message ?: "Could not animate this model."
                    }
            }
            .onFailure {
                _busy.value = null
                _message.value = it.message ?: "Could not work out that motion."
            }
    }

    /**
     * Builds a skeleton for the model and animates it — a walk cycle or spinning
     * wheels, which need bones rather than moving the whole object.
     */
    fun rig(model: AnimatableModel, rig: AutoRigger.Rig) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Building the skeleton…"

        val dir = File(getApplication<Application>().filesDir, "models3d").apply { mkdirs() }
        AutoRigger.rig(File(model.path), rig, dir)
            .onSuccess { file ->
                val name = "${model.name} · ${rig.label}"
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
                _message.value = it.message ?: "Could not rig this model."
            }
    }

    /**
     * Adds bones and skin weights only — no clip.
     *
     * This is the piece that has to be baked into the file. Motion can be
     * written in code anywhere (three.js, `<model-viewer>`, Unity, Godot), but
     * only against joints that already exist, and a generated model arrives with
     * none. So this produces the rigged .glb and hands back the bone names to
     * drive.
     */
    fun addBones(model: AnimatableModel) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Measuring the model and placing bones…"

        val dir = File(getApplication<Application>().filesDir, "models3d").apply { mkdirs() }
        AutoRigger.addBones(File(model.path), dir)
            .onSuccess { result ->
                val name = "${model.name} · rigged"
                runCatching {
                    db.generationDao().insert(
                        GenerationEntity(
                            type = "3d", prompt = name, modelPath = result.file.absolutePath,
                        )
                    )
                }
                _busy.value = null
                // The sheet stays up instead of jumping to the viewer: there is no
                // clip to watch, and the bone names are the thing worth reading.
                _rigged.value = result
            }
            .onFailure {
                _busy.value = null
                _message.value = it.message ?: "Could not add bones to this model."
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
