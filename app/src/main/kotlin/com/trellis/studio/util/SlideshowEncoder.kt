package com.trellis.studio.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Turns a sequence of scene images into a real .mp4 — the free path to a video:
 * the images are AI-generated per scene, and this gives each one a slow push-in
 * (a Ken Burns move) with its caption, then encodes the lot to H.264.
 *
 * It feeds the encoder through [MediaCodec.getInputImage], which hands back the
 * device's negotiated YUV planes with their real strides, so the same code holds
 * up across phones instead of guessing a colour format. No audio track — a
 * silent clip, which every player accepts.
 */
object SlideshowEncoder {

    data class Scene(val image: Bitmap, val seconds: Float, val caption: String)

    private const val MIME = "video/avc"
    private const val FPS = 15
    private const val I_FRAME_INTERVAL = 1

    suspend fun encode(
        scenes: List<Scene>,
        output: File,
        width: Int = 720,
        height: Int = 1280,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(scenes.isNotEmpty()) { "No scenes to encode." }
            // Even dimensions are required by most encoders.
            val w = width and 1.inv()
            val h = height and 1.inv()

            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, w * h * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val totalFrames = scenes.sumOf { (it.seconds * FPS).toInt().coerceAtLeast(1) }
            var frameIndex = 0
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)

            // End of stream is flagged on the last input buffer (ByteBuffer mode),
            // so drain just pulls output; when [waitForEnd] it blocks until the
            // encoder emits its END_OF_STREAM marker.
            fun drain(waitForEnd: Boolean) {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!waitForEnd) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    } else if (outIndex >= 0) {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            // We are not using a Surface, so end-of-stream is flagged on the last
            // input buffer instead of signalEndOfInputStream.
            scenes.forEachIndexed { sIndex, scene ->
                val frames = (scene.seconds * FPS).toInt().coerceAtLeast(1)
                for (f in 0 until frames) {
                    val last = sIndex == scenes.lastIndex && f == frames - 1
                    val progress = f.toFloat() / frames
                    drawFrame(canvas, frame, scene, progress)

                    var inIndex = codec.dequeueInputBuffer(20_000)
                    while (inIndex < 0) { drain(false); inIndex = codec.dequeueInputBuffer(20_000) }

                    val image = codec.getInputImage(inIndex)!!
                    fillYuv(frame, image)
                    // With flexible YUV the buffer can carry stride padding, so the
                    // queued size must be the buffer's real capacity, not w*h*3/2.
                    val size = codec.getInputBuffer(inIndex)?.capacity() ?: (w * h * 3 / 2)
                    val pts = frameIndex.toLong() * 1_000_000L / FPS
                    codec.queueInputBuffer(
                        inIndex, 0, size, pts,
                        if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                    )
                    frameIndex++
                    onProgress(frameIndex.toFloat() / totalFrames)
                    drain(false)
                }
            }
            drain(true)

            codec.stop(); codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
            frame.recycle()
            output
        }
    }

    // ------------------------------------------------------------- drawing

    private val captionBg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** A scene frame: the image pushed in slightly over time, plus its caption. */
    private fun drawFrame(canvas: Canvas, frame: Bitmap, scene: Scene, progress: Float) {
        canvas.drawColor(Color.BLACK)
        val w = frame.width; val h = frame.height

        // Ken Burns: scale from 1.0 to 1.08 across the scene, centred.
        val zoom = 1f + 0.08f * progress
        val src = scene.image
        val srcRatio = src.width.toFloat() / src.height
        val dstRatio = w.toFloat() / h
        // Cover the frame, then apply the zoom.
        val baseW: Float; val baseH: Float
        if (srcRatio > dstRatio) { baseH = h.toFloat(); baseW = h * srcRatio }
        else { baseW = w.toFloat(); baseH = w / srcRatio }
        val dw = baseW * zoom; val dh = baseH * zoom
        val left = (w - dw) / 2f; val top = (h - dh) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), null)

        if (scene.caption.isNotBlank()) {
            captionText.textSize = h * 0.030f
            val pad = h * 0.02f
            val boxTop = h - h * 0.14f
            canvas.drawRect(0f, boxTop, w.toFloat(), h.toFloat(), captionBg)
            // Wrap to at most two lines.
            val lines = wrap(scene.caption, captionText, w - pad * 2)
            var y = boxTop + pad + captionText.textSize
            lines.take(2).forEach { canvas.drawText(it, w / 2f, y, captionText); y += captionText.textSize * 1.2f }
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                lines += line.toString(); line = StringBuilder(word)
            } else line = StringBuilder(candidate)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    // ------------------------------------------------------------- YUV fill

    /**
     * Writes [frame]'s pixels into the encoder's YUV planes, honouring each
     * plane's row and pixel stride so it is correct on both planar (I420) and
     * semi-planar (NV12) devices.
     */
    private fun fillYuv(frame: Bitmap, image: android.media.Image) {
        val w = frame.width; val h = frame.height
        val argb = IntArray(w * h)
        frame.getPixels(argb, 0, w, 0, 0, w, h)

        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride

        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yB.put(j * yRow + i, y.coerceIn(0, 255).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cj = j / 2; val ci = i / 2
                    uB.put(cj * uRow + ci * uPix, u.coerceIn(0, 255).toByte())
                    vB.put(cj * vRow + ci * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
