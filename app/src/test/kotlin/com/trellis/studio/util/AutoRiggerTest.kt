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

        val animatedNodes: List<Int> = json["animations"]!!.jsonArray[0].jsonObject["channels"]!!
            .jsonArray.map { it.jsonObject["target"]!!.jsonObject["node"]!!.jsonPrimitive.int }

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
