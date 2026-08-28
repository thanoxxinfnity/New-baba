package com.trellis.studio.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.util.AutoRigger
import com.trellis.studio.util.MeshAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Rigs a model the user supplies, rather than only ones the app generated. */
class RigStudioViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /** What the mesh measured as, phrased for the UI. */
    data class Measured(val shapeSummary: String, val poseSummary: String)

    data class State(
        val sourcePath: String? = null,
        val sourceName: String? = null,
        val analysis: Measured? = null,
        val frame: AutoRigger.Frame = AutoRigger.Frame.HUMANOID,
        val pose: AutoRigger.Pose = AutoRigger.Pose.AUTO,
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
        val result: AutoRigger.Rigged? = null,
        /** Set after a successful save, so the screen can confirm it. */
        val savedNote: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setFrame(v: AutoRigger.Frame) = _state.update { it.copy(frame = v) }
    fun setPose(v: AutoRigger.Pose) = _state.update { it.copy(pose = v) }
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Copies the picked file somewhere stable and measures it, so the screen can
     * say what it found before the user commits to a pose.
     */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, status = "Reading the model…", error = null, result = null, analysis = null, savedNote = null)
            }
            val ctx = getApplication<Application>()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri)
                    val dir = File(ctx.filesDir, "imported").apply { mkdirs() }
                    val dest = File(dir, "in_${System.currentTimeMillis()}.glb")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: error("Could not open that file. Try picking it again.")

                    if (dest.length() < 20) error("That file is empty.")
                    // A .glb always starts with the ASCII magic "glTF".
                    val magic = dest.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                    if (String(magic) != "glTF") {
                        error("That isn't a .glb model. Export your model as glTF-Binary (.glb) and try again.")
                    }
                    name to dest
                }
            }

            outcome.onSuccess { (name, file) ->
                // Measuring can fail on an exotic file without the import being
                // wrong, so it reports separately instead of rejecting the model.
                val measured = MeshAnalyzer.analyse(file).getOrNull()
                _state.update {
                    it.copy(
                        busy = false, status = null,
                        sourcePath = file.absolutePath, sourceName = name,
                        analysis = measured?.let { m -> Measured(m.summary, m.poseLabel) },
                        frame = measured?.let { m ->
                            when (m.shape) {
                                MeshAnalyzer.Shape.QUADRUPED -> AutoRigger.Frame.QUADRUPED
                                MeshAnalyzer.Shape.VEHICLE -> AutoRigger.Frame.VEHICLE
                                else -> AutoRigger.Frame.HUMANOID
                            }
                        } ?: AutoRigger.Frame.HUMANOID,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, status = null, error = e.message) }
            }
        }
    }

    fun rig() {
        val s = _state.value
        val path = s.sourcePath ?: return
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Placing bones…", error = null, result = null, savedNote = null) }
            val ctx = getApplication<Application>()
            val dir = File(ctx.filesDir, "models3d").apply { mkdirs() }

            AutoRigger.addBones(File(path), dir, frame = s.frame, pose = s.pose)
                .onSuccess { rigged ->
                    runCatching {
                        db.generationDao().insert(
                            GenerationEntity(
                                type = "3d",
                                prompt = "${s.sourceName ?: "Imported"} · rigged (${s.pose.label})",
                                modelPath = rigged.file.absolutePath,
                            )
                        )
                    }
                    _state.update { it.copy(busy = false, status = null, result = rigged) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, status = null, error = e.message) }
                }
        }
    }

    /**
     * Copies the rigged .glb to a location the user picked, so it lands in their
     * own storage rather than only in the app's private folder. Sharing could
     * already hand it to another app; this is the plain "save it to my phone"
     * path people expect from a download.
     */
    fun saveTo(destination: Uri) {
        val file = _state.value.result?.file ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination)
                        ?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("Could not write to that location.")
                }
            }
            outcome
                .onSuccess { _state.update { it.copy(status = null, savedNote = "Saved to your device") } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** The name to offer in the save dialog. */
    fun suggestedFileName(): String {
        val base = _state.value.sourceName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "model"
        return "${base}_rigged.glb"
    }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.glb"
    }
}
