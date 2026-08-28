package com.trellis.studio.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The brain reads the model's next move out of free-form chat text, so the
 * parser has to survive everything a model actually emits: code fences, prose
 * around the JSON, Hinglish strings, and numbers as strings. A regex here has
 * bitten this app before (Android's engine rejected a pattern the JVM took), so
 * the parser is hand-written and these lock its behaviour down.
 */
class AgentBrainParseTest {

    @Test
    fun `plain object`() {
        val a = AgentBrain.debugParse("""{"action":"click_text","text":"Chrome"}""")!!
        assertEquals("click_text", a["action"])
        assertEquals("Chrome", a["text"])
    }

    @Test
    fun `wrapped in a code fence and prose`() {
        val raw = """
            Sure, I'll open it.
            ```json
            {"action":"launch","query":"chrome","say":"Chrome khol raha hoon"}
            ```
        """.trimIndent()
        val a = AgentBrain.debugParse(raw)!!
        assertEquals("launch", a["action"])
        assertEquals("chrome", a["query"])
        assertEquals("Chrome khol raha hoon", a["say"])
    }

    @Test
    fun `coordinates as numbers`() {
        val a = AgentBrain.debugParse("""{"action":"click_xy","x":540,"y":1200}""")!!
        assertEquals("click_xy", a["action"])
        assertEquals("540", a["x"])
        assertEquals("1200", a["y"])
    }

    @Test
    fun `nested braces in a string do not end the object early`() {
        val a = AgentBrain.debugParse("""{"action":"type_text","text":"use {curly} braces"}""")!!
        assertEquals("type_text", a["action"])
        assertEquals("use {curly} braces", a["text"])
    }

    @Test
    fun `done with a message`() {
        val a = AgentBrain.debugParse("""{"action":"done","message":"Ho gaya bhai"}""")!!
        assertEquals("done", a["action"])
        assertEquals("Ho gaya bhai", a["message"])
    }

    @Test
    fun `escaped quote inside a string`() {
        val a = AgentBrain.debugParse("""{"action":"type_text","text":"say \"hi\" now"}""")!!
        assertEquals("type_text", a["action"])
        assertEquals("say \"hi\" now", a["text"])
    }

    @Test
    fun `no json at all`() {
        assertNull(AgentBrain.debugParse("I'm not sure what you mean."))
    }
}
