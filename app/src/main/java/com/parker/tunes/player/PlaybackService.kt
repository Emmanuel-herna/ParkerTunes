package com.parker.tunes.player

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.parker.tunes.data.FavoriteEntity

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.parker.tunes.ParkerTunesApp
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
    private var autoplayResolving = false
    private val favoriteCommand = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon().add(favoriteCommand).build()
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
    }

    override fun onCreate() {
        super.onCreate()

        // ExoPlayer manages audio focus so playback pauses appropriately for calls
        // and other apps that request audio focus.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        crossfadeController = CrossfadeController(player, getSharedPreferences("parker_tunes_settings", MODE_PRIVATE))
        val effects = EqualizerManager(this)
        equalizerManager = effects
        EqualizerRegistry.manager = effects
        effects.attach(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attach(audioSessionId)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) effects.attach(player.audioSessionId)
                WidgetProvider.update(this@PlaybackService, player)
                if (playbackState == Player.STATE_ENDED) maybeAppendRelated(player)
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                WidgetProvider.update(this@PlaybackService, player)
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                WidgetProvider.update(this@PlaybackService, player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) effects.attach(player.audioSessionId)
                WidgetProvider.update(this@PlaybackService, player)
            }
        })
        val favoriteButton = CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("Save to favourites")
            .setSessionCommand(favoriteCommand)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(listOf(favoriteButton))
            .build()
        WidgetProvider.update(this, player)
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
                val related = withContext(Dispatchers.IO) { app.musicApi.getRelatedSongs(songId) }
                val queuedIds = (0 until player.mediaItemCount).mapNotNull { index ->
                    runCatching { player.getMediaItemAt(index).mediaId }.getOrNull()
                }.toSet()
                val additions = related.asSequence().filter { it.id !in queuedIds }
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
