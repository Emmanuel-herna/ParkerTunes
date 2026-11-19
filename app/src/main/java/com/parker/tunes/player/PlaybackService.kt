package com.parker.tunes.player

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.PlayHistoryEntity
import com.parker.tunes.data.ListeningEventEntity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.parker.tunes.ParkerTunesApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var equalizerManager: EqualizerManager? = null
    private var crossfadeController: CrossfadeController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val observedPlayers = mutableSetOf<ExoPlayer>()
    private val startedTrackByPlayer = mutableMapOf<ExoPlayer, String>()
    private val lastItemByPlayer = mutableMapOf<ExoPlayer, androidx.media3.common.MediaItem?>()
    private val qualifiedTracks = mutableSetOf<String>()
    private var autoplayResolving = false
    private val favoriteCommand = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
    private val skipNextCommand = SessionCommand(ACTION_SKIP_NEXT, Bundle.EMPTY)

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon().add(favoriteCommand).add(skipNextCommand).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SKIP_NEXT) {
                crossfadeController?.skipToNext() ?: session.player.seekToNextMediaItem()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_FAVORITE) {
                val item = session.player.currentMediaItem
                val songId = item?.mediaId.orEmpty()
                if (songId.isNotBlank()) {
                    serviceScope.launch(Dispatchers.IO) {
                        val dao = (application as ParkerTunesApp).database.libraryDao()
                        if (dao.isFavorite(songId)) dao.removeFavorite(songId)
                        else dao.addFavorite(FavoriteEntity(
                            songId = songId,
                            title = item?.mediaMetadata?.title?.toString().orEmpty(),
                            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                            coverUrl = item?.mediaMetadata?.artworkUri?.toString()
                        ))
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private companion object {
        const val ACTION_FAVORITE = "com.parker.tunes.action.FAVORITE"
        const val ACTION_SKIP_NEXT = "com.parker.tunes.action.SKIP_NEXT"
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Both players share the main application looper, allowing the single
        // MediaSession to move safely between them after each completed fade.
        val active = buildPlayer(audioAttributes)
        val spare = buildPlayer(audioAttributes)

        val effects = EqualizerManager(this)
        equalizerManager = effects
        EqualizerRegistry.manager = effects
        effects.attach(active.audioSessionId)

        val favoriteButton = CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("Save to favourites")
            .setSessionCommand(favoriteCommand)
            .build()
        val session = MediaSession.Builder(this, active)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(listOf(favoriteButton))
            .build()
        mediaSession = session

        observePlayer(active, effects)
        observePlayer(spare, effects)
        crossfadeController = CrossfadeController(
            initialActive = active,
            spare = spare,
            session = session,
            preferences = getSharedPreferences("parker_tunes_settings", MODE_PRIVATE),
            onActivePlayerChanged = { nowActive ->
                effects.attach(nowActive.audioSessionId)
                WidgetProvider.update(this, nowActive)
            }
        )
        WidgetProvider.update(this, active)
    }

    private fun buildPlayer(audioAttributes: AudioAttributes): ExoPlayer =
        ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

    private fun observePlayer(player: ExoPlayer, effects: EqualizerManager) {
        if (!observedPlayers.add(player)) return
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (mediaSession?.player === player) effects.attach(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (mediaSession?.player === player) {
                    if (playbackState == Player.STATE_READY) effects.attach(player.audioSessionId)
                    WidgetProvider.update(this@PlaybackService, player)
                    if (playbackState == Player.STATE_ENDED) {
                        recordEvent(player, "PLAY_COMPLETED")
                        handlePlaybackEnded(player)
                    }
                }
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                if (mediaSession?.player === player) {
                    val previous = lastItemByPlayer[player]
                    val previousId = previous?.mediaId.orEmpty()
                    if (previousId.isNotBlank() && previousId != mediaItem?.mediaId) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            recordEvent(player, "PLAY_COMPLETED", previous)
                        } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                            startedTrackByPlayer[player] == previousId) {
                            recordEvent(player, "SKIPPED", previous)
                        }
                    }
                    lastItemByPlayer[player] = mediaItem
                    WidgetProvider.update(this@PlaybackService, player)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                if (mediaSession?.player === player) WidgetProvider.update(this@PlaybackService, player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (mediaSession?.player === player) {
                    if (isPlaying) {
                        effects.attach(player.audioSessionId)
                        recordStartedTrack(player)
                    }
                    WidgetProvider.update(this@PlaybackService, player)
                }
            }
        })
        lastItemByPlayer[player] = player.currentMediaItem
    }

    private fun recordStartedTrack(player: ExoPlayer) {
        if (mediaSession?.player !== player) return
        val item = player.currentMediaItem ?: return
        val songId = item.mediaId.takeIf { it.isNotBlank() } ?: return
        if (startedTrackByPlayer[player] == songId) return
        startedTrackByPlayer[player] = songId
        val metadata = item.mediaMetadata
        serviceScope.launch(Dispatchers.IO) {
            val dao = (application as ParkerTunesApp).database.libraryDao()
            dao.recordPlay(PlayHistoryEntity(
                songId = songId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                coverUrl = metadata.artworkUri?.toString()
            ))
            dao.recordListeningEvent(ListeningEventEntity(
                songId = songId,
                eventType = "PLAY_STARTED",
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                coverUrl = metadata.artworkUri?.toString(),
                durationMs = player.duration.takeIf { it > 0L } ?: 0L
            ))
        }
        serviceScope.launch {
            kotlinx.coroutines.delay(30_000L)
            if (mediaSession?.player === player && player.isPlaying &&
                player.currentMediaItem?.mediaId == songId && qualifiedTracks.add(songId)) {
                recordEvent(player, "PLAY_QUALIFIED", item)
            }
        }
    }

    private fun recordEvent(player: ExoPlayer, eventType: String,
        item: androidx.media3.common.MediaItem? = player.currentMediaItem) {
        val songId = item?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val metadata = item.mediaMetadata
        val position = if (player.currentMediaItem?.mediaId == songId) player.currentPosition.coerceAtLeast(0L) else 0L
        val duration = if (player.currentMediaItem?.mediaId == songId) player.duration.takeIf { it > 0L } ?: 0L else 0L
        serviceScope.launch(Dispatchers.IO) {
            (application as ParkerTunesApp).database.libraryDao().recordListeningEvent(
                ListeningEventEntity(songId=songId,eventType=eventType,title=metadata.title?.toString().orEmpty(),
                    artist=metadata.artist?.toString().orEmpty(),coverUrl=metadata.artworkUri?.toString(),
                    positionMs=position,durationMs=duration)
            )
        }
    }

    /**
     * Handles the end of the active track. If the user already has another
     * track queued, advance to it immediately. Only request related songs when
     * the current track was the final queue item.
     */
    private fun handlePlaybackEnded(observedPlayer: ExoPlayer) {
        val activePlayer = crossfadeController?.activePlayer() ?: observedPlayer
        if (mediaSession?.player !== activePlayer) return
        if (activePlayer.repeatMode == Player.REPEAT_MODE_ONE) return

        val nextIndex = activePlayer.currentMediaItemIndex + 1
        if (nextIndex in 0 until activePlayer.mediaItemCount) {
            activePlayer.seekToNextMediaItem()
            activePlayer.prepare()
            activePlayer.play()
        } else {
            maybeAppendRelated(activePlayer)
        }
    }

    private fun maybeAppendRelated(player: ExoPlayer) {
        val prefs = getSharedPreferences("parker_tunes_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("smart_autoplay", true) || autoplayResolving ||
            player.repeatMode != Player.REPEAT_MODE_OFF ||
            player.currentMediaItemIndex < 0 ||
            player.currentMediaItemIndex < player.mediaItemCount - 1
        ) return
        val current = player.currentMediaItem ?: return
        val songId = current.mediaId.takeIf { it.isNotBlank() } ?: return
        autoplayResolving = true
        serviceScope.launch {
            try {
                val app = application as ParkerTunesApp
                // Keep the original related list as a safety fallback. If filtering or
                // the artist-search fallback fails, autoplay still behaves as before.
                val related = try {
                    withContext(Dispatchers.IO) { app.musicApi.getRelatedSongs(
                            songId,
                            current.mediaMetadata.title?.toString().orEmpty(),
                            current.mediaMetadata.artist?.toString().orEmpty()
                        ) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }

                // Default to the old unfiltered list. Only replace it after the
                // filter/search path completes successfully.
                var candidates = related
                try {
                    val filteredRelated = related.filterNot {
                        isLikelyMix(it.title, it.durationSec)
                    }
                    val artistName = current.mediaMetadata.artist?.toString()?.trim().orEmpty()
                    candidates = if (filteredRelated.size < 5 && artistName.isNotBlank()) {
                        val fallback = withContext(Dispatchers.IO) {
                            app.musicApi.search("$artistName songs").songs.take(15)
                        }
                        (filteredRelated + fallback.filterNot {
                            isLikelyMix(it.title, it.durationSec)
                        }).distinctBy { it.id }
                    } else {
                        filteredRelated
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Any filter/fallback problem keeps the original related results.
                    candidates = related
                }

                val queuedIds = (0 until player.mediaItemCount).mapNotNull { index ->
                    runCatching { player.getMediaItemAt(index).mediaId }.getOrNull()
                }.toSet()
                val additions = candidates.asSequence().filter { it.id !in queuedIds }
                    .distinctBy { it.id }.take(5).toList()
                val mediaItems = withContext(Dispatchers.IO) {
                    additions.mapNotNull { song ->
                        runCatching {
                            val stream = app.musicApi.getStreamUrl(song.id)
                            if (stream.isBlank()) null else androidx.media3.common.MediaItem.Builder()
                                .setMediaId(song.id)
                                .setUri(stream)
                                .setMediaMetadata(
                                    androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artist)
                                        .setArtworkUri(song.coverUrl?.let(android.net.Uri::parse))
                                        .build()
                                ).build()
                        }.getOrNull()
                    }
                }
                if (mediaItems.isNotEmpty()) {
                    player.addMediaItems(mediaItems)
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            } finally {
                autoplayResolving = false
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        crossfadeController?.release()
        crossfadeController = null
        equalizerManager?.close()
        equalizerManager = null
        EqualizerRegistry.manager = null
        mediaSession?.release()
        mediaSession = null
        observedPlayers.clear()
        super.onDestroy()
    }
}
