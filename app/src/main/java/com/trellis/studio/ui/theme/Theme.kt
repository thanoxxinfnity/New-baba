package com.trellis.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClaudeOrange    = Color(0xFFCF7A4F)
private val ClaudeOrangeDim = Color(0xFF7A3E1A)
private val ClaudeGold      = Color(0xFFD4A25A)

private val DarkColors = darkColorScheme(
    primary             = ClaudeOrange,
    onPrimary           = Color(0xFF1A0A00),
    primaryContainer    = ClaudeOrangeDim,
    onPrimaryContainer  = Color(0xFFFFDDC8),
    secondary           = ClaudeGold,
    onSecondary         = Color(0xFF1A0E00),
    secondaryContainer  = Color(0xFF3D2600),
    onSecondaryContainer= Color(0xFFFFDFA8),
    tertiary            = Color(0xFF7CB9A8),
    onTertiary          = Color(0xFF00201A),
    background          = Color(0xFF111111),
    onBackground        = Color(0xFFECE8E3),
    surface             = Color(0xFF1C1C1C),
    onSurface           = Color(0xFFECE8E3),
    surfaceVariant      = Color(0xFF2A2A2A),
    onSurfaceVariant    = Color(0xFFB8B0A8),
    surfaceTint         = ClaudeOrange,
    error               = Color(0xFFFFB4AB),
    onError             = Color(0xFF690005),
    errorContainer      = Color(0xFF93000A),
    onErrorContainer    = Color(0xFFFFDAD6),
    outline             = Color(0xFF3D3830),
    outlineVariant      = Color(0xFF2A2520)
)

@Composable
fun TrellisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
