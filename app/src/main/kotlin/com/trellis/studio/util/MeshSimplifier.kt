package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cuts a generated model down to a triangle budget, the way Meshy and Tripo ask
 * for a polycount before you download.
 *
 * TRELLIS output is dense — measured on real models, 9.8k to 24.5k triangles for
 * a single prop — which is far past what a mobile game wants for a background
 * object. Reduction happens by vertex clustering: the model is divided into a
 * grid, every vertex in a cell collapses onto one representative, and triangles
 * that end up with two corners in the same cell disappear.
 *
 * The representative is the vertex nearest its cell's centre, and its UV is
 * carried over untouched rather than averaged. Averaging looks tempting but
 * blends across UV seams, which drags unrelated parts of the texture into the
 * triangle and shows up as coloured smears.
 */
object MeshSimplifier {

    enum class Detail(
        val label: String,
        val note: String,
        val targetTriangles: Int,
        /** Longest texture edge in pixels; 0 keeps the original. */
        val textureSize: Int,
    ) {
        FULL("Full detail", "Every triangle as generated — film, print, sculpting", 0, 0),
        HIGH("High", "About 40k triangles, 1K texture — hero props on PC and console", 40_000, 1024),
        MEDIUM("Medium", "About 15k triangles, 1K texture — the usual game budget", 15_000, 1024),
        LOW("Low", "About 6k triangles, 512px texture — mobile, many objects", 6_000, 512),
        TINY("Very low", "About 2k triangles, 256px texture — distant scenery, VR", 2_000, 256);

        val isFull get() = targetTriangles <= 0 && textureSize <= 0
    }

    /**
     * Shrinks a PNG to fit a square of [maxEdge]. Supplied by the caller because
     * decoding an image needs Android's Bitmap, which does not exist on the JVM
     * where the mesh maths is tested.
     */
    fun interface TextureScaler {
        fun scale(png: ByteArray, maxEdge: Int): ByteArray?
    }

    /** What a model costs right now, for showing next to the choices. */
    data class Size(val triangles: Int, val vertices: Int, val bytes: Long)

    fun measure(glb: File): Size? = runCatching {
        val mesh = ModelExporter.parseGlb(glb)
        Size(mesh.triangleCount, mesh.vertexCount, glb.length())
    }.getOrNull()

