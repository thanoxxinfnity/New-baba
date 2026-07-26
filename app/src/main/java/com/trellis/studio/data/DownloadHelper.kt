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
    suspend fun saveToDownloads(context: Context, file: File): String =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "model/gltf-binary")
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
}
