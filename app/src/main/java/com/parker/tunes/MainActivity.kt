package com.parker.tunes

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.navigation.compose.*
import com.google.common.util.concurrent.ListenableFuture
import com.parker.tunes.data.ListenTogetherClient
import com.parker.tunes.data.JamTrack
import com.parker.tunes.data.PlayHistoryEntity
import com.parker.tunes.data.BackupManager
import com.parker.tunes.data.PlaybackSettingsStore
import com.parker.tunes.data.PlaybackSettings
import com.parker.tunes.data.EqualizerSettingsEntity
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.MusicApi
import com.parker.tunes.core.lyrics.LyricsParser
import com.parker.tunes.core.lyrics.LyricsLine as KaraokeLyricsLine
import com.parker.tunes.core.lyrics.VLine
import com.parker.tunes.core.lyrics.activeRows
import com.parker.tunes.data.LyricsCacheEntity
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.model.Artist
import com.parker.tunes.model.Album
import com.parker.tunes.model.Song
import com.parker.tunes.data.home.HomeShelf
import com.parker.tunes.player.PlaybackService
import com.parker.tunes.player.PlayerUiState
import com.parker.tunes.player.PlayerUiStateObserver
import com.parker.tunes.player.EqualizerManager
import com.parker.tunes.player.EqualizerRegistry
import com.parker.tunes.player.EqualizerState
import com.parker.tunes.ui.theme.ParkerTunesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedRate = getSharedPreferences("parker_tunes_settings", MODE_PRIVATE)
            .getInt("preferred_refresh_rate", 120)
        applyPreferredRefreshRate(this, savedRate)
        setContent { ParkerTunesTheme { ParkerTunesRoot() } }
    }
}

private val Ink = Color(0xFF000000)
private val Panel = Color(0xFF1A1A1A)
private val Accent = Color(0xFFB69CFF)
private val Muted = Color(0xFFA7A7A7)


private fun applyPreferredRefreshRate(activity: Activity, requestedRate: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val supportedModes = activity.window.decorView.display?.supportedModes.orEmpty()
    val supportedRates = supportedModes.map { it.refreshRate }.distinct()
    val selectedRate = supportedRates.minByOrNull { kotlin.math.abs(it - requestedRate) }
        ?: requestedRate.toFloat()
    activity.window.attributes = activity.window.attributes.apply {
        preferredRefreshRate = selectedRate
    }
}

