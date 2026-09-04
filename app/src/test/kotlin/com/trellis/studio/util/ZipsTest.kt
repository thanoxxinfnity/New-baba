package com.trellis.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Java's ZipInputStream assumes UTF-8 entry names and throws "malformed input"
 * on anything else, taking the whole archive with it. MikuMikuDance packs are
 * published from Japanese Windows with Shift-JIS names, so a real Miku archive
 * failed on its very first entry — the model could not even be listed.
 */
class ZipsTest {

    private fun zipWith(charset: Charset, vararg names: String): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out, charset).use { zip ->
                names.forEach { n ->
                    zip.putNextEntry(ZipEntry(n))
                    zip.write("data-for-$n".toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun `a utf-8 archive reads normally`() {
        val files = Zips.readAll(zipWith(Charsets.UTF_8, "model.gltf", "Textures/skin.png"))
        assertEquals(2, files.size)
        assertTrue(files.containsKey("Textures/skin.png"))
    }

    @Test
    fun `a shift-jis archive reads instead of throwing`() {
        val sjis = Charset.forName("windows-31j")
        val files = Zips.readAll(zipWith(sjis, "初音ミク.pmx", "Textures/＝ミク色.png"))
        assertEquals(2, files.size)
        assertTrue(
            "the Japanese name should come back readable, got ${files.keys}",
            files.keys.any { it.endsWith("初音ミク.pmx") },
        )
    }

    @Test
    fun `backslash paths are normalised and traversal is dropped`() {
        val files = Zips.readAll(zipWith(Charsets.UTF_8, "Textures\\body.png", "../escape.txt"))
        assertTrue("backslashes become slashes", files.containsKey("Textures/body.png"))
        assertTrue("a traversal entry must not survive", files.keys.none { it.contains("..") })
    }
}
