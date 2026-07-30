package com.trellis.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary             = Purple60,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF3D1E7A),
    onPrimaryContainer  = Purple80,
    secondary           = Amber,
    onSecondary         = Color(0xFF3D2000),
    secondaryContainer  = AmberDim,
    onSecondaryContainer= Color(0xFFFFDF9E),
    tertiary            = Teal,
    onTertiary          = Color(0xFF003733),
    background          = BgDark,
    onBackground        = TextPrimary,
    surface             = SurfDark,
    onSurface           = TextPrimary,
    surfaceVariant      = CardDark,
    onSurfaceVariant    = TextSecondary,
    outline             = BorderDark,
    error               = Red,
    onError             = Color.White,
)

@Composable
fun TrellisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography  = Typography,
        content     = content,
    )
}
