package com.trellis.studio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out what a generated model actually is, by measuring it.
 *
 * The rigger used to place bones at fixed fractions of the bounding box, which
 * is a guess dressed up as a measurement. On a real dog it put the rear legs
 * 0.13 units from where the rear legs are — on a body 0.99 long — so the "leg"
 * bone sat in the belly and dragged the belly with it when it moved. That is
 * what made rigging look like it was damaging the model rather than animating it.
 *
 * This finds the limbs instead. The model is sliced into horizontal bands and
 * each cross-section is clustered; a standing figure's cross-section splits into
 * one blob per leg near the floor and merges into one higher up. Measured on
 * real output, a dog splits into exactly four blobs at 10-20% height, centred at
 * x = ±0.07 and z = +0.16 / -0.37 — the actual legs, nothing like the guess.
 */
object MeshAnalyzer {

    enum class Shape {
        /** Two legs under a single torso. */
        BIPED,
        /** Four legs under a horizontal body. */
        QUADRUPED,
        /** Flat and wide, with round clusters at the low corners. */
        VEHICLE,
        /** Nothing limb-like found: a prop, a blob, or a pose that hides the legs. */
        SOLID,
    }

    /** One limb, measured rather than assumed. */
    data class Limb(
        val x: Float,
        val z: Float,
        /** Height of the ground contact and of the point where it joins the body. */
        val footY: Float,
        val hipY: Float,
        /** Half-width of the limb's cross-section, for sizing the skin falloff. */
        val radius: Float,
    )

    data class Analysis(
        val shape: Shape,
        val limbs: List<Limb>,
        val minY: Float,
        val maxY: Float,
        val centreX: Float,
        val centreZ: Float,
        val width: Float,
        val height: Float,
        val length: Float,
        /** Height at which the limbs merge into one body — where the hips belong. */
        val bodyBaseY: Float,
        /** How cleanly the limbs separated, 0..1. Low means the fit is a guess. */
        val confidence: Float,
    ) {
        val hasLimbs get() = limbs.isNotEmpty()

        /** Plain-language summary for the UI, so the user sees what was found. */
        val summary: String
            get() = when (shape) {
                Shape.BIPED -> "Two legs found — standing figure"
                Shape.QUADRUPED -> "Four legs found — animal standing on all fours"
                Shape.VEHICLE -> "${limbs.size} wheels found"
                Shape.SOLID -> "No separate limbs — one solid shape"
            }
    }

