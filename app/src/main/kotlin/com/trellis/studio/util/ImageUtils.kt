package com.trellis.studio.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Prepares picked photos for upload.
 *
 * A phone camera shot is 3–12 MB, well past what the vision and 3D endpoints
 * accept inline, so pictures were being silently dropped. Everything here
 * downscales and re-compresses first, and runs off the main thread — the old
 * inline copy blocked the UI and could leave a zero-byte file behind.
 */
object ImageUtils {

    /**
     * Copies [uri] into cache as a right-way-up JPEG no larger than [maxDimension]
     * on its long edge and [maxBytes] on disk.
     */
    suspend fun prepareForUpload(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1280,
        maxBytes: Int = 700_000,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeScaled(context, uri, maxDimension)
                ?: error("That file isn't an image this app can read.")
            val upright = applyExifRotation(context, uri, bitmap)

            val target = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
            // Step the quality down until it fits; 60 still looks fine at this size.
            var quality = 90
            do {
                target.outputStream().use { out ->
                    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                quality -= 10
            } while (target.length() > maxBytes && quality >= 60)

            if (upright !== bitmap) bitmap.recycle()
            upright.recycle()

            if (!target.exists() || target.length() == 0L) {
                error("Could not save the image. Try a different photo.")
            }
            target
        }
    }

    /** Two-pass decode so a large photo never has to fit in memory at full size. */
    private fun decodeScaled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("Could not open the selected image.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // inSampleSize only halves, so trim the remainder to hit the target exactly.
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxDimension) return decoded
        val ratio = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Photos taken in portrait carry rotation in EXIF rather than in the pixels. */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
