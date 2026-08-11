package com.trellis.studio.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.theme.Cyan
import com.trellis.studio.ui.theme.Pink
import com.trellis.studio.ui.theme.Purple60
import com.trellis.studio.ui.theme.Teal
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Small celebratory + "aura" animations shared across VOID: a confetti burst
 * when something finishes, and a smoothly counting number. Kept dependency-free
 * (pure Compose) so they run anywhere in the app.
 */

private data class Confetto(
    val angle: Float,
    val speed: Float,
    val color: Color,
    val size: Float,
    val spin: Float,
)

/**
 * A one-shot confetti burst from the centre. Drop it in a Box overlay and flip
 * [play] to true to fire it; it fades out on its own.
 */
@Composable
fun ConfettiBurst(play: Boolean, modifier: Modifier = Modifier) {
    if (!play) return
    val palette = listOf(Cyan, Pink, Purple60, Teal, Color(0xFFFFD166))
    val pieces = remember(play) {
        List(80) {
            Confetto(
                angle = Random.nextFloat() * 6.2832f,
                speed = 0.35f + Random.nextFloat() * 0.75f,
                color = palette[Random.nextInt(palette.size)],
                size = 6f + Random.nextFloat() * 10f,
                spin = (Random.nextFloat() - 0.5f) * 20f,
            )
        }
    }
    val t = remember(play) { Animatable(0f) }
    LaunchedEffect(play) { t.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing)) }

    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height * 0.42f
        val p = t.value
        pieces.forEach { c ->
            val dist = c.speed * p * size.minDimension * 0.9f
            val x = cx + cos(c.angle) * dist
            val y = cy + sin(c.angle) * dist + p * p * 500f   // gravity
            val alpha = (1f - p).coerceIn(0f, 1f)
            drawCircle(
                color = c.color.copy(alpha = alpha),
                radius = c.size * (1f - p * 0.4f),
                center = Offset(x, y),
            )
        }
    }
}

/** An integer that animates up to [value] whenever it changes — for stat tiles. */
@Composable
fun animatedCount(value: Int, durationMs: Int = 700): Int {
    val anim by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "count",
    )
    return anim
}

/** A slow breathing alpha for "aura" glows behind hero elements. */
@Composable
fun rememberAura(periodMs: Int = 2600): Float {
    val transition = rememberInfiniteTransition(label = "aura")
    val a by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auraAlpha",
    )
    return a
}
