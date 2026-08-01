package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a generated .glb into the formats game engines and DCC tools import.
 *
 * TRELLIS returns glTF-binary with POSITION, TEXCOORD_0 and an embedded PNG,
 * which Unity/Unreal/Blender read directly — but OBJ, STL and PLY are what most
 * older pipelines and asset stores expect, so the mesh is re-written into those
 * too, with the texture pulled out alongside.
 */
object ModelExporter {

    enum class Format(val ext: String, val label: String, val note: String) {
        GLB("glb", "GLB", "glTF binary — Unity, Unreal, Blender, three.js"),
        OBJ("obj", "OBJ + MTL", "Universal mesh format, keeps UVs and texture"),
        STL("stl", "STL", "Geometry only — 3D printing, CAD"),
        PLY("ply", "PLY", "Point/mesh data — scanning and research tools"),
        PNG("png", "Texture PNG", "The base colour map on its own"),
    }

    /** Mesh pulled out of a GLB, in engine-friendly arrays. */
    data class Mesh(
        val positions: FloatArray,     // xyz triples
        val uvs: FloatArray,           // uv pairs, may be empty
        val indices: IntArray,
        val texturePng: ByteArray?,
    ) {
        val vertexCount get() = positions.size / 3
        val triangleCount get() = indices.size / 3
    }

