package com.parker.tunes.player

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.parker.tunes.core.player.CrossfadeRules
import com.parker.tunes.core.player.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Two-player equal-power crossfade. The MediaSession follows the audible player
 * after every completed overlap, preserving app, notification and lock-screen control.
 *
 * TODO: STAGE 2 — SoundAlignment.offset(envelopeA, envelopeB), AudioMeter RMS
 * AudioProcessor and brief ±15% beat-drift correction. Not implemented in Stage 1.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    initialActive: ExoPlayer,
    private val spare: ExoPlayer,
    private val session: MediaSession,
    private val preferences: SharedPreferences,
    private val audioAttributes: AudioAttributes,
    private val onActivePlayerChanged: (ExoPlayer) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = initialActive
    private var incoming = spare
    private var preloadedForIndex = -1
    private var fadeStartedAt = 0L
    private var fading = false
    private var finishingFade = false

    private val activeListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (fading && !finishingFade) {
                cancelFade()
            } else if (!fading) {
                preloadedForIndex = -1
                preloadNext()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A pause or interruption must not leave the silent helper running.
            if (fading && !finishingFade && !playWhenReady) cancelFade()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // A seek invalidates the helper's preloaded position.
            if (fading && !finishingFade &&
                reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) cancelFade()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (!fading) {
                preloadedForIndex = -1
                preloadNext()
            }
        }
    }

    init {
        active.addListener(activeListener)
        // The preloaded player is muted and must never compete with the active
        // player for Android audio focus while an overlap is being prepared.
        incoming.setAudioAttributes(audioAttributes, false)
        active.setAudioAttributes(audioAttributes, true)
        incoming.volume = 0f
        scope.launch {
            while (isActive) {
                tick()
                delay(20L)
            }
        }
        preloadNext()
    }

    /** Applies a setting change immediately; the persisted preference is also observed by the tick loop. */
    fun updateCrossfadeSeconds(seconds: Int) {
        preferences.edit().putInt("crossfade_seconds", seconds.coerceIn(0, 12)).apply()
        if (seconds <= 0 && fading) finishFade()
    }

    private fun configuredCrossfadeSeconds(): Int {
        val manual = preferences.getInt("crossfade_seconds", 0).coerceIn(0, 12)
        return if (preferences.getBoolean("automix", false)) maxOf(manual, 4) else manual
    }

    private fun tick() {
        if (fading) {
            val fadeMs = configuredCrossfadeSeconds() * 1_000L
            if (fadeMs <= 0L) {
                finishFade()
                return
            }
            val progress = ((SystemClock.elapsedRealtime() - fadeStartedAt) / fadeMs.toFloat())
                .coerceIn(0f, 1f)
            active.volume = CrossfadeRules.fadeOut(progress)
            incoming.volume = CrossfadeRules.fadeIn(progress)
            if (progress >= 1f) finishFade()
            return
        }

        if (!active.isPlaying || active.repeatMode == Player.REPEAT_MODE_ONE) return
        val fadeMs = configuredCrossfadeSeconds() * 1_000L
        if (fadeMs <= 0L) return
        if (incoming.playbackState != Player.STATE_READY) return

        val duration = active.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val remaining = duration - active.currentPosition
        if (remaining > fadeMs || remaining < 0L) return

        val currentSong = active.currentMediaItem?.mediaMetadata?.toRuleSong(duration) ?: return
        val nextDuration = incoming.duration.takeUnless { it == C.TIME_UNSET || it <= 0L }
        val nextSong = incoming.currentMediaItem?.mediaMetadata?.toRuleSong(nextDuration)
        if (!CrossfadeRules.applies(
                (fadeMs / 1_000L).toInt(),
                currentSong,
                nextSong,
                active.repeatMode == Player.REPEAT_MODE_ONE
            )
        ) return

        startFade()
    }

    /** Used by the app's skip control: eligible tracks overlap, all other cases hard-cut. */
    fun skipToNext() {
        // A manual skip should move to the next item immediately. Crossfade remains
        // active for automatic end-of-track transitions, not for an explicit tap.
        if (fading) cancelFade()
        if (active.hasNextMediaItem()) {
            active.seekToNextMediaItem()
            if (active.playbackState == Player.STATE_IDLE ||
                active.playbackState == Player.STATE_ENDED
            ) active.prepare()
            active.play()
        }
    }

    private fun startFade() {
        incoming.seekTo(0L)
        incoming.volume = 0f
        incoming.playWhenReady = true
        incoming.play()
        fadeStartedAt = SystemClock.elapsedRealtime()
        fading = true
    }

    private fun finishFade() {
        if (!fading) return
        finishingFade = true
        val old = active
        val new = incoming
        old.volume = 0f
        old.pause()
        old.setAudioAttributes(audioAttributes, false)
        new.setAudioAttributes(audioAttributes, true)
        new.volume = 1f
        new.playWhenReady = true

        session.setPlayer(new)
        new.play()
        old.removeListener(activeListener)
        active = new
        incoming = old
        active.addListener(activeListener)
        onActivePlayerChanged(active)

        fading = false
        finishingFade = false
        fadeStartedAt = 0L
        preloadedForIndex = -1
        incoming.volume = 0f
        incoming.clearMediaItems()
        preloadNext()
    }

    /** Stops the helper when the listener changes playback during an overlap. */
    private fun cancelFade() {
        if (!fading || finishingFade) return
        fading = false
        fadeStartedAt = 0L
        active.volume = 1f
        incoming.volume = 0f
        incoming.playWhenReady = false
        incoming.pause()
        incoming.clearMediaItems()
        preloadedForIndex = -1
        preloadNext()
    }

    private fun preloadNext() {
        if (fading || active.mediaItemCount < 2) return
        val nextIndex = active.currentMediaItemIndex + 1
        if (nextIndex !in 0 until active.mediaItemCount || preloadedForIndex == nextIndex) return
        val queue = (0 until active.mediaItemCount).map { active.getMediaItemAt(it) }
        if (queue.drop(nextIndex).any { it.localConfiguration?.uri == null }) return

        incoming.playWhenReady = false
        incoming.pause()
        incoming.volume = 0f
        incoming.setMediaItems(queue, nextIndex, 0L)
        incoming.repeatMode = Player.REPEAT_MODE_OFF
        incoming.prepare()
        preloadedForIndex = nextIndex
    }

    private fun MediaMetadata.toRuleSong(durationMs: Long?): Song = Song(
        album = albumTitle?.toString(),
        trackNumber = trackNumber?.takeIf { it > 0 },
        durationMs = durationMs
    )

    fun activePlayer(): ExoPlayer = active

    fun release() {
        active.removeListener(activeListener)
        active.volume = 1f
        incoming.volume = 1f
        scope.cancel()
        active.release()
        incoming.release()
    }
}
