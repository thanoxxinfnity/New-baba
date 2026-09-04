package com.trellis.studio.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.util.GltfPacker
import com.trellis.studio.util.PmxConverter
import com.trellis.studio.util.Zips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a model archive into a .glb the rest of the app can open.
 *
 * Handles what people actually download: MikuMikuDance .pmx packs (the format
 * most anime character models come in) and glTF archives, plus a .glb that is
 * already fine and just needs importing.
 */
class ZipConvertViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    data class State(
        val sourceName: String? = null,
        val contents: String? = null,
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
        val resultPath: String? = null,
        val resultName: String? = null,
        val summary: String? = null,
        val notes: List<String> = emptyList(),
        val savedNote: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }

    fun convert(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update {
                State(busy = true, status = "Reading the archive…", sourceName = displayName(uri))
            }

            val outcome = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not open that file. Try picking it again.")
                }
                require(bytes.size > 8) { "That file is empty." }

                // A bare .glb needs nothing doing to it.
                if (String(bytes, 0, 4, Charsets.US_ASCII) == "glTF") {
                    val dir = File(ctx.filesDir, "models3d").apply { mkdirs() }
                    val out = File(dir, "${safe(baseName())}.glb").apply { writeBytes(bytes) }
                    return@runCatching Outcome(out, "Already a .glb — imported as is.", emptyList())
                }

                val entries = withContext(Dispatchers.IO) { Zips.readAll(bytes) }
                _state.update {
                    it.copy(
                        status = "Converting…",
                        contents = entries.keys.take(6).joinToString("\n") { k -> k.substringAfterLast('/') } +
                            if (entries.size > 6) "\n… and ${entries.size - 6} more" else "",
                    )
                }

                val dir = File(ctx.filesDir, "models3d").apply { mkdirs() }
                val pmx = entries.entries.firstOrNull { it.key.endsWith(".pmx", true) }
                when {
                    pmx != null -> {
                        val c = PmxConverter
                            .convert(pmx.value, entries, dir, baseName())
                            .getOrThrow()
                        Outcome(
                            c.file,
                            "${c.vertices} vertices · ${c.triangles} triangles · " +
                                "${c.materials} materials · ${c.bones} bones · ${c.textures} textures",
                            c.notes,
                        )
                    }
                    entries.keys.any { it.endsWith(".gltf", true) || it.endsWith(".glb", true) } -> {
                        val p = GltfPacker.packZip(bytes, dir, baseName()).getOrThrow()
                        Outcome(p.file, "${p.textures} textures packed in", emptyList())
                    }
                    else -> throw IllegalArgumentException(
                        "No model found in that archive. It needs a .pmx, .gltf or .glb inside — " +
                            "found: " + entries.keys.take(4).joinToString(", ") { it.substringAfterLast('/') }
                    )
                }
            }

            outcome
                .onSuccess { r ->
                    runCatching {
                        db.generationDao().insert(
                            GenerationEntity(
                                type = "3d",
                                prompt = "${_state.value.sourceName ?: "Imported"} → glb",
                                modelPath = r.file.absolutePath,
                            )
                        )
                    }
                    _state.update {
                        it.copy(
                            busy = false, status = null,
                            resultPath = r.file.absolutePath, resultName = r.file.name,
                            summary = r.summary, notes = r.notes,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, status = null, error = e.message) }
                }
        }
    }

    private class Outcome(val file: File, val summary: String, val notes: List<String>)

    fun saveTo(destination: Uri) {
        val path = _state.value.resultPath ?: return
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

    fun suggestedFileName(): String = "${safe(baseName())}.glb"

    private fun baseName(): String =
        (_state.value.sourceName ?: "model").substringBeforeLast('.')

    private fun safe(v: String) =
        v.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(48).ifBlank { "model" }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.zip"
    }
}
