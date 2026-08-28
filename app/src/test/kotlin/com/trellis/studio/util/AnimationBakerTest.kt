package com.trellis.studio.util

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AnimationBaker] writes a glTF container by hand, so every clip is baked into
 * a real GLB here and the result is validated the way a game engine's loader
 * would: chunk layout, buffer bounds, accessor bounds, and channel targets.
 */
class AnimationBakerTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `every clip produces a loadable glb`() = runBlocking {
        val source = temp.newFile("source.glb").apply { writeBytes(sampleGlb()) }
        val out = temp.newFolder("out")

        for (clip in AnimationBaker.Clip.entries) {
            val baked = AnimationBaker.bake(source, clip, out).getOrThrow()
            val parsed = Parsed(baked.readBytes())

            assertEquals("${clip.label}: wrong magic", "glTF", parsed.magic)
            assertEquals("${clip.label}: file length must match the header", baked.length().toInt(), parsed.declaredLength)
            assertEquals("${clip.label}: JSON chunk must be 4-byte aligned", 0, parsed.jsonLength % 4)
            assertEquals("${clip.label}: BIN chunk must be 4-byte aligned", 0, parsed.binLength % 4)

            val root = parsed.json
            val animations = root["animations"]!!.jsonArray
            assertEquals("${clip.label}: expected exactly one animation", 1, animations.size)

            val animation = animations[0].jsonObject
            assertEquals(clip.label, animation["name"]!!.jsonPrimitive.content)

            val samplers = animation["samplers"]!!.jsonArray
            val channels = animation["channels"]!!.jsonArray
            assertTrue("${clip.label}: needs at least one channel", channels.isNotEmpty())
            assertEquals("${clip.label}: one sampler per channel", samplers.size, channels.size)

            val accessors = root["accessors"]!!.jsonArray
            val bufferViews = root["bufferViews"]!!.jsonArray
            val nodes = root["nodes"]!!.jsonArray
            val bufferLength = root["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.int

            assertTrue(
                "${clip.label}: the BIN chunk may only exceed the buffer by its 4-byte padding",
                parsed.binPadding in 0..3,
            )
            assertTrue("${clip.label}: keyframes must have grown the buffer", bufferLength > 36)

            // Every bufferView has to sit inside the buffer it points at.
            bufferViews.forEachIndexed { i, view ->
                val v = view.jsonObject
                val start = v["byteOffset"]?.jsonPrimitive?.int ?: 0
                val length = v["byteLength"]!!.jsonPrimitive.int
                assertTrue(
                    "${clip.label}: bufferView $i runs past the buffer",
                    start + length <= bufferLength,
                )
            }

            channels.forEach { entry ->
                val channel = entry.jsonObject
                val target = channel["target"]!!.jsonObject
                val node = target["node"]!!.jsonPrimitive.int
                val path = target["path"]!!.jsonPrimitive.content

                assertTrue("${clip.label}: channel targets a node that does not exist", node in 0 until nodes.size)
                assertEquals(
                    "${clip.label}: the baker must drive its own inserted node",
                    "void_animator", nodes[node].jsonObject["name"]!!.jsonPrimitive.content,
                )

                val sampler = samplers[channel["sampler"]!!.jsonPrimitive.int].jsonObject
                val input = accessors[sampler["input"]!!.jsonPrimitive.int].jsonObject
                val output = accessors[sampler["output"]!!.jsonPrimitive.int].jsonObject

                assertEquals("${clip.label}: time accessor must be SCALAR", "SCALAR", input["type"]!!.jsonPrimitive.content)
                assertNotNull("${clip.label}: animation input needs min", input["min"])
                assertNotNull("${clip.label}: animation input needs max", input["max"])
                assertEquals(
                    "${clip.label}: sampler input and output must have the same key count",
                    input["count"]!!.jsonPrimitive.int, output["count"]!!.jsonPrimitive.int,
                )

                val expectedType = if (path == "rotation") "VEC4" else "VEC3"
                assertEquals("${clip.label}: wrong output type for $path", expectedType, output["type"]!!.jsonPrimitive.content)

                // The accessor's data has to fit inside the view it reads from.
                val components = if (expectedType == "VEC4") 4 else 3
                val view = bufferViews[output["bufferView"]!!.jsonPrimitive.int].jsonObject
                assertEquals(
                    "${clip.label}: $path view is the wrong size",
                    output["count"]!!.jsonPrimitive.int * components * 4,
                    view["byteLength"]!!.jsonPrimitive.int,
                )
                assertEquals(
                    "${clip.label}: FLOAT accessors must start on a 4-byte boundary",
                    0, (view["byteOffset"]?.jsonPrimitive?.int ?: 0) % 4,
                )
            }
        }
    }

