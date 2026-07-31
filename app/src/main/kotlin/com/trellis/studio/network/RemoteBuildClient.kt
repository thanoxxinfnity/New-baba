package com.trellis.studio.network

import android.content.Context
import com.trellis.studio.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * Talks to the user's own build server (exposed over ngrok) so real APKs can be
 * compiled with a real Android SDK.
 *
 * Android itself cannot compile an APK — W^X blocks executing binaries created
 * at runtime, and there is no JDK/aapt2/d8 on the device. Doing the build on a
 * machine that has the SDK is the only way to get a genuine .apk, so the app
 * ships a small server (tools/trellis_build_server.py) that exposes:
 *
 *   GET  /health                 -> {"ok":true,"sdk":true,"gradle":"8.x"}
 *   POST /exec   {"cmd":"…"}     -> {"output":"…","exitCode":0}
 *   POST /build  {"files":{…}}   -> {"jobId":"…"}
 *   GET  /build/{id}             -> {"state":"running|done|failed","log":"…","artifact":"…"}
 *   GET  /artifacts              -> [{"name":"app.apk","size":123,"url":"/artifacts/app.apk"}]
 *   GET  /artifacts/{name}       -> binary download
 */
class RemoteBuildClient(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    // NVIDIA rejects a charset parameter outright:
    //   415 "Unsupported media type: application/json; charset=utf-8.
    //        It must be application/json"
    private val JSON_MEDIA = "application/json".toMediaType()

    private fun base(url: String) = url.trimEnd('/')

    private fun Request.Builder.commonHeaders() = apply {
        // ngrok's free tier shows an interstitial to browsers without this.
        header("ngrok-skip-browser-warning", "true")
        header("Accept", "application/json")
    }

    /** Quick reachability + capability check. */
    suspend fun health(serverUrl: String): Result<BuildServerHealth> = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) {
            return@withContext Result.failure(Exception("No build server URL set. Add it in Settings."))
        }
        runCatching {
            val req = Request.Builder().url("${base(serverUrl)}/health").commonHeaders().get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception(describeFailure(resp.code, body, resp))
                json.decodeFromString<BuildServerHealth>(body)
            }
        }.mapRemoteFailure()
    }

    /** Runs one shell command on the build machine. */
    suspend fun exec(serverUrl: String, cmd: String): Result<RemoteExecResult> =
        withContext(Dispatchers.IO) {
            if (serverUrl.isBlank()) {
                return@withContext Result.failure(Exception("No build server URL set. Add it in Settings."))
            }
            runCatching {
                val payload = json.encodeToString(RemoteExecRequest(cmd))
                val req = Request.Builder()
                    .url("${base(serverUrl)}/exec")
                    .commonHeaders()
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception(describeFailure(resp.code, body, resp))
                    json.decodeFromString<RemoteExecResult>(body)
                }
            }.mapRemoteFailure()
        }

    /**
     * Sends a set of project files and builds them into an APK.
     * [onLog] receives the build log as it grows.
     */
    suspend fun build(
        serverUrl: String,
        files: Map<String, String>,
        onLog: suspend (String) -> Unit = {},
    ): Result<RemoteArtifact> = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) {
            return@withContext Result.failure(Exception("No build server URL set. Add it in Settings."))
        }
        if (files.isEmpty()) {
            return@withContext Result.failure(Exception("No project files to build."))
        }
        runCatching {
            val payload = json.encodeToString(RemoteBuildRequest(files))
            val req = Request.Builder()
                .url("${base(serverUrl)}/build")
                .commonHeaders()
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            val jobId = http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception(describeFailure(resp.code, body, resp))
                json.decodeFromString<RemoteBuildStarted>(body).jobId
            }

            // Poll until the build finishes, streaming new log output as it appears.
            var seen = 0
            repeat(MAX_POLLS) {
                delay(2_000)
                val statusReq = Request.Builder()
                    .url("${base(serverUrl)}/build/$jobId")
                    .commonHeaders().get().build()
                val status = http.newCall(statusReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception(describeFailure(resp.code, body, resp))
                    json.decodeFromString<RemoteBuildStatus>(body)
                }

                val log = status.log.orEmpty()
                if (log.length > seen) {
                    onLog(log.substring(seen))
                    seen = log.length
                }

                when (status.state) {
                    "done" -> {
                        val artifact = status.artifact
                            ?: throw Exception("Build finished but produced no APK. Check the log.")
                        return@runCatching RemoteArtifact(
                            name = artifact.substringAfterLast('/'),
                            url = if (artifact.startsWith("http")) artifact
                            else "${base(serverUrl)}/artifacts/${artifact.substringAfterLast('/')}",
                            size = status.size ?: 0L,
                        )
                    }
                    "failed" -> throw Exception(status.error ?: "Build failed. Check the log.")
                }
            }
            throw Exception("Build timed out after ${MAX_POLLS * 2 / 60} minutes.")
        }.mapRemoteFailure()
    }

    /** Lists APKs and other artifacts the server has produced. */
    suspend fun artifacts(serverUrl: String): Result<List<RemoteArtifact>> =
        withContext(Dispatchers.IO) {
            if (serverUrl.isBlank()) {
                return@withContext Result.failure(Exception("No build server URL set. Add it in Settings."))
            }
            runCatching {
                val req = Request.Builder()
                    .url("${base(serverUrl)}/artifacts").commonHeaders().get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception(describeFailure(resp.code, body, resp))
                    json.decodeFromString<List<RemoteArtifact>>(body).map {
                        it.copy(
                            url = if (it.url.startsWith("http")) it.url
                            else "${base(serverUrl)}${it.url}"
                        )
                    }
                }
            }.mapRemoteFailure()
        }

    /** Downloads an artifact into the app's export dir so it can be installed/shared. */
    suspend fun download(artifact: RemoteArtifact): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val target = File(dir, artifact.name.ifBlank { "artifact.apk" })
            val req = Request.Builder().url(artifact.url).commonHeaders().get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Download failed (HTTP ${resp.code}).")
                val bytes = resp.body?.bytes() ?: throw Exception("Downloaded file was empty.")
                FileOutputStream(target).use { it.write(bytes) }
            }
            target
        }.mapRemoteFailure()
    }

    /** Turns ngrok/server failures into something a user can act on. */
    private fun describeFailure(code: Int, body: String, resp: Response): String {
        val ngrokError = resp.header("ngrok-error-code")
        return when {
            ngrokError == "ERR_NGROK_3200" ->
                "Your build server is offline. Start the tunnel on your PC, then retry."
            ngrokError == "ERR_NGROK_8012" ->
                "The tunnel is up but nothing is listening on that port. Start trellis_build_server.py."
            ngrokError != null -> "Tunnel error ($ngrokError). Check your ngrok session."
            code == 404 ->
                "The server answered but has no build API. Run trellis_build_server.py behind the tunnel."
            code == 401 || code == 403 -> "Build server rejected the request ($code)."
            else -> "Build server error ($code): ${body.take(180)}"
        }
    }

    private companion object {
        const val MAX_POLLS = 450   // ~15 minutes
    }
}

private fun <T> Result<T>.mapRemoteFailure(): Result<T> = recoverCatching { e ->
    throw Exception(
        when (e) {
            is java.net.UnknownHostException -> "Cannot reach the build server. Check the URL and your connection."
            is java.net.SocketTimeoutException -> "The build server did not respond in time."
            is javax.net.ssl.SSLException -> "Secure connection to the build server failed."
            else -> e.message ?: "Build server request failed."
        }
    )
}
