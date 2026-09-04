package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a MikuMikuDance PMX model into a single .glb.
 *
 * MMD models ship as a .pmx next to Textures/ and Toon/ folders — the format
 * most anime-style character models are published in, and one nothing else in
 * this app can read. Everything portable is carried across: geometry, UVs,
 * normals, per-material texture assignment and the full bone skeleton with its
 * skin weights, which is what makes the result animatable.
 *
 * Left out on purpose, because glTF has no equivalent: MMD's toon and sphere
 * maps (a shading trick, not a texture slot), morphs, and the rigid-body
 * physics rig. The mesh, materials and skeleton are the parts that travel.
 */
object PmxConverter {

    data class Converted(
        val file: File,
        val vertices: Int,
        val triangles: Int,
        val materials: Int,
        val bones: Int,
        val textures: Int,
        val notes: List<String>,
    )

    /** True when these bytes look like a PMX 2.x model. */
    fun isPmx(bytes: ByteArray): Boolean =
        bytes.size > 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "PMX "

    /**
     * [assets] maps the archive's file paths to their bytes, so textures the PMX
     * names relative to itself can be found.
     */
    suspend fun convert(
        pmx: ByteArray,
        assets: Map<String, ByteArray>,
        outputDir: File,
        name: String,
    ): Result<Converted> = withContext(Dispatchers.IO) {
        runCatching {
            val m = parse(pmx)
            val notes = mutableListOf<String>()

            // ---- geometry buffers ------------------------------------------
            val blob = ByteArrayOutputStream()
            fun align() { while (blob.size() % 4 != 0) blob.write(0) }
            fun put(bytes: ByteArray): Int {
                align(); val at = blob.size(); blob.write(bytes); return at
            }
            fun floats(values: FloatArray): Int {
                val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { b.putFloat(it) }
                return put(b.array())
            }
            fun ushorts(values: IntArray): Int {
                val b = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { b.putShort(it.toShort()) }
                return put(b.array())
            }
            fun uints(values: IntArray): Int {
                val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { b.putInt(it) }
                return put(b.array())
            }

            val views = mutableListOf<JsonObject>()
            fun view(offset: Int, length: Int): Int {
                views += buildJsonObject {
                    put("buffer", 0); put("byteOffset", offset); put("byteLength", length)
                }
                return views.size - 1
            }
            val accessors = mutableListOf<JsonObject>()
            fun accessor(
                viewIndex: Int, componentType: Int, count: Int, type: String,
                min: List<Float>? = null, max: List<Float>? = null,
            ): Int {
                accessors += buildJsonObject {
                    put("bufferView", viewIndex); put("componentType", componentType)
                    put("count", count); put("type", type)
                    if (min != null) put("min", buildJsonArray { min.forEach { add(it) } })
                    if (max != null) put("max", buildJsonArray { max.forEach { add(it) } })
                }
                return accessors.size - 1
            }

            val n = m.positions.size / 3
            val posAt = floats(m.positions)
            val posView = view(posAt, m.positions.size * 4)
            val mins = FloatArray(3) { Float.MAX_VALUE }
            val maxs = FloatArray(3) { -Float.MAX_VALUE }
            for (i in 0 until n) for (c in 0..2) {
                val v = m.positions[i * 3 + c]
                if (v < mins[c]) mins[c] = v
                if (v > maxs[c]) maxs[c] = v
            }
            val posAcc = accessor(posView, 5126, n, "VEC3", mins.toList(), maxs.toList())

            val nrmAt = floats(m.normals)
            val nrmAcc = accessor(view(nrmAt, m.normals.size * 4), 5126, n, "VEC3")
            val uvAt = floats(m.uvs)
            val uvAcc = accessor(view(uvAt, m.uvs.size * 4), 5126, n, "VEC2")

            // Joint indices need 16 bits: MMD models routinely carry 400+ bones,
            // well past what an unsigned byte can address.
            val jntAt = ushorts(m.joints)
            val jntAcc = accessor(view(jntAt, m.joints.size * 2), 5123, n, "VEC4")
            val wgtAt = floats(m.weights)
            val wgtAcc = accessor(view(wgtAt, m.weights.size * 4), 5126, n, "VEC4")

            // ---- textures ---------------------------------------------------
            val imageIndex = HashMap<Int, Int>()      // pmx texture slot -> gltf image
            // glTF textures whose image carries an alpha channel. A material using
            // one has to be told it is cut out, or the viewer paints the
            // transparent parts as solid — which is what turned Miku's alpha-masked
            // hair ornaments into black rectangles.
            val alphaTextures = HashSet<Int>()
            val images = mutableListOf<JsonObject>()
            val samplers = listOf(buildJsonObject { put("wrapS", 10497); put("wrapT", 10497) })
            val texturesJson = mutableListOf<JsonObject>()
            m.textures.forEachIndexed { slot, path ->
                val bytes = findAsset(assets, path) ?: return@forEachIndexed
                // Judge the format by its magic bytes, not its name. MMD packs are
                // full of files renamed by hand — this model's sphere maps are
                // PNGs called .bmp — and trusting the extension threw away images
                // glTF could have carried perfectly well.
                val mime = when {
                    isPng(bytes) -> "image/png"
                    isJpeg(bytes) -> "image/jpeg"
                    else -> { notes += "Skipped ${path.substringAfterLast('/')} — " +
                        "glTF only carries PNG and JPEG"; return@forEachIndexed }
                }
                val at = put(bytes)
                images += buildJsonObject {
                    put("bufferView", view(at, bytes.size)); put("mimeType", mime)
                }
                texturesJson += buildJsonObject { put("sampler", 0); put("source", images.size - 1) }
                imageIndex[slot] = texturesJson.size - 1
                if (hasAlphaChannel(bytes)) alphaTextures += texturesJson.size - 1
            }

            // ---- one primitive per material, so each keeps its own texture ---
            val materialsJson = mutableListOf<JsonObject>()
            val primitives = mutableListOf<JsonObject>()
            var faceCursor = 0
            m.materials.forEachIndexed { i, mat ->
                val count = mat.faceCount
                if (count <= 0) return@forEachIndexed
                val slice = IntArray(count) { m.indices[faceCursor + it] }
                faceCursor += count
                val at = uints(slice)
                val acc = accessor(view(at, slice.size * 4), 5125, count, "SCALAR")

                val tex = imageIndex[mat.textureIndex]
                materialsJson += buildJsonObject {
                    put("name", mat.name)
                    put("pbrMetallicRoughness", buildJsonObject {
                        if (tex != null) {
                            put("baseColorTexture", buildJsonObject { put("index", tex) })
                        }
                        put("baseColorFactor", buildJsonArray {
                            add(mat.diffuse[0]); add(mat.diffuse[1]); add(mat.diffuse[2]); add(mat.diffuse[3])
                        })
                        // MMD models are flat-shaded anime art, not PBR metal.
                        put("metallicFactor", 0f); put("roughnessFactor", 0.9f)
                    })
                    // An additive sphere map is where some MMD materials keep all of
                    // their colour: the diffuse patch underneath is deliberately
                    // black and the sphere adds the shine on top. glTF has no
                    // sphere mapping, so dropping it left those parts pure black —
                    // Miku's hair ornaments and headphones came out as black boxes.
                    // The map's average colour goes in as emissive instead, which
                    // is additive in the same way, just not view-dependent.
                    if (mat.sphereMode == SPHERE_ADD) {
                        averageColour(m.textures.getOrNull(mat.sphereIndex), assets)?.let { rgb ->
                            put("emissiveFactor", buildJsonArray { rgb.forEach { add(it) } })
                        }
                    }
                    put("doubleSided", true)
                    when {
                        // A part the author faded out with the material's own alpha.
                        mat.diffuse[3] < 1f -> put("alphaMode", "BLEND")
                        // Transparency living in the texture instead. MMD alpha-tests
                        // these (hair cards, ornaments, lace), so MASK matches how
                        // they are meant to look and avoids sorting artefacts.
                        tex != null && tex in alphaTextures -> {
                            put("alphaMode", "MASK"); put("alphaCutoff", 0.5f)
                        }
                    }
                }
                primitives += buildJsonObject {
                    put("attributes", buildJsonObject {
                        put("POSITION", posAcc); put("NORMAL", nrmAcc); put("TEXCOORD_0", uvAcc)
                        put("JOINTS_0", jntAcc); put("WEIGHTS_0", wgtAcc)
                    })
                    put("indices", acc)
                    put("material", materialsJson.size - 1)
                }
            }

            // ---- skeleton ----------------------------------------------------
            // Joint nodes come first so their indices are stable, then the mesh
            // node; glTF wants a node per bone with local translations.
            val nodes = mutableListOf<JsonObject>()
            val childrenOf = HashMap<Int, MutableList<Int>>()
            m.bones.forEachIndexed { i, b ->
                if (b.parent in m.bones.indices) childrenOf.getOrPut(b.parent) { mutableListOf() } += i
            }
            m.bones.forEachIndexed { i, b ->
                val parent = b.parent.takeIf { it in m.bones.indices }
                val local = if (parent == null) b.position else floatArrayOf(
                    b.position[0] - m.bones[parent].position[0],
                    b.position[1] - m.bones[parent].position[1],
                    b.position[2] - m.bones[parent].position[2],
                )
                nodes += buildJsonObject {
                    put("name", b.name.ifBlank { "bone_$i" })
                    put("translation", buildJsonArray { local.forEach { add(it) } })
                    childrenOf[i]?.let { kids ->
                        put("children", buildJsonArray { kids.forEach { add(it) } })
                    }
                }
            }
            val meshNode = nodes.size
            nodes += buildJsonObject { put("name", "mesh"); put("mesh", 0); put("skin", 0) }

            // Inverse bind matrices: the bones carry no rotation, so each is just
            // a translation by minus the bone's world position.
            val ibm = FloatArray(m.bones.size * 16)
            m.bones.forEachIndexed { i, b ->
                val o = i * 16
                ibm[o] = 1f; ibm[o + 5] = 1f; ibm[o + 10] = 1f; ibm[o + 15] = 1f
                ibm[o + 12] = -b.position[0]; ibm[o + 13] = -b.position[1]; ibm[o + 14] = -b.position[2]
            }
            val ibmAt = floats(ibm)
            val ibmAcc = accessor(view(ibmAt, ibm.size * 4), 5126, m.bones.size, "MAT4")

            val roots = m.bones.indices.filter { m.bones[it].parent !in m.bones.indices }

            val binary = blob.toByteArray()
            val root = buildJsonObject {
                put("asset", buildJsonObject { put("version", "2.0"); put("generator", "VOID PMX converter") })
                put("scene", 0)
                put("scenes", buildJsonArray {
                    add(buildJsonObject {
                        put("nodes", buildJsonArray { roots.forEach { add(it) }; add(meshNode) })
                    })
                })
                put("nodes", JsonArray(nodes))
                put("meshes", buildJsonArray {
                    add(buildJsonObject {
                        put("name", name)
                        put("primitives", JsonArray(primitives))
                    })
                })
                put("skins", buildJsonArray {
                    add(buildJsonObject {
                        put("inverseBindMatrices", ibmAcc)
                        put("joints", buildJsonArray { m.bones.indices.forEach { add(it) } })
                    })
                })
                put("materials", JsonArray(materialsJson))
                if (texturesJson.isNotEmpty()) {
                    put("textures", JsonArray(texturesJson))
                    put("images", JsonArray(images))
                    put("samplers", JsonArray(samplers))
                }
                put("accessors", JsonArray(accessors))
                put("bufferViews", JsonArray(views))
                put("buffers", buildJsonArray {
                    add(buildJsonObject { put("byteLength", binary.size) })
                })
            }

            outputDir.mkdirs()
            val out = File(outputDir, "${safe(name)}.glb")
            out.writeBytes(Glb.assemble(root, binary))
            Converted(
                file = out,
                vertices = n,
                triangles = m.indices.size / 3,
                materials = materialsJson.size,
                bones = m.bones.size,
                textures = images.size,
                notes = notes.distinct(),
            )
        }
    }

