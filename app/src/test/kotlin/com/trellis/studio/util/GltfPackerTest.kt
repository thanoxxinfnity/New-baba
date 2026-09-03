package com.trellis.studio.util

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sketchfab hands back a zip of scene.gltf + scene.bin + a textures folder, and
 * nothing else in this app can open that — the viewer and the rigger both read
 * a single .glb. So the conversion is the part that decides whether a
 * downloaded model is usable at all, and it is checked against a real textured
 * glTF (Khronos' BoxTextured: an external .bin buffer and an external .png,
 * exactly the shape Sketchfab ships).
 */
class GltfPackerTest {

    @get:Rule val temp = TemporaryFolder()

    private fun sampleZip(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("sketchfab_sample.zip")!!.use { it.readBytes() }

    @Test
    fun `a gltf archive becomes one self-contained glb`() = runBlocking {
        val packed = GltfPacker
            .packZip(sampleZip(), temp.newFolder("packed"), "Box Textured")
            .getOrThrow()

        assertTrue("the file should exist", packed.file.exists())
        assertEquals("the texture should be pulled in", 1, packed.textures)

        val bytes = packed.file.readBytes()
        assertEquals("a glb starts with the glTF magic", "glTF", String(bytes, 0, 4))

        // It must parse back as a real glb, not merely look like one.
        val (root, bin) = Glb.split(bytes)

        // One buffer, no uri: nothing left pointing at a file on disk.
        val buffers = root["buffers"]!!.jsonArray
        assertEquals(1, buffers.size)
        assertFalse(
            "the buffer must not reference an external file",
            buffers[0].jsonObject.containsKey("uri"),
        )
        // The GLB spec aligns the BIN chunk to four bytes and allows it to run up
        // to three bytes longer than the buffer's declared byteLength, so this
        // checks that documented window rather than demanding an exact match.
        val declared = buffers[0].jsonObject["byteLength"]!!.jsonPrimitive.int
        assertTrue(
            "the binary chunk (${bin.size}) must cover the declared buffer ($declared), " +
                "padded by at most 3 bytes",
            bin.size >= declared && bin.size - declared <= 3,
        )

        // The image moved from a uri to a buffer view, and kept a mime type.
        val image = root["images"]!!.jsonArray[0].jsonObject
        assertFalse("the image must no longer be an external file", image.containsKey("uri"))
        assertTrue("the image needs a bufferView", image.containsKey("bufferView"))
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)

        // Every bufferView has to stay inside the binary chunk, or a loader
        // reads past the end and the model fails to open.
        root["bufferViews"]!!.jsonArray.forEach { v ->
            val o = v.jsonObject
            val offset = o["byteOffset"]?.jsonPrimitive?.int ?: 0
            val length = o["byteLength"]!!.jsonPrimitive.int
            assertTrue(
                "a bufferView runs past the end of the binary chunk",
                offset + length <= bin.size,
            )
        }
    }

    @Test
    fun `the texture survives the move byte for byte`() = runBlocking {
        val packed = GltfPacker
            .packZip(sampleZip(), temp.newFolder("tex"), "Box Textured")
            .getOrThrow()
        val (root, bin) = Glb.split(packed.file.readBytes())

        val view = root["bufferViews"]!!.jsonArray[
            root["images"]!!.jsonArray[0].jsonObject["bufferView"]!!.jsonPrimitive.int
        ].jsonObject
        val offset = view["byteOffset"]?.jsonPrimitive?.int ?: 0
        val length = view["byteLength"]!!.jsonPrimitive.int
        val png = bin.copyOfRange(offset, offset + length)

        // A PNG the viewer can decode still starts with the PNG signature.
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertTrue(
            "the embedded texture is not a PNG any more",
            png.copyOfRange(0, 4).contentEquals(signature),
        )
    }

    @Test
    fun `an archive with no scene is refused with a readable reason`() = runBlocking {
        val junk = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
                zip.write("nothing to see".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val result = GltfPacker.packZip(junk, temp.newFolder("junk"), "junk")
        assertTrue("an archive with no glTF must fail", result.isFailure)
        assertTrue(
            "the message should say what is wrong, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("no glTF", true) == true,
        )
    }
}
