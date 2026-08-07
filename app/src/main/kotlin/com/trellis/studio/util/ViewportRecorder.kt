package com.trellis.studio.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Records what a 3D viewport is showing into an .mp4, by grabbing the window's
 * pixels frame by frame while the model animates and the camera orbits. This is
 * how a rigged, animated model becomes a real motion video: the Filament surface
 * is already drawing it, and [PixelCopy] reads that surface — which a normal
 * screenshot cannot do for a SurfaceView.
 *
 * Frames stream straight into [VideoWriter], so memory stays flat however long
 * the recording runs.
 */
object ViewportRecorder {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Captures [seconds] of the region [rect] (window coordinates) at [fps] and
     * writes it to [output]. Progress is 0..1.
     */
    suspend fun record(
        window: Window,
        rect: Rect,
        seconds: Float,
        fps: Int,
        output: File,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Default) {
        runCatching {
            require(rect.width() > 0 && rect.height() > 0) { "Nothing to record yet." }
            val total = (seconds * fps).toInt().coerceAtLeast(1)
            val frameMs = 1000L / fps
            val writer = VideoWriter(output, rect.width(), rect.height(), fps)
            try {
                for (i in 0 until total) {
                    val started = System.currentTimeMillis()
                    val bmp = capture(window, rect) ?: continue
                    writer.addFrame(bmp)
                    bmp.recycle()
                    onProgress((i + 1f) / total)
                    // Pace to real time so the motion plays back at natural speed.
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < frameMs) delay(frameMs - elapsed)
                }
            } finally {
                writer.finish()
            }
            output
        }
    }

    /** One PixelCopy of the window region into a bitmap. */
    private suspend fun capture(window: Window, rect: Rect): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val bmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(window, rect, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bmp)
                    else { bmp.recycle(); cont.resume(null) }
                }, handler)
            }.onFailure { bmp.recycle(); cont.resume(null) }
        }
}
