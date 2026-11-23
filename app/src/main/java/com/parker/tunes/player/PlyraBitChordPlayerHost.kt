package com.parker.tunes.player

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.ui.player.AudioOutputDevice
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.LyricsRomanizationResult
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.OutputFormatUi
import com.music.bitchord.ui.player.PartyUi
import com.music.bitchord.ui.player.PlayerHost
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.ui.player.SystemVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts BitChord's shared player UI to the services Plyra actually provides.
 *
 * Unsupported BitChord-only features (Canvas video, cast/watch output, party
 * sync, and lyric translation) stay unavailable rather than being represented
 * by non-working controls.
 */
class PlyraBitChordPlayerHost(context: Context) : PlayerHost {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val settings: PlayerSettingsSource = PlyraPlayerSettings()

    private fun readSystemVolume(): Float {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
    }

    private val volumeFlow = MutableStateFlow(readSystemVolume())

    override val volume: SystemVolume = object : SystemVolume {
        override val level: StateFlow<Float> = volumeFlow

        override fun set(level: Float) {
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val requested = (level.coerceIn(0f, 1f) * maximum).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
            refresh()
        }

        override fun refresh() {
            volumeFlow.value = readSystemVolume()
        }
    }

    override val outputFormat: StateFlow<OutputFormatUi> =
        MutableStateFlow(OutputFormatUi())

    override val party: StateFlow<PartyUi> = MutableStateFlow(PartyUi())

    override fun cachedCanvas(song: Song): CanvasArtwork? = null

    override suspend fun canvasFor(song: Song): CanvasArtwork? = null

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
        // Plyra does not currently have a Canvas video decoder.
    }

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = emptyList()

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) {
        // Output routing remains under Android's system media controls.
    }

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult = LyricsTranslationResult.Unavailable

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult = LyricsRomanizationResult.Unavailable

    override fun showMessage(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    override fun AudioPipelineDialog(
        hazeState: dev.chrisbanes.haze.HazeState,
        isPlaying: Boolean,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Audio pipeline") },
            text = { Text("Plyra's detailed signal-chain view is not available yet.") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            },
        )
    }
}

private class PlyraPlayerSettings : PlayerSettingsSource {
    override val animatedCanvas = MutableStateFlow(false)
    override val audioQualityCellular = MutableStateFlow(AudioQuality.MEDIUM)
    override val audioQualityWifi = MutableStateFlow(AudioQuality.HIGH)
    override val canvasOverCellular = MutableStateFlow(false)
    override val fullBleedArtwork = MutableStateFlow(true)
    override val showTotalDuration = MutableStateFlow(true)
    override val hideSongStatus = MutableStateFlow(false)
    override val hideLyricsPreview = MutableStateFlow(false)
    override val showQueueHistory = MutableStateFlow(false)
    override val hideVolumeBar = MutableStateFlow(false)
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)
    override val playerTheme = MutableStateFlow(PlayerTheme.DEFAULT)
    override val lyricsBlur = MutableStateFlow(true)
    override val lyricsOffsetMs = MutableStateFlow(0)
    override val lyricsSourceOrder = MutableStateFlow(LyricsSource.offered)
    override val meteredConnection = MutableStateFlow<Boolean?>(null)
    override val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)
    override val preferUsbDac = MutableStateFlow(false)
    override val disableSpotifyCanvas = MutableStateFlow(true)
    override val prioritizeSpotifyCanvas = MutableStateFlow(false)
    override val reduceAnimation = MutableStateFlow(false)
    override val reduceDynamicBlur = MutableStateFlow(false)
    override val disableBlendEffects = MutableStateFlow(false)
    override val showNerdStats = MutableStateFlow(false)
    override val showBestPart = MutableStateFlow(false)
    override val showListenTogetherButton = MutableStateFlow(true)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val automixDetail = MutableStateFlow<String?>(null)
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartMixView = MutableStateFlow<MixTransitionView?>(null)
    override val smartMixProgress = MutableStateFlow(0f)
    override val smartMixPreview = MutableStateFlow<MixTransitionView?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = MutableStateFlow(false)
    override val syncedLyrics = MutableStateFlow(true)
    override val translationLanguage = MutableStateFlow("en")
    override val versionAlignmentInProgress = MutableStateFlow(false)

    override fun setLastPlayerScreen(value: LastPlayerScreen) {
        lastPlayerScreen.value = value
    }

    override fun setLyricsOffsetMs(value: Int) {
        lyricsOffsetMs.value = value.coerceIn(
            com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS,
            com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS,
        )
    }

    override fun setMixViewOpen(open: Boolean) = Unit
}
