package com.trellis.studio.data

import android.content.Context
import android.net.Uri
import com.trellis.studio.data.api.PollinationsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class ImageRepository(
    private val context: Context,
    private val api: PollinationsApi
) {

    private val imagesDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    /**
     * Generates an image from [prompt] with Pollinations FLUX and saves it to app storage.
     * @return the saved image file.
     */
    suspend fun generateImage(prompt: String): File = withContext(Dispatchers.IO) {
        val response = api.generateImage(prompt = prompt, seed = Random.nextLong(0, 1_000_000))
        if (!response.isSuccessful) {
            throw AppException.Api("Image generation failed (HTTP ${response.code()}). Please try again.")
        }
        val body = response.body()
            ?: throw AppException.Api("Image generation returned an empty response.")

        val file = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
        body.byteStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        if (file.length() == 0L) {
            file.delete()
            throw AppException.Api("Image generation returned no data.")
        }
        file
    }

    /** Copies a gallery [uri] into app storage so it can be re-used and stored in history. */
    suspend fun importFromGallery(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = File(imagesDir, "picked_${System.currentTimeMillis()}.img")
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw AppException.Api("Could not read the selected image. Please try picking it again.")
        } ?: throw AppException.Api("Could not read the selected image. Please try picking it again.")

        try {
            stream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            file.delete()
            throw AppException.Api("Could not read the selected image. Please try picking it again.")
        }

        if (file.length() == 0L) {
            file.delete()
            throw AppException.Api("The selected image appears to be empty. Please pick a different one.")
        }
        file
    }
}
