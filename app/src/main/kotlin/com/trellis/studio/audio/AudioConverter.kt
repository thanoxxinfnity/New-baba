package com.trellis.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a picked audio file (mp3, m4a, aac, ogg, wav …) into the mono 16-bit
 * PCM WAV that Riva's zero-shot cloning accepts.
 *
 * The service rejects compressed audio outright, so an uploaded song or voice
 * note has to be decoded first — MediaExtractor + MediaCodec does that with the
 * platform decoders, no extra dependency.
 */
object AudioConverter {

    private const val TARGET_RATE = 22050
    // Matches the zero-shot cloner's 3-10s prompt window.
    private const val MAX_SECONDS = 9
    private const val TIMEOUT_US = 10_000L

    suspend fun toWav(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: error("Could not open that audio file.")

                val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("That file has no audio track.")

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format.")
                val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val pcm = decode(extractor, format, mime)
                if (pcm.isEmpty()) error("Could not decode any audio from that file.")

                val mono = if (sourceChannels > 1) toMono(pcm, sourceChannels) else pcm
                val resampled = if (sourceRate != TARGET_RATE) resample(mono, sourceRate, TARGET_RATE) else mono

                val capped = resampled.copyOf(
                    minOf(resampled.size, TARGET_RATE * 2 * MAX_SECONDS)
                )
                if (capped.size < TARGET_RATE) {
                    error("That clip is too short — use at least 3 seconds of clear speech.")
                }

                val dir = File(context.filesDir, "voices").apply { mkdirs() }
                File(dir, "upload_${System.currentTimeMillis()}.wav").apply {
                    writeBytes(wavHeader(capped.size) + capped)
                }
            } finally {
                runCatching { extractor.release() }
            }
        }
    }

    /** Runs the platform decoder until the track is exhausted. */
    private fun decode(extractor: MediaExtractor, format: MediaFormat, mime: String): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        val out = ByteArrayOutputStream()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
                // Stop runaway files rather than filling memory.
                if (out.size() > TARGET_RATE * 2 * MAX_SECONDS * 8) break
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return out.toByteArray()
    }

    /** Averages interleaved channels down to one. */
    private fun toMono(pcm: ByteArray, channels: Int): ByteArray {
        val samples = pcm.size / 2 / channels
        val out = ByteArray(samples * 2)
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until samples) {
            var sum = 0
            for (c in 0 until channels) sum += src.get(i * channels + c).toInt()
            dst.put(i, (sum / channels).toShort())
        }
        return out
    }

    /** Linear resample — plenty for a voice prompt. */
    private fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
        if (from == to) return pcm
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inCount = src.limit()
        val outCount = (inCount.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ByteArray(outCount * 2)
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until outCount) {
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceIn(0, inCount - 1)
            val i1 = (i0 + 1).coerceAtMost(inCount - 1)
            val frac = pos - i0
            val value = src.get(i0) * (1 - frac) + src.get(i1) * frac
            dst.put(i, value.toInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val out = ByteArrayOutputStream(44)
        val byteRate = TARGET_RATE * 2
        fun i32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray()); out.write(i32(36 + dataSize)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(i32(16)); out.write(i16(1))
        out.write(i16(1)); out.write(i32(TARGET_RATE)); out.write(i32(byteRate))
        out.write(i16(2)); out.write(i16(16))
        out.write("data".toByteArray()); out.write(i32(dataSize))
        return out.toByteArray()
    }
}
