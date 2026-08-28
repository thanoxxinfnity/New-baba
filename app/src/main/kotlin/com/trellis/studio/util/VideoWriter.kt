package com.trellis.studio.util

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * A streaming H.264/MP4 writer: open once, hand it one frame bitmap at a time,
 * close to finish. Frames are encoded and released as they arrive, so recording
 * a long clip never holds hundreds of bitmaps in memory — which is what makes it
 * usable for capturing a live 3D animation frame by frame.
 *
 * Frames are fed through [MediaCodec.getInputImage] so the device's real YUV
 * plane strides are honoured, rather than guessing a colour format. Silent.
 */
class VideoWriter(
    private val output: File,
    width: Int,
    height: Int,
    private val fps: Int = 24,
) {
    private val w = width and 1.inv()
    private val h = height and 1.inv()

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private var frameIndex = 0
    private val info = MediaCodec.BufferInfo()
    private val argb = IntArray(w * h)

    init {
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, w * h * 5)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    val frameWidth get() = w
    val frameHeight get() = h

    /** Encodes one frame. The bitmap is scaled to the writer's size if needed. */
    fun addFrame(bitmap: Bitmap) {
        val frame = if (bitmap.width == w && bitmap.height == h) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)

        var inIndex = codec.dequeueInputBuffer(20_000)
        while (inIndex < 0) { drain(false); inIndex = codec.dequeueInputBuffer(20_000) }
        val image = codec.getInputImage(inIndex)!!
        fillYuv(frame, image)
        val size = codec.getInputBuffer(inIndex)?.capacity() ?: (w * h * 3 / 2)
        codec.queueInputBuffer(inIndex, 0, size, frameIndex.toLong() * 1_000_000L / fps, 0)
        frameIndex++
        if (frame !== bitmap) frame.recycle()
        drain(false)
    }

    /** Flushes the encoder and finalises the file. */
    fun finish(): File {
        val inIndex = codec.dequeueInputBuffer(20_000)
        if (inIndex >= 0) {
            codec.queueInputBuffer(inIndex, 0, 0, frameIndex.toLong() * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
        codec.stop(); codec.release()
        if (started) muxer.stop()
        muxer.release()
        return output
    }

    private fun drain(waitForEnd: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
            } else if (outIndex >= 0) {
                val buf = codec.getOutputBuffer(outIndex)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    private fun fillYuv(frame: Bitmap, image: android.media.Image) {
        frame.getPixels(argb, 0, w, 0, 0, w, h)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yB.put(j * yRow + i, y.coerceIn(0, 255).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uB.put((j / 2) * uRow + (i / 2) * uPix, u.coerceIn(0, 255).toByte())
                    vB.put((j / 2) * vRow + (i / 2) * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private companion object { const val MIME = "video/avc" }
}
