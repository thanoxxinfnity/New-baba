package com.trellis.studio.util

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [AutoRigger] writes glTF skinning by hand, so the output is checked the way a
 * renderer consumes it: joint indices in range, weights normalised, inverse bind
 * matrices that reproduce the mesh exactly in the bind pose, and a node tree
 * where nothing has two parents.
 */
class AutoRiggerTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a figure gets a working skeleton`() = runBlocking {
        val source = temp.newFile("figure.glb").apply { writeBytes(figureGlb()) }
        val out = temp.newFolder("rigs")

        for (rig in listOf(
            AutoRigger.Rig.HUMANOID_WALK,
            AutoRigger.Rig.HUMANOID_RUN,
            AutoRigger.Rig.QUADRUPED_WALK,
        )) {
            val rigged = AutoRigger.rig(source, rig, out).getOrThrow()
            val g = Rigged(rigged.readBytes())

            assertEquals("${rig.label}: file length must match the header", rigged.length().toInt(), g.declaredLength)
            assertTrue("${rig.label}: needs joints", g.joints.isNotEmpty())
            assertEquals(
                "${rig.label}: one inverse bind matrix per joint",
                g.joints.size, g.inverseBind.size / 16,
            )
            assertEquals("${rig.label}: the mesh must reference the skin", 0, g.skinOnMesh)

            g.jointIndices.forEach {
                assertTrue("${rig.label}: joint index $it is out of range", it in g.joints.indices)
            }
            for (v in 0 until g.vertexCount) {
                val sum = (0 until 4).sumOf { g.weights[v * 4 + it].toDouble() }
                assertTrue("${rig.label}: weights on vertex $v sum to $sum", abs(sum - 1.0) < 1e-3)
                assertTrue(
                    "${rig.label}: negative weight on vertex $v",
                    (0 until 4).all { g.weights[v * 4 + it] >= 0f },
                )
            }

            // A wrong inverse bind matrix is invisible until the model deforms, so
            // it is checked directly: with every joint at rest the skin has to
            // reproduce the original mesh to floating-point accuracy.
            val drift = g.bindPoseDrift()
            assertTrue("${rig.label}: bind pose moves the mesh by $drift", drift < 1e-4)

            assertTrue("${rig.label}: nothing is animated", g.animatedNodes.isNotEmpty())
            g.animatedNodes.forEach {
                assertTrue("${rig.label}: animates node $it, which is not a joint", it in g.joints)
            }
        }
    }

    @Test
    fun `no node ends up with two parents`() = runBlocking {
        val source = temp.newFile("parents.glb").apply { writeBytes(figureGlb()) }
        val rigged = AutoRigger
            .rig(source, AutoRigger.Rig.HUMANOID_WALK, temp.newFolder("parents"))
            .getOrThrow()
        val g = Rigged(rigged.readBytes())

        val seen = mutableSetOf<Int>()
        g.nodes.forEachIndexed { index, node ->
            node.jsonObject["children"]?.jsonArray?.forEach { child ->
                val c = child.jsonPrimitive.int
                assertTrue("node $c is a child of both ${seen.find { it == c }} and $index", seen.add(c))
            }
        }
    }

    @Test
    fun `wheels are found on something wheel-shaped`() = runBlocking {
        val car = temp.newFile("car.glb").apply { writeBytes(carGlb()) }
        val rigged = AutoRigger
            .rig(car, AutoRigger.Rig.VEHICLE_WHEELS, temp.newFolder("car"))
            .getOrThrow()
        val g = Rigged(rigged.readBytes())

        val wheels = g.joints.indices.filter { g.jointName(it).endsWith("wheel") }
        assertEquals("all four wheels should be found", 4, wheels.size)
        assertTrue("bind pose must be exact", g.bindPoseDrift() < 1e-4)

        // Every wheel vertex must be fully owned by its wheel: a blended weight
        // would tear the tyre away from the rim as it turns.
        val claimed = (0 until g.vertexCount).count { v ->
            g.jointIndices[v * 4] in wheels && g.weights[v * 4] == 1f
        }
        assertTrue("the wheels claimed no vertices", claimed > 0)
    }

    @Test
    fun `a model with no wheels is refused rather than mangled`() = runBlocking {
        // A flat slab has no round corner clusters. Spinning one would sweep it
        // into a disc and destroy the model, so this has to fail instead.
        val slab = temp.newFile("slab.glb").apply { writeBytes(slabGlb()) }
        val result = AutoRigger.rig(slab, AutoRigger.Rig.VEHICLE_WHEELS, temp.newFolder("slab"))
        assertTrue("a slab must not pass as a car", result.isFailure)
        assertTrue(
            "the failure should say what to do instead",
            result.exceptionOrNull()?.message.orEmpty().contains("wheels"),
        )
    }

    /**
     * The bones-only path is the deliverable on its own: an engine can animate
     * anything once the mesh is bound to joints, but it cannot create joints. So
     * the file has to carry a complete, valid skin with no clip in it.
     */
    @Test
    fun `bones are added with no animation clip`() = runBlocking {
        val source = temp.newFile("bare.glb").apply { writeBytes(bipedGlb()) }
        val result = AutoRigger.addBones(source, temp.newFolder("bare")).getOrThrow()
        val g = Rigged(result.file.readBytes())

        assertEquals("the file length must match the header", result.file.length().toInt(), g.declaredLength)
        // An empty animations array is invalid glTF, so the key must be absent
        // rather than present and empty.
        assertTrue("a rig-only file carries no clip", !g.hasAnimations)
        assertTrue("no channels either", g.animatedNodes.isEmpty())

        assertTrue("the skeleton must have joints", g.joints.isNotEmpty())
        assertEquals("the bone names are what code addresses", g.joints.size, result.bones.size)
        result.bones.forEachIndexed { i, name -> assertEquals(name, g.jointName(i)) }
        assertEquals("the mesh must reference the skin", 0, g.skinOnMesh)
        assertEquals("one inverse bind matrix per joint", g.joints.size, g.inverseBind.size / 16)

        g.jointIndices.forEach {
            assertTrue("joint index $it is out of range", it in g.joints.indices)
        }
        for (v in 0 until g.vertexCount) {
            val sum = (0 until 4).sumOf { g.weights[v * 4 + it].toDouble() }
            assertTrue("weights on vertex $v sum to $sum", abs(sum - 1.0) < 1e-3)
        }
        // Without a clip nothing ever moves the joints, so an unskinned-looking
        // model here would mean the skin itself is wrong.
        assertTrue("the bind pose must be exact, drifted ${g.bindPoseDrift()}", g.bindPoseDrift() < 1e-4)

        assertEquals(AutoRigger.Frame.HUMANOID, result.frame)
        // The failure this guards against: a leg bone bound to nothing, so the
        // rig reads as complete and the leg never moves when code rotates it.
        listOf("l_thigh", "l_knee", "r_thigh", "r_knee").forEach { name ->
            val bone = result.bones.indexOf(name)
            assertTrue("$name is missing from the skeleton", bone >= 0)
            val owned = (0 until g.vertexCount).count { v ->
                (0 until 4).any { g.jointIndices[v * 4 + it] == bone && g.weights[v * 4 + it] > 0.1f }
            }
            assertTrue("$name owns no vertices, so rotating it does nothing", owned > 0)
        }
    }

    /**
     * The question that actually matters for a game: if the engine rotates
     * `r_shoulder`, does the arm move?
     *
     * It only moves if arm geometry is bound to arm bones. The old rigger put
     * the arm chain straight down inside the chest whatever the pose, so on a
     * T-posed model those bones owned torso vertices — rotating a shoulder
     * twisted the body and left the arm hanging in space.
     */
    @Test
    fun `arm bones own the arm on a T-posed figure`() = runBlocking {
        val source = temp.newFile("tpose.glb").apply { writeBytes(tPosedGlb()) }
        val result = AutoRigger
            .addBones(source, temp.newFolder("tpose"), pose = AutoRigger.Pose.T_POSE)
            .getOrThrow()
        val g = Rigged(result.file.readBytes())

        listOf("l_shoulder", "l_elbow", "l_hand", "r_shoulder", "r_elbow", "r_hand").forEach { name ->
            val bone = result.bones.indexOf(name)
            assertTrue("$name is missing from the skeleton", bone >= 0)
            val owned = (0 until g.vertexCount).count { v ->
                (0 until 4).any { g.jointIndices[v * 4 + it] == bone && g.weights[v * 4 + it] > 0.1f }
            }
            assertTrue("$name owns no vertices, so rotating it does nothing", owned > 0)
        }

        // The point of the rig: geometry way out at the end of the arm must be
        // driven by the arm chain, not by the spine or chest. A hand vertex
        // sharing influence between r_elbow and r_hand is normal skinning — what
        // must never happen is a torso bone owning it, which is what the old
        // straight-down placement produced on a T-posed model.
        val armChain = listOf("r_shoulder", "r_elbow", "r_hand").map { result.bones.indexOf(it) }
        val farArm = (0 until g.vertexCount).filter { g.positions[it * 3] > 0.35f }
        assertTrue("the test figure should have geometry out at the hand", farArm.isNotEmpty())

        val drivenByArm = farArm.count { v ->
            val best = (0 until 4).maxByOrNull { g.weights[v * 4 + it] }!!
            g.jointIndices[v * 4 + best].toInt() in armChain
        }
        assertEquals(
            "every vertex at the end of the arm must be driven by an arm bone",
            farArm.size, drivenByArm,
        )
    }

    @Test
    fun `an A-posed figure binds its arms too`() = runBlocking {
        val source = temp.newFile("apose.glb").apply { writeBytes(tPosedGlb(armDropDegrees = 45f)) }
        val result = AutoRigger
            .addBones(source, temp.newFolder("apose"), pose = AutoRigger.Pose.A_POSE)
            .getOrThrow()
        val g = Rigged(result.file.readBytes())

        listOf("l_hand", "r_hand").forEach { name ->
            val bone = result.bones.indexOf(name)
            val owned = (0 until g.vertexCount).count { v ->
                (0 until 4).any { g.jointIndices[v * 4 + it] == bone && g.weights[v * 4 + it] > 0.1f }
            }
            assertTrue("$name owns no vertices on an A-pose", owned > 0)
        }
        // Weights must still be a valid partition of unity, pose notwithstanding.
        for (v in 0 until g.vertexCount) {
            val sum = (0 until 4).sumOf { g.weights[v * 4 + it].toDouble() }
            assertTrue("weights on vertex $v sum to $sum", abs(sum - 1.0) < 1e-3)
        }
    }

    @Test
    fun `the shape decides the skeleton without being told`() = runBlocking {
        val car = temp.newFile("bones_car.glb").apply { writeBytes(carGlb()) }
        val rigged = AutoRigger.addBones(car, temp.newFolder("bones_car")).getOrThrow()

        assertEquals(AutoRigger.Frame.VEHICLE, rigged.frame)
        assertEquals(
            "four wheels should each get a bone",
            4, rigged.bones.count { it.endsWith("wheel") },
        )
        // A bone nothing is weighted to is a bone that does nothing when rotated,
        // which is how a rig looks correct in the file and dead in the engine.
        val g = Rigged(rigged.file.readBytes())
        rigged.bones.indices.filter { rigged.bones[it].endsWith("wheel") }.forEach { bone ->
            val owned = (0 until g.vertexCount).count { v ->
                (0 until 4).any { g.jointIndices[v * 4 + it] == bone && g.weights[v * 4 + it] > 0.1f }
            }
            assertTrue("${rigged.bones[bone]} owns no vertices", owned > 0)
        }
    }

    @Test
    fun `a shape with no limbs is refused rather than given a spine`() = runBlocking {
        val slab = temp.newFile("bones_slab.glb").apply { writeBytes(slabGlb()) }
        val result = AutoRigger.addBones(slab, temp.newFolder("bones_slab"))
        assertTrue("a featureless slab has nothing to bend", result.isFailure)
        assertTrue(
            "the failure should say why",
            result.exceptionOrNull()?.message.orEmpty().contains("nothing for bones to bend"),
        )
    }

    @Test
    fun `proportions pick a sensible rig`() {
        val tall = temp.newFile("tall.glb").apply { writeBytes(figureGlb()) }
        assertEquals(AutoRigger.Rig.HUMANOID_WALK, AutoRigger.suggest(tall))

        val car = temp.newFile("wide.glb").apply { writeBytes(carGlb()) }
        assertEquals(AutoRigger.Rig.VEHICLE_WHEELS, AutoRigger.suggest(car))
    }

    // ------------------------------------------------------------------ model

    /** Parsed view of a rigged GLB, with just enough to evaluate the skin. */
    private class Rigged(bytes: ByteArray) {
        val declaredLength = le32(bytes, 8)
        private val jsonLength = le32(bytes, 12)
        val json: JsonObject = Json.parseToJsonElement(String(bytes, 20, jsonLength).trim()).jsonObject
        private val bin: ByteArray = run {
            val header = 20 + jsonLength
            val length = le32(bytes, header)
            bytes.copyOfRange(header + 8, header + 8 + length)
        }

        val nodes = json["nodes"]!!.jsonArray
        private val skin = json["skins"]!!.jsonArray[0].jsonObject
        val joints = skin["joints"]!!.jsonArray.map { it.jsonPrimitive.int }
        private val primitive = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!
            .jsonArray[0].jsonObject
        private val attributes = primitive["attributes"]!!.jsonObject

        val positions = floats(attributes["POSITION"]!!.jsonPrimitive.int, 3)
        val weights = floats(attributes["WEIGHTS_0"]!!.jsonPrimitive.int, 4)
        val inverseBind = floats(skin["inverseBindMatrices"]!!.jsonPrimitive.int, 16)
        val jointIndices = bytesOf(attributes["JOINTS_0"]!!.jsonPrimitive.int)
        val vertexCount = positions.size / 3

        val skinOnMesh = nodes.first { it.jsonObject.containsKey("mesh") }
            .jsonObject["skin"]!!.jsonPrimitive.int

        /** Empty when no clip was baked — bones-only output has none by design. */
        val animatedNodes: List<Int> = json["animations"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("channels")?.jsonArray
            ?.map { it.jsonObject["target"]!!.jsonObject["node"]!!.jsonPrimitive.int }
            ?: emptyList()

        val hasAnimations = json.containsKey("animations")

        fun jointName(jointIndex: Int) =
            nodes[joints[jointIndex]].jsonObject["name"]!!.jsonPrimitive.content

        /** Global joint position with no animation applied — the bind pose. */
        private fun globalOf(node: Int): FloatArray {
            val parent = nodes.indices.firstOrNull { i ->
                nodes[i].jsonObject["children"]?.jsonArray
                    ?.any { it.jsonPrimitive.int == node } == true
            }
            val local = nodes[node].jsonObject["translation"]?.jsonArray
                ?.map { it.jsonPrimitive.float }?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f)
            if (parent == null) return local
            val up = globalOf(parent)
            return floatArrayOf(up[0] + local[0], up[1] + local[1], up[2] + local[2])
        }

        /** Largest distance a vertex moves when the skin is evaluated at rest. */
        fun bindPoseDrift(): Float {
            val globals = joints.map { globalOf(it) }
            var worst = 0f
            for (v in 0 until vertexCount) {
                var x = 0f; var y = 0f; var z = 0f
                for (e in 0 until 4) {
                    val w = weights[v * 4 + e]
                    if (w <= 0f) continue
                    val j = jointIndices[v * 4 + e]
                    val m = j * 16
                    val g = globals[j]
                    // Bind transform is a pure translation, so the joint's effect is
                    // (vertex + inverseBindTranslation) + jointGlobalPosition.
                    x += w * (positions[v * 3] + inverseBind[m + 12] + g[0])
                    y += w * (positions[v * 3 + 1] + inverseBind[m + 13] + g[1])
                    z += w * (positions[v * 3 + 2] + inverseBind[m + 14] + g[2])
                }
                val dx = x - positions[v * 3]
                val dy = y - positions[v * 3 + 1]
                val dz = z - positions[v * 3 + 2]
                worst = maxOf(worst, sqrt(dx * dx + dy * dy + dz * dz))
            }
            return worst
        }

        private fun floats(accessorIndex: Int, components: Int): FloatArray {
            val accessor = json["accessors"]!!.jsonArray[accessorIndex].jsonObject
            val count = accessor["count"]!!.jsonPrimitive.int
            val view = json["bufferViews"]!!.jsonArray[
                accessor["bufferView"]!!.jsonPrimitive.int
            ].jsonObject
            val base = view["byteOffset"]?.jsonPrimitive?.int ?: 0
            val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count * components) { buffer.getFloat(base + it * 4) }
        }

        private fun bytesOf(accessorIndex: Int): IntArray {
            val accessor = json["accessors"]!!.jsonArray[accessorIndex].jsonObject
            val count = accessor["count"]!!.jsonPrimitive.int
            val view = json["bufferViews"]!!.jsonArray[
                accessor["bufferView"]!!.jsonPrimitive.int
            ].jsonObject
            val base = view["byteOffset"]?.jsonPrimitive?.int ?: 0
            return IntArray(count * 4) { bin[base + it].toInt() and 0xff }
        }

        private fun le32(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    }

    // ------------------------------------------------------------- test meshes

    /** Upright, taller than wide — the shape a humanoid rig expects. */
    private fun figureGlb(): ByteArray {
        val points = mutableListOf<Float>()
        for (i in 0 until 400) {
            val t = i / 400f
            val angle = t * 40f
            points += 0.18f * cos(angle)
            points += -0.5f + t                      // y spans -0.5 .. 0.5
            points += 0.14f * sin(angle)
        }
        return pointCloudGlb(points.toFloatArray())
    }

    /** Long, wide and flat, with four round wheels at the corners. */
    private fun carGlb(): ByteArray {
        val points = mutableListOf<Float>()
        // Body: a shallow box.
        for (i in 0 until 300) {
            val t = i / 300f
            points += -0.28f + 0.56f * ((i % 7) / 6f)
            points += -0.02f + 0.16f * ((i % 5) / 4f)
            points += -0.5f + t
        }
        // Wheels: filled discs, the way a real wheel is modelled — a face plus a
        // rim, so there is geometry at every radius and not just on the edge.
        for (sx in listOf(-0.26f, 0.26f)) {
            for (sz in listOf(-0.34f, 0.34f)) {
                for (ring in 1..5) {
                    val r = 0.09f * ring / 5f
                    for (i in 0 until 24) {
                        val a = i / 24f * 2f * Math.PI.toFloat()
                        points += sx + 0.03f * ((i % 3) - 1)
                        points += -0.10f + r * sin(a)
                        points += sz + r * cos(a)
                    }
                }
            }
        }
        return pointCloudGlb(points.toFloatArray())
    }

    /**
     * A torso on two real legs. [figureGlb] is a helix, which measures as one
     * solid shape — fine for checking glTF output, useless for checking that
     * bones land on limbs.
     */
    private fun bipedGlb(): ByteArray {
        val p = mutableListOf<Float>()
        listOf(-0.12f, 0.12f).forEach { lx ->
            var y = -0.5f
            while (y <= 0.05f) {
                // Filled, not a hollow ring: a real leg has geometry at every
                // radius, and an outline can slice into two arcs.
                for (ring in 1..4) {
                    val r = 0.06f * ring / 4f
                    for (i in 0 until 20) {
                        val a = i / 20f * 2f * Math.PI.toFloat()
                        p += lx + r * cos(a)
                        p += y
                        p += r * sin(a)
                    }
                }
                y += 0.02f
            }
        }
        var y = 0.05f
        while (y <= 0.5f) {
            for (ix in 0..12) for (iz in 0..8) {
                p += -0.18f + 0.36f * ix / 12f
                p += y
                p += -0.10f + 0.20f * iz / 8f
            }
            y += 0.03f
        }
        return pointCloudGlb(p.toFloatArray())
    }

    /** A biped whose arms leave the torso at a known angle: 0 is a T, 45 an A. */
    private fun tPosedGlb(armDropDegrees: Float = 0f): ByteArray {
        val p = mutableListOf<Float>()
        val torsoHalf = 0.18f
        listOf(-0.12f, 0.12f).forEach { lx ->
            var y = -0.5f
            while (y <= 0.05f) {
                for (ring in 1..4) {
                    val r = 0.06f * ring / 4f
                    for (i in 0 until 20) {
                        val a = i / 20f * 2f * Math.PI.toFloat()
                        p += lx + r * cos(a); p += y; p += r * sin(a)
                    }
                }
                y += 0.02f
            }
        }
        var y = 0.05f
        while (y <= 0.5f) {
            for (ix in 0..12) for (iz in 0..8) {
                p += -torsoHalf + 2f * torsoHalf * ix / 12f
                p += y
                p += -0.10f + 0.20f * iz / 8f
            }
            y += 0.03f
        }
        // Arms: solid tubes reaching well past the torso.
        val rad = Math.toRadians(armDropDegrees.toDouble())
        val shoulderY = 0.40f
        val reach = 0.34f
        listOf(-1f, 1f).forEach { sign ->
            var t = 0f
            while (t <= 1f) {
                val ax = sign * (torsoHalf + reach * t * cos(rad).toFloat())
                val ay = shoulderY - reach * t * sin(rad).toFloat()
                for (ring in 1..3) {
                    val r = 0.035f * ring / 3f
                    for (i in 0 until 16) {
                        val th = i / 16f * 2f * Math.PI.toFloat()
                        p += ax + r * cos(th) * sin(rad).toFloat()
                        p += ay + r * cos(th) * cos(rad).toFloat()
                        p += r * sin(th)
                    }
                }
                t += 0.035f
            }
        }
        return pointCloudGlb(p.toFloatArray())
    }

    /** Flat and featureless: no round corner clusters anywhere. */
    private fun slabGlb(): ByteArray {
        val points = mutableListOf<Float>()
        for (i in 0 until 600) {
            points += -0.3f + 0.6f * ((i % 13) / 12f)
            points += -0.04f + 0.08f * ((i % 3) / 2f)
            points += -0.5f + (i / 600f)
        }
        return pointCloudGlb(points.toFloatArray())
    }

    /**
     * Wraps positions in a minimal GLB shaped like TRELLIS output: a "world"
     * node wrapping a single mesh node, one primitive, no transforms.
     */
    private fun pointCloudGlb(positions: FloatArray): ByteArray {
        val binOut = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(positions.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach { buf.putFloat(it) }
        binOut.write(buf.array())
        var bin = binOut.toByteArray()
        while (bin.size % 4 != 0) bin += 0

        val count = positions.size / 3
        val json = buildJsonObject {
            put("asset", buildJsonObject { put("version", "2.0") })
            put("scene", 0)
            put("scenes", buildJsonArray {
                add(buildJsonObject { put("nodes", buildJsonArray { add(0) }) })
            })
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("name", "world"); put("children", buildJsonArray { add(1) })
                })
                add(buildJsonObject { put("name", "geometry_0"); put("mesh", 0) })
            })
            put("meshes", buildJsonArray {
                add(buildJsonObject {
                    put("primitives", buildJsonArray {
                        add(buildJsonObject {
                            put("attributes", buildJsonObject { put("POSITION", 0) })
                            put("mode", 0)
                        })
                    })
                })
            })
            put("accessors", buildJsonArray {
                add(buildJsonObject {
                    put("bufferView", 0); put("componentType", 5126)
                    put("count", count); put("type", "VEC3")
                })
            })
            put("bufferViews", buildJsonArray {
                add(buildJsonObject {
                    put("buffer", 0); put("byteOffset", 0); put("byteLength", positions.size * 4)
                })
            })
            put("buffers", buildJsonArray { add(buildJsonObject { put("byteLength", bin.size) }) })
        }

        var jsonBytes = json.toString().toByteArray()
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()

        val total = 12 + 8 + jsonBytes.size + 8 + bin.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("glTF".toByteArray()); out.putInt(2); out.putInt(total)
        out.putInt(jsonBytes.size); out.put("JSON".toByteArray()); out.put(jsonBytes)
        out.putInt(bin.size); out.put(byteArrayOf(0x42, 0x49, 0x4E, 0x00)); out.put(bin)
        return out.array()
    }
}
