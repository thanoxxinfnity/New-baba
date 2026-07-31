package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bakes keyframed animation into a generated .glb.
 *
 * No AI animation model is available on this account — the only motion-adjacent
 * NVCF functions are a video *understanding* model and a synthetic-video
 * *detector* — so rather than pretend, this writes real glTF animation channels.
 * Unity, Unreal, Godot, Blender, three.js and the in-app viewer all play them
 * straight from the file, which is what a game asset actually needs.
 *
 * The mesh is never touched. A new node is inserted above the scene's existing
 * roots and animated instead, so whatever transforms the model already carries
 * survive untouched.
 */
object AnimationBaker {

    enum class Clip(val label: String, val note: String, val seconds: Float) {
        SPIN("Spin", "Continuous Y-axis turn — pickups, coins, trophies", 4f),
        FLOAT("Float", "Gentle bob up and down — hovering items", 3f),
        SPIN_FLOAT("Spin + Float", "Turns while bobbing — classic collectible", 4f),
        PULSE("Pulse", "Scales in and out — power-ups, beacons", 2f),
        TUMBLE("Tumble", "Rolls on two axes — debris, asteroids", 5f),
        SWAY("Sway", "Rocks side to side — trees, flags, hanging props", 3.5f),
        BOUNCE("Bounce", "Hops with a squash on landing — characters, slimes", 1.4f),
    }

    /** Sample rate. LINEAR interpolation between these reads as smooth motion. */
    private const val SAMPLES_PER_SECOND = 15

    /** Name given to the node this baker inserts and drives. */
    private const val RIG_NODE = "void_animator"