    /**
     * Writes [format] next to the source model and returns the new file.
     * Exporting [Format.GLB] just copies, since that is the native output.
     */
    suspend fun export(glb: File, format: Format, outputDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                outputDir.mkdirs()
                val stem = glb.nameWithoutExtension
                val target = File(outputDir, "$stem.${format.ext}")

                if (format == Format.GLB) {
                    // A blind copy would happily hand back a truncated or corrupt
                    // file renamed .glb, which only fails later in the engine.
                    val header = ByteArray(4)
                    val read = glb.inputStream().use { it.read(header) }
                    require(read == 4 && String(header) == "glTF") { "Not a GLB file." }
                    glb.copyTo(target, overwrite = true)
                    return@runCatching target
                }

                val mesh = parseGlb(glb)

                when (format) {
                    Format.OBJ -> {
                        target.writeText(buildObj(mesh, stem))
                        // OBJ carries materials in a sidecar; write it and the map.
                        File(outputDir, "$stem.mtl").writeText(buildMtl(stem, mesh.texturePng != null))
                        mesh.texturePng?.let { File(outputDir, "$stem.png").writeBytes(it) }
                    }
                    Format.STL -> target.writeBytes(buildBinaryStl(mesh))
                    Format.PLY -> target.writeText(buildPly(mesh))
                    Format.PNG -> {
                        val png = mesh.texturePng ?: error("This model has no texture to export.")
                        target.writeBytes(png)
                    }
                    Format.GLB -> Unit
                }
                target
            }
        }

    /** Everything the model can be exported as, given what it actually contains. */
    fun availableFormats(glb: File): List<Format> {
        val hasTexture = runCatching { parseGlb(glb).texturePng != null }.getOrDefault(false)
        return Format.entries.filter { it != Format.PNG || hasTexture }
    }

    // ---------------------------------------------------------------- parsing

    fun parseGlb(file: File): Mesh {
        val bytes = file.readBytes()
        require(bytes.size > 20 && String(bytes, 0, 4) == "glTF") { "Not a GLB file." }

        val jsonLen = le32(bytes, 12)
        val json = Json.parseToJsonElement(String(bytes, 20, jsonLen)).jsonObject

        // The binary chunk follows the JSON chunk, both 4-byte aligned.
        var offset = 20 + jsonLen
        while (offset % 4 != 0) offset++
        val binLen = le32(bytes, offset)
        val binStart = offset + 8
        require(binStart + binLen <= bytes.size) { "GLB binary chunk is truncated." }

        val bufferViews = json["bufferViews"]!!.jsonArray
        val accessors = json["accessors"]!!.jsonArray
        val primitive = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val attributes = primitive["attributes"]!!.jsonObject

        fun viewRange(index: Int): Pair<Int, Int> {
            val view = bufferViews[index].jsonObject
            val start = binStart + (view["byteOffset"]?.jsonPrimitive?.int ?: 0)
            return start to view["byteLength"]!!.jsonPrimitive.int
        }

        fun readFloats(accessorIndex: Int, components: Int): FloatArray {
            val acc = accessors[accessorIndex].jsonObject
            val count = acc["count"]!!.jsonPrimitive.int
            val (start, _) = viewRange(acc["bufferView"]!!.jsonPrimitive.int)
            val base = start + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
            val buf = ByteBuffer.wrap(bytes, base, count * components * 4).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count * components) { buf.getFloat(base + it * 4) }
        }

        val positions = readFloats(attributes["POSITION"]!!.jsonPrimitive.int, 3)
        val uvs = attributes["TEXCOORD_0"]?.jsonPrimitive?.int
            ?.let { readFloats(it, 2) } ?: FloatArray(0)

        // Indices are unsigned byte/short/int depending on vertex count.
        val idxAcc = accessors[primitive["indices"]!!.jsonPrimitive.int].jsonObject
        val idxCount = idxAcc["count"]!!.jsonPrimitive.int
        val idxType = idxAcc["componentType"]!!.jsonPrimitive.int
        val (idxStart, _) = viewRange(idxAcc["bufferView"]!!.jsonPrimitive.int)
        val idxBase = idxStart + (idxAcc["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val indices = IntArray(idxCount) { i ->
            when (idxType) {
                5121 -> bytes[idxBase + i].toInt() and 0xff
                5123 -> le16(bytes, idxBase + i * 2)
                5125 -> le32(bytes, idxBase + i * 4)
                else -> error("Unsupported index type $idxType")
            }
        }

        val texture = json["images"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("bufferView")?.jsonPrimitive?.int
            ?.let { viewIndex ->
                val (start, length) = viewRange(viewIndex)
                bytes.copyOfRange(start, start + length)
            }

        return Mesh(positions, uvs, indices, texture)
    }

    // --------------------------------------------------------------- writers

    private fun buildObj(mesh: Mesh, name: String): String = buildString {
        appendLine("# Exported by VOID")
        appendLine("# $name — ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles")
        appendLine("mtllib $name.mtl")
        appendLine("o $name")

        for (i in 0 until mesh.vertexCount) {
            appendLine("v ${mesh.positions[i * 3]} ${mesh.positions[i * 3 + 1]} ${mesh.positions[i * 3 + 2]}")
        }
        if (mesh.uvs.isNotEmpty()) {
            for (i in 0 until mesh.vertexCount) {
                // OBJ's V axis runs opposite to glTF's.
                appendLine("vt ${mesh.uvs[i * 2]} ${1f - mesh.uvs[i * 2 + 1]}")
            }
        }
        appendLine("usemtl material0")
        appendLine("s off")
        // OBJ indices are 1-based.
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val a = mesh.indices[i] + 1
            val b = mesh.indices[i + 1] + 1
            val c = mesh.indices[i + 2] + 1
            if (mesh.uvs.isNotEmpty()) appendLine("f $a/$a $b/$b $c/$c")
            else appendLine("f $a $b $c")
            i += 3
        }
    }

    private fun buildMtl(name: String, hasTexture: Boolean): String = buildString {
        appendLine("# Exported by VOID")
        appendLine("newmtl material0")
        appendLine("Ka 1.000 1.000 1.000")
        appendLine("Kd 1.000 1.000 1.000")
        appendLine("Ks 0.000 0.000 0.000")
        appendLine("d 1.0")
        appendLine("illum 2")
        if (hasTexture) appendLine("map_Kd $name.png")
    }

    /** Binary STL: 80-byte header, triangle count, then 50 bytes per facet. */
    private fun buildBinaryStl(mesh: Mesh): ByteArray {
        val triangles = mesh.triangleCount
        val out = ByteBuffer.allocate(84 + triangles * 50).order(ByteOrder.LITTLE_ENDIAN)
        out.put(ByteArray(80))
        out.putInt(triangles)

        fun vertex(index: Int, axis: Int) = mesh.positions[index * 3 + axis]

        for (t in 0 until triangles) {
            val a = mesh.indices[t * 3]
            val b = mesh.indices[t * 3 + 1]
            val c = mesh.indices[t * 3 + 2]

            // STL stores a face normal, which glTF leaves implicit here.
            val ux = vertex(b, 0) - vertex(a, 0)
            val uy = vertex(b, 1) - vertex(a, 1)
            val uz = vertex(b, 2) - vertex(a, 2)
            val vx = vertex(c, 0) - vertex(a, 0)
            val vy = vertex(c, 1) - vertex(a, 1)
            val vz = vertex(c, 2) - vertex(a, 2)
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) { nx /= len; ny /= len; nz /= len }

            out.putFloat(nx); out.putFloat(ny); out.putFloat(nz)
            for (v in intArrayOf(a, b, c)) {
                out.putFloat(vertex(v, 0)); out.putFloat(vertex(v, 1)); out.putFloat(vertex(v, 2))
            }
            out.putShort(0)
        }
        return out.array()
    }

    private fun buildPly(mesh: Mesh): String = buildString {
        appendLine("ply")
        appendLine("format ascii 1.0")
        appendLine("comment Exported by VOID")
        appendLine("element vertex ${mesh.vertexCount}")
        appendLine("property float x")
        appendLine("property float y")
        appendLine("property float z")
        if (mesh.uvs.isNotEmpty()) {
            appendLine("property float s")
            appendLine("property float t")
        }
        appendLine("element face ${mesh.triangleCount}")
        appendLine("property list uchar int vertex_index")
        appendLine("end_header")

        for (i in 0 until mesh.vertexCount) {
            append("${mesh.positions[i * 3]} ${mesh.positions[i * 3 + 1]} ${mesh.positions[i * 3 + 2]}")
            if (mesh.uvs.isNotEmpty()) append(" ${mesh.uvs[i * 2]} ${mesh.uvs[i * 2 + 1]}")
            appendLine()
        }
        var i = 0
        while (i + 2 < mesh.indices.size) {
            appendLine("3 ${mesh.indices[i]} ${mesh.indices[i + 1]} ${mesh.indices[i + 2]}")
            i += 3
        }
    }

    /** Bundles every format into one .zip — the usual "give me the asset" case. */
    suspend fun exportAll(glb: File, outputDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(outputDir, "${glb.nameWithoutExtension}_export").apply { mkdirs() }
            availableFormats(glb).forEach { format ->
                export(glb, format, staging).getOrNull()
            }
            val zip = File(outputDir, "${glb.nameWithoutExtension}_all_formats.zip")
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                staging.listFiles()?.filter { it.isFile }?.forEach { file ->
                    out.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            staging.deleteRecursively()
            zip
        }
    }

    /** Progress while a batch runs: which item, and how many there are. */
    data class BatchProgress(val done: Int, val total: Int, val current: String)

    /**
     * Exports many models into one .zip — each in its own folder so filenames
     * from different models cannot collide.
     *
     * Failures are collected instead of aborting: one unreadable model should
     * not cost the user the other thirty.
     */
    suspend fun exportBatch(
        models: List<File>,
        formats: List<Format>,
        outputDir: File,
        zipName: String = "void_models",
        /** Triangle and texture budget applied to each model before converting. */
        detail: MeshSimplifier.Detail = MeshSimplifier.Detail.FULL,
        scaler: MeshSimplifier.TextureScaler? = null,
        onProgress: suspend (BatchProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(models.isNotEmpty()) { "Nothing selected to export." }
            require(formats.isNotEmpty()) { "Pick at least one format." }

            outputDir.mkdirs()
            val zip = File(outputDir, "${zipName.trim().ifBlank { "void_models" }}.zip")
            val failures = mutableListOf<String>()
            var written = 0

            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                val usedNames = mutableSetOf<String>()
                models.forEachIndexed { index, glb ->
                    onProgress(BatchProgress(index, models.size, glb.nameWithoutExtension))
                    if (!glb.exists() || glb.length() == 0L) {
                        failures += "${glb.name}: file is missing"
                        return@forEachIndexed
                    }

                    // Two models can share a name, so the folder is made unique.
                    var folder = safeName(glb.nameWithoutExtension)
                    if (!usedNames.add(folder)) {
                        folder = "${folder}_${index + 1}".also { usedNames.add(it) }
                    }

                    val staging = File(outputDir, ".batch_$index").apply { mkdirs() }
                    try {
                        // Reduce once per model, then convert the reduced copy into
                        // every requested format — otherwise each format would pay
                        // for the same simplification again.
                        val source = if (detail.isFull) glb else {
                            MeshSimplifier.simplify(glb, detail, staging, scaler)
                                .getOrElse {
                                    failures += "${glb.name}: ${it.message}"
                                    return@forEachIndexed
                                }
                        }
                        val wanted = formats.intersect(availableFormats(source).toSet())
                        if (wanted.isEmpty()) {
                            failures += "${glb.name}: none of the chosen formats apply"
                            return@forEachIndexed
                        }
                        var any = false
                        wanted.forEach { format ->
                            export(source, format, staging)
                                .onSuccess { any = true }
                                .onFailure { failures += "${glb.name} (${format.label}): ${it.message}" }
                        }
                        if (!any) return@forEachIndexed

                        // The reduced intermediate is a working file, not a
                        // deliverable, unless GLB was actually asked for.
                        val skip = if (source !== glb && Format.GLB !in wanted) source.name else null
                        staging.listFiles()?.filter { it.isFile && it.name != skip }?.forEach { file ->
                            out.putNextEntry(java.util.zip.ZipEntry("$folder/${file.name}"))
                            file.inputStream().use { it.copyTo(out) }
                            out.closeEntry()
                        }
                        written++
                    } finally {
                        staging.deleteRecursively()
                    }
                }

                // A note in the archive beats a toast the user has already dismissed.
                if (failures.isNotEmpty()) {
                    out.putNextEntry(java.util.zip.ZipEntry("SKIPPED.txt"))
                    out.write(
                        ("These models could not be exported:\n\n" + failures.joinToString("\n"))
                            .toByteArray()
                    )
                    out.closeEntry()
                }
            }

            onProgress(BatchProgress(models.size, models.size, "done"))
            if (written == 0) {
                zip.delete()
                throw Exception(failures.firstOrNull() ?: "Nothing could be exported.")
            }
            zip
        }
    }

    private fun safeName(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "model" }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
