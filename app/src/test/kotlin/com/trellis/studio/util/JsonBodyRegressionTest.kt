package com.trellis.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards two mistakes that already shipped once.
 *
 * The 415 charset bug came back because the fix lived at each call site: it is
 * easy to add a new POST with `String.toRequestBody` and reintroduce it, and
 * nothing would fail until a user hit the network. So the source itself is
 * checked.
 */
class JsonBodyRegressionTest {

    private val networkDir = File("src/main/kotlin/com/trellis/studio/network")

    @Test
    fun `no client posts a string body directly`() {
        assertTrue("network sources not found from ${File(".").absolutePath}", networkDir.isDirectory)

        val offenders = networkDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore("//")
                    // `asJsonBody` is the helper that encodes to bytes first; a raw
                    // String.toRequestBody is what silently appends a charset.
                    if (code.contains(".toRequestBody(") &&
                        !code.contains("toByteArray") &&
                        !code.contains("bytes.toRequestBody") &&
                        !code.contains("asJsonBody")
                    ) "${file.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertEquals(
            "these must go through asJsonBody, or NVIDIA answers 415:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    @Test
    fun `the json media type carries no charset`() {
        val declarations = networkDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { it.readLines() }
            .filter { it.contains("JSON_MEDIA") && it.contains("toMediaType") }
            .toList()

        assertTrue("no JSON_MEDIA declaration found", declarations.isNotEmpty())
        declarations.forEach {
            assertTrue("charset must never be declared: $it", !it.contains("charset"))
        }
    }
}
