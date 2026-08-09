package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.GameDirector
import com.trellis.studio.network.TrellisClient
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

/** A finished game in the history, newest first. */
data class GameHistoryItem(
    val id: Long,
    val name: String,
    val zipPath: String,
    val createdAt: Long,
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
    private val trellis = TrellisClient(app)

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

    /** Every game built so far — the generation history, newest first. */
    val games: StateFlow<List<GameHistoryItem>> = db.generationDao().getByType("game")
        .map { rows ->
            rows.mapNotNull { row ->
                val path = row.modelPath ?: return@mapNotNull null
                if (!File(path).exists()) return@mapNotNull null
                val name = (row.prompt ?: "")
                    .removeSuffix(" (Godot project)")
                    .ifBlank { "VOID Game" }
                GameHistoryItem(row.id, name, path, row.createdAt)
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
        if (apiKey.isBlank()) { _message.value = "Add your NVIDIA API key in Settings first."; return@launch }
        if (idea.isBlank()) { _message.value = "Describe the game you want first."; return@launch }

        // Map each chosen model to the res:// name the AI will be told to load.
        val inputs = selected.map { GameForge.ModelInput(File(it.path), it.name) }
        assembleGame(apiKey, prefs.gameModel.first(), gameName(idea), idea, inputs)
    }

    /**
     * The advanced-free path: the user only describes the game. VOID asks the AI
     * which 3D models it needs, generates each one (text → 3D), then writes and
     * assembles the game around them. No model picking required.
     */
    fun autoBuild(idea: String) = viewModelScope.launch {
        if (_status.value != null) return@launch
        val apiKey = prefs.nvidiaKey.first()
        if (apiKey.isBlank()) { _message.value = "Add your NVIDIA API key in Settings first."; return@launch }
        if (idea.isBlank()) { _message.value = "Describe the game you want first."; return@launch }

        val model = prefs.gameModel.first()

        _status.value = "Designing the game assets…"
        val specs = director.planAssets(apiKey, model, idea).getOrElse {
            // Planning failed — still build the game, from primitives.
            emptyList()
        }

        // Generate each asset. 3D generation is a capacity coin-flip, so a model
        // that won't come out is skipped rather than failing the whole build —
        // the game code falls back to primitives for anything missing.
        val inputs = ArrayList<GameForge.ModelInput>()
        specs.forEachIndexed { i, spec ->
            _status.value = "Generating model ${i + 1}/${specs.size}: ${spec.name}…"
            trellis.generateFromText(apiKey, spec.prompt)
                .onSuccess { path -> inputs.add(GameForge.ModelInput(File(path), spec.role)) }
        }

        if (inputs.isEmpty() && specs.isNotEmpty()) {
            _message.value = "The 3D service was busy, so the game uses simple shapes for now."
        }
        assembleGame(apiKey, model, gameName(idea), idea, inputs)
    }

    /** Shared: write the GDScript, assemble the project, store it in history. */
    private suspend fun assembleGame(
        apiKey: String,
        model: String,
        name: String,
        idea: String,
        inputs: List<GameForge.ModelInput>,
    ) {
        val resInputs = inputs.mapIndexed { i, input ->
            input to GameForge.modelName(input.role, i + 1)
        }
        val modelLines = resInputs.map { (input, resName) -> "- $resName.glb : ${input.role}" }

        _status.value = "Writing & checking the game code… (up to ~2 min)"
        director.write(apiKey, model, idea, modelLines)
            .onSuccess { code ->
                _status.value = "Assembling the Godot project…"
                GameForge.assemble(name, code, inputs, FileExport.outputDir(getApplication()))
                    .onSuccess { assembled ->
                        runCatching {
                            db.generationDao().insert(
                                GenerationEntity(
                                    type = "game",
                                    prompt = name,
                                    modelPath = assembled.zip.absolutePath,
                                )
                            )
                        }
                        _status.value = null
                        _built.value = assembled.zip
                        _message.value = "Game ready: $name"
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

    /** Re-share a game from the history. */
    fun openGame(item: GameHistoryItem) { _built.value = File(item.zipPath) }

    fun deleteGame(item: GameHistoryItem) = viewModelScope.launch {
        runCatching {
            File(item.zipPath).delete()
            db.generationDao().delete(item.id)
        }
    }

    private fun gameName(idea: String): String =
        idea.trim().split(Regex("\\s+")).take(4).joinToString(" ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "VOID Game" }
}
