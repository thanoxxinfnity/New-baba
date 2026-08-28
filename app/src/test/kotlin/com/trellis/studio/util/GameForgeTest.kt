package com.trellis.studio.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repair step fixes Godot-4 mistakes that the real Godot 4.3 parser rejects.
 * Each case here was an actual error caught by running the generated game in
 * headless Godot, so these lock the fixes so a regression can't ship a game that
 * won't Play.
 */
class GameForgeTest {

    @Test
    fun `get_window_size is replaced with the real API`() {
        val fixed = GameForge.repair("var s = OS.get_window_size()\nvar t = get_window_size()")
        assertFalse("no get_window_size should remain", fixed.contains("get_window_size"))
        assertTrue(fixed.contains("get_viewport().get_visible_rect().size"))
    }

    @Test
    fun `type inference on event is dropped so it compiles`() {
        val fixed = GameForge.repair("\t\t\tvar touch_pos := event.position")
        assertTrue("uses plain assignment", fixed.contains("var touch_pos = event.position"))
        assertFalse(fixed.contains(":="))
    }

    @Test
    fun `invalid sky sun properties are removed`() {
        val fixed = GameForge.repair(
            "\tsky_mat.sun_latitude = 35.0\n\tsky_mat.sun_longitude = 10.0\n\tsky_mat.sun_angle_max = 30.0"
        )
        assertFalse(fixed.contains("sun_latitude"))
        assertFalse(fixed.contains("sun_longitude"))
        // The valid one must survive.
        assertTrue("sun_angle_max is valid and kept", fixed.contains("sun_angle_max"))
    }

    @Test
    fun `valid code is left untouched`() {
        val code = "extends Node3D\n\nfunc _ready() -> void:\n\tvar v := Vector3.ZERO\n\tvelocity = v\n\tmove_and_slide()"
        assertTrue(GameForge.repair(code) == code)
    }
}
