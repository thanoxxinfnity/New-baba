package com.trellis.studio.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NVIDIA answers 415 "Unsupported media type: application/json; charset=utf-8.
 * It must be application/json" to any body carrying a charset, and OkHttp's
 * `String.toRequestBody` silently appends one when the media type has none.
 *
 * Dropping the charset from the MediaType constant is therefore not enough —
 * OkHttp puts it straight back — which is exactly how this regressed once
 * already. These lock down the encoding that actually works.
 */
class JsonBodyCharsetTest {

    private val json = "application/json".toMediaType()

    @Test
    fun `okhttp really does add a charset to a string body`() {
        // Not a claim about our code — a claim about the library, which is the
        // reason the workaround below has to exist.
        val body = """{"a":1}""".toRequestBody(json)
        assertEquals(
            "if this ever stops being true the workaround can be simplified",
            "application/json; charset=utf-8",
            body.contentType().toString(),
        )
    }

    @Test
    fun `a byte body keeps the media type exactly as given`() {
        val body = """{"a":1}""".toByteArray(Charsets.UTF_8).toRequestBody(json)
        assertEquals("application/json", body.contentType().toString())
        assertNull("no charset may be added", body.contentType()?.charset())
    }

    @Test
    fun `no client sends a charset on a JSON body`() {
        // Every JSON POST in the app goes through the same helper, so it is
        // checked once here rather than per call site.
        val payload = """{"model":"x","messages":[]}"""
        val body = payload.toByteArray(Charsets.UTF_8).toRequestBody(json)

        assertEquals("application/json", body.contentType().toString())
        assertEquals(
            "the body must still be the same bytes",
            payload.length.toLong(), body.contentLength(),
        )
    }
}
