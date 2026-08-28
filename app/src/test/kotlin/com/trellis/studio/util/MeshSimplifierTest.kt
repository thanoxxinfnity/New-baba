package com.trellis.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reduction has to hit the budget without breaking the model. These check the
 * two things a renderer cannot survive — indices pointing past the vertex array
 * and degenerate triangles — as well as the shape surviving recognisably.
 */
class MeshSimplifierTest {

    @Test
    fun `a dense mesh comes down to the budget`() {
        val sphere = sphere(rings = 80, segments = 80)
        assertTrue("test mesh should start dense", sphere.triangleCount > 10_000)

        for (target in listOf(6_000, 2_000, 800)) {
            val reduced = MeshSimplifier.reduce(sphere, target)
            assertTrue(
                "asked for $target, got ${reduced.triangleCount}",
                reduced.triangleCount <= target,
            )
            // A budget met by deleting almost everything is not a reduction.
            assertTrue(
                "$target left only ${reduced.triangleCount} triangles",
                reduced.triangleCount > target / 4,
            )
        }
    }

    @Test
    fun `every index stays inside the vertex array`() {
        val reduced = MeshSimplifier.reduce(sphere(60, 60), 3_000)
        reduced.indices.forEach {
            assertTrue("index $it is past ${reduced.vertexCount} vertices", it in 0 until reduced.vertexCount)
        }
        assertEquals("indices must come in whole triangles", 0, reduced.indices.size % 3)
    }

    @Test
    fun `no degenerate triangles survive`() {
        val reduced = MeshSimplifier.reduce(sphere(60, 60), 3_000)
        var t = 0
        while (t < reduced.indices.size) {
            val a = reduced.indices[t]; val b = reduced.indices[t + 1]; val c = reduced.indices[t + 2]
            assertTrue("triangle ${t / 3} repeats a corner", a != b && b != c && a != c)
            t += 3
        }
    }

    @Test
    fun `the shape is preserved`() {
        val sphere = sphere(70, 70)
        val reduced = MeshSimplifier.reduce(sphere, 2_000)

        fun bounds(m: ModelExporter.Mesh): FloatArray {
            val out = floatArrayOf(
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
            )
            var i = 0
            while (i < m.positions.size) {
                for (k in 0..2) {
                    out[k] = minOf(out[k], m.positions[i + k])
                    out[k + 3] = maxOf(out[k + 3], m.positions[i + k])
                }
                i += 3
            }
            return out
        }

        val before = bounds(sphere)
        val after = bounds(reduced)
        for (k in 0..5) {
            assertTrue(
                "axis $k moved from ${before[k]} to ${after[k]}",
                abs(before[k] - after[k]) < 0.12f,
            )
        }
    }

    @Test
    fun `uvs are kept per vertex and never averaged`() {
        val sphere = sphere(50, 50)
        val reduced = MeshSimplifier.reduce(sphere, 2_000)

        assertEquals(
            "one uv pair per surviving vertex",
            reduced.vertexCount * 2, reduced.uvs.size,
        )
        // Every surviving uv must be one that existed in the original: an averaged
        // uv would fall between seams and drag the wrong part of the texture in.
        val original = buildSet {
            var i = 0
            while (i < sphere.uvs.size) { add(sphere.uvs[i] to sphere.uvs[i + 1]); i += 2 }
        }
        var i = 0
        while (i < reduced.uvs.size) {
            assertTrue(
                "uv (${reduced.uvs[i]}, ${reduced.uvs[i + 1]}) was invented",
                (reduced.uvs[i] to reduced.uvs[i + 1]) in original,
            )
            i += 2
        }
    }

    @Test
    fun `a mesh already under budget is returned untouched`() {
        val small = sphere(8, 8)
        assertTrue(small.triangleCount < 5_000)
        val reduced = MeshSimplifier.reduce(small, 5_000)
        assertTrue("should be the same object", reduced === small)
    }

