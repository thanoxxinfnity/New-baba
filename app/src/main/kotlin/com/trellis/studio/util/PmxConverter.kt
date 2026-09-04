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
            val images = mutableListOf<JsonObject>()
            val samplers = listOf(buildJsonObject { put("wrapS", 10497); put("wrapT", 10497) })
            val texturesJson = mutableListOf<JsonObject>()
            m.textures.forEachIndexed { slot, path ->
                val bytes = findAsset(assets, path) ?: return@forEachIndexed
                val mime = when {
                    path.endsWith(".png", true) -> "image/png"
                    path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
                    // glTF images are PNG or JPEG only; MMD's .bmp/.tga/.spa files
                    // have no home here, and they are the toon and sphere maps
                    // this converter deliberately drops anyway.
                    else -> { notes += "Skipped ${path.substringAfterLast('/')} (${
                        path.substringAfterLast('.')
                    } isn't supported by glTF)"; return@forEachIndexed }
                }
                val at = put(bytes)
                images += buildJsonObject {
                    put("bufferView", view(at, bytes.size)); put("mimeType", mime)
                }
                texturesJson += buildJsonObject { put("sampler", 0); put("source", images.size - 1) }
                imageIndex[slot] = texturesJson.size - 1
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
                    put("doubleSided", true)
                    if (mat.diffuse[3] < 1f) put("alphaMode", "BLEND")
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
            uvs[i * 2] = b.float; uvs[i * 2 + 1] = b.float
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
            sidx(tIdx)                                       // sphere texture
            b.get()                                          // sphere mode
            val sharedToon = b.get().toInt()
            if (sharedToon == 0) sidx(tIdx) else b.get()
            text()                                           // memo
            val faceCount = b.int
            materials += Material(nameJp, texture, faceCount, diffuse)
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

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(48).ifBlank { "model" }
}
