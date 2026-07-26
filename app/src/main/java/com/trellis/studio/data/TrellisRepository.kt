package com.trellis.studio.data

import android.content.Context
import android.util.Base64
import com.trellis.studio.data.api.TrellisApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class TrellisRepository(
    private val context: Context,
    private val api: TrellisApi,
    /** Resolves the current API key — Settings first, BuildConfig fallback. */
    private val apiKeyProvider: () -> String
) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Sends [imageFile] to the NVIDIA TRELLIS endpoint and returns the generated .glb file.
     * Handles both the synchronous (200) and polled (202 + NVCF-REQID) response flows.
     */
    suspend fun generateModel(
        imageFile: File,
        onStatus: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw AppException.Api(
                "NVIDIA API key is missing. Add your key in the Settings tab."
            )
        }

        onStatus("Uploading image…")
        val imageBytes = imageFile.readBytes()
        val mime = when {
            imageBytes.size >= 8 &&
                imageBytes[0] == 0x89.toByte() && imageBytes[1] == 'P'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
        val dataUri = "data:$mime;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val payload = JSONObject().put("image", dataUri).toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        val auth = "Bearer $apiKey"

        var response = api.generate(GENERATE_URL, auth, body)

        // 202 means the job was queued: poll the NVCF status endpoint until it completes.
        if (response.code() == 202) {
            val requestId = response.headers()["NVCF-REQID"]
                ?: throw AppException.Api("Generation was queued but no request id was returned.")
            onStatus("Generating 3D model… this can take 30–90 seconds.")
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            while (true) {
                response = api.pollStatus("$STATUS_URL$requestId", auth)
                if (response.code() != 202) break
                if (System.currentTimeMillis() > deadline) {
                    throw AppException.Api("3D generation timed out. Please try again.")
                }
                delay(2_000)
            }
        } else {
            onStatus("Generating 3D model… this can take 30–90 seconds.")
        }

        if (!response.isSuccessful) {
            val rawBody = response.errorBody()?.string().orEmpty()
            val detail = runCatching { JSONObject(rawBody).optString("detail") }
                .getOrNull()?.takeIf { it.isNotBlank() }

            throw AppException.Api(
                when {
                    response.code() == 401 || response.code() == 403 ->
                        "The NVIDIA API key was rejected. Check the key in Settings."

                    response.code() == 429 ->
                        "NVIDIA API rate limit reached. Wait a moment and retry."

                    detail?.contains("example_id") == true ->
                        "NVIDIA's free TRELLIS preview endpoint only accepts its own sample " +
                            "images — it cannot process custom photos yet. This is a limit on " +
                            "NVIDIA's side, not something this app can work around."

                    response.code() == 500 ->
                        "NVIDIA's TRELLIS service returned a server error. Their preview " +
                            "endpoint has been unstable — please try again in a while."

                    detail != null -> "3D generation failed: $detail"

                    else -> "3D generation failed (HTTP ${response.code()}). " +
                        rawBody.take(200)
                }
            )
        }

        onStatus("Processing 3D model…")
        val resultBytes = response.body()?.bytes()
            ?: throw AppException.Api("3D generation returned an empty response.")
        val glbBytes = extractGlb(resultBytes)
            ?: throw AppException.Api("Could not find a GLB model in the API response.")

        val outFile = File(modelsDir, "model_${System.currentTimeMillis()}.glb")
        outFile.writeBytes(glbBytes)
        outFile
    }

    /**
     * The endpoint may answer with raw GLB bytes, a ZIP containing a .glb, or a JSON
     * envelope with a base64-encoded artifact. Normalizes all of them to GLB bytes.
     */
    private fun extractGlb(bytes: ByteArray): ByteArray? {
        if (bytes.isGlb()) return bytes
        if (bytes.isZip()) return unzipGlb(bytes)

        // JSON envelope: look for a base64 payload in the usual fields.
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (!text.trimStart().startsWith("{")) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        val base64 = json.optJSONArray("artifacts")?.optJSONObject(0)?.optString("base64")
            ?.takeIf { it.isNotBlank() }
            ?: sequenceOf("glb", "model", "data", "b64_json")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
            ?: return null

        val decoded = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            ?: return null
        return when {
            decoded.isGlb() -> decoded
            decoded.isZip() -> unzipGlb(decoded)
            else -> decoded
        }
    }

    private fun unzipGlb(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".glb", ignoreCase = true)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun ByteArray.isGlb(): Boolean =
        size >= 4 && this[0] == 'g'.code.toByte() && this[1] == 'l'.code.toByte() &&
            this[2] == 'T'.code.toByte() && this[3] == 'F'.code.toByte()

    private fun ByteArray.isZip(): Boolean =
        size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()

    companion object {
        private const val GENERATE_URL = "https://ai.api.nvidia.com/v1/genai/microsoft/trellis"
        private const val STATUS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status/"
        private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
