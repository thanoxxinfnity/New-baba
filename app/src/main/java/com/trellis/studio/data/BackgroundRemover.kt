package com.trellis.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Removes the background from a photo on-device with ML Kit Subject Segmentation
 * (free, no API key, no network call) and composites the subject onto a plain
 * white canvas — a cleaner input for 3D reconstruction than a busy background.
 */
class BackgroundRemover(private val context: Context) {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
    )

    /** @return the processed file, or the original [imageFile] if no clear subject was found. */
    suspend fun removeBackground(imageFile: File): File = withContext(Dispatchers.Default) {
        val original = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw AppException.Api("Could not read the image file.")

        val inputImage = InputImage.fromBitmap(original, 0)
        val result = segmenter.process(inputImage).awaitTask()
        val foreground = result.foregroundBitmap
            ?: return@withContext imageFile

        val composited = Bitmap.createBitmap(
            foreground.width,
            foreground.height,
            Bitmap.Config.ARGB_8888
        )
        Canvas(composited).apply {
            drawColor(Color.WHITE)
            drawBitmap(foreground, 0f, 0f, null)
        }

        val output = File(imageFile.parentFile, "nobg_${imageFile.nameWithoutExtension}.png")
        output.outputStream().use { out ->
            composited.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        composited.recycle()
        if (foreground !== original) foreground.recycle()
        original.recycle()
        output
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
