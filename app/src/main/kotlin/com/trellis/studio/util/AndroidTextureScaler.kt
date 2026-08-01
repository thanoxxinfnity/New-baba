package com.trellis.studio.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a model's base colour map using Android's own decoder.
 *
 * The decode is done at a reduced sample size first so a 4K texture never has to
 * exist at full resolution in memory just to be made smaller.
 */
object AndroidTextureScaler : MeshSimplifier.TextureScaler {

    override fun scale(png: ByteArray, maxEdge: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        // Already at or below the target — re-encoding would only lose quality.
        if (longest <= maxEdge) return null

        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            png, 0, png.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val scale = maxEdge.toFloat() / max(decoded.width, decoded.height)
        val out = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )

        val bytes = ByteArrayOutputStream().use { stream ->
            out.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        bytes
    }.getOrNull()
}
