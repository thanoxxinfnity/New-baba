package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.model.NIM_IMAGE_MODELS
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.ImageGenClient
import com.trellis.studio.network.TrellisClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class GenerateUiState(
    // Image tab
    val imagePrompt: String = "",
    val negativePrompt: String = "",
    val imageWidth: Int = 1024,
    val imageHeight: Int = 1024,
    val imageSeed: Long = 0L,
    val selectedImageModelId: String = AppPrefs.DEFAULT_IMG,
    val isGeneratingImage: Boolean = false,
    val generatedImagePath: String? = null,
    // 3D tab
    val sourceImagePath: String? = null,
    val selected3dBackend: String = "nvidia",   // "nvidia" | "fal" | "pollinations"
    val isGenerating3d: Boolean = false,
    val generatedModelPath: String? = null,
    val text3dPrompt: String = "",
    // shared
    val error: String? = null,
    val statusMessage: String? = null,
)

class GenerateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs   = AppPrefs(app)
    private val imgClient= ImageGenClient(app)
    private val trellisClient = TrellisClient(app)
    private val db      = AppDatabase.get(app)

    private val _state = MutableStateFlow(GenerateUiState())
    val state: StateFlow<GenerateUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.selectedImg.collect { id -> _state.update { it.copy(selectedImageModelId = id) } }
        }
        viewModelScope.launch {
            prefs.selected3d.collect { b -> _state.update { it.copy(selected3dBackend = b) } }
        }
    }

    fun setImagePrompt(v: String)    = _state.update { it.copy(imagePrompt = v) }
    fun setNegativePrompt(v: String) = _state.update { it.copy(negativePrompt = v) }
    fun setImageWidth(v: Int)        = _state.update { it.copy(imageWidth = v) }
    fun setImageHeight(v: Int)       = _state.update { it.copy(imageHeight = v) }
    fun setImageSeed(v: Long)        = _state.update { it.copy(imageSeed = v) }
    fun setSourceImagePath(v: String?) = _state.update { it.copy(sourceImagePath = v) }
    fun clearError()                 = _state.update { it.copy(error = null, statusMessage = null) }
    fun showError(msg: String)       = _state.update { it.copy(error = msg) }

    fun selectImageModel(id: String) {
        _state.update { it.copy(selectedImageModelId = id) }
        viewModelScope.launch { prefs.setSelectedImg(id) }
    }

    fun select3dBackend(v: String) {
        _state.update { it.copy(selected3dBackend = v) }
        viewModelScope.launch { prefs.setSelected3d(v) }
    }

    /** Generate image using selected NIM model */
    fun generateImage() {
        val s = _state.value
        if (s.imagePrompt.isBlank()) {
            _state.update { it.copy(error = "Please enter a prompt.") }
            return
        }
        val model = NIM_IMAGE_MODELS.find { it.id == s.selectedImageModelId }
            ?: NIM_IMAGE_MODELS.first()

        viewModelScope.launch {
            _state.update { it.copy(isGeneratingImage = true, error = null, statusMessage = "Generating image…") }
            val apiKey = prefs.nvidiaKey.first()
            imgClient.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = s.imagePrompt,
                negativePrompt = s.negativePrompt,
                width = s.imageWidth,
                height = s.imageHeight,
                seed = s.imageSeed,
            ).onSuccess { path ->
                // Save to DB gallery
                db.generationDao().insert(GenerationEntity(
                    type = "image", prompt = s.imagePrompt, modelId = model.id, imagePath = path,
                ))
                _state.update { it.copy(isGeneratingImage = false, generatedImagePath = path, statusMessage = null) }
            }.onFailure { e ->
                _state.update { it.copy(isGeneratingImage = false, error = e.message, statusMessage = null) }
            }
        }
    }

    /** Generate a 3D model from the selected source image. */
    fun generate3d() {
        val imgPath = _state.value.sourceImagePath ?: run {
            _state.update { it.copy(error = "Please select an image first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isGenerating3d = true, error = null, statusMessage = "Uploading image…") }
            try {
                val apiKey = prefs.nvidiaKey.first()
                val imageBytes = File(imgPath).readBytes()
                val mime = if (imgPath.endsWith(".png", true)) "image/png" else "image/jpeg"
                _state.update { it.copy(statusMessage = "Generating 3D model…") }

                trellisClient.generateFromImage(apiKey, imageBytes, mime)
                    .onSuccess { modelPath ->
                        db.generationDao().insert(
                            GenerationEntity(type = "3d", imagePath = imgPath, modelPath = modelPath)
                        )
                        _state.update {
                            it.copy(isGenerating3d = false, generatedModelPath = modelPath, statusMessage = null)
                        }
                    }.onFailure { e ->
                        _state.update {
                            it.copy(isGenerating3d = false, error = e.message, statusMessage = null)
                        }
                    }
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating3d = false, error = e.message, statusMessage = null) }
            }
        }
    }

    fun setTextTo3dPrompt(v: String) = _state.update { it.copy(text3dPrompt = v) }

    /** Text → 3D: the path that reliably accepts user input on this account. */
    fun generate3dFromText() {
        val prompt = _state.value.text3dPrompt.trim()
        if (prompt.isBlank()) {
            _state.update { it.copy(error = "Describe the object you want.") }
            return
        }
        if (_state.value.isGenerating3d) return
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating3d = true, error = null, statusMessage = "Building 3D from text…")
            }
            try {
                val apiKey = prefs.nvidiaKey.first()
                trellisClient.generateFromText(apiKey, prompt)
                    .onSuccess { modelPath ->
                        db.generationDao().insert(
                            GenerationEntity(type = "3d", prompt = prompt, modelPath = modelPath)
                        )
                        _state.update {
                            it.copy(isGenerating3d = false, generatedModelPath = modelPath, statusMessage = null)
                        }
                    }.onFailure { e ->
                        _state.update {
                            it.copy(isGenerating3d = false, error = e.message, statusMessage = null)
                        }
                    }
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating3d = false, error = e.message, statusMessage = null) }
            }
        }
    }

    /**
     * Runs TRELLIS on NVIDIA's bundled sample. This is the one input the
     * deployed endpoint accepts, so it always yields a real, viewable model.
     */
    fun generateSample3d() {
        viewModelScope.launch {
            _state.update {
                it.copy(isGenerating3d = true, error = null, statusMessage = "Generating sample 3D model…")
            }
            try {
                val apiKey = prefs.nvidiaKey.first()
                trellisClient.generateSample(apiKey)
                    .onSuccess { modelPath ->
                        db.generationDao().insert(GenerationEntity(type = "3d", modelPath = modelPath))
                        _state.update {
                            it.copy(isGenerating3d = false, generatedModelPath = modelPath, statusMessage = null)
                        }
                    }.onFailure { e ->
                        _state.update {
                            it.copy(isGenerating3d = false, error = e.message, statusMessage = null)
                        }
                    }
            } catch (e: Exception) {
                _state.update { it.copy(isGenerating3d = false, error = e.message, statusMessage = null) }
            }
        }
    }
}