    // ------------------------------------------------------------------ parse

    private class Bone(val name: String, val parent: Int, val position: FloatArray)
    private class Material(
        val name: String, val textureIndex: Int, val faceCount: Int, val diffuse: FloatArray,
        /** MMD sphere map: its own texture slot, and 0 none / 1 multiply / 2 add. */
        val sphereIndex: Int, val sphereMode: Int,
    )
    private class Model(
        val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray,
        val joints: IntArray, val weights: FloatArray, val indices: IntArray,
        val textures: List<String>, val materials: List<Material>, val bones: List<Bone>,
    )

    private fun parse(d: ByteArray): Model {
        require(isPmx(d)) { "This isn't a PMX model." }
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val version = b.float
        require(version >= 2.0f) { "Only PMX 2.0 and newer are supported (this is $version)." }
        val globalsCount = b.get().toInt()
        val globals = ByteArray(globalsCount).also { b.get(it) }
        val encoding = globals[0].toInt()          // 0 = UTF-16LE, 1 = UTF-8
        val addUv = globals[1].toInt()
        val vIdx = globals[2].toInt()
        val tIdx = globals[3].toInt()
        val mIdx = globals[4].toInt()
        val bIdx = globals[5].toInt()
        val moIdx = globals[6].toInt()
        val rIdx = globals[7].toInt()

        fun text(): String {
            val len = b.int
            if (len <= 0) return ""
            val raw = ByteArray(len).also { b.get(it) }
            return String(raw, if (encoding == 0) Charsets.UTF_16LE else Charsets.UTF_8)
        }
        // Signed index: -1 means "none".
        fun sidx(size: Int): Int = when (size) {
            1 -> b.get().toInt()
            2 -> b.short.toInt()
            else -> b.int
        }
        // Vertex indices are unsigned; a 2-byte one must not come back negative.
        fun vidx(size: Int): Int = when (size) {
            1 -> b.get().toInt() and 0xFF
            2 -> b.short.toInt() and 0xFFFF
            else -> b.int
        }

        repeat(4) { text() }                       // model names and comments

        val vertexCount = b.int
        require(vertexCount in 1..4_000_000) { "The model reports an impossible vertex count." }
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        val joints = IntArray(vertexCount * 4)
        val weights = FloatArray(vertexCount * 4)

        for (i in 0 until vertexCount) {
            // PMX is left-handed; glTF is right-handed. Negating Z converts the
            // handedness, and the triangle winding is reversed to match below.
            positions[i * 3] = b.float; positions[i * 3 + 1] = b.float; positions[i * 3 + 2] = -b.float
            normals[i * 3] = b.float; normals[i * 3 + 1] = b.float; normals[i * 3 + 2] = -b.float
            // V is flipped into glTF's convention. Passing it through unchanged
            // left every texture upside down in any spec-following viewer —
            // Blender, Unity, Godot — which showed up as a flat, dark face with
            // no eyelashes, because the eye and skin detail was being sampled
            // from the wrong half of the sheet.
            uvs[i * 2] = b.float; uvs[i * 2 + 1] = 1f - b.float
            repeat(addUv) { repeat(4) { b.float } }

            when (b.get().toInt()) {
                0 -> { joints[i * 4] = sidx(bIdx).coerceAtLeast(0); weights[i * 4] = 1f }
                1 -> {
                    val j0 = sidx(bIdx); val j1 = sidx(bIdx); val w = b.float
                    joints[i * 4] = j0.coerceAtLeast(0); joints[i * 4 + 1] = j1.coerceAtLeast(0)
                    weights[i * 4] = w; weights[i * 4 + 1] = 1f - w
                }
                2, 4 -> {                                   // BDEF4 and QDEF share a layout
                    val js = IntArray(4) { sidx(bIdx) }
                    val ws = FloatArray(4) { b.float }
                    val sum = ws.sum()
                    for (k in 0..3) {
                        joints[i * 4 + k] = js[k].coerceAtLeast(0)
                        weights[i * 4 + k] = if (sum > 0f) ws[k] / sum else if (k == 0) 1f else 0f
                    }
                }
                3 -> {                                      // SDEF: weighted like BDEF2
                    val j0 = sidx(bIdx); val j1 = sidx(bIdx); val w = b.float
                    joints[i * 4] = j0.coerceAtLeast(0); joints[i * 4 + 1] = j1.coerceAtLeast(0)
                    weights[i * 4] = w; weights[i * 4 + 1] = 1f - w
                    repeat(9) { b.float }                   // C, R0, R1 — no glTF equivalent
                }
                else -> throw IllegalArgumentException("Unknown vertex weight type in this PMX.")
            }
            b.float                                          // edge scale
        }

        val indexCount = b.int
        val indices = IntArray(indexCount)
        var i = 0
        while (i < indexCount) {
            val a = vidx(vIdx); val c = vidx(vIdx); val e = vidx(vIdx)
            // Reversed winding, to match the negated Z above.
            indices[i] = a; indices[i + 1] = e; indices[i + 2] = c
            i += 3
        }

        val textureCount = b.int
        val textures = (0 until textureCount).map { text().replace('\\', '/') }

        val materialCount = b.int
        val materials = ArrayList<Material>(materialCount)
        repeat(materialCount) {
            val nameJp = text(); text()
            val diffuse = FloatArray(4) { b.float }
            repeat(3) { b.float }                            // specular
            b.float                                          // shininess
            repeat(3) { b.float }                            // ambient
            b.get()                                          // draw flags
            repeat(4) { b.float }                            // edge colour
            b.float                                          // edge size
            val texture = sidx(tIdx)
            val sphere = sidx(tIdx)
            val sphereMode = b.get().toInt()
            val sharedToon = b.get().toInt()
            if (sharedToon == 0) sidx(tIdx) else b.get()
            text()                                           // memo
            val faceCount = b.int
            materials += Material(nameJp, texture, faceCount, diffuse, sphere, sphereMode)
        }

        val boneCount = b.int
        val bones = ArrayList<Bone>(boneCount)
        repeat(boneCount) {
            val nameJp = text(); text()
            val pos = floatArrayOf(b.float, b.float, -b.float)
            val parent = sidx(bIdx)
            b.int                                            // deform layer
            val flags = b.short.toInt() and 0xFFFF
            if (flags and 0x0001 != 0) sidx(bIdx) else repeat(3) { b.float }
            if (flags and (0x0100 or 0x0200) != 0) { sidx(bIdx); b.float }
            if (flags and 0x0400 != 0) repeat(3) { b.float }
            if (flags and 0x0800 != 0) repeat(6) { b.float }
            if (flags and 0x2000 != 0) b.int
            if (flags and 0x0020 != 0) {                     // IK
                sidx(bIdx); b.int; b.float
                val links = b.int
                repeat(links) {
                    sidx(bIdx)
                    if (b.get().toInt() != 0) repeat(6) { b.float }
                }
            }
            bones += Bone(nameJp, parent, pos)
        }

        return Model(positions, normals, uvs, joints, weights, indices, textures, materials, bones)
    }

