package com.trellis.studio.util

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Batch export has to survive the awkward cases a real gallery contains: two
 * models with the same name, and one that cannot be read at all.
 */
class BatchExportTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `every selected model lands in its own folder`() = runBlocking {
        val models = (1..3).map { i ->
            temp.newFile("model$i.glb").apply { writeBytes(triangleGlb()) }
        }
        val zip = ModelExporter.exportBatch(
            models = models,
            formats = listOf(ModelExporter.Format.GLB, ModelExporter.Format.OBJ),
            outputDir = temp.newFolder("out"),
        ).getOrThrow()

        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList().map { it.name }
            for (i in 1..3) {
                assertTrue("model$i is missing from the zip", entries.any { it.startsWith("model$i/") })
                assertTrue("model$i has no .glb", entries.any { it == "model$i/model$i.glb" })
            }
            assertTrue("nothing should have been skipped", entries.none { it == "SKIPPED.txt" })
        }
    }

    @Test
    fun `models sharing a name do not overwrite each other`() = runBlocking {
        val a = temp.newFolder("a")
        val b = temp.newFolder("b")
        val first = File(a, "car.glb").apply { writeBytes(triangleGlb()) }
        val second = File(b, "car.glb").apply { writeBytes(triangleGlb()) }

        val zip = ModelExporter.exportBatch(
            models = listOf(first, second),
            formats = listOf(ModelExporter.Format.GLB),
            outputDir = temp.newFolder("out2"),
        ).getOrThrow()

        ZipFile(zip).use { archive ->
            val folders = archive.entries().toList().map { it.name.substringBefore('/') }.toSet()
            assertEquals("both models need their own folder", 2, folders.size)
        }
    }

    @Test
    fun `one bad model does not lose the rest`() = runBlocking {
        val good = temp.newFile("good.glb").apply { writeBytes(triangleGlb()) }
        val broken = temp.newFile("broken.glb").apply { writeText("not a model at all") }

        val zip = ModelExporter.exportBatch(
            models = listOf(broken, good),
            formats = listOf(ModelExporter.Format.GLB),
            outputDir = temp.newFolder("out3"),
        ).getOrThrow()

        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList().map { it.name }
            assertTrue("the good model must still be exported", entries.any { it.startsWith("good/") })
            assertTrue("the failure should be recorded", entries.contains("SKIPPED.txt"))
            val note = archive.getInputStream(archive.getEntry("SKIPPED.txt")).readBytes().decodeToString()
            assertTrue("the note should name the bad file", note.contains("broken.glb"))
        }
    }

    @Test
    fun `an empty selection fails instead of writing an empty zip`() = runBlocking {
        val result = ModelExporter.exportBatch(
            models = emptyList(),
            formats = listOf(ModelExporter.Format.GLB),
            outputDir = temp.newFolder("out4"),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `progress is reported for every model`() = runBlocking {
        val models = (1..4).map { temp.newFile("m$it.glb").apply { writeBytes(triangleGlb()) } }
        val seen = mutableListOf<Int>()
        ModelExporter.exportBatch(
            models = models,
            formats = listOf(ModelExporter.Format.GLB),
            outputDir = temp.newFolder("out5"),
            onProgress = { seen += it.done },
        ).getOrThrow()

        assertEquals("one callback per model plus the finish", (0..4).toList(), seen)
    }

    /** A GLB with one textured triangle — enough for GLB and OBJ export. */
    private fun triangleGlb(): ByteArray {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val indices = shortArrayOf(0, 1, 2)

        val out = ByteArrayOutputStream()
        fun pad() { while (out.size() % 4 != 0) out.write(0) }
        val posAt = out.size()
        ByteBuffer.allocate(positions.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> positions.forEach { b.putFloat(it) } }.array().let { out.write(it) }
        pad()
        val uvAt = out.size()
        ByteBuffer.allocate(uvs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> uvs.forEach { b.putFloat(it) } }.array().let { out.write(it) }
        pad()
        val idxAt = out.size()
        ByteBuffer.allocate(indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> indices.forEach { b.putShort(it) } }.array().let { out.write(it) }
        pad()
        val bin = out.toByteArray()

        val json = buildJsonObject {
            put("asset", buildJsonObject { put("version", "2.0") })
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
                                put("POSITION", 0); put("TEXCOORD_0", 1)
                            })
                            put("indices", 2)
                        })
                    })
                })
            })
            put("accessors", buildJsonArray {
                add(buildJsonObject {
                    put("bufferView", 0); put("componentType", 5126)
                    put("count", 3); put("type", "VEC3")
                })
                add(buildJsonObject {
                    put("bufferView", 1); put("componentType", 5126)
                    put("count", 3); put("type", "VEC2")
                })
                add(buildJsonObject {
                    put("bufferView", 2); put("componentType", 5123)
                    put("count", 3); put("type", "SCALAR")
                })
            })
            put("bufferViews", buildJsonArray {
                add(buildJsonObject {
                    put("buffer", 0); put("byteOffset", posAt); put("byteLength", positions.size * 4)
                })
                add(buildJsonObject {
                    put("buffer", 0); put("byteOffset", uvAt); put("byteLength", uvs.size * 4)
                })
                add(buildJsonObject {
                    put("buffer", 0); put("byteOffset", idxAt); put("byteLength", indices.size * 2)
                })
            })
            put("buffers", buildJsonArray { add(buildJsonObject { put("byteLength", bin.size) }) })
        }

        var jsonBytes = json.toString().toByteArray()
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()

        val total = 12 + 8 + jsonBytes.size + 8 + bin.size
        val glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        glb.put("glTF".toByteArray()); glb.putInt(2); glb.putInt(total)
        glb.putInt(jsonBytes.size); glb.put("JSON".toByteArray()); glb.put(jsonBytes)
        glb.putInt(bin.size); glb.put(byteArrayOf(0x42, 0x49, 0x4E, 0x00)); glb.put(bin)
        return glb.array()
    }
}
