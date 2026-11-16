package com.parker.tunes

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.navigation.compose.*
import com.google.common.util.concurrent.ListenableFuture
import com.parker.tunes.data.PlayHistoryEntity
import com.parker.tunes.data.BackupManager
import com.parker.tunes.data.EqualizerSettingsEntity
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.model.Artist
import com.parker.tunes.model.Album
import com.parker.tunes.model.Song
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
    val dao = app.database.libraryDao()
    val scope = rememberCoroutineScope()
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
    var selectedTab by remember { mutableStateOf("Home") }
    val settingsPrefs = remember { context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE) }
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
        primary = Accent,
        background = Ink,
        surface = Panel,
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

        // Count only active playback time for listening statistics.
        LaunchedEffect(controller) {
            while (true) {
                delay(5_000L)
                val active = controller
                val mediaId = active?.currentMediaItem?.mediaId
                if (active?.isPlaying == true && !mediaId.isNullOrBlank()) {
                    withContext(Dispatchers.IO) { dao.addPlayDuration(mediaId, 5_000L) }
                }
            }
        }
        val currentArtist = playerState.artist

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
                    .setArtworkUri(song.coverUrl?.let(Uri::parse))
                    .build()
                val placeholder = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setMediaMetadata(metadata)
                    .build()
                activeController.setMediaItem(placeholder)
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
                        if (localFile != null) Uri.fromFile(localFile)
                        else Uri.parse(withContext(Dispatchers.IO) { api.getStreamUrl(song.id) })
                    }
                    if (resolvingSongId == song.id) {
                        result.onSuccess { playbackUri ->
                            if (playbackUri == null || playbackUri.toString().isBlank()) {
                                playbackLoadError = "Couldn't load this song. Tap the song again to retry."
                            } else {
                                val playableItem = MediaItem.Builder()
                                    .setMediaId(song.id)
                                    .setUri(playbackUri)
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
                    val uri = withContext(Dispatchers.IO) {
                        val local = dao.download(song.id)?.takeIf {
                            it.status == com.parker.tunes.data.DownloadEntity.STATUS_COMPLETE &&
                                File(it.filePath).isFile && File(it.filePath).length() > 0L
                        }
                        if (local != null) Uri.fromFile(File(local.filePath))
                        else Uri.parse(api.getStreamUrl(song.id))
                    }
                    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                        .setArtworkUri(song.coverUrl?.let(Uri::parse)).build()
                    MediaItem.Builder().setMediaId(song.id).setUri(uri).setMediaMetadata(metadata).build()
                }.onSuccess { item ->
                    val insertAt = if (playNext) (activeController.currentMediaItemIndex + 1).coerceAtLeast(0) else activeController.mediaItemCount
                    activeController.addMediaItem(insertAt, item)
                    Toast.makeText(context, if (playNext) "Playing next: ${song.title}" else "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                }.onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Couldn't add this song to the queue.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Scaffold(
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
                                onNext = { controller?.seekToNextMediaItem() })
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(36.dp))
                                .background(Color(0xFF1A1A1A)).padding(horizontal = 10.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                                listOf("Home", "Explore", "Library").forEach { item ->
                                    val active = selectedTab == item
                                    Row(Modifier.weight(1f).widthIn(min = 24.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                                        .clickable {
                                            selectedTab = item
                                            nav.navigate(when (item) { "Home" -> "home"; "Explore" -> "explore"; else -> "library" }) {
                                                launchSingleTop = true
                                            }
                                        }.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                        Icon(when (item) { "Home" -> Icons.Default.Home; "Explore" -> Icons.Default.GridView
                                            else -> Icons.Default.LibraryBooks }, item,
                                            tint = if (active) Color.White else Color(0xFFA7A7A7), modifier = Modifier.size(26.dp))
                                        if (active) Text(item, color = Color.White, fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF242424))
                                .clickable {
                                    selectedTab = "Search"
                                    nav.navigate("search") { launchSingleTop = true }
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, "Search", tint = Color.White, modifier = Modifier.size(25.dp))
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
                    SpotifyHomeScreen(dao = dao, api = api, player = controller, onPlay = playSong, onSettings = { nav.navigate("settings") }, onSearch = { selectedTab = "Search"; nav.navigate("search") })
                }
                composable("explore") {
                    ExploreScreen(onMood = { mood -> nav.navigate("mood/" + Uri.encode(mood)) })
                }
                composable("mood/{mood}") { entry ->
                    val mood = Uri.decode(entry.arguments?.getString("mood").orEmpty())
                    MoodResultsScreen(mood = mood, api = api, onBack = { nav.popBackStack() }, onPlay = playSong)
                }
                composable("search") {
                    SearchScreen(api = api, dao = dao, downloads = app.downloads, onPlay = playSong, onQueue = queueSong, onSettings = { nav.navigate("settings") }, onArtist = { selectedArtist = it; nav.navigate("artist") })
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
                composable("library") { LibraryScreen(dao, app.downloads, playSong) }
                composable("stats") { StatsScreen(dao = dao, onBack = { nav.popBackStack() }) }
                composable("equalizer") {
                    EqualizerScreen(manager = equalizerManager, onBack = { nav.popBackStack() }, onChanged = persistEqualizer)
                }
                composable("settings") {
                    SettingsScreen(
                        dao = dao,
                        settingsPrefs = settingsPrefs,
                        lightMode = lightMode,
                        onOpenEqualizer = { nav.navigate("equalizer") },
                        onOpenStats = { nav.navigate("stats") },
                        addonApi = api as? AddonMusicApi,
                        preferredRefreshRate = preferredRefreshRate,
                        onRefreshRate = { requestedRate ->
                            preferredRefreshRate = requestedRate
                            settingsPrefs.edit().putInt("preferred_refresh_rate", requestedRate).apply()
                            applyPreferredRefreshRate(context as Activity, requestedRate)
                        },
                        onLightMode = {
                            lightMode = it
                            settingsPrefs.edit().putBoolean("light_mode", it).apply()
                        }
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
                        controller, playerObserver, playerState,
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF01D1D1D))
            .clickable(onClick = onClick)
            .animateContentSize(animationSpec = tween(220))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303030)),
            contentAlignment = Alignment.Center) {
            if (coverUrl.isNullOrBlank()) Icon(Icons.Default.Album, null, tint = Color.White)
            else AsyncImage(model = coverUrl, contentDescription = "Current cover art",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
private fun NowPlayingScreen(
    player: MediaController?,
    playerObserver: PlayerUiStateObserver?,
    playerState: PlayerUiState,
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
    val position = progress.positionMs.coerceAtLeast(0L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    val playing = playerState.isPlaying
    val title = playerState.title
    val artist = playerState.artist
    val coverUrl = playerState.artworkUri
    val coverCandidates = remember(coverUrl) { playerCoverCandidates(coverUrl) }
    var coverCandidateIndex by remember(coverUrl) { mutableIntStateOf(0) }
    var coverImageLoaded by remember(coverUrl) { mutableStateOf(false) }
    val coverImageAlpha by animateFloatAsState(
        targetValue = if (coverImageLoaded) 1f else 0f,
        animationSpec = tween(360),
        label = "coverFadeIn"
    )
    val coverImageScale by animateFloatAsState(
        targetValue = if (coverImageLoaded) 1f else 0.975f,
        animationSpec = tween(420),
        label = "coverLoadScale"
    )
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    val dragAnimation = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    var coverExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { coverExpanded = true }
    val coverEntryScale by animateFloatAsState(
        targetValue = if (coverExpanded) 1f else 0.88f,
        animationSpec = tween(320),
        label = "coverExpandTransition"
    )
    val dragOffset = dragAnimation.value
    val coverScale = (1f - (dragOffset / 900f).coerceIn(0f, 0.16f))
    val shuffle = playerState.shuffleEnabled
    val repeat = playerState.repeatMode

    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = dragOffset; alpha = 1f - (dragOffset / 1000f).coerceIn(0f, 0.12f) }
            .pointerInput(onBack) {
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0f || totalDrag > 0f) {
                            totalDrag = (totalDrag + dragAmount).coerceAtLeast(0f)
                            val fingerPosition = totalDrag
                            dragScope.launch { dragAnimation.snapTo(fingerPosition) }
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (totalDrag > 135f) {
                            onBack()
                        } else {
                            dragScope.launch {
                                dragAnimation.animateTo(0f, spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ))
                            }
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = {
                        dragScope.launch {
                            dragAnimation.animateTo(0f, spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ))
                        }
                        totalDrag = 0f
                    }
                )
            }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Back") }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, letterSpacing = 2.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Icons.Default.MoreHoriz, "More") }
        }
        val coverPulse by animateFloatAsState(
            targetValue = if (isResolving && !coverImageLoaded) 0.965f else 1f,
            animationSpec = tween(650),
            label = "coverLoadingPulse"
        )
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 26.dp)
                .graphicsLayer {
                    scaleX = coverScale * coverEntryScale * coverPulse
                    scaleY = coverScale * coverEntryScale * coverPulse
                }
                .shadow(22.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF30264B), Color(0xFF171D31), Color(0xFF44345B))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (coverCandidates.isNotEmpty() && coverCandidateIndex < coverCandidates.size) {
                AsyncImage(
                    model = coverCandidates[coverCandidateIndex],
                    contentDescription = "Album cover",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = coverImageAlpha
                        scaleX = coverImageScale
                        scaleY = coverImageScale
                    },
                    contentScale = ContentScale.Crop,
                    onSuccess = { coverImageLoaded = true },
                    onError = {
                        if (coverCandidateIndex < coverCandidates.lastIndex) coverCandidateIndex++
                    }
                )
            } else {
                Icon(Icons.Default.Album, null, tint = Color.White.copy(alpha = .82f),
                    modifier = Modifier.size(112.dp))
            }
            if (!coverImageLoaded) {
                CircularProgressIndicator(
                    Modifier.size(28.dp), color = Color.White.copy(alpha = .72f), strokeWidth = 2.dp
                )
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Muted, fontSize = 15.sp)
            if (isResolving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Loading stream…", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            } else if (!loadError.isNullOrBlank()) {
                Text(loadError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Slider(
                value = if (seeking) seekValue else position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = {
                    seeking = true
                    seekValue = it
                    player?.seekTo(it.toLong())
                },
                onValueChangeFinished = { seeking = false },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (seeking) seekValue.toLong() else position), color = Muted, fontSize = 11.sp)
                Text(formatTime(duration), color = Muted, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player?.shuffleModeEnabled = !shuffle }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else Muted)
            }
            IconButton(onClick = { player?.seekToPreviousMediaItem() }) {
                Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { if (!isResolving) player?.let { if (it.isPlaying) it.pause() else it.play() } },
                enabled = !isResolving,
                modifier = Modifier.size(68.dp)
            ) {
                if (isResolving) CircularProgressIndicator(
                    Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp
                ) else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause",
                    modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { player?.seekToNextMediaItem() }) {
                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onOpenEqualizer) {
                Icon(Icons.Default.GraphicEq, "Equalizer", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                player?.repeatMode = when (repeat) {
                    androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (repeat > 0) MaterialTheme.colorScheme.primary else Muted)
            }
            IconButton(onClick = onOpenEqualizer) {
                Icon(Icons.Default.GraphicEq, "Open equalizer", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun playerCoverCandidates(coverUrl: String?): List<String> {
    if (coverUrl.isNullOrBlank()) return emptyList()
    val videoId = Regex("""/vi/([A-Za-z0-9_-]{11})/""")
        .find(coverUrl)?.groupValues?.getOrNull(1)
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
    onPlay: (Song) -> Unit
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
            TabRow(selectedTabIndex = listOf("Playlists", "Favorites", "History", "Downloads").indexOf(tab)) {
                listOf("Playlists", "Favorites", "History", "Downloads").forEach { label ->
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
                "History" -> if (history.isEmpty()) {
                    EmptyState(Icons.Default.History, "No listening history", "Tracks you play will appear here.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) {
                        LibrarySongRow(it.title.ifBlank { it.songId }, it.artist, Icons.Default.History)
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
fun SettingsScreen(
    dao: com.parker.tunes.data.LibraryDao,
    settingsPrefs: android.content.SharedPreferences,
    lightMode: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenStats: () -> Unit,
    addonApi: AddonMusicApi?,
    preferredRefreshRate: Int,
    onRefreshRate: (Int) -> Unit,
    onLightMode: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backupManager = remember(dao, context, settingsPrefs) { BackupManager(context, dao, settingsPrefs) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { backupManager.exportTo(uri) }
                .onSuccess { backupStatus = "Backup exported successfully." }
                .onFailure { backupStatus = it.message ?: "Could not export backup." }
        }
    }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { backupManager.importFrom(uri) }
                .onSuccess { count -> backupStatus = "Backup merged. $count new library items imported." }
                .onFailure { backupStatus = it.message ?: "Could not import backup." }
        }
    }
    var smartAutoplay by remember { mutableStateOf(settingsPrefs.getBoolean("smart_autoplay", true)) }
    var crossfadeSeconds by remember { mutableIntStateOf(settingsPrefs.getInt("crossfade_seconds", 0).coerceIn(0, 12)) }
    var addonUrl by remember(addonApi) {
        mutableStateOf(addonApi?.configuredAddonUrl.orEmpty())
    }
    var addonStatus by remember { mutableStateOf<String?>(null) }
    var checkingAddon by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenTitle("Make it yours", "Settings", "A few things to shape your listening.")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sound & Audio", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenStats).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text("Listening stats", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Text("Your listening time, top artists and songs", color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, "Open listening stats", tint = Muted)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenEqualizer).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium)
                    Text("Presets, frequency bands, bass and spatial sound",
                        color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, "Open equalizer", tint = Muted)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Light appearance", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text("Switch between dark and light mode", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = lightMode, onCheckedChange = onLightMode)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Backup & restore", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Export playlists, favourites, listening history and sound settings to a JSON file.",
                color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { exportBackup.launch("ParkerTunes-backup.json") },
                    modifier = Modifier.weight(1f)) { Text("Export") }
                Button(onClick = { importBackup.launch(arrayOf("application/json", "text/*")) },
                    modifier = Modifier.weight(1f)) { Text("Import") }
            }
            backupStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Playback", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smart autoplay", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Text("Play related songs when your queue ends", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = smartAutoplay, onCheckedChange = {
                    smartAutoplay = it
                    settingsPrefs.edit().putBoolean("smart_autoplay", it).apply()
                })
            }
            Text("Track transition fade · ${crossfadeSeconds}s", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium)
            Slider(
                value = crossfadeSeconds.toFloat(),
                onValueChange = {
                    crossfadeSeconds = it.toInt().coerceIn(0, 12)
                    settingsPrefs.edit().putInt("crossfade_seconds", crossfadeSeconds).apply()
                },
                valueRange = 0f..12f,
                steps = 11
            )
            Text("0 seconds disables the fade. ExoPlayer keeps playlist transitions gapless; the fade gently lowers and restores volume at track boundaries.",
                color = Muted, fontSize = 11.sp)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Display smoothness", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Choose the refresh-rate preference ParkerTunes requests from Android.",
                color = Muted, fontSize = 12.sp)
            listOf(60, 96, 120).forEach { rate ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onRefreshRate(rate) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = preferredRefreshRate == rate, onClick = { onRefreshRate(rate) })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("$rate Hz", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(when (rate) {
                            60 -> "Standard refresh rate"
                            96 -> "Balanced smoothness"
                            else -> "Maximum smoothness"
                        }, color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Text("Your phone must support a refresh rate for it to run at that exact speed. Android and the display may choose the closest supported mode, and battery use can increase at higher rates.",
                color = Muted, fontSize = 11.sp)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Optional music source", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "Connect a BitChord-compatible addon. Its tracks will appear alongside YouTube results.",
                color = Muted, fontSize = 12.sp
            )
            OutlinedTextField(
                value = addonUrl,
                onValueChange = { addonUrl = it; addonStatus = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Addon HTTPS URL") },
                placeholder = { Text("https://example.com") }
            )
            Button(
                onClick = {
                    scope.launch {
                        checkingAddon = true
                        addonStatus = "Checking addon manifest…"
                        runCatching {
                            requireNotNull(addonApi) { "Addon support is unavailable." }
                                .validateAddon(addonUrl)
                        }.onSuccess { name ->
                            addonApi?.configureAddon(addonUrl)
                            addonStatus = "Connected: $name. Its tracks will appear in Search."
                        }.onFailure { failure ->
                            addonStatus = failure.message ?: "Could not connect to this addon."
                        }
                        checkingAddon = false
                    }
                },
                enabled = !checkingAddon && addonUrl.isNotBlank() && addonApi != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checkingAddon) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…")
                } else Text("Verify and connect")
            }
            if (addonApi?.configuredAddonUrl?.isNotBlank() == true) {
                TextButton(
                    onClick = {
                        addonApi.configureAddon("")
                        addonUrl = ""
                        addonStatus = "Addon disconnected. YouTube remains available."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Disconnect addon") }
            }
            addonStatus?.let { Text(it, color = Muted, fontSize = 12.sp) }
            Text(
                "Addons must provide /manifest.json, /search and /stream/{id} over HTTPS. " +
                    "BitChord-only authenticated addons are not supported yet.",
                color = Muted, fontSize = 11.sp
            )
        }
        Text("ParkerTunes  •  Version 1.0.0", color = Muted, fontSize = 12.sp)
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
