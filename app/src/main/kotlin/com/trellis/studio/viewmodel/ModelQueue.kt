package com.trellis.studio.viewmodel

import android.app.Application
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import com.trellis.studio.data.entity.QueuedJobEntity
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.TrellisClient
import com.trellis.studio.util.MeshAnalyzer
import com.trellis.studio.service.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One queued text-to-3D request. */
data class ModelJob(
    val id: Long,
    val prompt: String,
    val detail: TrellisClient.Detail = TrellisClient.Detail.STANDARD,
    /** Set to repeat a result exactly, or to ask for a different take. */
    val seed: Long? = null,
    val status: Status = Status.QUEUED,
    val attempt: Int = 0,
    val totalAttempts: Int = 0,
    val modelPath: String? = null,
    val error: String? = null,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }

    val progressLabel: String
        get() = when (status) {
            Status.QUEUED -> "Waiting in queue…"
            Status.RUNNING -> when {
                attempt > 1 -> "NVIDIA busy — attempt $attempt of $totalAttempts (max ~90s)"
                else -> "Contacting NVIDIA 3D service…"
            }
            Status.DONE -> "Ready — tap to view"
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

    /**
     * False until restored jobs have been loaded. The service watches this: the
     * queue looks empty for the first moments after a restart, and stopping on
     * that would kill the very work being restored.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        // Anything left over from a previous run is picked back up. Without this
        // the queue only existed in memory, so swiping the app away silently
        // threw away every pending job.
        scope.launch {
            val pending = runCatching { db.queueDao().pending() }.getOrDefault(emptyList())
            if (pending.isEmpty()) { _ready.value = true; return@launch }
            _jobs.update { current ->
                current + pending.map { row ->
                    ModelJob(
                        id = row.id,
                        prompt = row.prompt,
                        detail = runCatching { TrellisClient.Detail.valueOf(row.detail) }
                            .getOrDefault(TrellisClient.Detail.STANDARD),
                    )
                }
            }
            _ready.value = true
            GenerationService.start(appContext)
            start()
        }
    }

    /** Adds prompts to the queue. Blank lines are ignored, duplicates allowed. */
    fun enqueue(
        prompts: List<String>,
        detail: TrellisClient.Detail = TrellisClient.Detail.STANDARD,
        seed: Long? = null,
    ) {
        val clean = prompts.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        // A foreground service is what keeps this running once the app is
        // backgrounded; the database is what gets it back if the process dies.
        GenerationService.start(appContext)
        scope.launch {
            clean.forEach { prompt ->
                val id = runCatching {
                    db.queueDao().insert(QueuedJobEntity(prompt = prompt, detail = detail.name))
                }.getOrDefault(System.nanoTime())
                _jobs.update { it + ModelJob(id = id, prompt = prompt, detail = detail, seed = seed) }
            }
            start()
        }
    }

    fun remove(id: Long) {
        _jobs.update { it.filterNot { job -> job.id == id && job.status != ModelJob.Status.RUNNING } }
        scope.launch { runCatching { db.queueDao().delete(id) } }
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
        scope.launch {
            runCatching {
                val job = _jobs.value.firstOrNull { it.id == id } ?: return@runCatching
                db.queueDao().insert(QueuedJobEntity(id = id, prompt = job.prompt, detail = job.detail.name))
            }
        }
        _jobs.update { list ->
            list.map {
                if (it.id == id) it.copy(status = ModelJob.Status.QUEUED, attempt = 0, error = null)
                else it
            }
        }
        GenerationService.start(appContext)
        start()
    }

    private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val apiKey = prefs.nvidiaKey.first()
                val job = _jobs.value.firstOrNull { it.status == ModelJob.Status.QUEUED } ?: break

                update(job.id) { it.copy(status = ModelJob.Status.RUNNING, attempt = 0) }

                if (apiKey.isBlank()) {
                    runCatching { db.queueDao().delete(job.id) }
                    update(job.id) {
                        it.copy(
                            status = ModelJob.Status.FAILED,
                            error = "Add your NVIDIA API key in Settings.",
                        )
                    }
                    continue
                }

                // Hard cap: if NVIDIA is down, don't block the queue for 9 minutes.
                // 100s covers MAX_ATTEMPTS=4 (2 rounds × 45s each) with a margin.
                val result = withTimeoutOrNull(100_000L) {
                    client.generateFromText(
                        apiKey = apiKey,
                        prompt = job.prompt,
                        detail = job.detail,
                        seed = job.seed,
                        onAttempt = { attempt, total ->
                            update(job.id) { it.copy(attempt = attempt, totalAttempts = total) }
                        },
                    )
                } ?: Result.failure(Exception("NVIDIA's 3D service timed out — try again later"))

                result.onSuccess { path ->
                    // Measure the model as soon as it lands: what shape it is and
                    // where its limbs are. Doing it here means the rig is built on
                    // measurements by the time the user asks for one, instead of on
                    // bounding-box guesses.
                    val shape = runCatching {
                        MeshAnalyzer.analyse(java.io.File(path)).getOrNull()?.summary
                    }.getOrNull()
                    runCatching {
                        db.generationDao().insert(
                            GenerationEntity(
                                type = "3d",
                                prompt = job.prompt,
                                modelId = shape,
                                modelPath = path,
                            )
                        )
                    }
                    runCatching { db.queueDao().delete(job.id) }
                    update(job.id) {
                        it.copy(status = ModelJob.Status.DONE, modelPath = path, error = null)
                    }
                }.onFailure { e ->
                    runCatching { db.queueDao().delete(job.id) }
                    update(job.id) {
                        it.copy(status = ModelJob.Status.FAILED, error = shortError(e.message))
                    }
                    // Brief pause before the next job — lets NVIDIA recover a little.
                    delay(3_000)
                }
            }
        }
    }

    /** Queue cards show one line, so failures have to fit on one line. */
    private fun shortError(message: String?): String = when {
        message == null -> "Failed"
        message.contains("overloaded", true) || message.contains("refused all", true) ->
            "NVIDIA's 3D service is overloaded — tap retry later"
        message.contains("API key", true) -> "Add your NVIDIA API key in Settings"
        message.contains("internet", true) || message.contains("connection", true) ->
            "No connection"
        message.length <= 60 -> message
        else -> message.take(57).substringBeforeLast(' ') + "…"
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
