package com.parker.tunes.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridges Media3 callbacks directly into Compose state. Playback controls never
 * wait for a UI polling interval; only the playhead is sampled every 200 ms.
 */
data class PlayerUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val title: String = "Nothing playing",
    val artist: String = "Choose a track",
    val artworkUri: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

data class PlayerProgress(val positionMs: Long = 0L, val durationMs: Long = 0L)

class PlayerUiStateObserver(private val player: Player) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(progressSnapshot())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()
    private var progressJob: Job? = null

    private fun snapshot(): PlayerUiState {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        return PlayerUiState(
            hasItem = item != null,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            title = metadata?.title?.toString() ?: "Nothing playing",
            artist = metadata?.artist?.toString() ?: "Choose a track",
            artworkUri = metadata?.artworkUri?.toString(),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode
        )
    }

    private fun progressSnapshot() = PlayerProgress(
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it > 0L } ?: 0L
    )

    private fun publish() {
        _state.value = snapshot()
        _progress.value = progressSnapshot()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = publish()
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = publish()
    }

    init {
        player.addListener(listener)
        progressJob = scope.launch {
            while (isActive) {
                delay(200L)
                val next = progressSnapshot()
                if (_progress.value != next) _progress.value = next
            }
        }
    }

    override fun close() {
        player.removeListener(listener)
        progressJob?.cancel()
        scope.cancel()
    }
}
