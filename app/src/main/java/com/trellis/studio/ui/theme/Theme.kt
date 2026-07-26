package com.trellis.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF9F8BFF)
private val PurpleDim = Color(0xFF4E3FD0)
private val Teal = Color(0xFF4FD8C4)

private val DarkColors = darkColorScheme(
    primary = Purple,
    onPrimary = Color(0xFF14082E),
    primaryContainer = PurpleDim,
    onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Teal,
    onSecondary = Color(0xFF00251F),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFE4E1EA),
    surface = Color(0xFF15161D),
    onSurface = Color(0xFFE4E1EA),
    surfaceVariant = Color(0xFF23242E),
    onSurfaceVariant = Color(0xFFB9B6C5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF3A3B47)
)

@Composable
fun TrellisTheme(content: @Composable () -> Unit) {
    // The app is dark-themed by design, regardless of system setting.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
