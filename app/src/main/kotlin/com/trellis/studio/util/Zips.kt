package com.trellis.studio.util

import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Reads a zip whose entry names may not be UTF-8.
 *
 * Java's ZipInputStream assumes UTF-8 and throws "malformed input" on anything
 * else, which kills the archive outright. Model packs hit this constantly:
 * MikuMikuDance models are published from Japanese Windows, so their entries
 * are Shift-JIS — a real Miku pack failed to open on its very first entry.
 */
object Zips {

    /** Charsets to try, in order. The first that reads every name wins. */
    private val CANDIDATES: List<Charset> = listOfNotNull(
        Charsets.UTF_8,
        runCatching { Charset.forName("windows-31j") }.getOrNull(),   // Shift-JIS
        runCatching { Charset.forName("GBK") }.getOrNull(),           // Simplified Chinese
        runCatching { Charset.forName("EUC-KR") }.getOrNull(),
        Charsets.ISO_8859_1,                                          // never throws
    )

    /**
     * Reads every file in [bytes] as a path → content map. Directory entries and
     * anything with a traversal segment are dropped.
     */
    fun readAll(bytes: ByteArray): Map<String, ByteArray> {
        var last: Exception? = null
        for (charset in CANDIDATES) {
            try {
                return read(bytes, charset)
            } catch (e: Exception) {
                last = e            // wrong charset for these names — try the next
            }
        }
        throw last ?: IllegalArgumentException("That archive could not be read.")
    }

    private fun read(bytes: ByteArray, charset: Charset): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream(), charset).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val clean = entry.name.replace('\\', '/').removePrefix("./")
                    if (!clean.contains("../")) files[clean] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "That archive has no files in it." }
        return files
    }
}
