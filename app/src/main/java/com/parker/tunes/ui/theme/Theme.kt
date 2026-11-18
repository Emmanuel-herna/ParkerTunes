package com.parker.tunes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69CFF),
    secondary = Color.White,
    background = Color.Black,
    surface = Color(0xFF101010),
    surfaceVariant = Color(0xFF171717),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF303030)
)

@Composable
fun ParkerTunesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
