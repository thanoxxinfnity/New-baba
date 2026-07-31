package com.trellis.studio.viewmodel

import android.app.Application
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.TrellisClient
import com.trellis.studio.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One queued text-to-3D request. */
data class ModelJob(
    val id: Long,
    val prompt: String,
    val detail: TrellisClient.Detail = TrellisClient.Detail.STANDARD,
    val status: Status = Status.QUEUED,
    val attempt: Int = 0,
    val totalAttempts: Int = 0,
    val modelPath: String? = null,
    val error: String? = null,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }

    val progressLabel: String
        get() = when (status) {
            Status.QUEUED -> "Waiting…"
            Status.RUNNING -> if (attempt > 0) "Generating — try $attempt of $totalAttempts" else "Starting…"
            Status.DONE -> "Ready"
            Status.FAILED -> error ?: "Failed"
        }
}

/**
 * Runs 3D generations one after another on an application-scoped coroutine, so
 * a queue keeps going while the user moves around the app — leaving the screen
 * no longer cancels the work.
 *
 * Jobs run sequentially on purpose: firing several at once made NVIDIA's
 * endpoint fail far more often in testing than spacing them out did.
 */
class ModelQueue private constructor(app: Application) {

    private val appContext = app

    private val prefs = AppPrefs(app)
    private val db = AppDatabase.get(app)
    private val client = TrellisClient(app)

    // Application-scoped: survives ViewModel and screen lifecycles.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _jobs = MutableStateFlow<List<ModelJob>>(emptyList())
    val jobs: StateFlow<List<ModelJob>> = _jobs.asStateFlow()

    private var worker: Job? = null
    private var nextId = 1L

    /** Adds prompts to the queue. Blank lines are ignored, duplicates allowed. */
    fun enqueue(prompts: List<String>, detail: TrellisClient.Detail = TrellisClient.Detail.STANDARD) {
        val clean = prompts.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        _jobs.update { current ->
            current + clean.map { ModelJob(id = nextId++, prompt = it, detail = detail) }
        }
        // A foreground service is what keeps this running once the app is
        // backgrounded or swiped away.
        GenerationService.start(appContext)
        start()
    }

    fun remove(id: Long) {
        _jobs.update { it.filterNot { job -> job.id == id && job.status != ModelJob.Status.RUNNING } }
    }

    fun clearFinished() {
        _jobs.update {
            it.filterNot { job ->
                job.status == ModelJob.Status.DONE || job.status == ModelJob.Status.FAILED
            }
        }
    }

    /** Puts a failed job back in the queue. */
    fun retry(id: Long) {
        _jobs.update { list ->
            list.map { if (it.id == id) it.copy(status = ModelJob.Status.QUEUED, error = null) else it }
        }
        GenerationService.start(appContext)
        start()
    }

    private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            val apiKey = prefs.nvidiaKey.first()
            while (true) {
                val job = _jobs.value.firstOrNull { it.status == ModelJob.Status.QUEUED } ?: break

                update(job.id) { it.copy(status = ModelJob.Status.RUNNING, attempt = 0) }

                if (apiKey.isBlank()) {
                    update(job.id) {
                        it.copy(
                            status = ModelJob.Status.FAILED,
                            error = "Add your NVIDIA API key in Settings.",
                        )
                    }
                    continue
                }

                client.generateFromText(
                    apiKey = apiKey,
                    prompt = job.prompt,
                    detail = job.detail,
                    onAttempt = { attempt, total ->
                        update(job.id) { it.copy(attempt = attempt, totalAttempts = total) }
                    },
                ).onSuccess { path ->
                    runCatching {
                        db.generationDao().insert(
                            GenerationEntity(type = "3d", prompt = job.prompt, modelPath = path)
                        )
                    }
                    update(job.id) {
                        it.copy(status = ModelJob.Status.DONE, modelPath = path, error = null)
                    }
                }.onFailure { e ->
                    update(job.id) {
                        it.copy(status = ModelJob.Status.FAILED, error = e.message ?: "Failed")
                    }
                }
            }
        }
    }

    private fun update(id: Long, transform: (ModelJob) -> ModelJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        @Volatile private var instance: ModelQueue? = null

        fun get(app: Application): ModelQueue = instance ?: synchronized(this) {
            instance ?: ModelQueue(app).also { instance = it }
        }
    }
}
