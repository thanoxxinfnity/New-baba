package com.trellis.studio.network

import com.trellis.studio.util.AutoRigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The director's input is an LLM reply, which is untrusted text. These cover the
 * shapes a model actually returns — fenced JSON, prose around it, invented bone
 * names, wild numbers — because any of them reaching the rigger unchecked would
 * produce a broken model rather than an error.
 */
class AnimationDirectorTest {

    private val director = AnimationDirector()

    /** Runs the private parser the same way a live reply would reach it. */
    private fun parse(reply: String): Result<AutoRigger.MotionSpec> = runCatching {
        val method = AnimationDirector::class.java
            .getDeclaredMethod("parse", String::class.java, String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        method.invoke(director, reply, "test prompt") as AutoRigger.MotionSpec
    }.recoverCatching { throw it.cause ?: it }

    @Test
    fun `a clean reply parses`() {
        val spec = parse(
            """
            {"name":"Walk","frame":"QUADRUPED","seconds":1.4,"bob":0.02,
             "tracks":[{"bone":"l_front_upper","axis":"x","amplitude":0.4,"phase":0.0,"wave":"sine","cycles":1}]}
            """.trimIndent()
        ).getOrThrow()

        assertEquals(AutoRigger.Frame.QUADRUPED, spec.frame)
        assertEquals("Walk", spec.name)
        assertEquals(1, spec.tracks.size)
        assertEquals("l_front_upper", spec.tracks[0].bone)
    }

    @Test
    fun `json wrapped in a fence and prose still parses`() {
        val spec = parse(
            """
            Sure! Here's the animation you asked for:

            ```json
            {"name":"Drive","frame":"VEHICLE","seconds":2,
             "tracks":[{"bone":"wheels","axis":"x","amplitude":1,"wave":"spin"}]}
            ```

            Let me know if you'd like it faster.
            """.trimIndent()
        ).getOrThrow()

        assertEquals(AutoRigger.Frame.VEHICLE, spec.frame)
        assertEquals("wheels", spec.tracks[0].bone)
    }

    @Test
    fun `invented bones are dropped and real ones kept`() {
        val spec = parse(
            """
            {"name":"Mixed","frame":"HUMANOID","seconds":1,
             "tracks":[
               {"bone":"tentacle_3","axis":"x","amplitude":0.5},
               {"bone":"l_thigh","axis":"x","amplitude":0.5},
               {"bone":"","axis":"x","amplitude":0.5}
             ]}
            """.trimIndent()
        ).getOrThrow()

        assertEquals("only the real bone should survive", 1, spec.tracks.size)
        assertEquals("l_thigh", spec.tracks[0].bone)
    }

    @Test
    fun `a reply with no usable bones fails loudly`() {
        val result = parse(
            """{"name":"Nonsense","frame":"HUMANOID","seconds":1,
                "tracks":[{"bone":"wing","axis":"x","amplitude":0.5}]}"""
        )
        assertTrue(result.isFailure)
        assertTrue(
            "the message should tell the user what to try",
            result.exceptionOrNull()?.message.orEmpty().contains("bones"),
        )
    }

    @Test
    fun `an unknown frame fails rather than guessing`() {
        val result = parse("""{"name":"X","frame":"OCTOPUS","seconds":1,"tracks":[]}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `a reply that is not json at all fails`() {
        assertTrue(parse("I'm sorry, I can't help with that.").isFailure)
    }

    @Test
    fun `wheel group aliases are accepted for a vehicle`() {
        for (alias in listOf("wheels", "wheel", "WHEELS")) {
            val spec = parse(
                """{"name":"Spin","frame":"VEHICLE","seconds":2,
                    "tracks":[{"bone":"$alias","axis":"x","amplitude":1,"wave":"spin"}]}"""
            ).getOrThrow()
            assertEquals("$alias should be kept", 1, spec.tracks.size)
        }
    }

    @Test
    fun `a track with no amplitude is dropped`() {
        val result = parse(
            """{"name":"X","frame":"HUMANOID","seconds":1,
                "tracks":[{"bone":"l_thigh","axis":"x"}]}"""
        )
        assertTrue("a bone with no amplitude cannot animate", result.isFailure)
    }
}
