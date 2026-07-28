package com.trellis.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Color palette ─────────────────────────────────────────────────────────────
val NimOrange      = Color(0xFFE07B39)
val NimOrangeDim   = Color(0xFF7A3A12)
val NimBlue        = Color(0xFF4A9EFF)
val NimGreen       = Color(0xFF3FB950)
val NimBg          = Color(0xFF080C10)       // near-black
val NimSurface     = Color(0xFF0F1923)       // dark navy
val NimSurfaceVar  = Color(0xFF1A2535)       // lighter navy
val NimOutline     = Color(0xFF2D3F55)
val NimText        = Color(0xFFEAF0FA)
val NimMuted       = Color(0xFF7A8FA8)
val NimError       = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary              = NimOrange,
    onPrimary            = Color(0xFF1A0700),
    primaryContainer     = NimOrangeDim,
    onPrimaryContainer   = Color(0xFFFFDCC8),
    secondary            = NimBlue,
    onSecondary          = Color(0xFF00143A),
    secondaryContainer   = Color(0xFF0D2445),
    onSecondaryContainer = Color(0xFFD0E8FF),
    tertiary             = NimGreen,
    onTertiary           = Color(0xFF002210),
    background           = NimBg,
    onBackground         = NimText,
    surface              = NimSurface,
    onSurface            = NimText,
    surfaceVariant       = NimSurfaceVar,
    onSurfaceVariant     = NimMuted,
    error                = NimError,
    onError              = Color(0xFF2D0000),
    errorContainer       = Color(0xFF4A0000),
    onErrorContainer     = Color(0xFFFFCDD0),
    outline              = NimOutline,
    outlineVariant       = Color(0xFF1E2D40)
)

@Composable
fun TrellisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