    /**
     * PMX names textures relative to itself with Windows separators, and the
     * archive may hold them under a folder. Matching on the tail of the path,
     * case-insensitively, is what actually finds them.
     */
    private fun findAsset(assets: Map<String, ByteArray>, path: String): ByteArray? {
        val wanted = path.replace('\\', '/').trimStart('.', '/')
        assets[wanted]?.let { return it }
        val lower = wanted.lowercase()
        assets.entries.firstOrNull { it.key.replace('\\', '/').lowercase() == lower }?.let { return it.value }
        val leaf = lower.substringAfterLast('/')
        return assets.entries.firstOrNull {
            it.key.replace('\\', '/').lowercase().substringAfterLast('/') == leaf
        }?.value
    }

    /**
     * True when a PNG declares an alpha channel. Read from the IHDR colour type
     * rather than by decoding the image, which would cost megabytes per texture:
     * 4 is grey+alpha and 6 is RGBA, and an indexed image can carry a tRNS
     * chunk instead. JPEG has no alpha at all.
     */
    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() &&
        b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun hasAlphaChannel(bytes: ByteArray): Boolean {
        if (bytes.size < 26) return false
        if (!isPng(bytes)) return false
        return when (bytes[25].toInt()) {
            4, 6 -> true
            3 -> String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).contains("tRNS")
            else -> false
        }
    }

    private const val SPHERE_ADD = 2

    /**
     * Mean colour of a sphere map, used as a flat stand-in for the shine it
     * would have added.
     *
     * PNG is decoded here rather than through the platform image loader so the
     * conversion behaves the same on a JVM test as on a device. Only the common
     * 8-bit RGB and RGBA forms are handled; anything else returns null and the
     * material simply keeps no emissive.
     */
    private fun averageColour(path: String?, assets: Map<String, ByteArray>): FloatArray? {
        if (path == null) return null
        val bytes = findAsset(assets, path) ?: return null
        return runCatching {
            when {
                isPng(bytes) -> averagePng(bytes)
                bytes.size > 54 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() ->
                    averageBmp(bytes)
                else -> null
            }
        }.getOrNull()
    }

    private fun averagePng(bytes: ByteArray): FloatArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val width = b.getInt(16)
        val height = b.getInt(20)
        val depth = bytes[24].toInt()
        val colour = bytes[25].toInt()
        if (depth != 8 || (colour != 2 && colour != 6)) return null
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null
        val channels = if (colour == 6) 4 else 3

        // Concatenate the IDAT chunks, then inflate.
        val idat = ByteArrayOutputStream()
        var p = 8
        while (p + 8 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, p, 4).order(ByteOrder.BIG_ENDIAN).int
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (len < 0 || p + 12 + len > bytes.size) break
            if (type == "IDAT") idat.write(bytes, p + 8, len)
            if (type == "IEND") break
            p += 12 + len
        }
        if (idat.size() == 0) return null
        val raw = java.util.zip.Inflater().run {
            setInput(idat.toByteArray())
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!finished()) {
                val n = inflate(buf)
                if (n == 0 && (needsInput() || needsDictionary())) break
                out.write(buf, 0, n)
            }
            end()
            out.toByteArray()
        }

        val stride = width * channels
        if (raw.size < (stride + 1) * height) return null
        val line = ByteArray(stride)
        val prev = ByteArray(stride)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        var off = 0
        for (y in 0 until height) {
            val filter = raw[off].toInt() and 0xFF
            System.arraycopy(raw, off + 1, line, 0, stride)
            off += 1 + stride
            // Undo the PNG scanline filter, which is what makes the bytes meaningful.
            for (i in 0 until stride) {
                val a = if (i >= channels) line[i - channels].toInt() and 0xFF else 0
                val bb = prev[i].toInt() and 0xFF
                val c = if (i >= channels) prev[i - channels].toInt() and 0xFF else 0
                val x = line[i].toInt() and 0xFF
                line[i] = when (filter) {
                    1 -> (x + a)
                    2 -> (x + bb)
                    3 -> (x + (a + bb) / 2)
                    4 -> {
                        val pp = a + bb - c
                        val pa = kotlin.math.abs(pp - a)
                        val pb = kotlin.math.abs(pp - bb)
                        val pc = kotlin.math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) bb else c
                    }
                    else -> x
                }.toByte()
            }
            System.arraycopy(line, 0, prev, 0, stride)
            var i = 0
            while (i < stride) {
                // Weight by alpha where there is one: a transparent pixel carries
                // no colour worth averaging in.
                val alpha = if (channels == 4) line[i + 3].toInt() and 0xFF else 255
                if (alpha > 8) {
                    r += (line[i].toInt() and 0xFF).toLong()
                    g += (line[i + 1].toInt() and 0xFF).toLong()
                    bl += (line[i + 2].toInt() and 0xFF).toLong()
                    n++
                }
                i += channels
            }
        }
        if (n == 0L) return null
        return floatArrayOf(r.toFloat() / n / 255f, g.toFloat() / n / 255f, bl.toFloat() / n / 255f)
    }

    private fun averageBmp(bytes: ByteArray): FloatArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = b.getInt(10)
        val width = b.getInt(18)
        val height = b.getInt(22)
        val bpp = b.getShort(28).toInt()
        if (bpp != 24 && bpp != 32) return null
        if (width <= 0 || height == 0) return null
        val step = bpp / 8
        val rowSize = ((width * step + 3) / 4) * 4
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        val stride = maxOf(1, width / 64)
        var y = 0
        while (y < kotlin.math.abs(height)) {
            var x = 0
            while (x < width) {
                val at = dataStart + y * rowSize + x * step
                if (at + 2 < bytes.size) {
                    bl += (bytes[at].toInt() and 0xFF).toLong()
                    g += (bytes[at + 1].toInt() and 0xFF).toLong()
                    r += (bytes[at + 2].toInt() and 0xFF).toLong()
                    n++
                }
                x += stride
            }
            y += stride
        }
        if (n == 0L) return null
        return floatArrayOf(r.toFloat() / n / 255f, g.toFloat() / n / 255f, bl.toFloat() / n / 255f)
    }

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(48).ifBlank { "model" }
}
