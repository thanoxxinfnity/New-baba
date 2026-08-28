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
 * Strategy: copy the URI to a local temp file first (one stream open), then do
 * the two-pass decode from the stable file. Virtual URIs (Google Photos, cloud
 * drives) can fail if opened more than once, causing "Could not open the
 * selected image." — the copy step eliminates that class of failure entirely.
 */
object ImageUtils {

    suspend fun prepareForUpload(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1280,
        maxBytes: Int = 700_000,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: copy the URI into a local file in one shot.
            val raw = File(context.cacheDir, "img_raw_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                raw.outputStream().use { out -> input.copyTo(out) }
            } ?: error("Could not open the selected image. Try picking it again.")

            if (!raw.exists() || raw.length() == 0L) error("The selected image appears to be empty.")

            // Step 2: two-pass decode from the stable local file.
            val bitmap = decodeScaledFromFile(raw, maxDimension)
                ?: error("That file isn't an image this app can read.")
            val upright = applyExifRotationFromFile(raw, bitmap)
            raw.delete()

            // Step 3: re-compress to target size.
            val target = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
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

    private fun decodeScaledFromFile(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

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

    private fun applyExifRotationFromFile(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
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
