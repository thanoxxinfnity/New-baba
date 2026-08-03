package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.GameDirector
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.GameForge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A generated 3D model the user can drop into a game. */
data class GameModel(
    val id: Long,
    val name: String,
    val path: String,
)

/**
 * Backs the Game screen: pick some of your generated 3D models, describe the
 * game, and get a complete Godot project that uses them — assembled locally, no
 * blind UI automation. The AI writes the GDScript; [GameForge] wraps it into a
 * project that opens and runs in Godot.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val director = GameDirector()

    val models: StateFlow<List<GameModel>> = db.generationDao().getByType("3d")
        .map { rows ->
            rows.mapNotNull { row ->
                val path = row.modelPath ?: return@mapNotNull null
                if (!File(path).exists()) return@mapNotNull null
                GameModel(row.id, row.prompt?.takeIf { it.isNotBlank() } ?: File(path).name, path)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The finished project zip, ready to share/open. */
    private val _built = MutableStateFlow<File?>(null)
    val built: StateFlow<File?> = _built.asStateFlow()

    val busy: StateFlow<Boolean> = _status.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun consumeMessage() { _message.value = null }
    fun consumeBuilt() { _built.value = null }

    /**
     * Writes the game code, assembles the Godot project and zips it. [selected]
     * are the models to include; [idea] is the plain-language description.
     */
    fun build(selected: List<GameModel>, idea: String) = viewModelScope.launch {
        if (_status.value != null) return@launch
        val apiKey = prefs.nvidiaKey.first()
        if (apiKey.isBlank()) {
            _message.value = "Add your NVIDIA API key in Settings first."
            return@launch
        }
        if (idea.isBlank()) {
            _message.value = "Describe the game you want first."
            return@launch
        }

        val model = prefs.gameModel.first()
        val name = gameName(idea)

        // Map each chosen model to the res:// name the AI will be told to load.
        val inputs = selected.mapIndexed { i, m ->
            GameForge.ModelInput(File(m.path), m.name) to GameForge.modelName(m.name, i + 1)
        }
        val modelLines = inputs.map { (input, resName) -> "- $resName.glb : ${input.role}" }

        _status.value = "Writing the game code…"
        director.write(apiKey, model, idea, modelLines)
            .onSuccess { code ->
                _status.value = "Assembling the Godot project…"
                GameForge.assemble(name, code, inputs.map { it.first }, FileExport.outputDir(getApplication()))
                    .onSuccess { assembled ->
                        // Register it in the gallery so it is not lost.
                        runCatching {
                            db.generationDao().insert(
                                GenerationEntity(
                                    type = "game",
                                    prompt = "$name (Godot project)",
                                    modelPath = assembled.zip.absolutePath,
                                )
                            )
                        }
                        _status.value = null
                        _built.value = assembled.zip
                        _message.value = "Game ready: ${assembled.zip.name}"
                    }
                    .onFailure {
                        _status.value = null
                        _message.value = it.message ?: "Couldn't assemble the project."
                    }
            }
            .onFailure {
                _status.value = null
                _message.value = it.message ?: "Couldn't write the game code."
            }
    }

    private fun gameName(idea: String): String =
        idea.trim().split(Regex("\\s+")).take(4).joinToString(" ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "VOID Game" }
}