    @Test
    fun `the rewritten glb round-trips`() {
        val reduced = MeshSimplifier.reduce(sphere(60, 60), 2_000)
        val bytes = MeshSimplifier.writeGlb(reduced.copy(texturePng = fakePng()))

        assertEquals("glTF", String(bytes, 0, 4))
        assertEquals("header length must match the file", bytes.size, le32(bytes, 8))

        // Reading it back with the app's own parser is the real proof.
        val file = java.io.File.createTempFile("reduced", ".glb").apply {
            writeBytes(bytes); deleteOnExit()
        }
        val reread = ModelExporter.parseGlb(file)
        assertEquals(reduced.vertexCount, reread.vertexCount)
        assertEquals(reduced.triangleCount, reread.triangleCount)
        assertNotNull("the texture must survive the rewrite", reread.texturePng)
        assertTrue(
            "positions must be unchanged",
            reduced.positions.zip(reread.positions.toTypedArray()).all { abs(it.first - it.second) < 1e-5f },
        )
    }

    @Test
    fun `an index count above 65535 vertices still writes correctly`() {
        // 16-bit indices would silently wrap here, which corrupts the mesh in a
        // way that only shows up in an engine.
        val big = sphere(300, 300)
        assertTrue("need more than 65535 vertices", big.vertexCount > 65_535)
        val bytes = MeshSimplifier.writeGlb(big)
        val file = java.io.File.createTempFile("big", ".glb").apply {
            writeBytes(bytes); deleteOnExit()
        }
        val reread = ModelExporter.parseGlb(file)
        assertEquals(big.vertexCount, reread.vertexCount)
        assertEquals(big.triangleCount, reread.triangleCount)
        assertTrue(reread.indices.max() == big.indices.max())
    }

