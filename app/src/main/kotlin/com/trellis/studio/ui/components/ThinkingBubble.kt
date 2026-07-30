package com.trellis.studio.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trellis.studio.ui.theme.*

/**
 * The live "thinking" bubble.
 *
 * While [streaming] is true it shows a pulsing indicator, a shimmering label and
 * the reasoning tokens as they arrive. When streaming ends it collapses to a
 * compact "Thought for Ns" chip that can be tapped to reopen.
 */
@Composable
fun ThinkingBubble(
    reasoning: String,
    streaming: Boolean,
    elapsedSeconds: Int,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank() && !streaming) return

    // Auto-expanded while thinking, auto-collapsed once the answer starts.
    var userToggled by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: streaming

    val scroll = rememberScrollState()
    LaunchedEffect(reasoning.length, streaming) {
        if (streaming && expanded) scroll.animateScrollTo(scroll.maxValue)
    }

    val glow = if (streaming) Cyan else Purple60

    Column(
        modifier
            .widthIn(max = 330.dp)
            .glass(
                shape = RoundedCornerShape(18.dp),
                fill = GlassFill,
                border = if (streaming) GlassBorderLit else GlassBorder,
                glow = if (streaming) glow else null,
            )
            .clickable { userToggled = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // ---- Header ----------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (streaming) {
                PingDot(color = Cyan, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                // Shimmering status label
                Text(
                    "THINKING",
                    style = TextStyle(
                        brush = shimmerBrush(base = Cyan),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OrbitingSpinner()
            } else {
                Icon(
                    Icons.Default.Psychology,
                    null,
                    tint = Purple60,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (elapsedSeconds > 0) "Thought for ${elapsedSeconds}s" else "Thoughts",
                    style = MaterialTheme.typography.labelMedium.copy(
                        brush = NeonBrushSoft,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = TextDisabled,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // ---- Body ------------------------------------------------------
        AnimatedVisibility(
            visible = expanded && reasoning.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(max = 180.dp).verticalScroll(scroll)) {
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        ),
                        color = TextSecondary,
                    )
                }
            }
        }

        // Collapsed preview of the newest thought, so something is always moving.
        AnimatedVisibility(visible = !expanded && streaming && reasoning.isNotBlank()) {
            Text(
                reasoning.takeLast(90),
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Two counter-rotating arcs — the "neural process" spinner. */
@Composable
private fun OrbitingSpinner(size: androidx.compose.ui.unit.Dp = 13.dp) {
    val t = rememberInfiniteTransition(label = "orbit")
    val angle = t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "orbitAngle",
    ).value

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(size).rotate(angle),
            color = Cyan,
            strokeWidth = 1.5.dp,
        )
    }
}

/**
 * Streaming answer text with a blinking caret, so the reply visibly types out.
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    onCopyFeedback: (String) -> Unit = {},
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f, fill = false)) {
            MarkdownText(text = text, onCopyFeedback = onCopyFeedback)
        }
        if (streaming) {
            val alpha = rememberPulse(min = 0f, max = 1f, periodMs = 600)
            Box(
                Modifier
                    .padding(start = 3.dp, bottom = 3.dp)
                    .size(width = 7.dp, height = 15.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Cyan.copy(alpha = alpha))
            )
        }
    }
}

/** Glowing three-dot indicator shown before the first token arrives. */
@Composable
fun NeuralTypingIndicator() {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Purple40, Cyan.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("T", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .glass(shape = RoundedCornerShape(16.dp), glow = Cyan)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val t = rememberInfiniteTransition(label = "dots")
            repeat(3) { i ->
                val a = t.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, delayMillis = i * 160, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                ).value
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(Cyan.copy(alpha = a))
                )
            }
        }
    }
}
