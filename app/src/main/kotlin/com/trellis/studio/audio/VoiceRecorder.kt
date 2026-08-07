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

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> {
        if (recording) return Result.success(Unit)
        if (!hasPermission()) {
            return Result.failure(Exception("Microphone permission is needed to record a voice sample."))
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        if (minBuffer <= 0) {
            return Result.failure(Exception("This device can't record at ${SAMPLE_RATE}Hz."))
        }
        val bufferSize = minBuffer * 2

        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, FORMAT, bufferSize)
        }.getOrElse { return Result.failure(Exception("Could not open the microphone: ${it.message}")) }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return Result.failure(Exception("Microphone is unavailable — another app may be using it."))
        }

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
        if (pcm.size < SAMPLE_RATE) {          // under ~0.5s of audio
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

    /** Wraps raw PCM in a RIFF header. */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        fun i32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray()); out.write(i32(36 + pcm.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(i32(16)); out.write(i16(1))
        out.write(i16(CHANNELS)); out.write(i32(SAMPLE_RATE)); out.write(i32(byteRate))
        out.write(i16(CHANNELS * BITS / 8)); out.write(i16(BITS))
        out.write("data".toByteArray()); out.write(i32(pcm.size)); out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 22050          // Riva accepts this for voice prompts
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
