package com.trellis.studio.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * Records mono 16-bit PCM and writes a WAV.
 *
 * Riva's zero-shot cloning wants raw linear PCM, so this uses AudioRecord
 * rather than MediaRecorder — MediaRecorder only produces compressed formats
 * (AAC/AMR), which the service rejects with a format mismatch.
 */
class VoiceRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    @Volatile private var recording = false
    private var worker: Thread? = null
    private val buffer = ByteArrayOutputStream()
    /** The rate actually used, chosen from what the device supports. */
    private var activeRate = SAMPLE_RATE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> {
        if (recording) return Result.success(Unit)
        if (!hasPermission()) {
            return Result.failure(Exception("Microphone permission is needed to record a voice sample."))
        }

        // Try the widely-supported rates in turn: a device that can't open one
        // often opens another, so this records where a single fixed rate failed.
        var recorder: AudioRecord? = null
        var chosen = SAMPLE_RATE
        for (rate in RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, FORMAT)
            if (minBuffer <= 0) continue
            val candidate = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL, FORMAT, minBuffer * 2)
            }.getOrNull()
            if (candidate?.state == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate; chosen = rate; break
            }
            runCatching { candidate?.release() }
        }
        if (recorder == null) {
            return Result.failure(Exception("Microphone is unavailable — another app may be using it."))
        }
        activeRate = chosen
        val bufferSize = AudioRecord.getMinBufferSize(chosen, CHANNEL, FORMAT) * 2

        buffer.reset()
        record = recorder
        recording = true
        recorder.startRecording()

        worker = thread(name = "voice-recorder") {
            val chunk = ByteArray(bufferSize)
            while (recording) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                    // Cap the sample so a forgotten recording can't fill storage.
                    if (buffer.size() > MAX_BYTES) recording = false
                }
            }
        }
        return Result.success(Unit)
    }

    fun stop(): Result<File> {
        if (!recording && buffer.size() == 0) {
            return Result.failure(Exception("Nothing was recorded."))
        }
        recording = false
        runCatching { worker?.join(1500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null

        val pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.size < activeRate) {           // under ~0.5s of audio
            return Result.failure(Exception("Recording too short — hold for at least 3 seconds."))
        }

        val dir = File(context.filesDir, "voices").apply { mkdirs() }
        val file = File(dir, "sample_${System.currentTimeMillis()}.wav")
        return runCatching {
            file.writeBytes(wav(pcm))
            file
        }.recoverCatching { throw Exception("Could not save the recording: ${it.message}") }
    }

    fun release() {
        recording = false
        runCatching { record?.release() }
        record = null
    }

    /** Wraps raw PCM in a RIFF header at the rate actually recorded. */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val byteRate = activeRate * CHANNELS * BITS / 8
        fun i32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray()); out.write(i32(36 + pcm.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(i32(16)); out.write(i16(1))
        out.write(i16(CHANNELS)); out.write(i32(activeRate)); out.write(i32(byteRate))
        out.write(i16(CHANNELS * BITS / 8)); out.write(i16(BITS))
        out.write("data".toByteArray()); out.write(i32(pcm.size)); out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        // 44.1kHz is the one rate essentially every Android mic supports, so it
        // records reliably where 22.05kHz silently failed on some devices; Riva
        // accepts it as a voice prompt too.
        const val SAMPLE_RATE = 44100
        /** Rates to try, most compatible first. */
        val RATES = intArrayOf(44100, 48000, 16000, 22050, 8000)
        const val CHANNELS = 1
        const val BITS = 16
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Let the user speak naturally for up to 30s instead of cutting them off at
        // 9 — that hard stop mid-sentence was read as the recording "breaking". The
        // cloner only wants 3-10s, but it trims to a clean middle window itself, so
        // a longer take just gives it a better slice to choose from.
        const val MAX_BYTES = SAMPLE_RATE * 2 * 30
    }
}
