package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.model.RemoteArtifact
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.RemoteApkBuilder
import com.trellis.studio.network.RemoteBuildClient
import com.trellis.studio.network.RemoteToolchain
import com.trellis.studio.network.TtydClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class BuildUiState(
    val serverUrl: String = "",
    val serverOnline: Boolean = false,
    val canBuildApk: Boolean = false,
    val artifacts: List<RemoteArtifact> = emptyList(),
    val localFiles: List<File> = emptyList(),
    val buildLog: String = "",
    val isBuilding: Boolean = false,
    val downloadingName: String? = null,
    val error: String? = null,
    /** Set when the remote machine is a ttyd terminal rather than the REST server. */
    val toolchain: RemoteToolchain? = null,
)

class BuildViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val client = RemoteBuildClient(app)
    private val ttyd = TtydClient()
    private val apkBuilder = RemoteApkBuilder(ttyd)

    private val _state = MutableStateFlow(BuildUiState())
    val state: StateFlow<BuildUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.buildServerUrl.collect { url ->
                _state.update { it.copy(serverUrl = url) }
                if (url.isNotBlank()) refresh()
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Re-checks the server and reloads both remote artifacts and local files. */
    fun refresh() {
        viewModelScope.launch {
            loadLocalFiles()
            val url = prefs.buildServerUrl.first()
            if (url.isBlank()) {
                _state.update { it.copy(serverOnline = false, canBuildApk = false, artifacts = emptyList()) }
                return@launch
            }
            // Prefer the REST build server; fall back to a plain ttyd terminal,
            // which is what "ttyd bash + ngrok" exposes.
            client.health(url)
                .onSuccess { health ->
                    _state.update {
                        it.copy(serverOnline = health.ok, canBuildApk = health.canBuildApk, error = null)
                    }
                    client.artifacts(url)
                        .onSuccess { list -> _state.update { it.copy(artifacts = list) } }
                        .onFailure { e -> _state.update { it.copy(error = e.message) } }
                }
                .onFailure {
                    ttyd.probe(url)
                        .onSuccess { tc ->
                            _state.update {
                                it.copy(
                                    serverOnline = true,
                                    canBuildApk = tc.canBuildApk,
                                    toolchain = tc,
                                    error = null,
                                )
                            }
                        }
                        .onFailure { e ->
                            _state.update {
                                it.copy(serverOnline = false, canBuildApk = false, error = e.message)
                            }
                        }
                }
        }
    }

    private fun loadLocalFiles() {
        val dirs = listOf("exports", "models3d", "generated")
            .map { File(getApplication<Application>().filesDir, it) }
        val files = dirs.filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        _state.update { it.copy(localFiles = files) }
    }

    /** Sends a project to the build server and waits for the APK. */
    fun buildProject(files: Map<String, String>) {
        if (_state.value.isBuilding) return
        viewModelScope.launch {
            val url = prefs.buildServerUrl.first()
            if (url.isBlank()) {
                _state.update { it.copy(error = "Set your build server URL in Settings first.") }
                return@launch
            }
            _state.update { it.copy(isBuilding = true, buildLog = "", error = null) }

            // ttyd terminal: build on the user's machine and pull the APK back.
            if (_state.value.toolchain != null) {
                apkBuilder.build(url, "app", files, onLog = { chunk ->
                    _state.update { it.copy(buildLog = it.buildLog + chunk) }
                }).onSuccess { artifact ->
                    _state.update {
                        it.copy(
                            isBuilding = false,
                            artifacts = listOf(artifact) + it.artifacts.filterNot { a -> a.name == artifact.name },
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(isBuilding = false, error = e.message) }
                }
                return@launch
            }

            client.build(url, files, onLog = { chunk ->
                _state.update { it.copy(buildLog = it.buildLog + chunk) }
            }).onSuccess { artifact ->
                _state.update {
                    it.copy(
                        isBuilding = false,
                        artifacts = listOf(artifact) + it.artifacts.filterNot { a -> a.name == artifact.name },
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isBuilding = false, error = e.message) }
            }
        }
    }

    /** Downloads an artifact locally; returns the file, or null on failure. */
    suspend fun download(artifact: RemoteArtifact): File? {
        _state.update { it.copy(downloadingName = artifact.name) }
        val result = client.download(artifact)
        _state.update { it.copy(downloadingName = null) }
        return result
            .onSuccess { loadLocalFiles() }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
            .getOrNull()
    }
}
