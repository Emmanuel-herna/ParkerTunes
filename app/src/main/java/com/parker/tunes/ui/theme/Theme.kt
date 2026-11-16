package com.parker.tunes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69CFF),
    secondary = Color(0xFF8EDBD0),
    background = Color(0xFF090B12),
    surface = Color(0xFF151925),
    onPrimary = Color(0xFF171126),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun ParkerTunesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
