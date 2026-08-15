package com.trellis.studio.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.HfVideoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Free AI video generation (text→video, image→video) via an LTX-Video Space. */
class AiVideoViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val client = HfVideoClient()
    private val db = AppDatabase.get(app)

    data class State(
        val prompt: String = "",
        val durationSec: Int = 2,
        val imagePath: String? = null,
        val busy: Boolean = false,
        val videoPath: String? = null,
        val status: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setPrompt(v: String) = _state.update { it.copy(prompt = v) }
    fun setDuration(v: Int) = _state.update { it.copy(durationSec = v) }
    fun clearImage() = _state.update { it.copy(imagePath = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(getApplication<Application>().cacheDir, "aivideo").apply { mkdirs() }
                    val f = File(dir, "in_${System.currentTimeMillis()}.png")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f.absolutePath
                }.getOrNull()
            }
            _state.update { it.copy(imagePath = path) }
        }
    }

    fun generate() {
        val s = _state.value
        if (s.busy || (s.prompt.isBlank() && s.imagePath == null)) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Generating video… (free GPU, ~10–60s)", error = null, videoPath = null) }
            val token = prefs.hfToken.first()
            client.generate(
                spaceUrl = HfVideoClient.DEFAULT_VIDEO_SPACE,
                hfToken = token,
                prompt = s.prompt,
                durationSec = s.durationSec,
                image = s.imagePath?.let { File(it) },
            ).onSuccess { bytes ->
                val path = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "videos").apply { mkdirs() }
                    File(dir, "ai_${System.currentTimeMillis()}.mp4").apply { writeBytes(bytes) }.absolutePath
                }
                runCatching {
                    db.generationDao().insert(GenerationEntity(type = "video", prompt = s.prompt.take(80), modelPath = path))
                }
                _state.update { it.copy(busy = false, status = null, videoPath = path) }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, status = null, error = e.message) }
            }
        }
    }
}
