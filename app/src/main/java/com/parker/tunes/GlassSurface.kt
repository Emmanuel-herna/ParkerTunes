package com.parker.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * A reusable frosted surface. Haze supplies the real backdrop blur on supported Android
 * versions; its fallbackTint keeps the surface translucent when backdrop blur is unavailable.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.liquidGlass(
    hazeState: HazeState,
    blurRadius: Dp,
    tintAlpha: Float,
    shape: Shape
): Modifier {
    val safeAlpha = tintAlpha.coerceIn(0.08f, 0.45f)
    val tint = HazeTint(Color.White.copy(alpha = safeAlpha))
    val style = HazeStyle(
        backgroundColor = Color.Transparent,
        tint = tint,
        blurRadius = blurRadius,
        noiseFactor = 0.10f,
        fallbackTint = tint
    )
    return this
        .clip(shape)
        .hazeChild(state = hazeState, style = style)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = (safeAlpha * 0.72f).coerceAtMost(0.30f)),
                    Color.White.copy(alpha = safeAlpha * 0.20f),
                    Color.Black.copy(alpha = safeAlpha * 0.30f)
                )
            )
        )
        .border(
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent
                    )
                )
            ),
            shape
        )
}

@Composable
fun GlassIntensityPreview(intensity: Int) {
    val fraction = intensity.coerceIn(0, 100) / 100f
    val safeAlpha = (0.08f + 0.37f * fraction).coerceIn(0.08f, 0.45f)
    val shape = RoundedCornerShape(18.dp)

    // This is only a settings preview. Use a static frosted sample instead of
    // running a second live backdrop-blur pipeline while the settings list scrolls.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF6B4C9A),
                        Color(0xFF286D83),
                        Color(0xFFB66B6B),
                        Color(0xFF25213A)
                    )
                )
            )
    ) {
        Box(
            Modifier
                .size(56.dp)
                .align(Alignment.CenterStart)
                .offset(x = 22.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.24f))
        )
        Box(
            Modifier
                .size(68.dp)
                .align(Alignment.CenterEnd)
                .offset(x = (-24).dp)
                .clip(CircleShape)
                .background(Color(0xFFFFC5A8).copy(alpha = 0.55f))
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.90f)
                .height(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = safeAlpha))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.22f))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ParkerTunes", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text("Liquid glass preview", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelSmall)
            }
            Text("▶", color = Color.White)
        }
    }
}
