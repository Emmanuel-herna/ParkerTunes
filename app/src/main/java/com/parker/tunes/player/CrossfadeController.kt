package com.parker.tunes.player

import android.os.SystemClock
import android.content.SharedPreferences
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Applies a short fade-out/fade-in at item boundaries. ExoPlayer's playlist
 * renderer remains gapless; this controller adds a configurable transition
 * envelope without creating a second audio pipeline.
 */
class CrossfadeController(
    private val player: Player,
    private val preferences: SharedPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fadeInStartedAt = SystemClock.elapsedRealtime()
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            fadeInStartedAt = SystemClock.elapsedRealtime()
        }
    }

    init {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                val fadeMs = preferences.getInt("crossfade_seconds", 0).coerceIn(0, 12) * 1_000L
                if (fadeMs == 0L) {
                    player.volume = 1f
                } else if (player.isPlaying) {
                    val duration = player.duration
                    val position = player.currentPosition
                    val remaining = duration - position
                    val elapsedIn = SystemClock.elapsedRealtime() - fadeInStartedAt
                    val fadeIn = (elapsedIn.toFloat() / fadeMs).coerceIn(0f, 1f)
                    val fadeOut = if (duration > 0L && remaining in 0..fadeMs) {
                        (remaining.toFloat() / fadeMs).coerceIn(0f, 1f)
                    } else 1f
                    player.volume = minOf(fadeIn, fadeOut)
                }
                delay(100L)
            }
        }
    }

    fun release() {
        player.removeListener(listener)
        player.volume = 1f
        scope.cancel()
    }
}