    @Test
    fun `smooth normals are unit length and point outwards`() {
        val sphere = sphere(40, 40)
        val normals = MeshSimplifier.smoothNormals(sphere)
        assertEquals(sphere.vertexCount * 3, normals.size)

        var worstLength = 0f
        var worstAlignment = 1f
        for (v in 0 until sphere.vertexCount) {
            val nx = normals[v * 3]; val ny = normals[v * 3 + 1]; val nz = normals[v * 3 + 2]
            worstLength = maxOf(worstLength, abs(kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) - 1f))
            // On a unit sphere centred at the origin the normal is the position,
            // which makes this checkable exactly rather than by eye.
            val px = sphere.positions[v * 3]; val py = sphere.positions[v * 3 + 1]; val pz = sphere.positions[v * 3 + 2]
            val len = kotlin.math.sqrt(px * px + py * py + pz * pz)
            if (len > 0.01f) {
                worstAlignment = minOf(worstAlignment, (nx * px + ny * py + nz * pz) / len)
            }
        }
        assertTrue("normals must be unit length, off by $worstLength", worstLength < 1e-3f)
        assertTrue("normals must face outwards, worst dot $worstAlignment", worstAlignment > 0.98f)
    }

    @Test
    fun `normals smooth across duplicated vertices`() {
        // A UV seam duplicates a position. Both copies must end up with the same
        // normal, or the seam shows as a hard lighting crease.
        val sphere = sphere(30, 30)
        val normals = MeshSimplifier.smoothNormals(sphere)

        val byPosition = HashMap<String, Int>()
        var compared = 0
        for (v in 0 until sphere.vertexCount) {
            val key = "%.4f,%.4f,%.4f".format(
                sphere.positions[v * 3], sphere.positions[v * 3 + 1], sphere.positions[v * 3 + 2],
            )
            val first = byPosition.put(key, v) ?: continue
            compared++
            for (k in 0..2) {
                assertTrue(
                    "duplicate at $key got different normals",
                    abs(normals[v * 3 + k] - normals[first * 3 + k]) < 1e-5f,
                )
            }
        }
        assertTrue("the test mesh should contain duplicated positions", compared > 0)
    }

    @Test
    fun `tangents are unit length, perpendicular, and signed`() {
        val sphere = sphere(30, 30)
        val normals = MeshSimplifier.smoothNormals(sphere)
        val tangents = MeshSimplifier.tangents(sphere, normals)
        assertEquals(sphere.vertexCount * 4, tangents.size)

        for (v in 0 until sphere.vertexCount) {
            val tx = tangents[v * 4]; val ty = tangents[v * 4 + 1]; val tz = tangents[v * 4 + 2]
            val w = tangents[v * 4 + 3]
            val len = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
            assertTrue("tangent $v is not unit length ($len)", abs(len - 1f) < 1e-3f)
            assertTrue("handedness must be +1 or -1, got $w", w == 1f || w == -1f)
            val dot = tx * normals[v * 3] + ty * normals[v * 3 + 1] + tz * normals[v * 3 + 2]
            assertTrue("tangent $v is not perpendicular to its normal ($dot)", abs(dot) < 1e-3f)
        }
    }

    @Test
    fun `normals and tangents survive the glb round trip`() {
        val sphere = sphere(30, 30)
        val normals = MeshSimplifier.smoothNormals(sphere)
        val tangents = MeshSimplifier.tangents(sphere, normals)
        val bytes = MeshSimplifier.writeGlb(sphere, normals, tangents)

        val json = String(bytes, 20, le32(bytes, 12))
        assertTrue("NORMAL must be declared", json.contains("\"NORMAL\""))
        assertTrue("TANGENT must be declared", json.contains("\"TANGENT\""))

        val file = java.io.File.createTempFile("shaded", ".glb").apply {
            writeBytes(bytes); deleteOnExit()
        }
        val reread = ModelExporter.parseGlb(file)
        assertEquals("adding attributes must not disturb the mesh", sphere.vertexCount, reread.vertexCount)
        assertEquals(sphere.triangleCount, reread.triangleCount)
    }

    @Test
    fun `the rewritten material is not metal`() {
        // Generated models omit metallicFactor, which glTF defaults to 1.0 — a
        // rough metal has no diffuse response, so the model renders dark and
        // colourless whatever its texture says.
        val bytes = MeshSimplifier.writeGlb(sphere(10, 10).copy(texturePng = fakePng()))
        val json = String(bytes, 20, le32(bytes, 12))
        assertTrue("metallicFactor must be written explicitly", json.contains("metallicFactor"))
        assertTrue("and it must be 0", json.contains("\"metallicFactor\":0"))
        assertTrue("the texture must drive base colour", json.contains("baseColorTexture"))
    }

    // ------------------------------------------------------------------ helpers

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    /** A UV-mapped sphere: dense, closed, and easy to check the shape of. */
    private fun sphere(rings: Int, segments: Int): ModelExporter.Mesh {
        val positions = ArrayList<Float>((rings + 1) * (segments + 1) * 3)
        val uvs = ArrayList<Float>((rings + 1) * (segments + 1) * 2)
        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            for (s in 0..segments) {
                val theta = 2 * Math.PI * s / segments
                positions += (sin(phi) * cos(theta)).toFloat()
                positions += cos(phi).toFloat()
                positions += (sin(phi) * sin(theta)).toFloat()
                uvs += s.toFloat() / segments
                uvs += r.toFloat() / rings
            }
        }
        val indices = ArrayList<Int>(rings * segments * 6)
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = r * (segments + 1) + s
                val b = a + segments + 1
                // Counter-clockwise seen from outside, which is what glTF means by
                // front-facing — the other order makes every normal point inwards.
                indices += a; indices += a + 1; indices += b
                indices += a + 1; indices += b + 1; indices += b
            }
        }
        return ModelExporter.Mesh(
            positions.toFloatArray(), uvs.toFloatArray(), indices.toIntArray(), null,
        )
    }

    /** Smallest valid PNG, so the texture path is exercised without a real image. */
    private fun fakePng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        0, 0, 0, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0, 1, 0, 0, 5, 0, 1,
        0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
