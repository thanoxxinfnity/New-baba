package com.trellis.studio.network

import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nvidia.riva.tts.RivaSpeechSynthesisGrpc
import nvidia.riva.tts.SynthesizeSpeechRequest
import nvidia.riva.tts.ZeroShotData
import nvidia.riva.AudioEncoding
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NVIDIA Riva text-to-speech, including zero-shot voice cloning.
 *
 * Riva is gRPC-only — the REST paths under integrate.api.nvidia.com and
 * ai.api.nvidia.com return 404 for TTS, and the NVCF HTTP proxy answers 500 for
 * these functions regardless of payload. Calling the gRPC endpoint with the
 * function-id metadata header is the interface that actually works; verified
 * live against ai-magpie-tts-multilingual, which returned real PCM audio.
 */
class NvidiaTtsClient {

    /** Deployed NVCF function ids, read from the account's function list. */
    object Functions {
        const val MULTILINGUAL = "877104f7-e885-42b9-8de8-f6e4c6303969"
        const val ZERO_SHOT = "55cf67bf-600f-4b04-8eac-12ed39537a08"
    }

    private fun channelFor(apiKey: String, functionId: String): ManagedChannel {
        val base = OkHttpChannelBuilder
            .forAddress(HOST, PORT)
            .useTransportSecurity()
            .build()
        return base
    }

