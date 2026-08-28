package com.trellis.studio.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Glassmorphic surface tokens.
//
// Compose has no CSS backdrop-filter, so the frosted look is built from a
// translucent fill + a hairline light border + a soft ambient glow, which
// reads the same over the app's dark background.
// ---------------------------------------------------------------------------

val GlassFill      = Color(0xFF161622).copy(alpha = 0.72f)
val GlassFillHigh  = Color(0xFF1E1E2E).copy(alpha = 0.85f)
val GlassBorder    = Color.White.copy(alpha = 0.10f)
val GlassBorderLit = Cyan.copy(alpha = 0.45f)

/** Neon accent gradient used for headings and active strokes. */
val NeonBrush = Brush.linearGradient(listOf(Cyan, Purple60, Pink))
val NeonBrushSoft = Brush.linearGradient(
    listOf(Cyan.copy(alpha = 0.85f), Purple60.copy(alpha = 0.85f)),
)

/**
 * Frosted card: translucent fill, 1px translucent border, ambient colour glow.
 * @param glow accent colour bled behind the card; null disables the glow.
 */
fun Modifier.glass(
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    fill: Color = GlassFill,
    border: Color = GlassBorder,
    glow: Color? = null,
    glowRadius: Dp = 26.dp,
): Modifier = this
    .then(
        if (glow != null) Modifier.drawBehind {
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.75f,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(glowRadius.toPx()),
            )
        } else Modifier
    )
    .clip(shape)
    .background(fill)
    .border(1.dp, border, shape)

/** Gradient hairline border, for the "glowing edge" look on active elements. */
fun Modifier.neonBorder(
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    brush: Brush = NeonBrush,
    width: Dp = 1.dp,
): Modifier = this.border(BorderStroke(width, brush), shape)

// ---------------------------------------------------------------------------
// Animation helpers
// ---------------------------------------------------------------------------

/** Slow breathing alpha, for "live" indicators. */
@Composable
fun rememberPulse(
    min: Float = 0.35f,
    max: Float = 1f,
    periodMs: Int = 1400,
): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    ).value
}

/**
 * Travelling highlight offset for shimmer effects, in px along the x axis.
 * Feed into a linear gradient's start/end.
 */
@Composable
fun rememberShimmer(widthPx: Float = 900f, periodMs: Int = 1600): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = -widthPx,
        targetValue = widthPx,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    ).value
}

/** Shimmering text brush that sweeps a bright band across the accent gradient. */
@Composable
fun shimmerBrush(base: Color = Cyan, highlight: Color = Color.White): Brush {
    val x = rememberShimmer()
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + 420f, 0f),
    )
}

/**
 * The expanding halo behind a live status dot — the Compose equivalent of
 * Tailwind's animate-ping.
 */
@Composable
fun PingDot(
    color: Color = Cyan,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "ping")
    val scale = transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "pingScale",
    ).value
    val alpha = transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "pingAlpha",
    ).value

    androidx.compose.foundation.layout.Box(
        modifier.size(size * 2.4f),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(size).scale(scale).clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        androidx.compose.foundation.layout.Box(
            Modifier.size(size).clip(CircleShape).background(color)
        )
    }
}
