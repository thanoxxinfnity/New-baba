package com.trellis.studio.viewmodel

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.model.NIM_IMAGE_MODELS
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.ImageGenClient
import com.trellis.studio.network.VideoDirector
import com.trellis.studio.network.YouTubeClient
import com.trellis.studio.util.FileExport
import com.trellis.studio.util.SlideshowEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the free video pipeline: the AI plans scenes, each scene's image is
 * generated (free), and the images are encoded into an .mp4 with motion and
 * captions. Nothing plays until the whole file is written — the screen watches
 * [built] for the finished path.
 */
class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val director = VideoDirector()
    private val youtube = YouTubeClient()
    private val images = ImageGenClient(app)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _built = MutableStateFlow<Pair<String, String>?>(null)
    val built: StateFlow<Pair<String, String>?> = _built.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun consumeBuilt() { _built.value = null }

    val styles = listOf(
        "Anime", "Cinematic", "Realistic", "Cartoon", "Pixel art",
        "Watercolor", "Cyberpunk", "3D render", "Comic book",
    )

    /**
     * Plans, renders and encodes the video. [targetSeconds] sets roughly how long
     * and therefore how many scenes; the whole thing runs to a saved .mp4.
     */
    fun create(idea: String, style: String, targetSeconds: Int) = viewModelScope.launch {
        if (_status.value != null) return@launch
        val apiKey = prefs.nvidiaKey.first()
        if (apiKey.isBlank()) { _message.value = "Add your NVIDIA API key in Settings first."; return@launch }
        if (idea.isBlank()) { _message.value = "Describe the video first."; return@launch }

        val llm = prefs.agentModel.first()
        val ytKey = prefs.youtubeKey.first()

        _status.value = "Planning the scenes…"
        val reference = runCatching { youtube.referenceTitles(ytKey, "$idea $style") }.getOrDefault(emptyList())

        val board = director.plan(apiKey, llm, idea, style, targetSeconds, reference).getOrElse {
            _status.value = null
            _message.value = it.message ?: "Couldn't plan the video."
            return@launch
        }

        // Render each scene's image (free Pollinations), 720x1280 portrait.
        val pollModel = NIM_IMAGE_MODELS.first()
        val scenes = ArrayList<SlideshowEncoder.Scene>()
        board.scenes.forEachIndexed { i, scene ->
            _status.value = "Drawing scene ${i + 1} of ${board.scenes.size}…"
            val styled = "${scene.prompt}, ${board.style} style"
            val path = images.generateImage(
                apiKey = apiKey, model = pollModel, prompt = styled,
                width = 720, height = 1280, seed = (i + 1) * 1000L,
            ).getOrNull()
            val bmp = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            if (bmp != null) scenes += SlideshowEncoder.Scene(bmp, scene.seconds, scene.caption)
        }

        if (scenes.isEmpty()) {
            _status.value = null
            _message.value = "None of the scene images could be generated. Try again."
            return@launch
        }

        _status.value = "Stitching the video…"
        val out = File(FileExport.outputDir(getApplication()), "video_${System.currentTimeMillis()}.mp4")
        SlideshowEncoder.encode(scenes, out, onProgress = { p ->
            _status.value = "Stitching the video… ${(p * 100).toInt()}%"
        }).onSuccess { file ->
            scenes.forEach { runCatching { it.image.recycle() } }
            runCatching {
                db.generationDao().insert(
                    GenerationEntity(type = "video", prompt = board.title, modelPath = file.absolutePath)
                )
            }
            _status.value = null
            _built.value = file.absolutePath to board.title
            _message.value = "Video ready: ${board.title}"
        }.onFailure {
            scenes.forEach { runCatching { it.image.recycle() } }
            _status.value = null
            _message.value = it.message ?: "Couldn't stitch the video."
        }
    }
}
