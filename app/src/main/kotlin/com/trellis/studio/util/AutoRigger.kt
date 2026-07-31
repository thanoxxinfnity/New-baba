package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a skeleton for an unrigged generated mesh and animates it.
 *
 * A TRELLIS model is one fused shell: measured on real output, a dog and a
 * human weld down to a single connected surface and a car to 94% one surface
 * with the wheels merged into the body. There are no joints, no skin weights
 * and no separate parts, so nothing in the file can "walk" or "spin" as it
 * stands — and no AI rigging model is available on this account to add them.
 *
 * This does it procedurally instead: bones are placed from the mesh's own
 * proportions, every vertex gets skin weights, and the bones are keyframed.
 * The output is standard glTF skinning, so Unity, Unreal, Godot, Blender and
 * three.js deform it exactly the same way the in-app viewer does.
 *
 * It is a fit, not an understanding of the model — see [Rig.caveat].
 */
object AutoRigger {

    enum class Rig(
        val label: String,
        val note: String,
        val caveat: String,
        val seconds: Float,
    ) {
        HUMANOID_WALK(
            "Walk (two legs)",
            "Hips, spine, arms and legs — a stepping walk cycle",
            "Fits a person-shaped model standing upright. A crouched or seated pose will bend oddly.",
            1.2f,
        ),
        HUMANOID_RUN(
            "Run (two legs)",
            "Same skeleton, faster stride with more lean",
            "Fits a person-shaped model standing upright.",
            0.75f,
        ),
        QUADRUPED_WALK(
            "Walk (four legs)",
            "Spine plus four legs — diagonal gait for animals",
            "Fits an animal standing on four legs, longer than it is tall.",
            1.4f,
        ),
        VEHICLE_WHEELS(
            "Spinning wheels",
            "Finds the wheels and turns them on their axles",
            "Needs four visible wheels near the corners. The wheels are cut out of the body by shape, so a heavily skirted car may drag a little bodywork.",
            2f,
        );

        val isVehicle get() = this == VEHICLE_WHEELS
    }

    private const val SAMPLES_PER_SECOND = 20
    private const val MAX_INFLUENCES = 4
    private const val SKIN_ROOT = "void_rig_root"

    /** What the model's proportions suggest, so the UI can preselect sensibly. */
    fun suggest(glb: File): Rig = runCatching {
        val mesh = readMesh(glb) ?: return Rig.HUMANOID_WALK
        val b = Bounds(mesh.positions)
        // Up is the axis the model is tallest on for a figure, and a vehicle is
        // the flat, long case: much longer than tall and wider than tall.
        val flatAndLong = b.height < 0.6f * b.length && b.width > 1.2f * b.height
        val tall = b.height > 1.15f * max(b.length, b.width)
        when {
            flatAndLong -> Rig.VEHICLE_WHEELS
            tall -> Rig.HUMANOID_WALK
            else -> Rig.QUADRUPED_WALK
        }
    }.getOrDefault(Rig.HUMANOID_WALK)

