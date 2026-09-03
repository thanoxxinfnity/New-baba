package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Turns a Sketchfab glTF archive into one self-contained .glb.
 *
 * Sketchfab hands back a zip holding scene.gltf, scene.bin and a textures
 * folder. Nothing else in this app can open that: the 360° viewer and the
 * rigger both read a single .glb. So the buffer and every texture are pulled
 * into one binary chunk and the JSON is rewritten to point at buffer views
 * instead of files on disk.
 */
object GltfPacker {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Everything a packed model carries, for the UI to describe it. */
    data class Packed(val file: File, val textures: Int, val bytes: Long)

    suspend fun packZip(zipBytes: ByteArray, outputDir: File, name: String): Result<Packed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = unzip(zipBytes)

                // A few authors upload a .glb directly; then there is nothing to do.
                entries.entries.firstOrNull { it.key.endsWith(".glb", true) }?.let { glb ->
                    outputDir.mkdirs()
                    val out = File(outputDir, "${safe(name)}.glb")
                    out.writeBytes(glb.value)
                    return@runCatching Packed(out, textures = 0, bytes = out.length())
                }

                val gltfEntry = entries.entries.firstOrNull { it.key.endsWith(".gltf", true) }
                    ?: throw IllegalArgumentException(
                        "That archive has no glTF scene in it — nothing to convert."
                    )
                val root = json.parseToJsonElement(String(gltfEntry.value, Charsets.UTF_8)).jsonObject
                val baseDir = gltfEntry.key.substringBeforeLast('/', "")

                // The blob starts as the model's own .bin, so every bufferView
                // offset already in the JSON stays correct.
                val blob = ByteArrayOutputStream()
                val buffers = root["buffers"]?.jsonArray ?: JsonArray(emptyList())
                buffers.forEach { b ->
                    val uri = b.jsonObject["uri"]?.jsonPrimitive?.content
                    blob.write(
                        when {
                            uri == null -> ByteArray(0)          // already embedded
                            uri.startsWith("data:") -> decodeDataUri(uri)
                            else -> entries[resolve(baseDir, uri)]
                                ?: throw IllegalArgumentException("The archive is missing $uri.")
                        }
                    )
                }

                val views = (root["bufferViews"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
                var textureCount = 0

                // Pull each external image into the blob and hand it a buffer view.
                val images = root["images"]?.jsonArray?.map { img ->
                    val o = img.jsonObject
                    val uri = o["uri"]?.jsonPrimitive?.content
                        ?: return@map img            // already a bufferView
                    val bytes = if (uri.startsWith("data:")) decodeDataUri(uri)
                    else entries[resolve(baseDir, uri)]
                    if (bytes == null) {
                        // A missing texture must not sink the whole model; the mesh
                        // is still worth having, so the image is dropped instead.
                        buildJsonObject { o.forEach { (k, v) -> if (k != "uri") put(k, v) } }
                    } else {
                        while (blob.size() % 4 != 0) blob.write(0)
                        val offset = blob.size()
                        blob.write(bytes)
                        views += buildJsonObject {
                            put("buffer", 0); put("byteOffset", offset); put("byteLength", bytes.size)
                        }
                        textureCount++
                        buildJsonObject {
                            o.forEach { (k, v) -> if (k != "uri") put(k, v) }
                            put("bufferView", views.size - 1)
                            put("mimeType", o["mimeType"]?.jsonPrimitive?.content ?: mimeOf(uri))
                        }
                    }
                }

                val binary = blob.toByteArray()
                val packed = buildJsonObject {
                    root.forEach { (k, v) ->
                        when (k) {
                            "buffers", "bufferViews", "images" -> Unit    // replaced below
                            else -> put(k, v)
                        }
                    }
                    put("buffers", buildJsonArray {
                        add(buildJsonObject { put("byteLength", binary.size) })
                    })
                    put("bufferViews", JsonArray(views))
                    if (images != null) put("images", JsonArray(images))
                }

                outputDir.mkdirs()
                val out = File(outputDir, "${safe(name)}.glb")
                out.writeBytes(Glb.assemble(packed, binary))
                Packed(out, textureCount, out.length())
            }
        }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    // Zip entries can carry ".." paths; nothing here is written by
                    // name, but the guard keeps a crafted archive from mattering.
                    val clean = entry.name.replace('\\', '/').removePrefix("./")
                    if (!clean.contains("../")) files[clean] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        return files
    }

    /** glTF uris are relative to the .gltf file and are percent-encoded. */
    private fun resolve(baseDir: String, uri: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(uri, "UTF-8") }.getOrDefault(uri)
        return if (baseDir.isBlank()) decoded else "$baseDir/$decoded"
    }

    private fun decodeDataUri(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        require(comma > 0) { "A data: uri in the model is malformed." }
        return android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT)
    }

    private fun mimeOf(uri: String): String = when {
        uri.endsWith(".png", true) -> "image/png"
        uri.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(48).ifBlank { "model" }
}
