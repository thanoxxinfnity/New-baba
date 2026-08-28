package com.trellis.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Arm bones used to be dropped straight down the sides at a fixed offset,
 * whatever the model was doing. A T-posed character — the standard way models
 * are authored for rigging — therefore got its arm chain buried vertically
 * inside its chest, and rotating that "shoulder" tore the torso instead of
 * raising the arm.
 *
 * These tests build figures whose arm angle is known exactly, then check both
 * that the angle is measured back and that the bones land on the arms.
 */
class ArmPoseTest {

    @Test
    fun `a T-posed figure measures as arms out`() {
        val a = MeshAnalyzer.analyse(figure(armDropDegrees = 0f))
        assertEquals(2, a.arms.size)
        val drop = a.armDropDegrees!!
        assertTrue("expected a level arm, measured ${drop}°", drop < 15f)
        assertTrue(a.poseLabel.startsWith("T-pose"))
    }

    @Test
    fun `an A-posed figure measures as arms angled down`() {
        val a = MeshAnalyzer.analyse(figure(armDropDegrees = 45f))
        assertEquals(2, a.arms.size)
        val drop = a.armDropDegrees!!
        assertTrue("expected roughly 45°, measured ${drop}°", abs(drop - 45f) < 18f)
        assertTrue(a.poseLabel.startsWith("A-pose"))
    }

    @Test
    fun `arms held against the body are reported as not separated`() {
        // Nothing reaches past the torso, so there is genuinely nothing to
        // measure — and claiming an arm here is what would misplace the bones.
        val a = MeshAnalyzer.analyse(figure(armDropDegrees = 90f, armReach = 0f))
        assertTrue("no arms should be found, got ${a.arms.size}", a.arms.isEmpty())
        assertEquals(null, a.armDropDegrees)
    }

    @Test
    fun `bones follow a T-posed arm out to the hand`() {
        val a = MeshAnalyzer.analyse(figure(armDropDegrees = 0f))
        val arm = a.arms.maxByOrNull { it.handX }!!   // the right arm

        // The hand must be measured well out to the side, not at the body.
        assertTrue(
            "hand should reach past the torso, x=${arm.handX}",
            arm.handX > a.centreX + a.width * 0.25f,
        )
        // And level with the shoulder, which is what makes it a T rather than an A.
        assertTrue(
            "hand should sit near shoulder height, dy=${arm.shoulderY - arm.handY}",
            abs(arm.shoulderY - arm.handY) < a.height * 0.10f,
        )
    }

    @Test
    fun `an A-posed arm ends below its shoulder and still out to the side`() {
        val a = MeshAnalyzer.analyse(figure(armDropDegrees = 45f))
        val arm = a.arms.maxByOrNull { it.handX }!!
        assertTrue("hand should be below the shoulder", arm.handY < arm.shoulderY)
        assertTrue("hand should still be out to the side", arm.handX > arm.shoulderX)
    }

    /**
     * A biped with arms leaving the torso at a known angle.
     *
     * [armDropDegrees] is measured down from horizontal: 0 is a T-pose, 45 an
     * A-pose, 90 straight down. [armReach] of 0 keeps the arms inside the torso.
     */
    private fun figure(
        armDropDegrees: Float,
        armReach: Float = 0.34f,
        torsoHalf: Float = 0.16f,
        floor: Float = -0.50f,
        hipY: Float = 0.0f,
        topY: Float = 0.60f,
    ): FloatArray {
        val p = mutableListOf<Float>()

        // Two legs, so the analysis reads a biped and finds the hips.
        listOf(-0.11f, 0.11f).forEach { lx ->
            var y = floor
            while (y <= hipY) {
                for (i in 0 until 24) {
                    val t = i / 24f * 2f * Math.PI.toFloat()
                    p += lx + 0.055f * cos(t); p += y; p += 0.055f * sin(t)
                }
                y += 0.02f
            }
        }

        // Torso: a filled slab from the hips up.
        var y = hipY
        while (y <= topY) {
            for (ix in 0..12) for (iz in 0..8) {
                p += -torsoHalf + 2f * torsoHalf * ix / 12f
                p += y
                p += -0.10f + 0.20f * iz / 8f
            }
            y += 0.03f
        }

        // Arms: tubes leaving the torso at the requested angle.
        if (armReach > 0f) {
            val rad = Math.toRadians(armDropDegrees.toDouble())
            val shoulderY = hipY + (topY - hipY) * 0.80f
            listOf(-1f, 1f).forEach { sign ->
                var t = 0f
                while (t <= 1f) {
                    val ax = sign * (torsoHalf + armReach * t * cos(rad).toFloat())
                    val ay = shoulderY - armReach * t * sin(rad).toFloat()
                    for (i in 0 until 16) {
                        val th = i / 16f * 2f * Math.PI.toFloat()
                        // A tube around the arm's axis, thin enough to read as a limb.
                        p += ax + 0.030f * cos(th) * sin(rad).toFloat()
                        p += ay + 0.030f * cos(th) * cos(rad).toFloat()
                        p += 0.030f * sin(th)
                    }
                    t += 0.04f
                }
            }
        }
        return p.toFloatArray()
    }
}
