package com.trellis.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The analysis decides where every bone goes, so it has to be right about two
 * things: what the shape is, and where its limbs actually are.
 *
 * The bug this exists to prevent was silent. Bones came from bounding-box
 * fractions, which put a dog's rear legs 0.13 units from the real ones on a body
 * 0.99 long — so the bone sat in the belly, the belly moved instead of the leg,
 * and one whole leg ended up with no geometry bound to it at all.
 */
class MeshAnalyzerTest {

    @Test
    fun `four legs are found where they actually are`() {
        val legs = listOf(-0.09f to 0.30f, 0.09f to 0.30f, -0.09f to -0.30f, 0.09f to -0.30f)
        val mesh = standingFigure(legs, legRadius = 0.05f, bodyTop = 0.30f, floor = -0.50f)

        val a = MeshAnalyzer.analyse(mesh)
        assertEquals(MeshAnalyzer.Shape.QUADRUPED, a.shape)
        assertEquals(4, a.limbs.size)
        assertTrue("the fit should be confident, got ${a.confidence}", a.confidence > 0.8f)

        // Every real leg must be matched by a found limb, within its own radius.
        legs.forEach { (x, z) ->
            val nearest = a.limbs.minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }!!
            val error = kotlin.math.sqrt(
                (nearest.x - x) * (nearest.x - x) + (nearest.z - z) * (nearest.z - z)
            )
            assertTrue("leg at ($x, $z) was found at (${nearest.x}, ${nearest.z})", error < 0.05f)
        }
    }

    @Test
    fun `two legs read as a biped`() {
        val mesh = standingFigure(
            listOf(-0.12f to 0f, 0.12f to 0f),
            legRadius = 0.06f, bodyTop = 0.45f, floor = -0.50f,
        )
        val a = MeshAnalyzer.analyse(mesh)
        assertEquals(MeshAnalyzer.Shape.BIPED, a.shape)
        assertEquals(2, a.limbs.size)
    }

    @Test
    fun `a solid blob is reported as solid, not rigged`() {
        // The failure mode worth preventing: a shape with no limbs must not be
        // handed a skeleton, because the rig would bend the entire model.
        val a = MeshAnalyzer.analyse(blob())
        assertEquals(MeshAnalyzer.Shape.SOLID, a.shape)
        assertTrue("a solid shape has no limbs", a.limbs.isEmpty())
        assertEquals("and no confidence in a fit", 0f, a.confidence, 1e-6f)
    }

    @Test
    fun `noise does not pass as a pair of legs`() {
        // Two clusters at essentially the same place are one shape the slicer
        // split, not two legs. Accepting them put bones on top of each other.
        val mesh = standingFigure(
            listOf(-0.005f to -0.02f, 0.005f to 0.02f),
            legRadius = 0.05f, bodyTop = 0.4f, floor = -0.5f,
        )
        assertEquals(MeshAnalyzer.Shape.SOLID, MeshAnalyzer.analyse(mesh).shape)
    }

    @Test
    fun `the body base is measured, not assumed`() {
        val floor = -0.5f
        val bodyTop = 0.2f
        val a = MeshAnalyzer.analyse(
            standingFigure(
                listOf(-0.09f to 0.30f, 0.09f to 0.30f, -0.09f to -0.30f, 0.09f to -0.30f),
                legRadius = 0.05f, bodyTop = bodyTop, floor = floor,
            )
        )
        // The hips belong where the legs meet the body, not at a fixed fraction.
        assertTrue(
            "body base ${a.bodyBaseY} should be near where the legs end ($bodyTop)",
            a.bodyBaseY > floor + (bodyTop - floor) * 0.4f,
        )
    }

    @Test
    fun `round wheels are found and flat panels are not`() {
        val car = vehicle(wheelRadius = 0.10f, round = true)
        val analysed = MeshAnalyzer.analyse(car)
        assertEquals(MeshAnalyzer.Shape.VEHICLE, analysed.shape)
        assertEquals(4, analysed.limbs.size)

        // Spinning a flat panel sweeps it into a disc and destroys the model, so
        // a squashed cluster must not be accepted as a wheel.
        val flat = vehicle(wheelRadius = 0.10f, round = false)
        assertTrue(
            "a flat corner cluster is not a wheel",
            MeshAnalyzer.analyse(flat).shape != MeshAnalyzer.Shape.VEHICLE,
        )
    }

    // ------------------------------------------------------------------ meshes

    /** Legs as vertical cylinders under a solid body block. */
    private fun standingFigure(
        legs: List<Pair<Float, Float>>,
        legRadius: Float,
        bodyTop: Float,
        floor: Float,
    ): ModelExporter.Mesh {
        val p = mutableListOf<Float>()
        legs.forEach { (lx, lz) ->
            var y = floor
            while (y <= bodyTop) {
                for (i in 0 until 24) {
                    val t = i / 24f * 2f * Math.PI.toFloat()
                    p += lx + legRadius * cos(t)
                    p += y
                    p += lz + legRadius * sin(t)
                }
                y += 0.02f
            }
        }
        // Body: a filled slab above the legs, so the cross-section merges there.
        var y = bodyTop
        while (y <= bodyTop + 0.35f) {
            for (ix in 0..14) for (iz in 0..18) {
                p += -0.20f + 0.40f * ix / 14f
                p += y
                p += -0.45f + 0.90f * iz / 18f
            }
            y += 0.05f
        }
        return ModelExporter.Mesh(p.toFloatArray(), FloatArray(0), IntArray(0), null)
    }

    /** A single ellipsoid: nothing to attach a skeleton to. */
    private fun blob(): ModelExporter.Mesh {
        val p = mutableListOf<Float>()
        for (r in 0..40) {
            val phi = Math.PI * r / 40
            for (s in 0..40) {
                val th = 2 * Math.PI * s / 40
                p += (0.3 * sin(phi) * cos(th)).toFloat()
                p += (0.5 * cos(phi)).toFloat()
                p += (0.3 * sin(phi) * sin(th)).toFloat()
            }
        }
        return ModelExporter.Mesh(p.toFloatArray(), FloatArray(0), IntArray(0), null)
    }

    /** A flat wide body with four corner clusters, round or squashed. */
    private fun vehicle(wheelRadius: Float, round: Boolean): ModelExporter.Mesh {
        val p = mutableListOf<Float>()
        // Body slab: long, wide and shallow.
        for (ix in 0..20) for (iz in 0..30) for (iy in 0..3) {
            p += -0.28f + 0.56f * ix / 20f
            p += -0.05f + 0.16f * iy / 3f
            p += -0.50f + 1.00f * iz / 30f
        }
        for (sx in listOf(-0.26f, 0.26f)) for (sz in listOf(-0.34f, 0.34f)) {
            for (ring in 1..6) {
                val r = wheelRadius * ring / 6f
                for (i in 0 until 30) {
                    val t = i / 30f * 2f * Math.PI.toFloat()
                    p += sx + 0.03f * ((i % 3) - 1)
                    p += -0.12f + r * sin(t)
                    // A squashed cluster keeps the same footprint but no roundness.
                    p += sz + (if (round) r else r * 0.25f) * cos(t)
                }
            }
        }
        return ModelExporter.Mesh(p.toFloatArray(), FloatArray(0), IntArray(0), null)
    }
}