    suspend fun analyse(glb: File): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching { analyse(ModelExporter.parseGlb(glb)) }
    }

    internal fun analyse(mesh: ModelExporter.Mesh): Analysis = analyse(mesh.positions)

    /** Positions are all this needs; the rigger already has them in hand. */
    internal fun analyse(p: FloatArray): Analysis {
        require(p.size >= 9) { "Model has no geometry to analyse." }

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < p.size) {
            minX = min(minX, p[i]); maxX = max(maxX, p[i])
            minY = min(minY, p[i + 1]); maxY = max(maxY, p[i + 1])
            minZ = min(minZ, p[i + 2]); maxZ = max(maxZ, p[i + 2])
            i += 3
        }
        val width = maxX - minX
        val height = (maxY - minY).coerceAtLeast(1e-6f)
        val length = maxZ - minZ

        // Slice from just above the floor to the middle. Limbs, if there are any,
        // separate low down and merge higher — above the midpoint everything is
        // body and the split says nothing.
        val bands = (0 until BAND_COUNT).map { b ->
            val fraction = LOW_BAND + (MID_BAND - LOW_BAND) * b / (BAND_COUNT - 1f)
            fraction to cluster(p, minY + height * fraction, height * BAND_THICKNESS, width, length)
        }

        val lower = bands.take(BAND_COUNT / 2).map { it.second.size }
        val legCount = lower.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1

        // The body base is the lowest band where the limbs have merged — that is
        // where the hips actually are, rather than a fraction of the height.
        val mergeFraction = bands.firstOrNull { (f, c) ->
            f > LOW_BAND && c.size < legCount && c.size <= 2
        }?.first ?: MID_BAND
        val bodyBaseY = minY + height * mergeFraction

        // Average each limb's position across every band that found it, so one
        // noisy slice cannot move a bone.
        val limbs = buildLimbs(bands, legCount, minY, height)

        // How consistently the same number of limbs was seen. A shape that splits
        // in one slice and not the next has not really been identified.
        val agreement = if (lower.isEmpty()) 0f
        else lower.count { it == legCount }.toFloat() / lower.size

        // Wheels come from the roundness-checked detector rather than from band
        // clustering: a car's underside connects its wheels, so a horizontal
        // slice sees one blob, but the wheels are perfectly findable by shape.
        val flatAndWide = height < 0.7f * length && width > 1.05f * height
        val wheels = if (flatAndWide) findWheels(p, minX, maxX, minY, minZ, maxZ, height) else emptyList()

        // Two blobs at the same place are one shape the slicer split by noise,
        // not two legs. Real limbs stand apart.
        val separated = limbs.size >= 2 && limbs.indices.all { a ->
            limbs.indices.any { b -> b != a && distance(limbs[a], limbs[b]) > width * MIN_LIMB_GAP }
        }
        val trusted = agreement >= MIN_AGREEMENT && separated

        val shape = when {
            wheels.size >= 3 -> Shape.VEHICLE
            trusted && limbs.size == 4 -> Shape.QUADRUPED
            trusted && limbs.size == 2 -> Shape.BIPED
            else -> Shape.SOLID
        }
        val confidence = when (shape) {
            Shape.VEHICLE -> wheels.size / 4f
            Shape.SOLID -> 0f
            else -> agreement
        }

        return Analysis(
            shape = shape,
            limbs = when (shape) {
                Shape.VEHICLE -> wheels
                Shape.SOLID -> emptyList()
                else -> limbs
            },
            minY = minY, maxY = maxY,
            centreX = (minX + maxX) / 2f, centreZ = (minZ + maxZ) / 2f,
            width = width, height = height, length = length,
            bodyBaseY = bodyBaseY,
            confidence = confidence,
        )
    }

    // ------------------------------------------------------------- clustering

    private class Blob(val x: Float, val z: Float, val radius: Float, val count: Int)

    /**
     * Connected clusters of a horizontal cross-section, found on a grid.
     *
     * The same approach located wheels accurately enough to measure their
     * roundness at 0.97-1.00 on a real car, so it is reused rather than invented
     * again for limbs.
     */
    private fun cluster(
        p: FloatArray,
        y: Float,
        thickness: Float,
        width: Float,
        length: Float,
    ): List<Blob> {
        val cell = (max(width, length) / GRID).coerceAtLeast(1e-6f)
        val occupied = HashMap<Long, MutableList<Int>>()
        var i = 0
        while (i < p.size) {
            if (abs(p[i + 1] - y) <= thickness) {
                val cx = Math.floorDiv((p[i] / cell).toInt(), 1)
                val cz = Math.floorDiv((p[i + 2] / cell).toInt(), 1)
                occupied.getOrPut(key(cx, cz)) { mutableListOf() } += i
            }
            i += 3
        }
        if (occupied.size < MIN_CELLS) return emptyList()

        val seen = HashSet<Long>()
        val blobs = mutableListOf<Blob>()
        for (start in occupied.keys) {
            if (!seen.add(start)) continue
            val queue = ArrayDeque(listOf(start))
            val members = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                members += occupied[k] ?: continue
                val (kx, kz) = unkey(k)
                for (dx in -1..1) for (dz in -1..1) {
                    val n = key(kx + dx, kz + dz)
                    if (occupied.containsKey(n) && seen.add(n)) queue.addLast(n)
                }
            }
            // A handful of stray points is noise, not a limb.
            if (members.size < MIN_POINTS) continue
            var sx = 0.0; var sz = 0.0
            members.forEach { sx += p[it]; sz += p[it + 2] }
            val cx = (sx / members.size).toFloat()
            val cz = (sz / members.size).toFloat()
            var radius = 0f
            members.forEach {
                val dx = p[it] - cx; val dz = p[it + 2] - cz
                radius = max(radius, sqrt(dx * dx + dz * dz))
            }
            blobs += Blob(cx, cz, radius, members.size)
        }
        return blobs.sortedByDescending { it.count }
    }

    /**
     * Matches blobs across bands into limbs. A limb keeps roughly the same x and
     * z from one slice to the next, so nearest-position matching is enough — and
     * it means the final position is an average over the whole limb rather than
     * one arbitrary slice.
     */
    private fun buildLimbs(
        bands: List<Pair<Float, List<Blob>>>,
        expected: Int,
        minY: Float,
        height: Float,
    ): List<Limb> {
        if (expected !in 2..4) return emptyList()

        // Seed from the lowest band that actually shows the expected count.
        val seedBand = bands.firstOrNull { it.second.size == expected } ?: return emptyList()
        val tracks = seedBand.second.map { mutableListOf(seedBand.first to it) }

        bands.forEach { (fraction, blobs) ->
            if (fraction <= seedBand.first || blobs.size != expected) return@forEach
            val taken = BooleanArray(blobs.size)
            tracks.forEach { track ->
                val last = track.last().second
                var best = -1
                var bestDistance = Float.MAX_VALUE
                blobs.forEachIndexed { index, b ->
                    if (taken[index]) return@forEachIndexed
                    val d = (b.x - last.x) * (b.x - last.x) + (b.z - last.z) * (b.z - last.z)
                    if (d < bestDistance) { bestDistance = d; best = index }
                }
                if (best >= 0) { taken[best] = true; track += fraction to blobs[best] }
            }
        }

        return tracks.map { track ->
            val x = track.map { it.second.x }.average().toFloat()
            val z = track.map { it.second.z }.average().toFloat()
            val radius = track.map { it.second.radius }.average().toFloat()
            Limb(
                x = x, z = z,
                footY = minY,
                hipY = minY + height * track.maxOf { it.first },
                radius = radius,
            )
        }
    }

    private fun distance(a: Limb, b: Limb): Float {
        val dx = a.x - b.x; val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    /**
     * Finds wheels by fitting a circle in each low corner and keeping only the
     * ones that are actually round.
     *
     * Spinning a flat panel sweeps it into a disc and destroys the model, so
     * roundness is the gate: measured on real output a car's wheels come out
     * 0.97-1.00 round and a spaceship's false positives 0.32-0.44.
     */
    internal fun findWheels(
        p: FloatArray,
        minX: Float, maxX: Float,
        minY: Float,
        minZ: Float, maxZ: Float,
        height: Float,
    ): List<Limb> {
        val centreX = (minX + maxX) / 2f
        val centreZ = (minZ + maxZ) / 2f
        val band = minY + height * WHEEL_BAND
        val found = mutableListOf<Limb>()

        for (zFront in listOf(true, false)) for (xRight in listOf(false, true)) {
            val quadrant = ArrayList<Int>()
            val seed = ArrayList<Int>()
            var i = 0
            while (i < p.size) {
                if ((p[i + 2] > centreZ) == zFront && (p[i] > centreX) == xRight) {
                    quadrant += i
                    if (p[i + 1] <= band) seed += i
                }
                i += 3
            }
            if (seed.size < 24) continue

            // How tall the corner cluster is, kept for the collapse check below.
            var sy = 0.0
            seed.forEach { sy += p[it + 1] }
            val seedY = (sy / seed.size).toFloat()
            var spanY = 0f
            seed.forEach { spanY = max(spanY, abs(p[it + 1] - seedY)) }
            if (spanY <= 0f) continue

            var cy = seed.sumOf { p[it + 1].toDouble() }.toFloat() / seed.size
            var cz = seed.sumOf { p[it + 2].toDouble() }.toFloat() / seed.size
            var radius = 0f
            var source: List<Int> = seed
            repeat(REFIT_PASSES) {
                val sorted = source.map {
                    val dy = p[it + 1] - cy; val dz = p[it + 2] - cz
                    sqrt(dy * dy + dz * dz)
                }.sorted()
                radius = sorted[(sorted.size * 3) / 4].coerceAtLeast(1e-4f)
                val near = quadrant.filter {
                    val dy = p[it + 1] - cy; val dz = p[it + 2] - cz
                    sqrt(dy * dy + dz * dz) <= radius * 1.15f
                }
                if (near.size < 12) return@repeat
                cy = near.sumOf { p[it + 1].toDouble() }.toFloat() / near.size
                cz = near.sumOf { p[it + 2].toDouble() }.toFloat() / near.size
                source = near
            }

            // A flat cluster has no circle to find, so the fit collapses onto the
            // thin part of it — and inside that sliver everything looks round.
            // The giveaway is the fitted radius against the cluster's real height:
            // measured, a genuine wheel comes out at 1.17-1.39 of it and a flat
            // panel at 0.31.
            if (radius < spanY * MIN_FIT_RATIO) continue

            val ring = quadrant.filter {
                val dy = p[it + 1] - cy; val dz = p[it + 2] - cz
                sqrt(dy * dy + dz * dz) <= radius * 1.15f
            }
            if (ring.size < 12) continue
            val cx = ring.sumOf { p[it].toDouble() }.toFloat() / ring.size

            // Roundness is measured on the vertices that will actually be spun —
            // inside the trimmed radius and inside the wheel's own width. Measuring
            // the wider search ring let neighbouring bodywork fill in the missing
            // side and make a flat cluster look round.
            var halfWidth = 0f
            ring.forEach { halfWidth = max(halfWidth, abs(p[it] - cx)) }
            val claimed = ring.filter {
                val dy = p[it + 1] - cy; val dz = p[it + 2] - cz
                sqrt(dy * dy + dz * dz) <= radius * WHEEL_TRIM && abs(p[it] - cx) <= halfWidth
            }
            if (claimed.size < 12) continue

            var ry = 0f; var rz = 0f
            claimed.forEach {
                ry = max(ry, abs(p[it + 1] - cy)); rz = max(rz, abs(p[it + 2] - cz))
            }
            val roundness = if (max(ry, rz) <= 0f) 0f else min(ry, rz) / max(ry, rz)
            val share = claimed.size.toFloat() / (p.size / 3)
            if (roundness < MIN_ROUNDNESS || share > MAX_WHEEL_SHARE) continue

            found += Limb(x = cx, z = cz, footY = cy - radius, hipY = cy, radius = radius * WHEEL_TRIM)
        }
        return found
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
    private fun unkey(k: Long): Pair<Int, Int> = (k shr 32).toInt() to k.toInt()

    // Bands run from just off the floor to the midpoint: limbs separate below
    // that and everything above is body.
    private const val LOW_BAND = 0.06f
    private const val MID_BAND = 0.50f
    private const val BAND_COUNT = 12
    private const val BAND_THICKNESS = 0.025f
    private const val GRID = 24
    private const val MIN_CELLS = 3
    private const val MIN_POINTS = 12
    /** Limbs must agree across most slices, and stand apart from each other. */
    private const val MIN_AGREEMENT = 0.6f
    private const val MIN_LIMB_GAP = 0.15f
    // Wheel fitting, matched to what worked on real vehicles.
    private const val WHEEL_BAND = 0.38f
    private const val REFIT_PASSES = 4
    private const val WHEEL_TRIM = 0.88f
    private const val MIN_ROUNDNESS = 0.75f
    private const val MAX_WHEEL_SHARE = 0.18f
    /** Fitted radius against the cluster's height — catches a collapsed fit. */
    private const val MIN_FIT_RATIO = 0.5f
}
