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

    /** The skeleton shape. A written prompt picks one of these directly. */
    enum class Frame(val label: String) {
        HUMANOID("Two legs"),
        QUADRUPED("Four legs"),
        VEHICLE("Wheels"),
    }

    enum class Rig(
        val label: String,
        val note: String,
        val caveat: String,
        val seconds: Float,
        val frame: Frame,
    ) {
        HUMANOID_WALK(
            "Walk (two legs)",
            "Hips, spine, arms and legs — a stepping walk cycle",
            "Fits a person-shaped model standing upright. A crouched or seated pose will bend oddly.",
            1.2f, Frame.HUMANOID,
        ),
        HUMANOID_RUN(
            "Run (two legs)",
            "Same skeleton, faster stride with more lean",
            "Fits a person-shaped model standing upright.",
            0.75f, Frame.HUMANOID,
        ),
        QUADRUPED_WALK(
            "Walk (four legs)",
            "Spine plus four legs — diagonal gait for animals",
            "Fits an animal standing on four legs, longer than it is tall.",
            1.4f, Frame.QUADRUPED,
        ),
        VEHICLE_WHEELS(
            "Spinning wheels",
            "Finds the wheels and turns them on their axles",
            "Needs four visible wheels near the corners. The wheels are cut out of the body by shape, so a heavily skirted car may drag a little bodywork.",
            2f, Frame.VEHICLE,
        );

        val isVehicle get() = frame == Frame.VEHICLE
    }

    /**
     * A motion described from outside — one entry per bone the caller wants to
     * move. This is what a written prompt turns into.
     */
    data class Track(
        val bone: String,
        /** "x" pitch, "y" yaw, "z" roll. */
        val axis: String,
        /** Peak rotation in radians. */
        val amplitude: Float,
        /** Where in the loop this bone peaks, 0..1. */
        val phase: Float = 0f,
        /** "sine" swings both ways, "half" only one way, "spin" turns full circles. */
        val wave: String = "sine",
        /** Constant rotation added on top, radians. */
        val offset: Float = 0f,
        /** Repeats per loop; 2 makes a bone move twice per stride. */
        val cycles: Float = 1f,
    )

    data class MotionSpec(
        val name: String,
        val frame: Frame,
        val seconds: Float,
        val tracks: List<Track>,
        /** Up-and-down travel of the whole body, in model units. */
        val bob: Float = 0f,
        val bobCycles: Float = 2f,
    )

    private const val SAMPLES_PER_SECOND = 20
    // Guard rails for a described motion: a bad number should make the model look
    // dull, never make it explode.
    private const val MIN_SECONDS = 0.4f
    private const val MAX_SECONDS = 8f
    private const val MAX_KEYS = 240
    private const val MAX_TRACKS = 40
    private const val MAX_RADIANS = 2.2f
    private const val MAX_BOB = 0.12f
    private const val MAX_INFLUENCES = 4
    private const val SKIN_ROOT = "void_rig_root"

    /** What the model's proportions suggest, so the UI can preselect sensibly. */
    fun suggest(glb: File): Rig = runCatching {
        val mesh = readMesh(glb) ?: return Rig.HUMANOID_WALK
        // The same measurement the rigger will use, so what is recommended and
        // what gets built cannot disagree.
        when (MeshAnalyzer.analyse(mesh.positions).shape) {
            MeshAnalyzer.Shape.VEHICLE -> Rig.VEHICLE_WHEELS
            MeshAnalyzer.Shape.QUADRUPED -> Rig.QUADRUPED_WALK
            MeshAnalyzer.Shape.BIPED -> Rig.HUMANOID_WALK
            MeshAnalyzer.Shape.SOLID -> Rig.HUMANOID_WALK
        }
    }.getOrDefault(Rig.HUMANOID_WALK)

    /**
     * Writes a new .glb with a skeleton, skin weights and [rig]'s motion.
     * The original mesh data is untouched; everything is appended.
     */
    suspend fun rig(
        glb: File,
        rig: Rig,
        outputDir: File,
        /** Supply this to drive the bones from a written description instead. */
        spec: MotionSpec? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(glb.exists() && glb.length() > 20) { "Model file is missing or empty." }
            outputDir.mkdirs()

            val (root, bin) = Glb.split(glb.readBytes())
            val mesh = readMesh(root, bin)
                ?: throw IllegalArgumentException("This model has no mesh to rig.")

            val frame = spec?.frame ?: rig.frame
            // Measure the model before touching it. Bones placed on guessed
            // positions is what made rigging damage models instead of animating
            // them; this is the step that reads the actual geometry.
            val analysis = MeshAnalyzer.analyse(mesh.positions)
            val skeleton = when (frame) {
                Frame.HUMANOID -> humanoid(analysis)
                Frame.QUADRUPED -> quadruped(analysis)
                Frame.VEHICLE -> vehicle(analysis)
            }

            val weights = skinWeights(mesh.positions, skeleton, frame, analysis)
            val clips = spec?.let { custom(skeleton, it) } ?: motion(skeleton, rig)
            val label = spec?.name ?: rig.label
            val suffix = (spec?.name ?: rig.name).lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(28).ifBlank { "motion" }
            val out = File(outputDir, "${glb.nameWithoutExtension}_$suffix.glb")
            out.writeBytes(inject(root, bin, mesh, skeleton, weights, clips, label))
            out
        }
    }

    /** Bone names a given frame will create, so a prompt can be told what exists. */
    fun boneNames(frame: Frame): List<String> {
        val unit = MeshAnalyzer.Analysis(
            shape = MeshAnalyzer.Shape.SOLID, limbs = emptyList(),
            minY = -0.5f, maxY = 0.5f, centreX = 0f, centreZ = 0f,
            width = 1f, height = 1f, length = 1f, bodyBaseY = 0f, confidence = 0f,
        )
        return when (frame) {
            Frame.HUMANOID -> humanoid(unit).bones.map { it.name }
            Frame.QUADRUPED -> quadruped(unit).bones.map { it.name }
            // Wheel bones only exist once wheels are actually found on a model.
            Frame.VEHICLE -> listOf("body", "l_front_wheel", "r_front_wheel", "l_rear_wheel", "r_rear_wheel")
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

    /**
     * Hips, spine, head, two arms and two legs, placed on the legs the analysis
     * actually found.
     *
     * The previous version derived leg positions from bounding-box fractions.
     * On a real dog that put the rear legs 0.13 units from the real ones, on a
     * body 0.99 long — so the bone sat in the belly and moving it dragged the
     * belly. Measured positions are the whole point of this.
     */
    private fun humanoid(a: MeshAnalyzer.Analysis): Skeleton {
        val cx = a.centreX
        val cz = a.centreZ
        val legs = a.limbs.sortedBy { it.x }
        // Fall back to a symmetric guess only when the analysis found nothing —
        // the caller is warned about that separately.
        val leftX = legs.firstOrNull()?.x ?: (cx - a.width * 0.18f)
        val rightX = legs.lastOrNull()?.x ?: (cx + a.width * 0.18f)
        val legZ = legs.map { it.z }.average().toFloat().takeIf { legs.isNotEmpty() } ?: cz
        val hipY = if (a.hasLimbs) a.bodyBaseY else a.minY + a.height * 0.53f
        val armSpread = a.width * 0.34f

        val bones = mutableListOf<Bone>()
        fun add(name: String, parent: Int, x: Float, y: Float, z: Float): Int {
            bones += Bone(name, parent, x, y, z); return bones.size - 1
        }

        val hips = add("hips", -1, cx, hipY, cz)
        val spine = add("spine", hips, cx, lerp(hipY, a.maxY, 0.35f), cz)
        val chest = add("chest", spine, cx, lerp(hipY, a.maxY, 0.62f), cz)
        add("head", chest, cx, lerp(hipY, a.maxY, 0.92f), cz)

        listOf("l" to leftX, "r" to rightX).forEachIndexed { index, (side, legX) ->
            val sign = if (index == 0) -1f else 1f
            val shoulder = add("${side}_shoulder", chest, cx + sign * armSpread, lerp(hipY, a.maxY, 0.58f), cz)
            val elbow = add("${side}_elbow", shoulder, cx + sign * armSpread, lerp(hipY, a.maxY, 0.30f), cz)
            add("${side}_hand", elbow, cx + sign * armSpread, hipY, cz)

            // The knee sits midway down the real leg, and the foot on the floor.
            val thigh = add("${side}_thigh", hips, legX, hipY, legZ)
            val knee = add("${side}_knee", thigh, legX, lerp(a.minY, hipY, 0.45f), legZ)
            add("${side}_foot", knee, legX, a.minY, legZ)
        }
        return Skeleton(bones)
    }

    /** Spine along the body with the four legs the analysis measured. */
    private fun quadruped(a: MeshAnalyzer.Analysis): Skeleton {
        val cx = a.centreX
        val hipY = if (a.hasLimbs) a.bodyBaseY else a.minY + a.height * 0.62f

        // Split the measured legs into front and rear by their position along the
        // body, then into left and right. Naming them from the data is what lets
        // a gait line up with the actual animal.
        //
        // With no measured limbs — a solid shape, or the bone-name listing — fall
        // back to a symmetric layout so the skeleton is still well formed. The UI
        // does not offer a rig in that case; this only has to not crash.
        val legs = a.limbs.ifEmpty {
            val dx = a.width * 0.28f
            val dz = a.length * 0.30f
            listOf(
                MeshAnalyzer.Limb(cx - dx, a.centreZ + dz, a.minY, hipY, dx),
                MeshAnalyzer.Limb(cx + dx, a.centreZ + dz, a.minY, hipY, dx),
                MeshAnalyzer.Limb(cx - dx, a.centreZ - dz, a.minY, hipY, dx),
                MeshAnalyzer.Limb(cx + dx, a.centreZ - dz, a.minY, hipY, dx),
            )
        }
        val frontRear = legs.sortedBy { it.z }
        val rear = frontRear.take(2).ifEmpty { legs }
        val front = frontRear.drop(2).ifEmpty { rear }
        fun side(pair: List<MeshAnalyzer.Limb>, right: Boolean): MeshAnalyzer.Limb {
            val sorted = pair.sortedBy { it.x }
            return if (right) sorted.last() else sorted.first()
        }

        val frontZ = front.map { it.z }.average().toFloat()
        val rearZ = rear.map { it.z }.average().toFloat()

        val bones = mutableListOf<Bone>()
        fun add(name: String, parent: Int, x: Float, y: Float, z: Float): Int {
            bones += Bone(name, parent, x, y, z); return bones.size - 1
        }

        val hips = add("hips", -1, cx, hipY, rearZ)
        val spine = add("spine", hips, cx, hipY, (rearZ + frontZ) / 2f)
        val chest = add("chest", spine, cx, hipY, frontZ)
        add("head", chest, cx, lerp(hipY, a.maxY, 0.55f), a.centreZ + a.length * 0.45f)

        listOf(false to "l", true to "r").forEach { (right, label) ->
            val f = side(front, right)
            val fUpper = add("${label}_front_upper", chest, f.x, hipY, f.z)
            val fLower = add("${label}_front_lower", fUpper, f.x, lerp(a.minY, hipY, 0.45f), f.z)
            add("${label}_front_paw", fLower, f.x, a.minY, f.z)

            val r = side(rear, right)
            val rUpper = add("${label}_rear_upper", hips, r.x, hipY, r.z)
            val rLower = add("${label}_rear_lower", rUpper, r.x, lerp(a.minY, hipY, 0.45f), r.z)
            add("${label}_rear_paw", rLower, r.x, a.minY, r.z)
        }
        return Skeleton(bones)
    }

    /** A body bone plus one bone per wheel the analysis located. */
    private fun vehicle(a: MeshAnalyzer.Analysis): Skeleton {
        require(a.limbs.size >= MIN_WHEELS) {
            "No round wheels found on this model — it needs visible wheels near the " +
                "corners. Use a motion clip from Animate instead."
        }
        val bones = mutableListOf(Bone("body", -1, a.centreX, a.minY + a.height * 0.5f, a.centreZ))
        val radii = FloatArray(a.limbs.size + 1)
        var halfWidth = 0f

        // Named by corner so a description can address one wheel if it wants to.
        a.limbs.sortedWith(compareByDescending<MeshAnalyzer.Limb> { it.z }.thenBy { it.x })
            .forEachIndexed { index, wheel ->
                val zLabel = if (wheel.z > a.centreZ) "front" else "rear"
                val xLabel = if (wheel.x > a.centreX) "r" else "l"
                bones += Bone("${xLabel}_${zLabel}_wheel", 0, wheel.x, wheel.hipY, wheel.z)
                radii[bones.size - 1] = wheel.radius
                halfWidth = max(halfWidth, wheel.radius)
            }
        return Skeleton(bones, radii, halfWidth * WHEEL_AXLE_WIDTH)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Centre-refit iterations, and how far inside the fitted rim to cut. */
    private const val REFIT_PASSES = 4
    private const val WHEEL_AXLE_WIDTH = 1.6f
    /** How far outside a limb's measured radius still counts as that limb. */
    private const val LIMB_MARGIN = 1.45f
    /** A little above the body base, so the hip joint blends rather than cuts. */
    private const val LIMB_HEADROOM = 0.10f
    /** How close a bone must sit to a limb's footprint to be part of its chain. */
    private const val CHAIN_TOLERANCE = 0.05f
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

    private fun skinWeights(
        positions: FloatArray,
        s: Skeleton,
        frame: Frame,
        analysis: MeshAnalyzer.Analysis,
    ): Weights {
        val count = positions.size / 3
        val joints = ByteArray(count * 4)
        val values = FloatArray(count * 4)

        // Each limb's bone chain, found by matching bone positions to the limb the
        // analysis measured. Binding by geometry rather than by distance is what
        // fixes the real failure: with a pure distance falloff the hips' long
        // segment ran straight past the rear legs and won them, so an entire leg
        // ended up owning no vertices and simply did not move.
        val chains = analysis.limbs.map { limb ->
            limb to s.bones.indices
                .filter { b ->
                    val bone = s.bones[b]
                    val dx = bone.x - limb.x
                    val dz = bone.z - limb.z
                    // Only the limb's own bones sit directly above its footprint.
                    sqrt(dx * dx + dz * dz) < (limb.radius + analysis.width * CHAIN_TOLERANCE) &&
                        bone.name != "hips" && bone.name != "spine" &&
                        bone.name != "chest" && bone.name != "head"
                }
                .sortedByDescending { s.bones[it].y }
        }.filter { it.second.isNotEmpty() }

        for (v in 0 until count) {
            val x = positions[v * 3]; val y = positions[v * 3 + 1]; val z = positions[v * 3 + 2]

            if (frame == Frame.VEHICLE) {
                // Wheels have to turn as solid objects: a smooth falloff would
                // smear the tyre into the wing. Each vertex is either in a wheel
                // or it is body, with nothing in between.
                var owner = 0
                for (bone in 1 until s.bones.size) {
                    val b = s.bones[bone]
                    val dy = y - b.y; val dz = z - b.z
                    if (sqrt(dy * dy + dz * dz) <= s.wheelRadius[bone] &&
                        abs(x - b.x) <= s.wheelHalfWidth
                    ) { owner = bone; break }
                }
                joints[v * 4] = owner.toByte()
                values[v * 4] = 1f
                continue
            }

            // Inside a limb's footprint and below the body: it is that limb's.
            val limb = if (y <= analysis.bodyBaseY + analysis.height * LIMB_HEADROOM) {
                chains.minByOrNull { (l, _) ->
                    val dx = x - l.x; val dz = z - l.z
                    dx * dx + dz * dz
                }?.takeIf { (l, _) ->
                    val dx = x - l.x; val dz = z - l.z
                    sqrt(dx * dx + dz * dz) <= l.radius * LIMB_MARGIN
                }
            } else null

            if (limb != null) {
                writeChainWeights(s, limb.second, y, joints, values, v)
                continue
            }

            // Everything else falls back to inverse-distance over the bones that
            // are not part of a limb, so a body vertex cannot be claimed by a leg.
            val candidates = s.bones.indices.filter { b -> chains.none { b in it.second } }
                .ifEmpty { s.bones.indices.toList() }
            val bestIdx = IntArray(MAX_INFLUENCES)
            val bestW = FloatArray(MAX_INFLUENCES)
            for (bone in candidates) {
                val d = distanceToBone(s, bone, x, y, z)
                val w = 1f / (d * d * d * d + 1e-6f)
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

    /**
     * Blends a vertex between the two bones of its limb it sits between, by
     * height. A hard assignment would crease the leg at every joint.
     */
    private fun writeChainWeights(
        s: Skeleton,
        chain: List<Int>,
        y: Float,
        joints: ByteArray,
        values: FloatArray,
        v: Int,
    ) {
        if (chain.size == 1) {
            joints[v * 4] = chain[0].toByte(); values[v * 4] = 1f
            return
        }
        // The chain runs top to bottom, so find the pair this height falls between.
        var upper = 0
        while (upper < chain.size - 2 && y < s.bones[chain[upper + 1]].y) upper++
        val a = chain[upper]
        val b = chain[upper + 1]
        val top = s.bones[a].y
        val bottom = s.bones[b].y
        val t = if (top - bottom <= 1e-6f) 0f else ((top - y) / (top - bottom)).coerceIn(0f, 1f)

        joints[v * 4] = a.toByte(); values[v * 4] = 1f - t
        joints[v * 4 + 1] = b.toByte(); values[v * 4 + 1] = t
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

    /**
     * Turns a described motion into keyframes.
     *
     * Anything the description asks for that this skeleton does not have is
     * dropped rather than guessed at, and every amplitude is clamped, so a
     * confused description produces a tame animation instead of a mangled one.
     */
    private fun custom(s: Skeleton, spec: MotionSpec): Motion {
        val seconds = spec.seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val count = (seconds * SAMPLES_PER_SECOND).toInt().coerceIn(4, MAX_KEYS) + 1
        val times = FloatArray(count) { it * seconds / (count - 1) }
        val tau = (2 * PI).toFloat()
        val tracks = mutableMapOf<Int, FloatArray>()

        spec.tracks.take(MAX_TRACKS).forEach { t ->
            // Wheel bones are named per corner, so "wheel" is allowed to mean all
            // of them — the obvious thing to write for a car.
            val targets = if (t.bone.equals("wheels", true) || t.bone.equals("wheel", true)) {
                s.bones.indices.filter { s.bones[it].name.endsWith("wheel") }
            } else {
                s.bones.indices.filter { s.bones[it].name.equals(t.bone, true) }
            }
            if (targets.isEmpty()) return@forEach

            val amplitude = t.amplitude.coerceIn(-MAX_RADIANS, MAX_RADIANS)
            val offset = t.offset.coerceIn(-MAX_RADIANS, MAX_RADIANS)
            val cycles = t.cycles.coerceIn(0.25f, 6f)
            val phase = t.phase

            targets.forEach { bone ->
                val existing = tracks[bone]
                val out = existing ?: FloatArray(count * 4)
                for (i in 0 until count) {
                    val p = times[i] / seconds
                    val theta = tau * (p * cycles + phase)
                    val angle = offset + when (t.wave.lowercase()) {
                        // A full turn: what a wheel or a propeller does.
                        "spin" -> amplitude * tau * p * cycles
                        // One-directional: a knee folds back but never forward.
                        "half" -> amplitude * max(0f, sin(theta))
                        else -> amplitude * sin(theta)
                    }
                    val q = when (t.axis.lowercase()) {
                        "y" -> quatY(angle)
                        "z" -> quatZ(angle)
                        else -> quatX(angle)
                    }
                    if (existing == null) q.copyInto(out, i * 4)
                    else multiply(existing, i * 4, q)
                }
                tracks[bone] = out
            }
        }

        val bob = spec.bob.coerceIn(-MAX_BOB, MAX_BOB)
        val rootMove = if (bob == 0f || tracks.isEmpty()) null else FloatArray(count * 3).also { out ->
            val cycles = spec.bobCycles.coerceIn(0.5f, 6f)
            for (i in 0 until count) {
                out[i * 3 + 1] = bob * sin(tau * (times[i] / seconds) * cycles)
            }
        }
        return Motion(times, tracks, rootMove)
    }

    /** Composes a second rotation onto a key already written at [at]. */
    private fun multiply(target: FloatArray, at: Int, q: FloatArray) {
        val ax = target[at]; val ay = target[at + 1]; val az = target[at + 2]; val aw = target[at + 3]
        val bx = q[0]; val by = q[1]; val bz = q[2]; val bw = q[3]
        var x = aw * bx + ax * bw + ay * bz - az * by
        var y = aw * by - ax * bz + ay * bw + az * bx
        var z = aw * bz + ax * by - ay * bx + az * bw
        var w = aw * bw - ax * bx - ay * by - az * bz
        // glTF requires unit quaternions on a rotation channel.
        val length = sqrt(x * x + y * y + z * z + w * w)
        if (length > 1e-6f) { x /= length; y /= length; z /= length; w /= length } else { x = 0f; y = 0f; z = 0f; w = 1f }
        target[at] = x; target[at + 1] = y; target[at + 2] = z; target[at + 3] = w
    }

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
        label: String,
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
            put("name", label)
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
