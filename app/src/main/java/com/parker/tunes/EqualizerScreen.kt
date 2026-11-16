package com.parker.tunes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parker.tunes.player.EqualizerManager
import com.parker.tunes.player.EqualizerState

@Composable
fun EqualizerScreen(
    manager: EqualizerManager?,
    onBack: () -> Unit,
    onChanged: (EqualizerState) -> Unit
) {
    val stateFlow = remember(manager) { manager?.state }
    val state by stateFlow?.collectAsState() ?: remember { mutableStateOf(EqualizerState()) }
    val persist: () -> Unit = { manager?.state?.value?.let(onChanged) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Equalizer", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Shape your sound", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f), fontSize = 12.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }

        if (manager == null || !state.supported) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Equalizer unavailable", fontWeight = FontWeight.SemiBold)
                Text("This device or its current audio output does not expose compatible audio effects. The option will become available when a supported player audio session is active.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), fontSize = 13.sp)
            }
        } else {
            EqualizerCurve(state)
            Text("Presets", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = state.preset == -1, onClick = {
                        manager.selectPreset(0)
                        manager.state.value.let { current ->
                            current.bandLevels.indices.forEach { manager.setBand(it, current.bandLevels[it]) }
                        }
                        persist()
                    }, label = { Text("Custom") })
                }
                itemsIndexed(state.presetNames) { index, name ->
                    FilterChip(selected = state.preset == index, onClick = {
                        manager.selectPreset(index)
                        persist()
                    }, label = { Text(name) })
                }
            }

            Text("Frequency bands", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.bandLevels.forEachIndexed { index, level ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(state.bandLabels.getOrNull(index).orEmpty(), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), maxLines = 1)
                        Box(Modifier.width(48.dp).height(158.dp), contentAlignment = Alignment.Center) {
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { manager.setBand(index, it.toInt()); persist() },
                                valueRange = state.minLevel.toFloat()..state.maxLevel.toFloat(),
                                modifier = Modifier.width(150.dp).height(42.dp).graphicsLayer { rotationZ = -90f },
                                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text("%+.1f dB".format(level / 100f), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), maxLines = 1)
                    }
                }
            }

            Text("Sound enhancements", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            EffectControl(
                title = "Bass boost", enabled = state.bassEnabled, strength = state.bassStrength,
                onEnabled = { manager.setBass(enabled = it); persist() },
                onStrength = { manager.setBass(strength = it); persist() }
            )
            EffectControl(
                title = "Virtualizer", enabled = state.virtualizerEnabled, strength = state.virtualizerStrength,
                onEnabled = { manager.setVirtualizer(enabled = it); persist() },
                onStrength = { manager.setVirtualizer(strength = it); persist() }
            )
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Loudness enhancer", fontWeight = FontWeight.SemiBold)
                        Text("Increase perceived output level", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 11.sp)
                    }
                    Switch(checked = state.loudnessEnabled, onCheckedChange = {
                        manager.setLoudness(enabled = it); persist()
                    })
                }
                if (state.loudnessEnabled) {
                    Text("Gain: ${state.loudnessGain / 100f} dB", fontSize = 12.sp)
                    Slider(value = state.loudnessGain.toFloat(), onValueChange = {
                        manager.setLoudness(gain = it.toInt()); persist()
                    }, valueRange = 0f..1500f)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mono audio", fontWeight = FontWeight.SemiBold)
                        Text("Request mono output from the device audio service", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 11.sp)
                    }
                    Switch(checked = state.monoEnabled, onCheckedChange = {
                        manager.setMono(it); persist()
                    })
                }
            }
            OutlinedButton(onClick = { manager.reset(); persist() }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to flat")
            }
        }
    }
}

@Composable
private fun EqualizerCurve(state: EqualizerState) {
    Canvas(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
        if (state.bandLevels.isEmpty()) return@Canvas
        val mid = size.height / 2f
        val range = (state.maxLevel - state.minLevel).toFloat().coerceAtLeast(1f)
        val path = Path()
        state.bandLevels.forEachIndexed { index, value ->
            val x = if (state.bandLevels.size == 1) size.width / 2f
                else index * size.width / (state.bandLevels.size - 1)
            val y = size.height - ((value - state.minLevel) / range) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(MaterialTheme.colorScheme.onSurface.copy(alpha = .18f),
            Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        drawPath(path, MaterialTheme.colorScheme.primary, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun EffectControl(
    title: String,
    enabled: Boolean,
    strength: Int,
    onEnabled: (Boolean) -> Unit,
    onStrength: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Intensity: ${strength / 10}%", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        Slider(value = strength.toFloat(), onValueChange = { onStrength(it.toInt()) },
            valueRange = 0f..1000f, enabled = enabled)
    }
}