    /**
     * Writes a new .glb with a skeleton, skin weights and [rig]'s motion.
     * The original mesh data is untouched; everything is appended.
     */
    suspend fun rig(glb: File, rig: Rig, outputDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(glb.exists() && glb.length() > 20) { "Model file is missing or empty." }
                outputDir.mkdirs()

                val (root, bin) = Glb.split(glb.readBytes())
                val mesh = readMesh(root, bin)
                    ?: throw IllegalArgumentException("This model has no mesh to rig.")

                val bounds = Bounds(mesh.positions)
                val skeleton = when (rig) {
                    Rig.HUMANOID_WALK, Rig.HUMANOID_RUN -> humanoid(bounds)
                    Rig.QUADRUPED_WALK -> quadruped(bounds)
                    Rig.VEHICLE_WHEELS -> vehicle(mesh.positions, bounds)
                }

                val weights = skinWeights(mesh.positions, skeleton, rig)
                val clips = motion(skeleton, rig)
                val out = File(outputDir, "${glb.nameWithoutExtension}_${rig.name.lowercase()}.glb")
                out.writeBytes(inject(root, bin, mesh, skeleton, weights, clips, rig))
                out
            }
        }

    // ------------------------------------------------------------------ mesh

    private class MeshRef(
        val meshIndex: Int,
        val primitiveIndex: Int,
        val nodeIndex: Int,
        val positionAccessor: Int,
        val positions: FloatArray,
    )

    private fun readMesh(glb: File): MeshRef? = runCatching {
        val (root, bin) = Glb.split(glb.readBytes())
        readMesh(root, bin)
    }.getOrNull()

    private fun readMesh(root: JsonObject, bin: ByteArray): MeshRef? {
        val nodes = root["nodes"]?.jsonArray ?: return null
        val nodeIndex = nodes.indexOfFirst { it.jsonObject.containsKey("mesh") }
        if (nodeIndex < 0) return null
        val node = nodes[nodeIndex].jsonObject

        // A rig places joints in model space, so a transform on the mesh node
        // would silently shift every bone. TRELLIS output has none.
        if (node.keys.any { it == "translation" || it == "rotation" || it == "scale" || it == "matrix" }) {
            throw IllegalArgumentException("This model's mesh carries its own transform, which auto-rigging cannot place bones through.")
        }

        val meshIndex = node["mesh"]!!.jsonPrimitive.int
        val primitives = root["meshes"]!!.jsonArray[meshIndex].jsonObject["primitives"]!!.jsonArray
        if (primitives.size != 1) {
            throw IllegalArgumentException("This model has ${primitives.size} mesh parts; auto-rigging handles a single one.")
        }
        val prim = primitives[0].jsonObject
        val attributes = prim["attributes"]!!.jsonObject
        if (attributes.containsKey("JOINTS_0")) {
            throw IllegalArgumentException("This model already has a skeleton.")
        }
        val posAccessor = attributes["POSITION"]?.jsonPrimitive?.int ?: return null
        val positions = Glb.readFloats(root, bin, posAccessor, 3)
        return MeshRef(meshIndex, 0, nodeIndex, posAccessor, positions)
    }

    private class Bounds(p: FloatArray) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        init {
            var i = 0
            while (i < p.size) {
                minX = min(minX, p[i]); maxX = max(maxX, p[i])
                minY = min(minY, p[i + 1]); maxY = max(maxY, p[i + 1])
                minZ = min(minZ, p[i + 2]); maxZ = max(maxZ, p[i + 2])
                i += 3
            }
        }

        val width get() = maxX - minX      // left-right
        val height get() = maxY - minY     // up
        val length get() = maxZ - minZ     // front-back
        val centreX get() = (minX + maxX) / 2f
        val centreZ get() = (minZ + maxZ) / 2f

        fun y(fraction: Float) = minY + height * fraction
        fun z(fraction: Float) = minZ + length * fraction
    }

    // -------------------------------------------------------------- skeleton

    /** One bone: a position in model space, its parent, and the axis it turns on. */
    private class Bone(
        val name: String,
        val parent: Int,
        val x: Float, val y: Float, val z: Float,
    )

    private class Skeleton(
        val bones: List<Bone>,
        /** Per-bone wheel radius; 0 for anything that is not a wheel. */
        val wheelRadius: FloatArray = FloatArray(bones.size),
        val wheelHalfWidth: Float = 0f,
    ) {
        /** Bone index → the child it points at, used for segment distances. */
        val childOf: IntArray = IntArray(bones.size) { -1 }

        init {
            bones.forEachIndexed { i, bone ->
                if (bone.parent >= 0 && childOf[bone.parent] == -1) childOf[bone.parent] = i
            }
        }

        fun localTranslation(i: Int): FloatArray {
            val b = bones[i]
            val p = if (b.parent < 0) null else bones[b.parent]
            return if (p == null) floatArrayOf(b.x, b.y, b.z)
            else floatArrayOf(b.x - p.x, b.y - p.y, b.z - p.z)
        }
    }

    /** Hips, spine, head, two arms and two legs, sized off the bounding box. */
    private fun humanoid(b: Bounds): Skeleton {
        val cx = b.centreX
        val cz = b.centreZ
        val legSpread = b.width * 0.18f
        val armSpread = b.width * 0.34f
        val bones = mutableListOf<Bone>()
        fun add(name: String, parent: Int, x: Float, y: Float, z: Float): Int {
            bones += Bone(name, parent, x, y, z); return bones.size - 1
        }

        val hips = add("hips", -1, cx, b.y(0.53f), cz)
        val spine = add("spine", hips, cx, b.y(0.70f), cz)
        val chest = add("chest", spine, cx, b.y(0.82f), cz)
        add("head", chest, cx, b.y(0.95f), cz)

        for ((side, sign) in listOf("l" to -1f, "r" to 1f)) {
            val shoulder = add("${side}_shoulder", chest, cx + sign * armSpread, b.y(0.80f), cz)
            val elbow = add("${side}_elbow", shoulder, cx + sign * armSpread, b.y(0.66f), cz)
            add("${side}_hand", elbow, cx + sign * armSpread, b.y(0.53f), cz)

            val thigh = add("${side}_thigh", hips, cx + sign * legSpread, b.y(0.50f), cz)
            val knee = add("${side}_knee", thigh, cx + sign * legSpread, b.y(0.27f), cz)
            add("${side}_foot", knee, cx + sign * legSpread, b.y(0.02f), cz)
        }
        return Skeleton(bones)
    }

    /** Spine along the long axis with four legs hanging off it. */
    private fun quadruped(b: Bounds): Skeleton {
        val cx = b.centreX
        val legSpread = b.width * 0.28f
        val bones = mutableListOf<Bone>()
        fun add(name: String, parent: Int, x: Float, y: Float, z: Float): Int {
            bones += Bone(name, parent, x, y, z); return bones.size - 1
        }

        val hips = add("hips", -1, cx, b.y(0.62f), b.z(0.30f))
        val spine = add("spine", hips, cx, b.y(0.66f), b.z(0.55f))
        val chest = add("chest", spine, cx, b.y(0.66f), b.z(0.75f))
        add("head", chest, cx, b.y(0.72f), b.z(0.94f))

        // Front pair hangs from the chest, rear pair from the hips.
        for ((side, sign) in listOf("l" to -1f, "r" to 1f)) {
            val fUpper = add("${side}_front_upper", chest, cx + sign * legSpread, b.y(0.58f), b.z(0.74f))
            val fLower = add("${side}_front_lower", fUpper, cx + sign * legSpread, b.y(0.30f), b.z(0.74f))
            add("${side}_front_paw", fLower, cx + sign * legSpread, b.y(0.02f), b.z(0.74f))

            val rUpper = add("${side}_rear_upper", hips, cx + sign * legSpread, b.y(0.58f), b.z(0.26f))
            val rLower = add("${side}_rear_lower", rUpper, cx + sign * legSpread, b.y(0.30f), b.z(0.26f))
            add("${side}_rear_paw", rLower, cx + sign * legSpread, b.y(0.02f), b.z(0.26f))
        }
        return Skeleton(bones)
    }

    /**
     * A body bone plus one bone per wheel, with each wheel's centre and radius
     * measured from the vertices actually sitting in that corner.
     */
    private fun vehicle(positions: FloatArray, b: Bounds): Skeleton {
        val bones = mutableListOf<Bone>()
        bones += Bone("body", -1, b.centreX, b.y(0.5f), b.centreZ)

        val radii = mutableListOf<Float>()
        var maxHalfWidth = 0f
        val vertexCount = positions.size / 3

        for ((zLabel, zFront) in listOf("front" to true, "rear" to false)) {
            for ((xLabel, xRight) in listOf("l" to false, "r" to true)) {
                fun inQuadrant(i: Int) =
                    (positions[i + 2] > b.centreZ) == zFront && (positions[i] > b.centreX) == xRight

                // Seed from the low corner, where a wheel must be, then refit against
                // the whole quadrant. Staying inside the low band would truncate the
                // top of the wheel and pull the fitted centre downwards, which makes
                // the wheel wobble off its axle once it turns.
                val seed = ArrayList<Int>()
                val quadrant = ArrayList<Int>()
                var i = 0
                while (i < positions.size) {
                    if (inQuadrant(i)) {
                        quadrant += i
                        if (positions[i + 1] <= b.y(0.38f)) seed += i
                    }
                    i += 3
                }
                if (seed.size < 24) continue

                var cy = seed.sumOf { positions[it + 1].toDouble() }.toFloat() / seed.size
                var cz = seed.sumOf { positions[it + 2].toDouble() }.toFloat() / seed.size
                var radius = 0f
                var source = seed

                repeat(REFIT_PASSES) {
                    val distances = source.map { v ->
                        val dy = positions[v + 1] - cy; val dz = positions[v + 2] - cz
                        sqrt(dy * dy + dz * dz)
                    }.sorted()
                    // A wheel's rim dominates the cluster, so the upper quartile of
                    // radial distance sits close to its true radius.
                    radius = distances[(distances.size * 3) / 4].coerceAtLeast(1e-4f)
                    val near = quadrant.filter { v ->
                        val dy = positions[v + 1] - cy; val dz = positions[v + 2] - cz
                        sqrt(dy * dy + dz * dz) <= radius * 1.15f
                    }
                    if (near.size < 12) return@repeat
                    cy = near.sumOf { positions[it + 1].toDouble() }.toFloat() / near.size
                    cz = near.sumOf { positions[it + 2].toDouble() }.toFloat() / near.size
                    source = ArrayList(near)
                }

                val ring = quadrant.filter { v ->
                    val dy = positions[v + 1] - cy; val dz = positions[v + 2] - cz
                    sqrt(dy * dy + dz * dz) <= radius * 1.15f
                }
                if (ring.size < 12) continue
                val cx = ring.sumOf { positions[it].toDouble() }.toFloat() / ring.size
                var halfWidth = 0f
                ring.forEach { halfWidth = max(halfWidth, abs(positions[it] - cx)) }

                // Only keep it if it is actually round. A wheel turning on its axle
                // sweeps a disc, so claiming a flat slab — a hull panel, a fin —
                // would smear it into one. Measured on real output: a real car's
                // wheels come out 0.97-1.00 round, a spaceship's false positives
                // 0.32-0.44.
                fun claim(cut: Float) = ring.filter { v ->
                    val dy = positions[v + 1] - cy; val dz = positions[v + 2] - cz
                    sqrt(dy * dy + dz * dz) <= cut && abs(positions[v] - cx) <= halfWidth
                }
                // Cutting inside the fitted rim keeps bodywork out of the wheel. A
                // wheel modelled as a bare rim has nothing inside it though, so fall
                // back to the rim itself rather than discarding a real wheel.
                var trimmed = radius * WHEEL_TRIM
                var claimed = claim(trimmed)
                if (claimed.size < 12) {
                    trimmed = radius
                    claimed = claim(trimmed)
                }
                if (claimed.size < 12) continue
                var ry = 0f; var rz = 0f
                claimed.forEach {
                    ry = max(ry, abs(positions[it + 1] - cy))
                    rz = max(rz, abs(positions[it + 2] - cz))
                }
                val roundness = if (max(ry, rz) <= 0f) 0f else min(ry, rz) / max(ry, rz)
                val share = claimed.size.toFloat() / vertexCount
                if (roundness < MIN_ROUNDNESS || share > MAX_WHEEL_SHARE) continue

                radii += trimmed
                maxHalfWidth = max(maxHalfWidth, halfWidth)
                bones += Bone("${xLabel}_${zLabel}_wheel", 0, cx, cy, cz)
            }
        }

        if (radii.size < MIN_WHEELS) {
            throw IllegalArgumentException(
                "No round wheels found on this model — it needs visible wheels near the " +
                    "corners. Use a motion clip from Animate instead."
            )
        }
        val wheelRadius = FloatArray(bones.size)
        radii.forEachIndexed { i, r -> wheelRadius[i + 1] = r }
        return Skeleton(bones, wheelRadius, maxHalfWidth * 1.05f)
    }

    /** Centre-refit iterations, and how far inside the fitted rim to cut. */
    private const val REFIT_PASSES = 4
    private const val WHEEL_TRIM = 0.88f
    /** How round a claimed region has to be before it is believed to be a wheel. */
    private const val MIN_ROUNDNESS = 0.75f
    /** A wheel is a small part of a vehicle; more than this is bodywork. */
    private const val MAX_WHEEL_SHARE = 0.18f
    private const val MIN_WHEELS = 2

    // --------------------------------------------------------------- weights

    private class Weights(val joints: ByteArray, val values: FloatArray)

    /** Shortest distance from a point to the segment between two bones. */
    private fun distanceToBone(s: Skeleton, bone: Int, x: Float, y: Float, z: Float): Float {
        val b = s.bones[bone]
        val childIndex = s.childOf[bone]
        if (childIndex < 0) {
            val dx = x - b.x; val dy = y - b.y; val dz = z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val c = s.bones[childIndex]
        val ax = c.x - b.x; val ay = c.y - b.y; val az = c.z - b.z
        val len2 = ax * ax + ay * ay + az * az
        if (len2 < 1e-9f) {
            val dx = x - b.x; val dy = y - b.y; val dz = z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val t = (((x - b.x) * ax + (y - b.y) * ay + (z - b.z) * az) / len2).coerceIn(0f, 1f)
        val px = b.x + ax * t; val py = b.y + ay * t; val pz = b.z + az * t
        val dx = x - px; val dy = y - py; val dz = z - pz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun skinWeights(positions: FloatArray, s: Skeleton, rig: Rig): Weights {
        val count = positions.size / 3
        val joints = ByteArray(count * 4)
        val values = FloatArray(count * 4)

        for (v in 0 until count) {
            val x = positions[v * 3]; val y = positions[v * 3 + 1]; val z = positions[v * 3 + 2]

            if (rig.isVehicle) {
                // Wheels have to turn as solid objects: a smooth falloff would
                // smear the tyre into the wing. Each vertex is either in a wheel
                // or it is body, with nothing in between.
                var owner = 0
                for (bone in 1 until s.bones.size) {
                    val b = s.bones[bone]
                    val dy = y - b.y; val dz = z - b.z
                    val radial = sqrt(dy * dy + dz * dz)
                    if (radial <= s.wheelRadius[bone] && abs(x - b.x) <= s.wheelHalfWidth) {
                        owner = bone
                        break
                    }
                }
                joints[v * 4] = owner.toByte()
                values[v * 4] = 1f
                continue
            }

            // Inverse-distance falloff, sharp enough that a vertex is dominated by
            // the bone it sits on but still blends across a joint.
            val bestIdx = IntArray(MAX_INFLUENCES)
            val bestW = FloatArray(MAX_INFLUENCES)
            for (bone in s.bones.indices) {
                val d = distanceToBone(s, bone, x, y, z)
                val w = 1f / (d * d * d * d + 1e-6f)
                // Keep the running top four.
                var slot = -1
                var lowest = w
                for (k in 0 until MAX_INFLUENCES) {
                    if (bestW[k] < lowest) { lowest = bestW[k]; slot = k }
                }
                if (slot >= 0) { bestW[slot] = w; bestIdx[slot] = bone }
            }
            val total = bestW.sum()
            for (k in 0 until MAX_INFLUENCES) {
                joints[v * 4 + k] = bestIdx[k].toByte()
                values[v * 4 + k] = if (total > 0f) bestW[k] / total else if (k == 0) 1f else 0f
            }
        }
        return Weights(joints, values)
    }

    // ---------------------------------------------------------------- motion

    /** Per-bone rotation tracks, indexed by bone. */
    private class Motion(
        val times: FloatArray,
        val rotations: Map<Int, FloatArray>,       // bone → xyzw per key
        val rootTranslation: FloatArray?,          // xyz per key, on bone 0
    )

    private fun quatX(a: Float) = floatArrayOf(sin(a / 2f), 0f, 0f, cos(a / 2f))
    private fun quatY(a: Float) = floatArrayOf(0f, sin(a / 2f), 0f, cos(a / 2f))
    private fun quatZ(a: Float) = floatArrayOf(0f, 0f, sin(a / 2f), cos(a / 2f))

    private fun motion(s: Skeleton, rig: Rig): Motion {
        val count = (rig.seconds * SAMPLES_PER_SECOND).toInt().coerceAtLeast(4) + 1
        val times = FloatArray(count) { it * rig.seconds / (count - 1) }
        val tau = (2 * PI).toFloat()
        val tracks = mutableMapOf<Int, FloatArray>()
        var rootMove: FloatArray? = null

        fun index(name: String) = s.bones.indexOfFirst { it.name == name }
        fun track(name: String, build: (Float) -> FloatArray) {
            val bone = index(name)
            if (bone < 0) return
            tracks[bone] = FloatArray(count * 4).also { out ->
                for (i in 0 until count) build(times[i] / rig.seconds).copyInto(out, i * 4)
            }
        }

        when (rig) {
            Rig.VEHICLE_WHEELS -> {
                // Every wheel turns together, on the left-right axis.
                s.bones.forEachIndexed { i, bone ->
                    if (bone.name.endsWith("wheel")) {
                        tracks[i] = FloatArray(count * 4).also { out ->
                            for (k in 0 until count) {
                                quatX(-tau * (times[k] / rig.seconds)).copyInto(out, k * 4)
                            }
                        }
                    }
                }
            }

            Rig.HUMANOID_WALK, Rig.HUMANOID_RUN -> {
                val swing = if (rig == Rig.HUMANOID_RUN) 0.72f else 0.46f
                val bend = if (rig == Rig.HUMANOID_RUN) 0.95f else 0.55f
                val lean = if (rig == Rig.HUMANOID_RUN) 0.20f else 0.06f

                for ((side, phase) in listOf("l" to 0f, "r" to 0.5f)) {
                    track("${side}_thigh") { p -> quatX(swing * sin(tau * (p + phase))) }
                    // The knee only folds backwards, and only on the back half of
                    // the stride — a symmetric bend reads as a limp.
                    track("${side}_knee") { p ->
                        quatX(-bend * max(0f, sin(tau * (p + phase) + PI.toFloat() * 0.5f)))
                    }
                    track("${side}_foot") { p -> quatX(0.25f * sin(tau * (p + phase) - 0.6f)) }
                    // Arms counter-swing against the legs.
                    track("${side}_shoulder") { p -> quatX(-swing * 0.7f * sin(tau * (p + phase))) }
                    track("${side}_elbow") { p -> quatX(-0.3f - 0.2f * sin(tau * (p + phase))) }
                }
                track("spine") { p -> quatX(lean + 0.04f * sin(2 * tau * p)) }
                // Two bobs per stride: the body rises on each foot plant.
                rootMove = FloatArray(count * 3).also { out ->
                    for (i in 0 until count) out[i * 3 + 1] = 0.02f * sin(2 * tau * (times[i] / rig.seconds))
                }
            }

            Rig.QUADRUPED_WALK -> {
                // Diagonal gait: front-left moves with rear-right.
                val gait = listOf(
                    "l_front" to 0f, "r_rear" to 0f,
                    "r_front" to 0.5f, "l_rear" to 0.5f,
                )
                for ((leg, phase) in gait) {
                    track("${leg}_upper") { p -> quatX(0.42f * sin(tau * (p + phase))) }
                    track("${leg}_lower") { p ->
                        quatX(-0.5f * max(0f, sin(tau * (p + phase) + PI.toFloat() * 0.5f)))
                    }
                    track("${leg}_paw") { p -> quatX(0.2f * sin(tau * (p + phase) - 0.5f)) }
                }
                track("spine") { p -> quatY(0.05f * sin(tau * p)) }
                track("head") { p -> quatZ(0.05f * sin(2 * tau * p)) }
                rootMove = FloatArray(count * 3).also { out ->
                    for (i in 0 until count) out[i * 3 + 1] = 0.015f * sin(2 * tau * (times[i] / rig.seconds))
                }
            }
        }
        return Motion(times, tracks, rootMove)
    }

    // ------------------------------------------------------------------ glTF

    private fun inject(
        root: JsonObject,
        bin: ByteArray,
        mesh: MeshRef,
        skeleton: Skeleton,
        weights: Weights,
        motion: Motion,
        rig: Rig,
    ): ByteArray {
        val extra = ByteArrayOutputStream()
        fun align() { while (extra.size() % 4 != 0) extra.write(0) }
        fun putFloats(values: FloatArray): Int {
            align()
            val at = extra.size()
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buf.putFloat(it) }
            extra.write(buf.array())
            return at
        }
        fun putBytes(values: ByteArray): Int {
            align()
            val at = extra.size()
            extra.write(values)
            return at
        }

        val vertexCount = weights.values.size / 4
        val jointsAt = putBytes(weights.joints)
        val weightsAt = putFloats(weights.values)

        // Inverse bind matrix: the joint sits at its model-space position with no
        // rotation, so the inverse is just a translation back to the origin.
        val ibm = FloatArray(skeleton.bones.size * 16)
        skeleton.bones.forEachIndexed { i, bone ->
            val m = i * 16
            ibm[m] = 1f; ibm[m + 5] = 1f; ibm[m + 10] = 1f; ibm[m + 15] = 1f
            ibm[m + 12] = -bone.x; ibm[m + 13] = -bone.y; ibm[m + 14] = -bone.z
        }
        val ibmAt = putFloats(ibm)
        val timesAt = putFloats(motion.times)
        val trackAt = motion.rotations.mapValues { (_, data) -> putFloats(data) }
        val rootMoveAt = motion.rootTranslation?.let { putFloats(it) }
        align()

        val binSize = bin.size
        val bufferViews = (root["bufferViews"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val accessors = (root["accessors"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val nodes = (root["nodes"]!!.jsonArray).toMutableList()

        fun addView(offset: Int, length: Int): Int {
            bufferViews += buildJsonObject {
                put("buffer", 0)
                put("byteOffset", binSize + offset)
                put("byteLength", length)
            }
            return bufferViews.size - 1
        }

        fun addAccessor(
            view: Int, componentType: Int, type: String, count: Int,
            min: Float? = null, max: Float? = null, normalized: Boolean = false,
        ): Int {
            accessors += buildJsonObject {
                put("bufferView", view)
                put("componentType", componentType)
                put("count", count)
                put("type", type)
                if (normalized) put("normalized", true)
                if (min != null && max != null) {
                    put("min", buildJsonArray { add(min) })
                    put("max", buildJsonArray { add(max) })
                }
            }
            return accessors.size - 1
        }

        val jointsAccessor = addAccessor(addView(jointsAt, vertexCount * 4), 5121, "VEC4", vertexCount)
        val weightsAccessor = addAccessor(addView(weightsAt, vertexCount * 16), 5126, "VEC4", vertexCount)
        val ibmAccessor = addAccessor(addView(ibmAt, skeleton.bones.size * 64), 5126, "MAT4", skeleton.bones.size)
        val timeAccessor = addAccessor(
            addView(timesAt, motion.times.size * 4), 5126, "SCALAR", motion.times.size,
            motion.times.first(), motion.times.last(),
        )

        // Joints go in as nodes parented to each other, all under one rig root.
        val firstJointNode = nodes.size
        val childrenOf = mutableMapOf<Int, MutableList<Int>>()
        skeleton.bones.forEachIndexed { i, bone ->
            if (bone.parent >= 0) childrenOf.getOrPut(bone.parent) { mutableListOf() } += firstJointNode + i
        }
        skeleton.bones.forEachIndexed { i, bone ->
            val t = skeleton.localTranslation(i)
            nodes += buildJsonObject {
                put("name", bone.name)
                put("translation", buildJsonArray { add(t[0]); add(t[1]); add(t[2]) })
                childrenOf[i]?.let { kids ->
                    put("children", buildJsonArray { kids.forEach { add(it) } })
                }
            }
        }

        val meshNodeIndex = mesh.nodeIndex
        nodes[meshNodeIndex] = buildJsonObject {
            nodes[meshNodeIndex].jsonObject.forEach { (k, v) -> put(k, v) }
            put("skin", (root["skins"]?.jsonArray?.size ?: 0))
        }

        // The rig root adopts the joint chain alongside the scene's existing
        // roots. Reparenting the mesh node itself would give it two parents,
        // which glTF forbids — and a skinned mesh ignores its node transform
        // anyway, so leaving it where it is costs nothing.
        val existingRoots = (root["scenes"]?.jsonArray
            ?.getOrNull(root["scene"]?.jsonPrimitive?.intOrNull ?: 0)
            ?.jsonObject?.get("nodes")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull })
            ?: listOf(0)
        val rigRootNode = nodes.size
        nodes += buildJsonObject {
            put("name", SKIN_ROOT)
            put("children", buildJsonArray {
                add(firstJointNode)
                existingRoots.forEach { add(it) }
            })
        }

        val skins = (root["skins"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        skins += buildJsonObject {
            put("inverseBindMatrices", ibmAccessor)
            put("skeleton", firstJointNode)
            put("joints", buildJsonArray { skeleton.bones.indices.forEach { add(firstJointNode + it) } })
        }

        // Rebuild the mesh primitive with the two new skinning attributes.
        val meshes = root["meshes"]!!.jsonArray.toMutableList()
        val target = meshes[mesh.meshIndex].jsonObject
        val primitives = target["primitives"]!!.jsonArray.toMutableList()
        val prim = primitives[mesh.primitiveIndex].jsonObject
        primitives[mesh.primitiveIndex] = buildJsonObject {
            prim.forEach { (k, v) -> if (k != "attributes") put(k, v) }
            put("attributes", buildJsonObject {
                prim["attributes"]!!.jsonObject.forEach { (k, v) -> put(k, v) }
                put("JOINTS_0", jointsAccessor)
                put("WEIGHTS_0", weightsAccessor)
            })
        }
        meshes[mesh.meshIndex] = buildJsonObject {
            target.forEach { (k, v) -> if (k != "primitives") put(k, v) }
            put("primitives", JsonArray(primitives))
        }

        // Animation channels, one per animated joint.
        val samplers = mutableListOf<JsonObject>()
        val channels = mutableListOf<JsonObject>()
        fun addChannel(node: Int, path: String, offset: Int, components: Int, count: Int) {
            val acc = addAccessor(
                addView(offset, count * components * 4), 5126,
                if (components == 4) "VEC4" else "VEC3", count,
            )
            samplers += buildJsonObject {
                put("input", timeAccessor); put("output", acc); put("interpolation", "LINEAR")
            }
            channels += buildJsonObject {
                put("sampler", samplers.size - 1)
                putJsonObject("target") { put("node", node); put("path", path) }
            }
        }

        trackAt.forEach { (bone, offset) ->
            addChannel(firstJointNode + bone, "rotation", offset, 4, motion.times.size)
        }
        rootMoveAt?.let { offset ->
            addChannel(firstJointNode, "translation", offset, 3, motion.times.size)
        }

        val animations = (root["animations"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        animations += buildJsonObject {
            put("name", rig.label)
            put("samplers", JsonArray(samplers))
            put("channels", JsonArray(channels))
        }

        // The rig root replaces whatever the scene pointed at; the old roots stay
        // in the node list but are no longer reachable from the scene.
        val scenes = (root["scenes"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        val sceneIndex = root["scene"]?.jsonPrimitive?.intOrNull ?: 0
        val newScene = buildJsonObject {
            scenes.getOrNull(sceneIndex)?.jsonObject?.forEach { (k, v) -> if (k != "nodes") put(k, v) }
            put("nodes", buildJsonArray { add(rigRootNode) })
        }
        if (sceneIndex < scenes.size) scenes[sceneIndex] = newScene else scenes += newScene

        val extraBytes = extra.toByteArray()
        val json = buildJsonObject {
            root.forEach { (key, value) ->
                when (key) {
                    "bufferViews", "accessors", "nodes", "scenes", "buffers",
                    "animations", "meshes", "skins" -> Unit
                    else -> put(key, value)
                }
            }
            put("bufferViews", JsonArray(bufferViews))
            put("accessors", JsonArray(accessors))
            put("nodes", JsonArray(nodes))
            put("scenes", JsonArray(scenes))
            put("meshes", JsonArray(meshes))
            put("skins", JsonArray(skins))
            put("animations", JsonArray(animations))
            put("buffers", buildJsonArray {
                add(buildJsonObject { put("byteLength", binSize + extraBytes.size) })
            })
        }
        return Glb.assemble(json, bin + extraBytes)
    }
}

/** Shared GLB container reading and writing. */
internal object Glb {

    fun split(bytes: ByteArray): Pair<JsonObject, ByteArray> {
        require(bytes.size > 20 && String(bytes, 0, 4) == "glTF") { "Not a .glb file." }
        val jsonLen = le32(bytes, 12)
        require(jsonLen > 0 && 20 + jsonLen <= bytes.size) { "The .glb header is damaged." }
        val root = runCatching {
            Json.parseToJsonElement(String(bytes, 20, jsonLen, Charsets.UTF_8)).jsonObject
        }.getOrElse { throw IllegalArgumentException("The .glb has unreadable glTF JSON.") }

        var offset = 20 + jsonLen
        while (offset % 4 != 0) offset++
        require(offset + 8 <= bytes.size) { "The .glb has no binary chunk." }
        val binLen = le32(bytes, offset)
        val binStart = offset + 8
        require(binLen >= 0 && binStart + binLen <= bytes.size) { "The .glb binary chunk is truncated." }
        return root to bytes.copyOfRange(binStart, binStart + binLen)
    }

    fun assemble(json: JsonObject, bin: ByteArray): ByteArray {
        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()
        var binBytes = bin
        while (binBytes.size % 4 != 0) binBytes += 0

        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("glTF".toByteArray()); out.putInt(2); out.putInt(total)
        out.putInt(jsonBytes.size); out.put("JSON".toByteArray()); out.put(jsonBytes)
        out.putInt(binBytes.size); out.put(byteArrayOf(0x42, 0x49, 0x4E, 0x00)); out.put(binBytes)
        return out.array()
    }

    /** Reads a FLOAT accessor, honouring the bufferView's stride if it has one. */
    fun readFloats(root: JsonObject, bin: ByteArray, accessorIndex: Int, components: Int): FloatArray {
        val accessor = root["accessors"]!!.jsonArray[accessorIndex].jsonObject
        require(accessor["componentType"]!!.jsonPrimitive.int == 5126) { "Expected float vertex data." }
        val count = accessor["count"]!!.jsonPrimitive.int
        val view = root["bufferViews"]!!.jsonArray[
            accessor["bufferView"]!!.jsonPrimitive.int
        ].jsonObject
        val base = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) +
            (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val stride = view["byteStride"]?.jsonPrimitive?.int ?: (components * 4)

        val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count * components)
        for (i in 0 until count) {
            for (c in 0 until components) {
                out[i * components + c] = buffer.getFloat(base + i * stride + c * 4)
            }
        }
        return out
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
}