    /**
     * Writes a new .glb next to [outputDir] containing [clip] as a looping
     * animation. Returns the new file; the input is left alone.
     */
    suspend fun bake(glb: File, clip: Clip, outputDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(glb.exists() && glb.length() > 20) { "Model file is missing or empty." }
                outputDir.mkdirs()

                val bytes = glb.readBytes()
                val (root, bin) = splitGlb(bytes)

                val frames = buildFrames(clip)
                val extra = encodeFrames(frames)
                val newJson = injectAnimation(root, bin.size, extra, clip)

                // The keyframe data is appended after the existing buffer, so every
                // byteOffset already in the file stays valid.
                val target = File(outputDir, "${glb.nameWithoutExtension}_${clip.name.lowercase()}.glb")
                target.writeBytes(assembleGlb(newJson, bin + extra.bytes))
                target
            }
        }

    /**
     * Names of the animations already inside a .glb, in glTF order.
     *
     * Reads only the JSON chunk — a generated model is several megabytes of mesh
     * and texture, and pulling all of that in to look at a header would spike
     * memory for nothing.
     */
    fun clipNames(glb: File): List<String> = runCatching {
        val root = java.io.RandomAccessFile(glb, "r").use { raf ->
            val header = ByteArray(20)
            if (raf.read(header) < 20) return@runCatching emptyList()
            if (String(header, 0, 4) != "glTF") return@runCatching emptyList()
            val jsonLen = le32(header, 12)
            if (jsonLen <= 0 || 20L + jsonLen > raf.length()) return@runCatching emptyList()
            val chunk = ByteArray(jsonLen)
            raf.readFully(chunk)
            Json.parseToJsonElement(String(chunk, Charsets.UTF_8).trim()).jsonObject
        }
        root["animations"]?.jsonArray?.map { entry ->
            entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.orEmpty()
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ container

    private fun splitGlb(bytes: ByteArray): Pair<JsonObject, ByteArray> {
        require(bytes.size > 20 && String(bytes, 0, 4) == "glTF") { "Not a .glb file." }

        val jsonLen = le32(bytes, 12)
        require(jsonLen > 0 && 20 + jsonLen <= bytes.size) { "The .glb header is damaged." }
        val root = runCatching {
            Json.parseToJsonElement(String(bytes, 20, jsonLen, Charsets.UTF_8)).jsonObject
        }.getOrElse { throw IllegalArgumentException("The .glb has unreadable glTF JSON.") }

        // The JSON chunk is spec-padded to 4 bytes, but tolerate a writer that forgot.
        var offset = 20 + jsonLen
        while (offset % 4 != 0) offset++
        require(offset + 8 <= bytes.size) { "The .glb has no binary chunk." }

        val binLen = le32(bytes, offset)
        val binStart = offset + 8
        require(binLen >= 0 && binStart + binLen <= bytes.size) { "The .glb binary chunk is truncated." }
        return root to bytes.copyOfRange(binStart, binStart + binLen)
    }

    /** Re-emits a GLB container with padded, aligned chunks. */
    private fun assembleGlb(json: JsonObject, bin: ByteArray): ByteArray {
        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()   // JSON pads with spaces
        var binBytes = bin
        while (binBytes.size % 4 != 0) binBytes += 0                     // BIN pads with zeroes

        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("glTF".toByteArray()); out.putInt(2); out.putInt(total)
        out.putInt(jsonBytes.size); out.put("JSON".toByteArray()); out.put(jsonBytes)
        out.putInt(binBytes.size); out.put(byteArrayOf(0x42, 0x49, 0x4E, 0x00)); out.put(binBytes)
        return out.array()
    }

    // ------------------------------------------------------------ keyframes

    private class Frames(
        val times: FloatArray,
        val rotations: FloatArray?,      // xyzw quaternions
        val translations: FloatArray?,   // xyz
        val scales: FloatArray?,         // xyz
    )

    private fun quatY(angle: Float) = floatArrayOf(0f, sin(angle / 2f), 0f, cos(angle / 2f))

    private fun quatZ(angle: Float) = floatArrayOf(0f, 0f, sin(angle / 2f), cos(angle / 2f))

    /** X then Y, composed as q = qy * qx — enough for a convincing tumble. */
    private fun quatXY(ax: Float, ay: Float): FloatArray {
        val sx = sin(ax / 2f); val cx = cos(ax / 2f)
        val sy = sin(ay / 2f); val cy = cos(ay / 2f)
        return floatArrayOf(sx * cy, cx * sy, -sx * sy, cx * cy)
    }

    private fun buildFrames(clip: Clip): Frames {
        val count = (clip.seconds * SAMPLES_PER_SECOND).toInt().coerceAtLeast(2) + 1
        val times = FloatArray(count) { it * clip.seconds / (count - 1) }
        val tau = (2 * PI).toFloat()

        // Progress through the loop, 0..1. The last sample lands exactly on 1 so
        // the first and last keyframes match and the loop has no visible seam.
        fun p(i: Int) = times[i] / clip.seconds

        var rotations: FloatArray? = null
        var translations: FloatArray? = null
        var scales: FloatArray? = null

        fun rot(build: (Int) -> FloatArray) = FloatArray(count * 4).also { out ->
            for (i in 0 until count) build(i).copyInto(out, i * 4)
        }

        when (clip) {
            Clip.SPIN -> rotations = rot { quatY(tau * p(it)) }

            Clip.FLOAT -> translations = FloatArray(count * 3).also { out ->
                for (i in 0 until count) out[i * 3 + 1] = 0.12f * sin(tau * p(i))
            }

            Clip.SPIN_FLOAT -> {
                rotations = rot { quatY(tau * p(it)) }
                translations = FloatArray(count * 3).also { out ->
                    for (i in 0 until count) out[i * 3 + 1] = 0.10f * sin(tau * p(i))
                }
            }

            Clip.PULSE -> scales = FloatArray(count * 3).also { out ->
                for (i in 0 until count) {
                    val s = 1f + 0.12f * sin(tau * p(i))
                    out[i * 3] = s; out[i * 3 + 1] = s; out[i * 3 + 2] = s
                }
            }

            Clip.TUMBLE -> rotations = rot { quatXY(tau * p(it), tau * p(it) * 2f) }

            Clip.SWAY -> rotations = rot { quatZ(0.20f * sin(tau * p(it))) }

            Clip.BOUNCE -> {
                // |sin| gives the hop; the squash is driven off the same phase so
                // the model flattens exactly when it touches down.
                translations = FloatArray(count * 3).also { out ->
                    for (i in 0 until count) out[i * 3 + 1] = 0.28f * kotlin.math.abs(sin(PI.toFloat() * p(i)))
                }
                scales = FloatArray(count * 3).also { out ->
                    for (i in 0 until count) {
                        val air = kotlin.math.abs(sin(PI.toFloat() * p(i)))   // 0 on the ground, 1 at apex
                        val squash = 0.14f * (1f - air)
                        out[i * 3] = 1f + squash
                        out[i * 3 + 1] = 1f - squash
                        out[i * 3 + 2] = 1f + squash
                    }
                }
            }
        }
        return Frames(times, rotations, translations, scales)
    }

    private class Encoded(
        val bytes: ByteArray,
        val timeOffset: Int, val timeCount: Int, val timeMin: Float, val timeMax: Float,
        val rotOffset: Int?, val transOffset: Int?, val scaleOffset: Int?,
        val keyCount: Int,
    )

    /** Lays the keyframe arrays out back to back, each 4-byte aligned. */
    private fun encodeFrames(f: Frames): Encoded {
        val out = java.io.ByteArrayOutputStream()
        fun align() { while (out.size() % 4 != 0) out.write(0) }
        fun put(values: FloatArray): Int {
            align()
            val at = out.size()
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buf.putFloat(it) }
            out.write(buf.array())
            return at
        }

        val timeAt = put(f.times)
        val rotAt = f.rotations?.let { put(it) }
        val transAt = f.translations?.let { put(it) }
        val scaleAt = f.scales?.let { put(it) }
        align()

        return Encoded(
            bytes = out.toByteArray(),
            timeOffset = timeAt, timeCount = f.times.size,
            timeMin = f.times.first(), timeMax = f.times.last(),
            rotOffset = rotAt, transOffset = transAt, scaleOffset = scaleAt,
            keyCount = f.times.size,
        )
    }

    // ----------------------------------------------------------------- glTF

    private fun injectAnimation(
        root: JsonObject,
        binSize: Int,
        e: Encoded,
        clip: Clip,
    ): JsonObject {
        val bufferViews = (root["bufferViews"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val accessors = (root["accessors"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val nodes = (root["nodes"]?.jsonArray
            ?: throw IllegalArgumentException("This .glb has no nodes to animate."))
            .toMutableList()
        require(nodes.isNotEmpty()) { "This .glb has no nodes to animate." }

        val scenes = (root["scenes"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val sceneIndex = root["scene"]?.jsonPrimitive?.intOrNull ?: 0

        // Drive a node inserted above the scene's roots rather than the mesh node
        // itself. Overwriting a node's TRS with animation channels would throw away
        // whatever transform the exporter put there; a fresh parent cannot.
        val existingRoots: List<Int> = scenes.getOrNull(sceneIndex)
            ?.jsonObject?.get("nodes")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?: listOf(0)

        nodes += buildJsonObject {
            put("name", RIG_NODE)
            put("children", buildJsonArray { existingRoots.forEach { add(it) } })
        }
        val rigNode = nodes.size - 1

        val newScene = buildJsonObject {
            scenes.getOrNull(sceneIndex)?.jsonObject?.forEach { (k, v) -> if (k != "nodes") put(k, v) }
            put("nodes", buildJsonArray { add(rigNode) })
        }
        if (sceneIndex < scenes.size) scenes[sceneIndex] = newScene else scenes += newScene

        fun addView(byteOffset: Int, byteLength: Int): Int {
            bufferViews += buildJsonObject {
                put("buffer", 0)
                put("byteOffset", binSize + byteOffset)
                put("byteLength", byteLength)
            }
            return bufferViews.size - 1
        }

        fun addAccessor(view: Int, type: String, count: Int, min: Float? = null, max: Float? = null): Int {
            accessors += buildJsonObject {
                put("bufferView", view)
                put("componentType", 5126)    // FLOAT
                put("count", count)
                put("type", type)
                // glTF requires min/max on an animation sampler's input accessor;
                // without them players mis-time or refuse the clip.
                if (min != null && max != null) {
                    put("min", buildJsonArray { add(min) })
                    put("max", buildJsonArray { add(max) })
                }
            }
            return accessors.size - 1
        }

        val timeAccessor = addAccessor(
            addView(e.timeOffset, e.timeCount * 4), "SCALAR", e.timeCount, e.timeMin, e.timeMax,
        )

        val samplers = mutableListOf<JsonObject>()
        val channels = mutableListOf<JsonObject>()

        fun addChannel(path: String, offset: Int, components: Int) {
            val acc = addAccessor(
                addView(offset, e.keyCount * components * 4),
                if (components == 4) "VEC4" else "VEC3",
                e.keyCount,
            )
            samplers += buildJsonObject {
                put("input", timeAccessor)
                put("output", acc)
                put("interpolation", "LINEAR")
            }
            channels += buildJsonObject {
                put("sampler", samplers.size - 1)
                putJsonObject("target") {
                    put("node", rigNode)
                    put("path", path)
                }
            }
        }

        e.rotOffset?.let { addChannel("rotation", it, 4) }
        e.transOffset?.let { addChannel("translation", it, 3) }
        e.scaleOffset?.let { addChannel("scale", it, 3) }

        val animations = (root["animations"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        animations += buildJsonObject {
            put("name", clip.label)
            put("samplers", JsonArray(samplers))
            put("channels", JsonArray(channels))
        }

        return buildJsonObject {
            root.forEach { (key, value) ->
                when (key) {
                    "bufferViews", "accessors", "nodes", "scenes", "buffers", "animations" -> Unit
                    else -> put(key, value)
                }
            }
            put("bufferViews", JsonArray(bufferViews))
            put("accessors", JsonArray(accessors))
            put("nodes", JsonArray(nodes))
            put("scenes", JsonArray(scenes))
            put("buffers", buildJsonArray {
                add(buildJsonObject { put("byteLength", binSize + e.bytes.size) })
            })
            put("animations", JsonArray(animations))
        }
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