@Composable
fun ParkerTunesRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as ParkerTunesApp
    val api = app.musicApi
    val listenTogether = remember(context) { ListenTogetherClient(context) }
    val dao = app.database.libraryDao()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Media notifications are enabled when permission is granted. */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var query by remember { mutableStateOf("") }
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedHomeShelf by remember { mutableStateOf<HomeShelf?>(null) }
    var selectedTab by remember { mutableStateOf("Home") }
    val settingsPrefs = remember { context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE) }
    val settingsRepository = remember(context) { com.parker.tunes.data.SettingsRepository(context) }
    val selectedAccent by settingsRepository.accentColor.collectAsState(initial = "purple")
    val useAmoledBlack by settingsRepository.amoledBlack.collectAsState(initial = true)
    val liveAccent = when (selectedAccent) {
        "blue" -> Color(0xFF64B5F6)
        "cyan" -> Color(0xFF4DD0E1)
        "green" -> Color(0xFF81C784)
        "yellow" -> Color(0xFFFFD54F)
        "orange" -> Color(0xFFFFAB91)
        "pink" -> Color(0xFFF48FB1)
        "red" -> Color(0xFFEF5350)
        else -> Color(0xFFB69CFF)
    }
    var lightMode by remember { mutableStateOf(settingsPrefs.getBoolean("light_mode", false)) }
    var preferredRefreshRate by remember { mutableIntStateOf(settingsPrefs.getInt("preferred_refresh_rate", 120)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var resolvingSongId by remember { mutableStateOf<String?>(null) }
    var playbackLoadError by remember { mutableStateOf<String?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
            controller = null
        }
    }

    val scheme = if (lightMode) lightColorScheme(
        primary = Color(0xFF6848B8),
        background = Color(0xFFF6F4FA),
        surface = Color.White,
        onBackground = Color(0xFF171421),
        onSurface = Color(0xFF171421)
    ) else darkColorScheme(
        primary = liveAccent,
        background = if (useAmoledBlack) Color.Black else Color(0xFF101010),
        surface = if (useAmoledBlack) Color.Black else Panel,
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(colorScheme = scheme) {
        val playerObserver = remember(controller) { controller?.let(::PlayerUiStateObserver) }
        val equalizerManager = remember(controller) { EqualizerRegistry.manager }
        LaunchedEffect(equalizerManager, dao) {
            if (equalizerManager != null) {
                dao.equalizerSettings().flowOn(Dispatchers.IO).collect { saved ->
                    if (saved != null) {
                        equalizerManager.applySaved(EqualizerState(
                            preset = saved.preset,
                            bandLevels = saved.bandLevels.split(',').mapNotNull { it.toIntOrNull() },
                            bassEnabled = saved.bassEnabled, bassStrength = saved.bassStrength,
                            virtualizerEnabled = saved.virtualizerEnabled, virtualizerStrength = saved.virtualizerStrength,
                            loudnessEnabled = saved.loudnessEnabled, loudnessGain = saved.loudnessGain,
                            monoEnabled = saved.monoEnabled
                        ))
                    }
                }
            }
        }
        val persistEqualizer: (EqualizerState) -> Unit = { state ->
            scope.launch(Dispatchers.IO) {
                dao.saveEqualizerSettings(EqualizerSettingsEntity(
                    preset = state.preset, bandLevels = state.bandLevels.joinToString(","),
                    bassEnabled = state.bassEnabled, bassStrength = state.bassStrength,
                    virtualizerEnabled = state.virtualizerEnabled, virtualizerStrength = state.virtualizerStrength,
                    loudnessEnabled = state.loudnessEnabled, loudnessGain = state.loudnessGain,
                    monoEnabled = state.monoEnabled
                ))
            }
        }
        DisposableEffect(playerObserver) {
            onDispose { playerObserver?.close() }
        }
        val playerState by (playerObserver?.state
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayerUiState()) }).collectAsState()
        val isPlaying = playerState.isPlaying
        val currentTitle = playerState.title

        // Persist listening time only after a track has accumulated at least 10 seconds.
        // Any partial time is committed when playback is paused or the track changes.
        LaunchedEffect(controller, dao) {
            var trackedId: String? = null
            var pendingMs = 0L
            var lastSample = System.currentTimeMillis()
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val active = controller
                val mediaId = active?.currentMediaItem?.mediaId
                if (mediaId != trackedId) {
                    val previousId = trackedId
                    if (previousId != null && pendingMs >= 10_000L) {
                        val commit = pendingMs
                        withContext(Dispatchers.IO) { dao.addPlayDuration(previousId, commit) }
                    }
                    trackedId = mediaId
                    pendingMs = 0L
                }
                if (active?.isPlaying == true && !mediaId.isNullOrBlank()) {
                    pendingMs += (now - lastSample).coerceIn(0L, 1_500L)
                    if (pendingMs >= 10_000L) {
                        val commit = pendingMs
                        pendingMs = 0L
                        withContext(Dispatchers.IO) { dao.addPlayDuration(mediaId, commit) }
                    }
                } else if (pendingMs >= 10_000L && !trackedId.isNullOrBlank()) {
                    val commit = pendingMs
                    pendingMs = 0L
                    withContext(Dispatchers.IO) { dao.addPlayDuration(trackedId!!, commit) }
                }
                lastSample = now
            }
        }
        val currentArtist = playerState.artist

        DisposableEffect(controller, listenTogether) {
            val active = controller
            if (active == null) onDispose { }
            else {
                val syncListener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (!listenTogether.canBroadcastLocal()) return
                        val meta = mediaItem?.mediaMetadata ?: return
                        val id = mediaItem.mediaId
                        if (id.isNotBlank() && listenTogether.state.value.connected) {
                            listenTogether.control("setTrack", positionMs = active.currentPosition.coerceAtLeast(0L),
                                track = JamTrack(id, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), meta.artworkUri?.toString(), active.duration.takeIf { it > 0L } ?: 0L),
                                isPlaying = active.isPlaying)
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (listenTogether.canBroadcastLocal() && listenTogether.state.value.connected) listenTogether.control(if (isPlaying) "play" else "pause", positionMs = active.currentPosition.coerceAtLeast(0L))
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && listenTogether.canBroadcastLocal() && listenTogether.state.value.connected) listenTogether.control("seek", positionMs = newPosition.positionMs.coerceAtLeast(0L))
                    }
                }
                active.addListener(syncListener)
                onDispose { active.removeListener(syncListener) }
            }
        }

        // Persist every newly selected media item from Media3 callbacks. This
        // also covers tracks started from Home, playlists, and system controls.
        DisposableEffect(controller, dao) {
            val activePlayer = controller
            if (activePlayer == null) {
                onDispose { }
            } else {
                var lastRecordedMediaId: String? = null
                val historyListener = object : Player.Listener {
                    private fun record(item: MediaItem?) {
                        val songId = item?.mediaId?.takeIf { it.isNotBlank() } ?: return
                        val metadata = item.mediaMetadata
                        scope.launch(Dispatchers.IO) {
                            dao.recordPlay(
                                PlayHistoryEntity(
                                    songId = songId,
                                    title = metadata.title?.toString().orEmpty(),
                                    artist = metadata.artist?.toString().orEmpty(),
                                    coverUrl = metadata.artworkUri?.toString()
                                )
                            )
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val nextId = mediaItem?.mediaId
                        if (!nextId.isNullOrBlank() && nextId != lastRecordedMediaId) {
                            lastRecordedMediaId = nextId
                            record(mediaItem)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            val current = activePlayer.currentMediaItem
                            if (current?.mediaId != lastRecordedMediaId) {
                                lastRecordedMediaId = current?.mediaId
                                record(current)
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            val current = activePlayer.currentMediaItem
                            if (current?.mediaId != lastRecordedMediaId) {
                                lastRecordedMediaId = current?.mediaId
                                record(current)
                            }
                        }
                    }
                }
                activePlayer.addListener(historyListener)
                onDispose { activePlayer.removeListener(historyListener) }
            }
        }

        val playSong: (Song) -> Unit = { song ->
            val activeController = controller
            if (activeController != null) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setTrackNumber(song.trackNumber ?: 0)
                    .setArtworkUri(song.coverUrl?.let(Uri::parse))
                    .build()
                val placeholder = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setMediaMetadata(metadata)
                    .build()
                activeController.setMediaItem(placeholder)
                if (listenTogether.state.value.connected) listenTogether.control("setTrack", positionMs = 0L, track = JamTrack(song.id, song.title, song.artist, song.coverUrl, song.durationSec * 1000L), isPlaying = true)
                resolvingSongId = song.id
                playbackLoadError = null
                nav.navigate("nowplaying") { launchSingleTop = true }

                scope.launch {
                    val result = runCatching {
                        val localFile = withContext(Dispatchers.IO) {
                            dao.download(song.id)?.takeIf { item ->
                                item.status == com.parker.tunes.data.DownloadEntity.STATUS_COMPLETE &&
                                    File(item.filePath).isFile && File(item.filePath).length() > 0L
                            }?.let { File(it.filePath) }
                        }
                        if (localFile != null) Uri.fromFile(localFile) to null
                        else {
                            val addonStream = if (api is AddonMusicApi) {
                                withContext(Dispatchers.IO) { api.getAddonStream(song.id) }
                            } else null
                            if (addonStream != null) Uri.parse(addonStream.url) to addonStream.format.mimeType
                            else Uri.parse(withContext(Dispatchers.IO) { api.getStreamUrl(song.id) }) to null
                        }
                    }
                    if (resolvingSongId == song.id) {
                        result.onSuccess { (playbackUri, mimeType) ->
                            if (playbackUri == null || playbackUri.toString().isBlank()) {
                                playbackLoadError = "Couldn't load this song. Tap the song again to retry."
                            } else {
                                val playableItem = MediaItem.Builder()
                                    .setMediaId(song.id)
                                    .setUri(playbackUri)
                                    .apply { mimeType?.let(::setMimeType) }
                                    .setMediaMetadata(metadata)
                                    .build()
                                activeController.setMediaItem(playableItem)
                                activeController.prepare()
                                activeController.play()
                            }
                        }.onFailure {
                            playbackLoadError = it.message ?: "Couldn't load this song. Tap the song again to retry."
                        }
                        resolvingSongId = null
                    }
                }
            }
        }

        val queueSong: (Song, Boolean) -> Unit = { song, playNext ->
            val activeController = controller
            if (activeController != null) scope.launch {
                runCatching {
                    val (uri, mimeType) = withContext(Dispatchers.IO) {
                        val local = dao.download(song.id)?.takeIf {
                            it.status == com.parker.tunes.data.DownloadEntity.STATUS_COMPLETE &&
                                File(it.filePath).isFile && File(it.filePath).length() > 0L
                        }
                        if (local != null) Uri.fromFile(File(local.filePath)) to null
                        else {
                            val addonStream = if (api is AddonMusicApi) api.getAddonStream(song.id) else null
                            if (addonStream != null) Uri.parse(addonStream.url) to addonStream.format.mimeType
                            else Uri.parse(api.getStreamUrl(song.id)) to null
                        }
                    }
                    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                        .setAlbumTitle(song.album).setTrackNumber(song.trackNumber ?: 0)
                        .setArtworkUri(song.coverUrl?.let(Uri::parse)).build()
                    MediaItem.Builder().setMediaId(song.id).setUri(uri).apply { mimeType?.let(::setMimeType) }
                        .setMediaMetadata(metadata).build()
                }.onSuccess { item ->
                    val insertAt = if (playNext) (activeController.currentMediaItemIndex + 1).coerceAtLeast(0) else activeController.mediaItemCount
                    activeController.addMediaItem(insertAt, item)
                    if (listenTogether.state.value.connected) listenTogether.addToQueue(JamTrack(song.id, song.title, song.artist, song.coverUrl, song.durationSec * 1000L), playNext)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = if (playNext) "Queued to play next: ${song.title}" else "Added to queue: ${song.title}",
                            actionLabel = "Undo",
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed &&
                            insertAt < activeController.mediaItemCount &&
                            activeController.getMediaItemAt(insertAt).mediaId == song.id
                        ) activeController.removeMediaItem(insertAt)
                    }
                }.onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Couldn't add this song to the queue.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                AnimatedVisibility(
                    visible = currentRoute != "nowplaying",
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 }
                ) {
                    Column(Modifier.fillMaxWidth().background(Color.Black).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedVisibility(visible = playerState.hasItem, enter = fadeIn(), exit = fadeOut()) {
                            MiniPlayer(title = currentTitle, artist = currentArtist, coverUrl = playerState.artworkUri,
                                playing = isPlaying, onClick = { nav.navigate("nowplaying") },
                                onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                                onNext = { controller?.sendCustomCommand(SessionCommand("com.parker.tunes.action.SKIP_NEXT", Bundle.EMPTY), Bundle.EMPTY) })
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.weight(1f).height(68.dp).clip(RoundedCornerShape(28.dp))
                                    .background(Color(0xFF1A1A1A)).padding(horizontal = 5.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf("Home", "Explore", "Library", "Together").forEach { item ->
                                    val active = selectedTab == item
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                                            .background(if (active) Color(0xFF34303B) else Color.Transparent)
                                            .clickable {
                                                selectedTab = item
                                                nav.navigate(when (item) { "Home" -> "home"; "Explore" -> "explore"; "Library" -> "library"; else -> "together" }) {
                                                    launchSingleTop = true
                                                }
                                            }.padding(vertical = 5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            when (item) { "Home" -> Icons.Default.Home; "Explore" -> Icons.Default.GridView
                                                "Library" -> Icons.Default.LibraryBooks; else -> Icons.Default.Groups },
                                            contentDescription = item,
                                            tint = if (active) Color.White else Color(0xFFA7A7A7),
                                            modifier = Modifier.size(21.dp)
                                        )
                                        Text(
                                            item, color = if (active) Color.White else Color(0xFFA7A7A7),
                                            fontSize = 10.sp, maxLines = 1, softWrap = false,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF242424))
                                .clickable {
                                    selectedTab = "Search"
                                    nav.navigate("search") { launchSingleTop = true }
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, "Search", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.padding(padding)
            ) {
                composable("home") {
                    HomeScreen(
                        dao = dao, api = api, player = controller, onPlay = playSong,
                        onQueue = { song, playNext -> queueSong(song, playNext) },
                        onSettings = { nav.navigate("settings") },
                        onSearch = { nav.navigate("search") { launchSingleTop = true } },
                        onExplore = { nav.navigate("explore") { launchSingleTop = true } },
                        homeApi = app.homeApi,
                        onOpenPlaylist = { item, shelf ->
                            val browseId = item.browseId.orEmpty()
                            if (browseId.startsWith("VL") || browseId.startsWith("MPRE")) {
                                selectedHomeShelf = shelf
                                nav.navigate("playlist/${Uri.encode(browseId)}?params=${Uri.encode(item.params.orEmpty())}")
                            } else if (item.videoId != null) {
                                playSong(Song(item.videoId, item.title, item.subtitle.orEmpty(), item.thumbnailUrl))
                            }
                        }
                    )
                }
                composable("explore") {
                    ExploreScreen(onMood = { mood -> nav.navigate("mood/" + Uri.encode(mood)) })
                }
                composable("mood/{mood}") { entry ->
                    val mood = Uri.decode(entry.arguments?.getString("mood").orEmpty())
                    MoodResultsScreen(mood = mood, api = api, onBack = { nav.popBackStack() }, onPlay = playSong)
                }
                composable("search") {
                    SearchScreen(api = api, dao = dao, settingsRepository = settingsRepository, downloads = app.downloads, onLowStorage = { scope.launch { snackbarHostState.showSnackbar("Low storage · free up at least 200 MB first.") } }, onPlay = playSong, onQueue = queueSong, onSettings = { nav.navigate("settings") }, onArtist = { selectedArtist = it; nav.navigate("artist") })
                }
                composable("artist") {
                    selectedArtist?.let { artist ->
                        ArtistProfileScreen(artist = artist, api = api, onBack = { nav.popBackStack() }, onPlay = playSong,
                            onAlbum = { album -> selectedAlbum = album; nav.navigate("album") })
                    }
                }
                composable("album") {
                    selectedAlbum?.let { album ->
                        AlbumScreen(album = album, api = api, onBack = { nav.popBackStack() }, onPlay = playSong)
                    }
                }
                composable(
                    route = "playlist/{browseId}?params={params}",
                    arguments = listOf(
                        androidx.navigation.navArgument("browseId") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("params") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) {
                    selectedHomeShelf?.let { shelf ->
                        HomePlaylistScreen(shelf = shelf, onBack = { nav.popBackStack() }, onPlay = playSong)
                    }
                }
                composable("library") { LibraryScreen(dao, app.downloads, playSong, onOpenAddons = { nav.navigate("addons") }) }
                composable("together") { ListenTogetherScreen(client = listenTogether, player = controller, api = api, onBack = { nav.popBackStack() }) }
                composable("addons") { AddonsScreen(app.database.sourceRegistryDao()) { nav.popBackStack() } }
                composable("stats") { StatsScreen(dao = dao, onBack = { nav.popBackStack() }) }
                composable("equalizer") {
                    EqualizerScreen(manager = equalizerManager, onBack = { nav.popBackStack() }, onChanged = persistEqualizer)
                }
                composable("settings") {
                    SettingsScreen(
                        dao = dao,
                        repository = settingsRepository,
                        settingsPrefs = settingsPrefs,
                        onOpenEqualizer = { nav.navigate("equalizer") },
                        onOpenStats = { nav.navigate("stats") },
                        onOpenDownloads = { nav.navigate("library") }
                    )
                }
                composable(
                    route = "nowplaying",
                    enterTransition = {
                        fadeIn(animationSpec = tween(240)) +
                            slideInVertically(animationSpec = tween(280)) { it / 12 }
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(180)) +
                            slideOutVertically(animationSpec = tween(220)) { it / 10 }
                    }
                ) {
                    NowPlayingScreen(
                        controller, playerObserver, playerState, api, dao,
                        isResolving = resolvingSongId != null &&
                            resolvingSongId == controller?.currentMediaItem?.mediaId,
                        loadError = playbackLoadError,
                        onOpenEqualizer = { nav.navigate("equalizer") },
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(kicker.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 30.sp,
            fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun HomeScreen(
    dao: com.parker.tunes.data.LibraryDao,
    onPlay: (Song) -> Unit
) {
    // Room Flow collection is moved to IO; Home never calls the music network API.
    val recentFlow = remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }
    val favoriteFlow = remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }
    val mostPlayedFlow = remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }
    val recent by recentFlow.collectAsState(initial = emptyList())
    val favorites by favoriteFlow.collectAsState(initial = emptyList())
    val mostPlayed by mostPlayedFlow.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "YOUR MUSIC, ALWAYS CLOSE",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    "Good to have you back",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("Pick up right where you left off.", color = Muted, fontSize = 13.sp)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeSectionHeading("RECENTLY PLAYED", "Jump back in")
                if (recent.isEmpty()) {
                    Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(vertical = 24.dp)) {
                        EmptyState(
                            Icons.Default.History,
                            "Nothing here yet",
                            "Search for your first song!"
                        )
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recent, key = { it.id }) { entry ->
                            HomeSongCard(
                                song = Song(
                                    id = entry.songId,
                                    title = entry.title.ifBlank { entry.songId },
                                    artist = entry.artist,
                                    coverUrl = entry.coverUrl
                                ),
                                onClick = {
                                    onPlay(Song(
                                        id = entry.songId,
                                        title = entry.title.ifBlank { entry.songId },
                                        artist = entry.artist,
                                        coverUrl = entry.coverUrl
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeSectionHeading("QUICK ACCESS", "Favorites")
                if (favorites.isEmpty()) {
                    Text(
                        "Songs you favorite will be ready here.",
                        color = Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 22.dp)
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(favorites.take(12), key = { it.songId }) { entry ->
                            HomeSongCard(
                                song = Song(
                                    id = entry.songId,
                                    title = entry.title.ifBlank { entry.songId },
                                    artist = entry.artist,
                                    coverUrl = entry.coverUrl
                                ),
                                onClick = {
                                    onPlay(Song(
                                        id = entry.songId,
                                        title = entry.title.ifBlank { entry.songId },
                                        artist = entry.artist,
                                        coverUrl = entry.coverUrl
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("LISTEN AGAIN", "Your most played")
                if (mostPlayed.isEmpty()) {
                    Text(
                        "Play a few songs and your regulars will appear here.",
                        color = Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 22.dp)
                    )
                } else {
                    Column(
                        Modifier.padding(horizontal = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        mostPlayed.forEach { entry ->
                            HomeListenAgainRow(
                                entry = entry,
                                onClick = {
                                    onPlay(Song(
                                        id = entry.songId,
                                        title = entry.title.ifBlank { entry.songId },
                                        artist = entry.artist,
                                        coverUrl = entry.coverUrl
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeading(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp
        )
        Text(title, color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeSongCard(song: Song, onClick: () -> Unit) {
    Column(
        Modifier.width(142.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier.size(142.dp).shadow(7.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (song.coverUrl.isNullOrBlank()) {
                Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp))
            } else {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = "${song.title} cover art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            song.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
        Text(
            song.artist,
            color = Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp)
        )
    }
}

@Composable
private fun HomeListenAgainRow(
    entry: com.parker.tunes.data.PlayCountItem,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.coverUrl.isNullOrBlank()) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            } else {
                AsyncImage(
                    model = entry.coverUrl,
                    contentDescription = "Cover art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title.ifBlank { entry.songId }, color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.artist, color = Muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text(
            "${entry.playCount} ${if (entry.playCount == 1) "play" else "plays"}",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MiniPlayer(
    title: String, artist: String, coverUrl: String?, playing: Boolean,
    onClick: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit
) {
    val miniDrag = remember { Animatable(0f) }
    val miniDragScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .graphicsLayer {
                translationY = miniDrag.value
                alpha = 1f - ((-miniDrag.value) / 150f).coerceIn(0f, 0.22f)
                scaleX = 1f - ((-miniDrag.value) / 150f).coerceIn(0f, 0.22f) * 0.025f
                scaleY = scaleX
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF01D1D1D))
            .pointerInput(onClick, density) {
                var verticalDrag = 0f
                val threshold = with(density) { 48.dp.toPx() }
                val exitDistance = size.height.toFloat().coerceAtLeast(threshold * 2)
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount < 0f || verticalDrag < 0f) {
                            verticalDrag = (verticalDrag + dragAmount).coerceAtMost(0f)
                            change.consume()
                            miniDragScope.launch { miniDrag.snapTo(verticalDrag) }
                        }
                    },
                    onDragEnd = {
                        val shouldExpand = verticalDrag < -threshold
                        verticalDrag = 0f
                        miniDragScope.launch {
                            if (shouldExpand) {
                                miniDrag.animateTo(
                                    -exitDistance,
                                    tween(190, easing = FastOutSlowInEasing)
                                )
                                onClick()
                                miniDrag.snapTo(0f)
                            } else {
                                miniDrag.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        verticalDrag = 0f
                        miniDragScope.launch {
                            miniDrag.animateTo(
                                0f,
                                spring(dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow)
                            )
                        }
                    }
                )
            }
            .clickable(onClick = onClick)
            .animateContentSize(animationSpec = tween(220))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var coverFailed by remember(coverUrl) { mutableStateOf(false) }
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303030)),
            contentAlignment = Alignment.Center) {
            if (coverUrl.isNullOrBlank() || coverFailed) {
                Icon(Icons.Default.Album, null, tint = Color.White)
            } else {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Current cover art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { coverFailed = true }
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(artist, color = Color(0xFFA7A7A7), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlayPause) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                "Play or pause", tint = Color.White)
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
    }
}

@Composable
private fun AmLyricsWebView(
    trackId: String,
    title: String,
    artist: String,
    durationMs: Long,
    currentTimeMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestDuration by rememberUpdatedState(durationMs)
    val trackJson = remember(trackId, title, artist, durationMs, currentTimeMs, isPlaying) {
        JSONObject()
            .put("id", trackId)
            .put("title", title)
            .put("artist", artist)
            .put("query", listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("positionMs", currentTimeMs.coerceAtLeast(0L))
            .put("playing", isPlaying)
            .toString()
    }
    val latestTrackJson by rememberUpdatedState(trackJson)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun seekTo(timestampMs: Double) {
                if (!timestampMs.isFinite()) return
                val requested = timestampMs.toLong().coerceAtLeast(0L)
                mainHandler.post {
                    val safeTimestamp = if (latestDuration > 0L) {
                        requested.coerceAtMost(latestDuration)
                    } else {
                        requested
                    }
                    latestOnSeek(safeTimestamp)
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(context)
                )
                .build()

            WebView(context).apply {
                if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(bridge, "ParkerLyricsBridge")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        Log.d("ParkerLyricsWeb", "JS ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                        return true
                    }
                }
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val response = assetLoader.shouldInterceptRequest(request.url)
                        if (request.url.toString().contains("/assets/lyrics/")) {
                            Log.d("ParkerLyricsWeb", "Asset request: ${request.url} | ${if (response != null) "served by asset loader" else "not handled by asset loader"}")
                        }
                        return response
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("ParkerLyricsWeb", "Page finished: $url")
                        view.evaluateJavascript(
                            "({pageReady: !!document.readyState, trackHandler: typeof window.setParkerTrack, componentDefined: !!customElements.get('am-lyrics')})",
                        ) { result -> Log.d("ParkerLyricsWeb", "Page state: $result") }
                        view.evaluateJavascript(
                            "if (window.setParkerTrack) { window.setParkerTrack($latestTrackJson); 'track-delivered'; } else { 'track-handler-missing'; }",
                        ) { result -> Log.d("ParkerLyricsWeb", "Track delivery: $result") }
                    }

                }
                loadUrl("https://appassets.androidplatform.net/assets/lyrics/index.html")
            }
        },
        update = { webView ->
            webView.evaluateJavascript(
                "if (window.setParkerTrack) { window.setParkerTrack($trackJson); 'track-delivered'; } else { 'track-handler-not-ready'; }",
            ) { result -> Log.d("ParkerLyricsWeb", "Updated track delivery: $result") }
        }
    )
}

@Composable
private fun NowPlayingScreen(
    player: MediaController?,
    playerObserver: PlayerUiStateObserver?,
    playerState: PlayerUiState,
    api: MusicApi,
    dao: com.parker.tunes.data.LibraryDao,
    isResolving: Boolean,
    loadError: String?,
    onOpenEqualizer: () -> Unit,
    onBack: () -> Unit
) {
    val progressFlow = remember(playerObserver) {
        playerObserver?.progress ?: kotlinx.coroutines.flow.MutableStateFlow(com.parker.tunes.player.PlayerProgress())
    }
    val progress by progressFlow.collectAsState()
    val duration = progress.durationMs.coerceAtLeast(0L)
    val position = progress.positionMs.coerceAtLeast(0L)
        .coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    val playing = playerState.isPlaying
    val title = playerState.title
    val artist = playerState.artist
    val coverUrl = playerState.artworkUri
    val coverCandidates = remember(coverUrl, player?.currentMediaItem?.mediaId) {
        playerCoverCandidates(coverUrl, player?.currentMediaItem?.mediaId)
    }
    var coverCandidateIndex by remember(coverUrl) { mutableIntStateOf(0) }
    var coverImageLoaded by remember(coverUrl) { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    val shuffle = playerState.shuffleEnabled
    val repeat = playerState.repeatMode
    val accent = Color(0xFFB69CFF)
    val muted = Color(0xFF96939F)
    val scrollState = rememberScrollState()
    val dragAnimation = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dragOffset = dragAnimation.value
    val progressValue = if (seeking) seekValue
        else position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragOffset
                val progress = (dragOffset / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                alpha = 1f - progress * 0.22f
                scaleX = 1f - progress * 0.035f
                scaleY = scaleX
            }
            .background(Color(0xFF08080B))
            .pointerInput(onBack, scrollState, density) {
                var totalDrag = 0f
                val dismissThreshold = with(density) { 120.dp.toPx() }
                val exitDistance = size.height.toFloat().coerceAtLeast(dismissThreshold * 2)
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if ((scrollState.value == 0 && dragAmount > 0f) ||
                            (totalDrag > 0f && dragAmount != 0f)) {
                            totalDrag = (totalDrag + dragAmount).coerceAtLeast(0f)
                            change.consume()
                            dragScope.launch { dragAnimation.snapTo(totalDrag) }
                        }
                    },
                    onDragEnd = {
                        val shouldDismiss = totalDrag > dismissThreshold
                        totalDrag = 0f
                        dragScope.launch {
                            if (shouldDismiss) {
                                dragAnimation.animateTo(
                                    exitDistance,
                                    tween(240, easing = FastOutSlowInEasing)
                                )
                                onBack()
                            } else {
                                dragAnimation.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        totalDrag = 0f
                        dragScope.launch {
                            dragAnimation.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
            }
            .verticalScroll(scrollState)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close player",
                    tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", color = Color.White, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
                Text("PARKERTUNES", color = muted, fontSize = 9.sp,
                    letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenEqualizer) {
                Icon(Icons.Default.GraphicEq, contentDescription = "Equalizer",
                    tint = accent, modifier = Modifier.size(23.dp))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .aspectRatio(1f)
                .shadow(28.dp, RoundedCornerShape(30.dp), clip = false)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF33284A), Color(0xFF17151F), Color(0xFF282238))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (coverCandidates.isNotEmpty() && coverCandidateIndex < coverCandidates.size) {
                AsyncImage(
                    model = coverCandidates[coverCandidateIndex],
                    contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { coverImageLoaded = true },
                    onError = {
                        coverImageLoaded = false
                        if (coverCandidateIndex < coverCandidates.lastIndex) coverCandidateIndex++
                    }
                )
            }
            if (!coverImageLoaded) {
                Icon(Icons.Default.Album, contentDescription = null,
                    tint = Color.White.copy(alpha = .82f), modifier = Modifier.size(88.dp))
            }
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = .10f))
                    )
                )
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        title.ifBlank { "Nothing playing" },
                        color = Color.White,
                        fontSize = 25.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        artist.ifBlank { "Choose a song to start listening" },
                        color = muted,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenEqualizer) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Open equalizer",
                        tint = accent, modifier = Modifier.size(25.dp))
                }
            }
            if (isResolving) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), color = accent, strokeWidth = 2.dp)
                    Text("Preparing your music…", color = accent, fontSize = 12.sp)
                }
            } else if (!loadError.isNullOrBlank()) {
                Text(loadError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Slider(
                value = progressValue,
                onValueChange = {
                    seeking = true
                    seekValue = it
                    player?.seekTo(it.toLong())
                },
                onValueChangeFinished = { seeking = false },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color(0xFF34313C)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (seeking) seekValue.toLong() else position),
                    color = muted, fontSize = 11.sp)
                Text(formatTime(duration), color = muted, fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player?.shuffleModeEnabled = !shuffle }) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle",
                    tint = if (shuffle) accent else muted, modifier = Modifier.size(23.dp))
            }
            IconButton(onClick = { player?.seekToPreviousMediaItem() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track",
                    tint = Color.White, modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = {
                    if (!isResolving) player?.let { if (it.isPlaying) it.pause() else it.play() }
                },
                enabled = !isResolving,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF111014),
                    disabledContainerColor = Color(0xFF77737F)
                )
            ) {
                if (isResolving) {
                    CircularProgressIndicator(Modifier.size(28.dp),
                        color = Color(0xFF111014), strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            IconButton(onClick = {
                player?.sendCustomCommand(
                    SessionCommand("com.parker.tunes.action.SKIP_NEXT", Bundle.EMPTY), Bundle.EMPTY
                )
            }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next track",
                    tint = Color.White, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = {
                player?.repeatMode = when (repeat) {
                    androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(Icons.Default.Repeat, contentDescription = "Repeat",
                    tint = if (repeat > 0) accent else muted, modifier = Modifier.size(23.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF15141A))
                .clickable(onClick = onOpenEqualizer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null,
                    tint = accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Audio settings", color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("Equalizer and sound controls", color = muted, fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open audio settings",
                tint = muted)
        }

        if (title.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF111015))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                AmLyricsWebView(
                    trackId = player?.currentMediaItem?.mediaId.orEmpty(),
                    title = title,
                    artist = artist,
                    durationMs = duration,
                    currentTimeMs = position,
                    isPlaying = playing,
                    onSeek = { timestamp -> player?.seekTo(timestamp) },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }
        }
    }
}

private fun playerCoverCandidates(coverUrl: String?, currentSongId: String? = null): List<String> {
    if (coverUrl.isNullOrBlank()) return emptyList()
    val videoId = currentSongId?.takeIf { it.length == 11 }
        ?: Regex("""/vi/([A-Za-z0-9_-]{11})/""").find(coverUrl)?.groupValues?.getOrNull(1)
    if (videoId != null) return listOf(
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    )
    return listOf(coverUrl)
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun LibraryScreen(
    dao: com.parker.tunes.data.LibraryDao,
    downloadManager: com.parker.tunes.data.SongDownloadManager,
    onPlay: (Song) -> Unit,
    onOpenAddons: () -> Unit = {}
) {
    var tab by remember { mutableStateOf("Playlists") }
    var showCreate by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<com.parker.tunes.data.PlaylistEntity?>(null) }
    val scope = rememberCoroutineScope()
    val playlists by dao.playlists().collectAsState(initial = emptyList())
    val favorites by remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val history by remember(dao) { dao.history().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val mostPlayed by remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val downloads by remember(dao) { dao.downloads().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    var showDeleteAllDownloads by remember { mutableStateOf(false) }
    val tracksFlow: kotlinx.coroutines.flow.Flow<List<com.parker.tunes.data.PlaylistTrackEntity>> = remember(selectedPlaylist?.id) {
        selectedPlaylist?.let { dao.playlistTracks(it.id) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val playlistTracks by tracksFlow.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (selectedPlaylist != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedPlaylist = null }) {
                    Icon(Icons.Default.ArrowBack, "Back to playlists")
                }
                Column {
                    Text("PLAYLIST", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(selectedPlaylist!!.name, color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (playlistTracks.isEmpty()) {
                EmptyState(Icons.Default.QueueMusic, "This playlist is empty",
                    "Add songs from the Search screen using the playlist button.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(playlistTracks, key = { it.songId }) { track ->
                        LibrarySongRow(track.title.ifBlank { track.songId }, track.artist, Icons.Default.MusicNote)
                    }
                }
            }
        } else {
            ScreenTitle("Keep it close", "Your library", "Everything you want to come back to.")
            ScrollableTabRow(selectedTabIndex = listOf("Playlists", "Favorites", "Most Played", "History", "Downloads", "Addons").indexOf(tab)) {
                listOf("Playlists", "Favorites", "Most Played", "History", "Downloads", "Addons").forEach { label ->
                    Tab(selected = tab == label, onClick = { tab = label }, text = { Text(label, fontSize = 12.sp) })
                }
            }
            when (tab) {
                "Playlists" -> {
                    Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create playlist")
                    }
                    if (playlists.isEmpty()) {
                        EmptyState(Icons.Default.QueueMusic, "No playlists yet", "Create a playlist to organise your music.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { selectedPlaylist = playlist }.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(playlist.name, modifier = Modifier.weight(1f).padding(start = 12.dp),
                                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                }
                            }
                        }
                    }
                }
                "Favorites" -> if (favorites.isEmpty()) {
                    EmptyState(Icons.Default.FavoriteBorder, "No favourites yet", "Tap the heart on a search result to save it.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favorites, key = { it.songId }) {
                        LibrarySongRow(it.title.ifBlank { it.songId }, it.artist, Icons.Default.Favorite)
                    }
                }
                "Most Played" -> if (mostPlayed.isEmpty()) {
                    EmptyState(Icons.Default.BarChart, "Nothing played enough yet", "Your most-played songs will appear here as you listen.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mostPlayed, key = { it.songId }) { track ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    onPlay(Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl))
                                }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)).background(Panel),
                                contentAlignment = Alignment.Center) {
                                if (track.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                                else AsyncImage(model = track.coverUrl, contentDescription = "Most played cover",
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(track.title.ifBlank { track.songId }, color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist.ifBlank { "Unknown artist" }, color = Muted, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${track.playCount} plays", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                "History" -> if (history.isEmpty()) {
                    EmptyState(Icons.Default.History, "No listening history", "Tracks you play will appear here.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) {
                        LibrarySongRow(it.title.ifBlank { it.songId }, it.artist, Icons.Default.History)
                    }
                }
                "Addons" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connect compatible music servers to search their catalogues alongside YouTube Music.", color = Muted, fontSize = 13.sp)
                        Button(onClick = onOpenAddons, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Extension, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Manage addons")
                        }
                    }
                }
                else -> {
                    val complete = downloads.filter { it.status == com.parker.tunes.data.DownloadEntity.STATUS_COMPLETE && File(it.filePath).isFile }
                    val totalBytes = complete.sumOf { it.fileSizeBytes }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${complete.size} songs · ${formatFileSize(totalBytes)}",
                                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text("Saved on this device", color = Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { showDeleteAllDownloads = true }, enabled = complete.isNotEmpty()) {
                                Text("Delete all")
                            }
                        }
                        if (complete.isEmpty()) {
                            EmptyState(Icons.Default.Download, "No downloads yet", "Songs you download will appear here.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(complete, key = { it.songId }) { item ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable {
                                                onPlay(Song(item.songId, item.title, item.artist, item.coverUrl, item.durationSec))
                                            }.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(9.dp))
                                            .background(Panel), contentAlignment = Alignment.Center) {
                                            if (item.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                                            else AsyncImage(model = item.coverUrl, contentDescription = "Downloaded cover",
                                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        }
                                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                            Text(item.title, color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${item.artist} · ${formatFileSize(item.fileSizeBytes)}",
                                                color = Muted, fontSize = 11.sp, maxLines = 1)
                                        }
                                        IconButton(onClick = { scope.launch(Dispatchers.IO) { downloadManager.remove(item.songId) } }) {
                                            Icon(Icons.Default.Delete, "Remove download", tint = Muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAllDownloads) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDownloads = false },
            title = { Text("Delete all downloads?") },
            text = { Text("This removes all downloaded audio files from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAllDownloads = false
                    scope.launch(Dispatchers.IO) { downloadManager.removeAll() }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllDownloads = false }) { Text("Cancel") } }
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    singleLine = true,
                    label = { Text("Playlist name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = playlistName.isNotBlank(),
                    onClick = {
                        val name = playlistName.trim()
                        scope.launch {
                            dao.savePlaylist(
                                com.parker.tunes.data.PlaylistEntity(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = name
                                )
                            )
                        }
                        playlistName = ""
                        showCreate = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0)
    else if (mb >= 1.0) String.format("%.1f MB", mb)
    else String.format("%.0f KB", bytes.coerceAtLeast(0L) / 1024.0)
}

@Composable
private fun LibrarySongRow(title: String, artist: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}


@Composable
private fun HomePlaylistScreen(shelf: HomeShelf, onBack: () -> Unit, onPlay: (Song) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text(shelf.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        shelf.strapline?.let { Text(it, color = Color.LightGray, fontSize = 13.sp) }
        LazyColumn(contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shelf.items, key = { it.browseId ?: it.videoId ?: it.title }) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable {
                        item.videoId?.let { onPlay(Song(it, item.title, item.subtitle.orEmpty(), item.thumbnailUrl)) }
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!item.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(model = item.thumbnailUrl, contentDescription = item.title,
                            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    } else Icon(Icons.Default.MusicNote, null, tint = Color.LightGray, modifier = Modifier.size(54.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.subtitle.orEmpty(), color = Color.LightGray, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
