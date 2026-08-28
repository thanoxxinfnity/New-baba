package com.trellis.studio.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writing files the user can open and share: single files and .zip archives. */
object FileExport {

    /** Where generated files live. Exposed through the FileProvider in the manifest. */
    fun outputDir(context: Context): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    /** A file inside the export dir with a safe, unique name. */
    fun newFile(context: Context, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val dir = outputDir(context)
        var file = File(dir, safe)
        if (file.exists()) {
            val stem = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', "")
            val suffix = if (ext.isBlank()) "" else ".$ext"
            file = File(dir, "${stem}_${System.currentTimeMillis()}$suffix")
        }
        return file
    }

    suspend fun writeText(context: Context, name: String, content: String): File =
        withContext(Dispatchers.IO) {
            newFile(context, name).apply { writeText(content) }
        }

    /**
     * Zips a set of in-memory files. [entries] maps archive path → contents,
     * so nested paths like "app/src/Main.kt" are supported.
     */
    suspend fun writeZip(
        context: Context,
        name: String,
        entries: Map<String, String>,
    ): File = withContext(Dispatchers.IO) {
        val target = newFile(context, if (name.endsWith(".zip")) name else "$name.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        target
    }

    /** Recursively zips a directory from the terminal workspace. */
    suspend fun zipDirectory(context: Context, dir: File, name: String): File =
        withContext(Dispatchers.IO) {
            val target = newFile(context, if (name.endsWith(".zip")) name else "$name.zip")
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                dir.walkTopDown()
                    .filter { it.isFile && it.absolutePath != target.absolutePath }
                    .forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(dir).path))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            target
        }

    /** Opens the system share sheet so the file can leave the sandbox. */
    fun share(context: Context, file: File, mime: String = "application/octet-stream") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share ${file.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