    @Test
    fun `the original mesh data survives untouched`() = runBlocking {
        val original = sampleGlb()
        val source = temp.newFile("mesh.glb").apply { writeBytes(original) }
        val baked = AnimationBaker
            .bake(source, AnimationBaker.Clip.SPIN, temp.newFolder("keep"))
            .getOrThrow()

        val before = Parsed(original)
        val after = Parsed(baked.readBytes())

        // The keyframes are appended, so the leading bytes must be byte-identical
        // or every existing byteOffset in the file would now point at the wrong data.
        val head = after.bin.copyOfRange(0, before.bin.size)
        assertTrue("appending must not disturb the existing buffer", head.contentEquals(before.bin))
        assertEquals(
            "the mesh itself must be left alone",
            before.json["meshes"].toString(), after.json["meshes"].toString(),
        )
    }

    @Test
    fun `the scene root is wrapped rather than overwritten`() = runBlocking {
        val source = temp.newFile("scene.glb").apply { writeBytes(sampleGlb()) }
        val baked = AnimationBaker
            .bake(source, AnimationBaker.Clip.TUMBLE, temp.newFolder("scene"))
            .getOrThrow()
        val root = Parsed(baked.readBytes()).json

        val nodes = root["nodes"]!!.jsonArray
        val sceneRoots = root["scenes"]!!.jsonArray[0].jsonObject["nodes"]!!.jsonArray
        assertEquals("the scene should now have a single root", 1, sceneRoots.size)

        val rig = nodes[sceneRoots[0].jsonPrimitive.int].jsonObject
        assertEquals("void_animator", rig["name"]!!.jsonPrimitive.content)
        assertEquals(
            "the old root must become a child of the animated node",
            listOf(0), rig["children"]!!.jsonArray.map { it.jsonPrimitive.int },
        )
        // Overwriting the model's own node would have destroyed this transform.
        assertNotNull("the original node's transform must survive", nodes[1].jsonObject["translation"])
    }

    @Test
    fun `clip names can be read back`() = runBlocking {
        val source = temp.newFile("named.glb").apply { writeBytes(sampleGlb()) }
        assertEquals(emptyList<String>(), AnimationBaker.clipNames(source))

        val baked = AnimationBaker
            .bake(source, AnimationBaker.Clip.BOUNCE, temp.newFolder("named"))
            .getOrThrow()
        assertEquals(listOf("Bounce"), AnimationBaker.clipNames(baked))
    }

    @Test
    fun `a file that is not a glb fails instead of writing garbage`() = runBlocking {
        val junk = temp.newFile("notes.txt").apply { writeText("this is not a model") }
        val result = AnimationBaker.bake(junk, AnimationBaker.Clip.SPIN, temp.newFolder("junk"))
        assertTrue("a text file must not bake", result.isFailure)
    }

    // ------------------------------------------------------------------ helpers

    private class Parsed(bytes: ByteArray) {
        val magic = String(bytes, 0, 4)
        val declaredLength = le32(bytes, 8)
        val jsonLength = le32(bytes, 12)
        val json: JsonObject = Json.parseToJsonElement(String(bytes, 20, jsonLength).trim()).jsonObject
        private val binHeader = 20 + jsonLength
        val binLength = le32(bytes, binHeader)
        val bin: ByteArray = bytes.copyOfRange(binHeader + 8, binHeader + 8 + binLength)

        /** Zero bytes the container added to reach a 4-byte boundary. */
        val binPadding: Int = run {
            val declared = json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.int
            binLength - declared
        }

        private fun le32(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    }

    /**
     * A minimal but valid GLB: one triangle under a translated node, shaped the
     * same way NVIDIA's TRELLIS output is (a "world" root wrapping a mesh node).
     */
    private fun sampleGlb(): ByteArray {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val binOut = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(positions.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach { buf.putFloat(it) }
        binOut.write(buf.array())
        var bin = binOut.toByteArray()
        while (bin.size % 4 != 0) bin += 0

        val json = buildJsonObject {
            put("asset", buildJsonObject { put("version", "2.0") })
            put("scene", 0)
            put("scenes", buildJsonArray {
                add(buildJsonObject { put("nodes", buildJsonArray { add(0) }) })
            })
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("name", "world")
                    put("children", buildJsonArray { add(1) })
                })
                add(buildJsonObject {
                    put("name", "geometry_0")
                    put("mesh", 0)
                    // A transform the baker must not clobber.
                    put("translation", buildJsonArray { add(0f); add(0.5f); add(0f) })
                })
            })
            put("meshes", buildJsonArray {
                add(buildJsonObject {
                    put("primitives", buildJsonArray {
                        add(buildJsonObject {
                            put("attributes", buildJsonObject { put("POSITION", 0) })
                        })
                    })
                })
            })
            put("accessors", buildJsonArray {
                add(buildJsonObject {
                    put("bufferView", 0)
                    put("componentType", 5126)
                    put("count", 3)
                    put("type", "VEC3")
                    put("min", buildJsonArray { add(0f); add(0f); add(0f) })
                    put("max", buildJsonArray { add(1f); add(1f); add(0f) })
                })
            })
            put("bufferViews", buildJsonArray {
                add(buildJsonObject {
                    put("buffer", 0)
                    put("byteOffset", 0)
                    put("byteLength", positions.size * 4)
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