    /** Attaches the NVCF routing + auth headers to every call. */
    private fun interceptor(apiKey: String, functionId: String) = object : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> =
            object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(FUNCTION_ID_KEY, functionId)
                    headers.put(AUTH_KEY, "Bearer $apiKey")
                    super.start(responseListener, headers)
                }
            }
    }

    /**
     * Speaks [text] with a built-in Magpie voice.
     * @return WAV bytes, ready to write to a file or play.
     */
    suspend fun synthesize(
        apiKey: String,
        text: String,
        voiceName: String = DEFAULT_VOICE,
        languageCode: String = "en-US",
        sampleRateHz: Int = 44100,
    ): Result<ByteArray> = call(apiKey, Functions.MULTILINGUAL) { stub ->
        val request = SynthesizeSpeechRequest.newBuilder()
            .setText(text)
            .setLanguageCode(languageCode)
            .setEncoding(AudioEncoding.LINEAR_PCM)
            .setSampleRateHz(sampleRateHz)
            .setVoiceName(voiceName)
            .build()
        wrapWav(stub.synthesize(request).audio.toByteArray(), sampleRateHz)
    }

    /**
     * Zero-shot voice cloning: speaks [text] in the voice heard in [voiceSample].
     *
     * @param voiceSample a short clean WAV recording of the target voice
     * @param quality 1–40; higher takes longer but tracks the sample more closely
     */
    suspend fun cloneVoice(
        apiKey: String,
        text: String,
        voiceSample: File,
        languageCode: String = "en-US",
        sampleRateHz: Int = 44100,
        quality: Int = 20,
        transcript: String = "",
    ): Result<ByteArray> {
        if (!voiceSample.exists() || voiceSample.length() < 1024) {
            return Result.failure(Exception("Voice sample is missing or too short. Record at least 3 seconds."))
        }
        val raw = runCatching { WavFile.read(voiceSample) }.getOrElse {
            return Result.failure(Exception("Could not read the voice sample: ${it.message}"))
        }

        // The service enforces a 3-10s prompt and rejects anything outside it
        // ("Audio prompt duration (12.6) ... is not between 3-10 seconds"), so
        // trim a long recording rather than letting it bounce.
        val bytesPerSecond = raw.sampleRate * 2 * raw.channels
        val seconds = raw.pcm.size.toFloat() / bytesPerSecond
        if (seconds < MIN_PROMPT_SECONDS) {
            return Result.failure(
                Exception(
                    "Voice sample is only ${"%.1f".format(seconds)}s — the cloner needs at " +
                        "least ${MIN_PROMPT_SECONDS}s of speech."
                )
            )
        }
        val wav = if (seconds > MAX_PROMPT_SECONDS) {
            // Keep the middle, which is usually cleaner than the start or end.
            val keep = (bytesPerSecond * MAX_PROMPT_SECONDS).toInt().let { it - it % 2 }
            val start = ((raw.pcm.size - keep) / 2).let { it - it % 2 }.coerceAtLeast(0)
            raw.copy(pcm = raw.pcm.copyOfRange(start, (start + keep).coerceAtMost(raw.pcm.size)))
        } else raw

        return call(apiKey, Functions.ZERO_SHOT, timeoutSeconds = CLONE_TIMEOUT_SECONDS) { stub ->
            val zeroShot = ZeroShotData.newBuilder()
                .setAudioPrompt(ByteString.copyFrom(wav.pcm))
                .setSampleRateHz(wav.sampleRate)
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setQuality(quality.coerceIn(1, 40))
                .apply { if (transcript.isNotBlank()) setTranscript(transcript) }
                .build()

            val request = SynthesizeSpeechRequest.newBuilder()
                .setText(text)
                .setLanguageCode(languageCode)
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setSampleRateHz(sampleRateHz)
                .setZeroShotData(zeroShot)
                .build()
            wrapWav(stub.synthesize(request).audio.toByteArray(), sampleRateHz)
        }
    }

    /** Shared plumbing: build channel, run the call, always shut the channel down. */
    private suspend fun <T> call(
        apiKey: String,
        functionId: String,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
        body: (RivaSpeechSynthesisGrpc.RivaSpeechSynthesisBlockingStub) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("NVIDIA API key is missing. Add it in Settings."))
        }
        var channel: ManagedChannel? = null
        try {
            channel = channelFor(apiKey, functionId)
            val intercepted = ClientInterceptors.intercept(channel, interceptor(apiKey, functionId))
            val stub = RivaSpeechSynthesisGrpc.newBlockingStub(intercepted)
                .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
            Result.success(body(stub))
        } catch (e: io.grpc.StatusRuntimeException) {
            Result.failure(Exception(describe(e)))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Speech synthesis failed."))
        } finally {
            runCatching { channel?.shutdownNow() }
        }
    }

    private fun describe(e: io.grpc.StatusRuntimeException): String {
        val detail = e.status.description.orEmpty()
        return when (e.status.code) {
            io.grpc.Status.Code.UNAUTHENTICATED,
            io.grpc.Status.Code.PERMISSION_DENIED ->
                "NVIDIA API key rejected. Check it in Settings."
            io.grpc.Status.Code.DEADLINE_EXCEEDED ->
                "NVIDIA's voice-cloning worker accepted the request but didn't return audio. " +
                    "That's a capacity problem on their side — your sample is saved, try again later."
            io.grpc.Status.Code.UNAVAILABLE ->
                "NVIDIA's voice service is unavailable right now. Try again shortly."
            io.grpc.Status.Code.RESOURCE_EXHAUSTED ->
                "Rate limit reached on the voice service. Wait a moment."
            io.grpc.Status.Code.INVALID_ARGUMENT ->
                if (detail.contains("format", true))
                    "The voice sample format wasn't accepted. Use a mono 16-bit WAV."
                else "Voice request rejected: $detail"
            else -> detail.ifBlank { "Speech synthesis failed (${e.status.code})." }
        }
    }

    /** Riva returns headerless PCM; players need a RIFF header in front of it. */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        fun i32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray()); out.write(i32(36 + pcm.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(i32(16)); out.write(i16(1))
        out.write(i16(channels)); out.write(i32(sampleRate)); out.write(i32(byteRate))
        out.write(i16(channels * bits / 8)); out.write(i16(bits))
        out.write("data".toByteArray()); out.write(i32(pcm.size)); out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val HOST = "grpc.nvcf.nvidia.com"
        const val PORT = 443
        const val TIMEOUT_SECONDS = 120L
        // Cloning either answers reasonably fast or not at all; a long wait just
        // leaves the user staring at a spinner.
        const val CLONE_TIMEOUT_SECONDS = 100L
        // Hard limits reported by the zero-shot model itself.
        const val MIN_PROMPT_SECONDS = 3f
        const val MAX_PROMPT_SECONDS = 9f
        const val DEFAULT_VOICE = "Magpie-Multilingual.EN-US.Sofia"

        val FUNCTION_ID_KEY: Metadata.Key<String> =
            Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER)
        val AUTH_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

/** Minimal WAV reader — enough to pull mono 16-bit PCM out of a recording. */
object WavFile {
    data class Pcm(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    fun read(file: File): Pcm {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "file too small" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "not a WAV file"
        }
        var pos = 12
        var sampleRate = 44100
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                }
                "data" -> {
                    val end = (body + size).coerceAtMost(bytes.size)
                    return Pcm(bytes.copyOfRange(body, end), sampleRate, channels)
                }
            }
            pos = body + size + (size and 1)   // chunks are word-aligned
        }
        error("WAV had no data chunk")
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
