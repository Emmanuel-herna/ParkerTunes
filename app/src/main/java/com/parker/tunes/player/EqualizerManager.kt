package com.parker.tunes.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class EqualizerState(
    val supported: Boolean = false,
    val preset: Int = -1,
    val presetNames: List<String> = emptyList(),
    val bandLevels: List<Int> = emptyList(),
    val bandLabels: List<String> = emptyList(),
    val minLevel: Int = -1200,
    val maxLevel: Int = 1200,
    val bassEnabled: Boolean = false,
    val bassStrength: Int = 0,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val loudnessEnabled: Boolean = false,
    val loudnessGain: Int = 0,
    val monoEnabled: Boolean = false
)

class EqualizerManager(context: Context) : AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var sessionId = 0
    private var lastConfig = EqualizerState()
    private val _state = MutableStateFlow(lastConfig)
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || (audioSessionId == sessionId && equalizer != null)) return
        releaseEffects()
        sessionId = audioSessionId
        try {
            val eq = Equalizer(0, audioSessionId)
            val bass = BassBoost(0, audioSessionId)
            val virtual = Virtualizer(0, audioSessionId)
            val loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            equalizer = eq
            bassBoost = bass
            virtualizer = virtual
            loudnessEnhancer = loudness
            val range = eq.bandLevelRange
            val names = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            val levels = (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }
            val labels = (0 until eq.numberOfBands.toInt()).map { formatFrequency(eq.getCenterFreq(it.toShort())) }
            lastConfig = lastConfig.copy(
                supported = true, presetNames = names,
                bandLevels = if (lastConfig.bandLevels.size == levels.size) lastConfig.bandLevels else levels,
                bandLabels = labels, minLevel = range[0].toInt(), maxLevel = range[1].toInt()
            )
            _state.value = lastConfig
            applyAll()
        } catch (_: UnsupportedOperationException) {
            releaseEffects()
            _state.value = lastConfig.copy(supported = false)
        } catch (_: RuntimeException) {
            releaseEffects()
            _state.value = lastConfig.copy(supported = false)
        }
    }

    @Synchronized
    fun applySaved(config: EqualizerState) {
        lastConfig = config
        _state.value = config.copy(
            supported = equalizer != null, presetNames = _state.value.presetNames,
            bandLabels = _state.value.bandLabels, minLevel = _state.value.minLevel,
            maxLevel = _state.value.maxLevel
        )
        applyAll()
    }

    @Synchronized
    fun selectPreset(index: Int) {
        val eq = equalizer ?: return
        runCatching {
            if (index in 0 until eq.numberOfPresets.toInt()) {
                eq.usePreset(index.toShort())
                val levels = (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }
                update(lastConfig.copy(preset = index, bandLevels = levels))
            }
        }
    }

    @Synchronized
    fun setBand(index: Int, value: Int) {
        val eq = equalizer ?: return
        if (index !in 0 until eq.numberOfBands.toInt()) return
        val level = value.coerceIn(lastConfig.minLevel, lastConfig.maxLevel)
        runCatching { eq.setBandLevel(index.toShort(), level.toShort()) }
        val levels = lastConfig.bandLevels.toMutableList()
        if (index in levels.indices) levels[index] = level
        update(lastConfig.copy(preset = -1, bandLevels = levels))
    }

    @Synchronized
    fun setBass(enabled: Boolean = lastConfig.bassEnabled, strength: Int = lastConfig.bassStrength) {
        val next = lastConfig.copy(bassEnabled = enabled, bassStrength = strength.coerceIn(0, 1000))
        runCatching { bassBoost?.setStrength(next.bassStrength.toShort()); bassBoost?.enabled = enabled }
        update(next)
    }

    @Synchronized
    fun setVirtualizer(enabled: Boolean = lastConfig.virtualizerEnabled, strength: Int = lastConfig.virtualizerStrength) {
        val next = lastConfig.copy(virtualizerEnabled = enabled, virtualizerStrength = strength.coerceIn(0, 1000))
        runCatching { virtualizer?.setStrength(next.virtualizerStrength.toShort()); virtualizer?.enabled = enabled }
        update(next)
    }

    @Synchronized
    fun setLoudness(enabled: Boolean = lastConfig.loudnessEnabled, gain: Int = lastConfig.loudnessGain) {
        val next = lastConfig.copy(loudnessEnabled = enabled, loudnessGain = gain.coerceIn(0, 1500))
        runCatching { loudnessEnhancer?.setTargetGain(next.loudnessGain); loudnessEnhancer?.enabled = enabled }
        update(next)
    }

    @Synchronized
    fun setMono(enabled: Boolean) {
        runCatching { audioManager.setParameters("mono_output=" + if (enabled) "on" else "off") }
        update(lastConfig.copy(monoEnabled = enabled))
    }

    @Synchronized
    fun reset() {
        val eq = equalizer
        runCatching { eq?.usePreset(0) }
        val zeros = List(_state.value.bandLabels.size) { 0 }
        zeros.forEachIndexed { i, _ -> runCatching { eq?.setBandLevel(i.toShort(), 0) } }
        setBass(false, 0)
        setVirtualizer(false, 0)
        setLoudness(false, 0)
        setMono(false)
        update(lastConfig.copy(preset = -1, bandLevels = zeros))
    }

    private fun applyAll() {
        val config = lastConfig
        runCatching {
            equalizer?.let { eq ->
                config.bandLevels.forEachIndexed { index, value ->
                    if (index < eq.numberOfBands.toInt()) eq.setBandLevel(index.toShort(), value.toShort())
                }
            }
            bassBoost?.let { it.setStrength(config.bassStrength.toShort()); it.enabled = config.bassEnabled }
            virtualizer?.let { it.setStrength(config.virtualizerStrength.toShort()); it.enabled = config.virtualizerEnabled }
            loudnessEnhancer?.let { it.setTargetGain(config.loudnessGain); it.enabled = config.loudnessEnabled }
            audioManager.setParameters("mono_output=" + if (config.monoEnabled) "on" else "off")
        }
    }

    private fun update(next: EqualizerState) {
        lastConfig = next
        _state.value = next.copy(
            supported = equalizer != null, presetNames = _state.value.presetNames,
            bandLabels = _state.value.bandLabels, minLevel = _state.value.minLevel,
            maxLevel = _state.value.maxLevel
        )
    }

    @Synchronized
    private fun releaseEffects() {
        listOf(equalizer, bassBoost, virtualizer, loudnessEnhancer).forEach { effect ->
            runCatching { effect?.enabled = false }
            runCatching { effect?.release() }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }

    override fun close() {
        releaseEffects()
        sessionId = 0
        _state.value = _state.value.copy(supported = false)
    }

    private fun formatFrequency(milliHz: Int): String {
        val hz = milliHz / 1000
        return if (hz >= 1000) {
            val khz = hz / 1000f
            if (khz == khz.toInt().toFloat()) "${khz.toInt()}kHz"
            else String.format(Locale.US, "%.1fkHz", khz)
        } else "${hz}Hz"
    }
}

object EqualizerRegistry {
    @Volatile var manager: EqualizerManager? = null
}
