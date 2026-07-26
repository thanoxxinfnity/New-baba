package com.trellis.studio.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DownloadHelper {

    /**
     * Copies [file] into the public Downloads/TRELLIS folder via MediaStore.
     * @return the display path shown to the user.
     */
    suspend fun saveToDownloads(
        context: Context,
        file: File,
        mimeType: String = "model/gltf-binary"
    ): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/TRELLIS"
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AppException.Api("Could not create the download file.")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw AppException.Api("Could not write the download file.")
        "Downloads/TRELLIS/${file.name}"
    }

    /** Saves [content] as a text file into Downloads/TRELLIS — used for chat code exports. */
    suspend fun saveTextToDownloads(context: Context, fileName: String, content: String): String =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/TRELLIS"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw AppException.Api("Could not create the download file.")
            resolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray())
            } ?: throw AppException.Api("Could not write the download file.")
            "Downloads/TRELLIS/$fileName"
        }
}
