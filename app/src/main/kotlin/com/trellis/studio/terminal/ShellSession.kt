package com.trellis.studio.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs real shell commands inside the app's own sandbox.
 *
 * Scope and limits — these are Android platform rules, not choices:
 *  - commands run as the app's UID, with no root
 *  - only the app's private directories are writable
 *  - Android 10+ (W^X) forbids executing binaries written at runtime, so
 *    installing a toolchain and compiling here is not possible
 */
class ShellSession(context: Context) {

    /** Writable working root for the session. */
    val root: File = File(context.filesDir, "workspace").apply { mkdirs() }

    var cwd: File = root
        private set

    /** Commands handled in-process, either for speed or because `cd` must persist. */
    private val builtins = setOf("cd", "pwd", "clear", "help", "exit")

    fun isBuiltin(cmd: String): Boolean = cmd.trim().split(Regex("\\s+")).firstOrNull() in builtins

    /**
     * Executes [line] and returns its combined stdout/stderr.
     * Never throws: failures come back as readable text.
     */
    suspend fun run(line: String): ShellResult = withContext(Dispatchers.IO) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@withContext ShellResult("", 0)

        val parts = trimmed.split(Regex("\\s+"))
        when (parts[0]) {
            "cd" -> return@withContext changeDir(parts.getOrNull(1))
            "pwd" -> return@withContext ShellResult(cwd.absolutePath, 0)
            "help" -> return@withContext ShellResult(HELP, 0)
        }

        val proc = runCatching {
            ProcessBuilder("/system/bin/sh", "-c", trimmed)
                .directory(cwd)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { e ->
            return@withContext ShellResult("shell: cannot start: ${e.message}", 127)
        }

        val output = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val text = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                text to code
            }
        }

        if (output == null) {
            runCatching { proc.destroyForcibly() }
            return@withContext ShellResult("shell: timed out after ${TIMEOUT_MS / 1000}s", 124)
        }

        val (text, code) = output
        val capped = if (text.length > MAX_OUTPUT) {
            text.take(MAX_OUTPUT) + "\n… output truncated (${text.length} chars)"
        } else text
        ShellResult(capped.trimEnd(), code)
    }

    private fun changeDir(target: String?): ShellResult {
        val dest = when {
            target == null || target == "~" -> root
            target.startsWith("/") -> File(target)
            else -> File(cwd, target)
        }
        val canonical = runCatching { dest.canonicalFile }.getOrElse { dest }
        return when {
            !canonical.exists() -> ShellResult("cd: ${target}: no such directory", 1)
            !canonical.isDirectory -> ShellResult("cd: ${target}: not a directory", 1)
            !canonical.canRead() -> ShellResult("cd: ${target}: permission denied", 1)
            else -> {
                cwd = canonical
                ShellResult("", 0)
            }
        }
    }

    /** Short path for the prompt, e.g. ~/src instead of the full sandbox path. */
    fun prompt(): String {
        val abs = cwd.absolutePath
        val base = root.absolutePath
        return if (abs.startsWith(base)) "~" + abs.removePrefix(base) else abs
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT = 60_000

        val HELP = """
            Trellis Terminal — a real shell in the app's private sandbox.

            Works: ls, cat, echo, grep, mkdir, rm, cp, mv, touch, find, ps,
                   df, du, wc, head, tail, sed, date, whoami, getprop, pm, am,
                   plus cd / pwd / clear / help.

            Writable: ~ (the app workspace). The rest of the device is read-only
            or hidden — Android runs this as the app's own user, without root.

            Not possible here: compiling an APK. Android blocks executing
            binaries created at runtime (W^X), and there is no JDK, aapt2 or d8
            on the device. Use "Export Project" in Generate to get a buildable
            .zip instead.
        """.trimIndent()
    }
}

data class ShellResult(val output: String, val exitCode: Int)
