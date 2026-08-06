package com.trellis.studio.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins several .mp4 clips into one, end to end — the step that turns a set of
 * short generated clips into a single longer video. It copies the encoded video
 * samples across without re-encoding, shifting each clip's timestamps so they
 * play in sequence, which is fast and lossless.
 *
 * The clips must share a codec and resolution; the clips this app stitches all
 * come from the same model, so they do. Video track only (the clips are silent).
 */
object Mp4Concat {

    suspend fun join(clips: List<File>, output: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val usable = clips.filter { it.exists() && it.length() > 0 }
            require(usable.isNotEmpty()) { "No clips to join." }
            if (usable.size == 1) { usable[0].copyTo(output, overwrite = true); return@runCatching output }

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var outTrack = -1
            var started = false
            var timeOffsetUs = 0L
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            usable.forEach { clip ->
                val extractor = MediaExtractor()
                extractor.setDataSource(clip.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: run { extractor.release(); return@forEach }

                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                if (!started) {
                    outTrack = muxer.addTrack(format)
                    muxer.start(); started = true
                }

                var lastPts = 0L
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = timeOffsetUs + sampleTime
                    info.flags = extractorFlagsToCodec(extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                    lastPts = sampleTime
                    extractor.advance()
                }
                // Next clip starts just after this one ends.
                timeOffsetUs += lastPts + FRAME_GAP_US
                extractor.release()
            }

            muxer.stop(); muxer.release()
            output
        }
    }

    private fun extractorFlagsToCodec(flags: Int): Int {
        var out = 0
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        return out
    }

    // A one-frame gap at ~24fps so the join doesn't overlap timestamps.
    private const val FRAME_GAP_US = 41_000L
}
