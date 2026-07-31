package com.trellis.studio.network

import com.trellis.studio.data.model.RemoteArtifact

/**
 * Builds a real APK on the user's own machine through their ttyd terminal.
 *
 * Every step here was verified against the live Cloud Shell session:
 *  - Java 21, Android SDK (platform 34, build-tools 34.0.0) and Gradle present
 *  - `gradle assembleDebug` produced app/build/outputs/apk/debug/app-debug.apk
 *  - the APK uploaded from that machine downloads as a genuine package
 *
 * The APK comes back via an upload + plain HTTPS download rather than through
 * the terminal itself: a TTY mangles binary, so base64 over the socket returns
 * corrupt bytes.
 */
class RemoteApkBuilder(private val ttyd: TtydClient = TtydClient()) {

    /** Heredoc-safe writer: quoted delimiter stops the shell touching the body. */
    private fun writeFile(path: String, content: String): String {
        val delim = "TRELLIS_EOF_${path.hashCode().toString().replace("-", "N")}"
        return buildString {
            append("mkdir -p \"\$(dirname '$path')\" && ")
            append("cat > '$path' <<'$delim'\n")
            append(content)
            if (!content.endsWith("\n")) append("\n")
            append(delim)
        }
    }

    /**
     * Writes [files] into a project directory, builds it, uploads the APK and
     * returns a downloadable artifact.
     *
     * @param files archive-relative path -> contents
     * @param onLog receives terminal output as it arrives
     */
    suspend fun build(
        terminalUrl: String,
        projectName: String,
        files: Map<String, String>,
        onLog: (String) -> Unit = {},
    ): Result<RemoteArtifact> {
        if (files.isEmpty()) return Result.failure(Exception("No project files to build."))

        val dir = "~/trellis-projects/${projectName.sanitised()}"

        // 1. Lay the project down.
        val writeScript = buildString {
            append("rm -rf $dir && mkdir -p $dir && cd $dir")
            files.forEach { (path, content) ->
                append(" && ")
                append(writeFile("$dir/${path.trimStart('/')}", content))
            }
        }
        ttyd.run(terminalUrl, writeScript, timeoutMs = 90_000, onOutput = onLog)
            .onFailure { return Result.failure(it) }

        // 2. Make sure the SDK location is set, then build.
        val buildScript = buildString {
            append("cd $dir && ")
            append("echo \"sdk.dir=\$ANDROID_HOME\" > local.properties && ")
            append("(if [ -x ./gradlew ]; then ./gradlew assembleDebug --no-daemon; ")
            append("else gradle assembleDebug --no-daemon; fi) 2>&1 | tail -40")
        }
        val buildOut = ttyd.run(terminalUrl, buildScript, timeoutMs = 900_000, onOutput = onLog)
            .getOrElse { return Result.failure(it) }

        // 3. Locate the APK.
        val findOut = ttyd.run(
            terminalUrl,
            "find $dir -name '*.apk' -newermt '-30 minutes' | head -1",
            timeoutMs = 60_000,
        ).getOrDefault("")
        val apkPath = findOut.lines().map { it.trim() }
            .firstOrNull { it.endsWith(".apk") }

        if (apkPath.isNullOrBlank()) {
            val reason = buildOut.lines().lastOrNull { it.contains("error", true) || it.contains("FAILURE", true) }
            return Result.failure(
                Exception(reason ?: "Build finished but produced no APK. Check the build log.")
            )
        }

        // 4. Upload it so the phone can fetch it over plain HTTPS.
        val uploadOut = ttyd.run(
            terminalUrl,
            "curl -sS -F \"file=@$apkPath\" https://tmpfiles.org/api/v1/upload",
            timeoutMs = 300_000,
            onOutput = onLog,
        ).getOrElse { return Result.failure(it) }

        val pageUrl = UPLOAD_URL.find(uploadOut)?.value
            ?: return Result.failure(
                Exception("APK built at $apkPath but the upload failed. Output: ${uploadOut.take(160)}")
            )

        // tmpfiles serves the file from /dl/<id>, not the page URL.
        val directUrl = pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")

        val size = ttyd.run(terminalUrl, "stat -c%s $apkPath", timeoutMs = 45_000)
            .getOrDefault("").lines().firstOrNull { it.trim().toLongOrNull() != null }
            ?.trim()?.toLongOrNull() ?: 0L

        return Result.success(
            RemoteArtifact(name = apkPath.substringAfterLast('/'), url = directUrl, size = size)
        )
    }

    private fun String.sanitised() =
        replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }

    private companion object {
        val UPLOAD_URL = Regex("""https://tmpfiles\.org/[A-Za-z0-9]+/[^"\\\s]+""")
    }
}