    /**
     * Writes a copy of [glb] reduced to roughly [detail]'s triangle budget.
     * Returns the input unchanged when no reduction is wanted or needed.
     */
    suspend fun simplify(
        glb: File,
        detail: Detail,
        outputDir: File,
        scaler: TextureScaler? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (detail.isFull) return@runCatching glb
            outputDir.mkdirs()

            val mesh = ModelExporter.parseGlb(glb)

            // The texture is the bigger half of the file — measured on real
            // output, 52% to 66% of the bytes — so shrinking it matters at least
            // as much as dropping triangles.
            val texture = if (detail.textureSize > 0 && mesh.texturePng != null && scaler != null) {
                scaler.scale(mesh.texturePng, detail.textureSize) ?: mesh.texturePng
            } else {
                mesh.texturePng
            }

            val overBudget = detail.targetTriangles in 1 until mesh.triangleCount
            // Already small enough in both respects: rebuilding would lose
            // quality for nothing.
            if (!overBudget && texture === mesh.texturePng) return@runCatching glb

            val reduced = if (overBudget) reduce(mesh, detail.targetTriangles) else mesh
            val target = File(outputDir, "${glb.nameWithoutExtension}_${detail.name.lowercase()}.glb")
            target.writeBytes(writeGlb(reduced.copy(texturePng = texture)))
            target
        }
    }

    // ------------------------------------------------------------- clustering

    /**
     * Collapses [mesh] onto a grid chosen so the result lands near [target]
     * triangles. The grid size that hits a budget cannot be computed directly —
     * it depends on how the geometry is distributed — so it is searched for.
     */
    internal fun reduce(mesh: ModelExporter.Mesh, target: Int): ModelExporter.Mesh {
        if (mesh.triangleCount <= target || mesh.vertexCount == 0) return mesh

        // A cube grid of n cells per side yields at most n^3 vertices, and a
        // closed surface has roughly twice as many triangles as vertices. That
        // gives a starting guess; the search corrects it.
        var low = 2
        var high = 512
        var best: ModelExporter.Mesh? = null

        var guess = cbrt(target.toDouble() / 2.0).roundToInt().coerceIn(low, high)
        repeat(SEARCH_STEPS) {
            val candidate = cluster(mesh, guess)
            if (candidate.triangleCount <= target) {
                // Good enough, and the closest one under budget wins.
                if (best == null || candidate.triangleCount > best!!.triangleCount) best = candidate
                low = guess
            } else {
                high = guess
            }
            val next = (low + high) / 2
            if (next == guess) return@repeat
            guess = next
        }
        // Falling back to the densest grid tried keeps some geometry rather than
        // returning something emptier than asked for.
        return best ?: cluster(mesh, low)
    }

    private fun cluster(mesh: ModelExporter.Mesh, gridSize: Int): ModelExporter.Mesh {
        val p = mesh.positions
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < p.size) {
            minX = min(minX, p[i]); maxX = max(maxX, p[i])
            minY = min(minY, p[i + 1]); maxY = max(maxY, p[i + 1])
            minZ = min(minZ, p[i + 2]); maxZ = max(maxZ, p[i + 2])
            i += 3
        }
        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)
        val spanZ = (maxZ - minZ).coerceAtLeast(1e-6f)
        val n = gridSize.coerceIn(1, 1024)

        fun cellOf(v: Int): Long {
            val cx = floor((p[v * 3] - minX) / spanX * n).toInt().coerceIn(0, n - 1)
            val cy = floor((p[v * 3 + 1] - minY) / spanY * n).toInt().coerceIn(0, n - 1)
            val cz = floor((p[v * 3 + 2] - minZ) / spanZ * n).toInt().coerceIn(0, n - 1)
            return (cx.toLong() * n + cy) * n + cz
        }

        // One representative per cell: the vertex closest to the cell's centre,
        // so its UV can be reused verbatim instead of blended across a seam.
        val bestInCell = HashMap<Long, Int>(mesh.vertexCount / 2 + 8)
        val bestDistance = HashMap<Long, Float>(mesh.vertexCount / 2 + 8)
        for (v in 0 until mesh.vertexCount) {
            val cell = cellOf(v)
            val cx = (cell / n / n).toInt()
            val cy = ((cell / n) % n).toInt()
            val cz = (cell % n).toInt()
            val centreX = minX + (cx + 0.5f) * spanX / n
            val centreY = minY + (cy + 0.5f) * spanY / n
            val centreZ = minZ + (cz + 0.5f) * spanZ / n
            val dx = p[v * 3] - centreX
            val dy = p[v * 3 + 1] - centreY
            val dz = p[v * 3 + 2] - centreZ
            val d = dx * dx + dy * dy + dz * dz
            if (bestDistance[cell]?.let { d < it } != false) {
                bestDistance[cell] = d
                bestInCell[cell] = v
            }
        }

        // Renumber the survivors and rebuild the vertex arrays.
        val newIndexOf = HashMap<Long, Int>(bestInCell.size)
        val positions = FloatArray(bestInCell.size * 3)
        val hasUvs = mesh.uvs.size >= mesh.vertexCount * 2
        val uvs = if (hasUvs) FloatArray(bestInCell.size * 2) else FloatArray(0)
        var next = 0
        for ((cell, v) in bestInCell) {
            newIndexOf[cell] = next
            positions[next * 3] = p[v * 3]
            positions[next * 3 + 1] = p[v * 3 + 1]
            positions[next * 3 + 2] = p[v * 3 + 2]
            if (hasUvs) {
                uvs[next * 2] = mesh.uvs[v * 2]
                uvs[next * 2 + 1] = mesh.uvs[v * 2 + 1]
            }
            next++
        }

        val indices = ArrayList<Int>(mesh.indices.size / 2)
        var t = 0
        while (t < mesh.indices.size) {
            val a = newIndexOf[cellOf(mesh.indices[t])] ?: -1
            val b = newIndexOf[cellOf(mesh.indices[t + 1])] ?: -1
            val c = newIndexOf[cellOf(mesh.indices[t + 2])] ?: -1
            // Two corners landing in the same cell means the triangle collapsed
            // to a line; keeping it would leave invisible degenerate geometry.
            if (a >= 0 && b >= 0 && c >= 0 && a != b && b != c && a != c) {
                indices += a; indices += b; indices += c
            }
            t += 3
        }

        return ModelExporter.Mesh(positions, uvs, indices.toIntArray(), mesh.texturePng)
    }

    // ------------------------------------------------------------- glTF write

    /** Packs a mesh back into a self-contained .glb, texture included. */
    internal fun writeGlb(mesh: ModelExporter.Mesh): ByteArray {
        val bin = ByteArrayOutputStream()
        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun putFloats(values: FloatArray): Pair<Int, Int> {
            align()
            val at = bin.size()
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buf.putFloat(it) }
            bin.write(buf.array())
            return at to values.size * 4
        }

        val (posAt, posLen) = putFloats(mesh.positions)
        val uvSlot = if (mesh.uvs.isNotEmpty()) putFloats(mesh.uvs) else null

        align()
        val idxAt = bin.size()
        // Indices stay 32-bit: a reduced mesh can still exceed 65535 vertices,
        // and guessing wrong here corrupts the model silently.
        val idxBuf = ByteBuffer.allocate(mesh.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        mesh.indices.forEach { idxBuf.putInt(it) }
        bin.write(idxBuf.array())
        val idxLen = mesh.indices.size * 4

        align()
        val textureSlot = mesh.texturePng?.let {
            val at = bin.size()
            bin.write(it)
            align()
            at to it.size
        }

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < mesh.positions.size) {
            minX = min(minX, mesh.positions[i]); maxX = max(maxX, mesh.positions[i])
            minY = min(minY, mesh.positions[i + 1]); maxY = max(maxY, mesh.positions[i + 1])
            minZ = min(minZ, mesh.positions[i + 2]); maxZ = max(maxZ, mesh.positions[i + 2])
            i += 3
        }

        val views = mutableListOf<JsonObject>()
        fun addView(offset: Int, length: Int, target: Int? = null): Int {
            views += buildJsonObject {
                put("buffer", 0); put("byteOffset", offset); put("byteLength", length)
                if (target != null) put("target", target)
            }
            return views.size - 1
        }

        val posView = addView(posAt, posLen, 34962)          // ARRAY_BUFFER
        val uvView = uvSlot?.let { addView(it.first, it.second, 34962) }
        val idxView = addView(idxAt, idxLen, 34963)          // ELEMENT_ARRAY_BUFFER
        val texView = textureSlot?.let { addView(it.first, it.second) }

        val accessors = mutableListOf<JsonObject>()
        accessors += buildJsonObject {
            put("bufferView", posView); put("componentType", 5126)
            put("count", mesh.vertexCount); put("type", "VEC3")
            put("min", buildJsonArray { add(minX); add(minY); add(minZ) })
            put("max", buildJsonArray { add(maxX); add(maxY); add(maxZ) })
        }
        val uvAccessor = uvView?.let {
            accessors += buildJsonObject {
                put("bufferView", it); put("componentType", 5126)
                put("count", mesh.vertexCount); put("type", "VEC2")
            }
            accessors.size - 1
        }
        accessors += buildJsonObject {
            put("bufferView", idxView); put("componentType", 5125)   // UNSIGNED_INT
            put("count", mesh.indices.size); put("type", "SCALAR")
        }
        val idxAccessor = accessors.size - 1

        val json = buildJsonObject {
            put("asset", buildJsonObject {
                put("version", "2.0"); put("generator", "VOID mesh simplifier")
            })
            put("scene", 0)
            put("scenes", buildJsonArray {
                add(buildJsonObject { put("nodes", buildJsonArray { add(0) }) })
            })
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("name", "world"); put("children", buildJsonArray { add(1) }) })
                add(buildJsonObject { put("name", "geometry_0"); put("mesh", 0) })
            })
            put("meshes", buildJsonArray {
                add(buildJsonObject {
                    put("primitives", buildJsonArray {
                        add(buildJsonObject {
                            put("attributes", buildJsonObject {
                                put("POSITION", 0)
                                uvAccessor?.let { put("TEXCOORD_0", it) }
                            })
                            put("indices", idxAccessor)
                            if (texView != null) put("material", 0)
                        })
                    })
                })
            })
            put("accessors", JsonArray(accessors))
            put("bufferViews", JsonArray(views))
            put("buffers", buildJsonArray {
                add(buildJsonObject { put("byteLength", bin.size()) })
            })
            if (texView != null) {
                put("images", buildJsonArray {
                    add(buildJsonObject { put("bufferView", texView); put("mimeType", "image/png") })
                })
                put("samplers", buildJsonArray {
                    add(buildJsonObject { put("magFilter", 9729); put("minFilter", 9987) })
                })
                put("textures", buildJsonArray {
                    add(buildJsonObject { put("sampler", 0); put("source", 0) })
                })
                put("materials", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "material_0")
                        put("pbrMetallicRoughness", buildJsonObject {
                            put("baseColorTexture", buildJsonObject { put("index", 0) })
                            put("metallicFactor", 0.0)
                            put("roughnessFactor", 1.0)
                        })
                        put("doubleSided", true)
                    })
                })
            }
        }

        return Glb.assemble(json, bin.toByteArray())
    }

    private const val SEARCH_STEPS = 12
}
