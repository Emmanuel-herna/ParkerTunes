package com.parker.tunes

import android.Manifest
import android.content.ComponentName
import android.net.Uri
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
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.model.Song
import com.parker.tunes.player.PlaybackService
import com.parker.tunes.player.PlayerUiState
import com.parker.tunes.player.PlayerUiStateObserver
import com.parker.tunes.ui.theme.ParkerTunesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ParkerTunesTheme { ParkerTunesRoot() } }
    }
}

private val Ink = Color(0xFF090B12)
private val Panel = Color(0xFF151925)
private val Accent = Color(0xFFB69CFF)
private val Muted = Color(0xFF9298AA)

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
    var selectedTab by remember { mutableStateOf("Home") }
    var lightMode by remember {
        mutableStateOf(context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("light_mode", false))
    }
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
        DisposableEffect(playerObserver) {
            onDispose { playerObserver?.close() }
        }
        val playerState by (playerObserver?.state
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayerUiState()) }).collectAsState()
        val isPlaying = playerState.isPlaying
        val currentTitle = playerState.title
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
                        withContext(Dispatchers.IO) { api.getStreamUrl(song.id) }
                    }
                    if (resolvingSongId == song.id) {
                        result.onSuccess { streamUrl ->
                            if (streamUrl.isBlank()) {
                                playbackLoadError = "Couldn't load this song. Tap the song again to retry."
                            } else {
                                val playableItem = MediaItem.Builder()
                                    .setMediaId(song.id)
                                    .setUri(streamUrl)
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

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                AnimatedVisibility(
                    visible = currentRoute != "nowplaying",
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 }
                ) {
                    Column {
                        AnimatedVisibility(
                            visible = playerState.hasItem,
                            enter = fadeIn(), exit = fadeOut()
                        ) {
                            MiniPlayer(
                                title = currentTitle,
                                artist = currentArtist,
                                coverUrl = playerState.artworkUri,
                                playing = isPlaying,
                                onClick = { nav.navigate("nowplaying") },
                                onPlayPause = {
                                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                },
                                onNext = { controller?.seekToNextMediaItem() }
                            )
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("Home", "Search", "Library", "Settings").forEach { item ->
                                NavigationBarItem(
                                    selected = selectedTab == item,
                                    onClick = {
                                        selectedTab = item
                                        nav.navigate(item.lowercase()) { launchSingleTop = true }
                                    },
                                    icon = {
                                        Icon(
                                            when (item) {
                                                "Home" -> Icons.Default.Home
                                                "Search" -> Icons.Default.Search
                                                "Library" -> Icons.Default.LibraryMusic
                                                else -> Icons.Default.Settings
                                            }, contentDescription = item
                                        )
                                    },
                                    label = { Text(item) }
                                )
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
                    HomeScreen(dao = dao, onPlay = playSong)
                }
                composable("search") {
                    SearchScreen(query, { query = it }, api, dao, onPlay = playSong)
                }
                composable("library") { LibraryScreen(dao) }
                composable("settings") {
                    SettingsScreen(lightMode, api as? AddonMusicApi) {
                        lightMode = it
                        context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("light_mode", it).apply()
                    }
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
                        loadError = playbackLoadError
                    ) {
                        nav.popBackStack()
                    }
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
fun SearchScreen(
    query: String, onQuery: (String) -> Unit, api: MusicApi,
    dao: com.parker.tunes.data.LibraryDao, onPlay: (Song) -> Unit
) {
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var moreError by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var retryToken by remember { mutableIntStateOf(0) }
    var moreRetryToken by remember { mutableIntStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val resultCache = remember(api) {
        object : LinkedHashMap<String, List<Song>>(24, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<Song>>?
            ): Boolean = size > 20
        }
    }

    LaunchedEffect(query, retryToken, api) {
        val key = query.trim()
        if (key.isBlank()) {
            results = emptyList()
            error = null
            moreError = null
            loading = false
            hasMore = false
        } else {
            val cached = resultCache[key]
            if (cached != null) {
                results = cached
                error = null
                loading = false
            } else {
                results = emptyList()
                loading = true
                error = null
                delay(250)
                runCatching { api.search(key) }
                    .onSuccess {
                        results = it
                        resultCache[key] = it
                        hasMore = it.isNotEmpty()
                    }
                    .onFailure {
                        error = it.message ?: "Search is temporarily unavailable."
                        hasMore = false
                    }
                loading = false
            }
            moreError = null
            listState.scrollToItem(0)
        }
    }

    // Request another page only when the user scrolls close to the bottom.
    // The first screen is always rendered from page one without waiting for covers.
    LaunchedEffect(query, api, listState, moreRetryToken) {
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            val key = query.trim()
            if (key.isNotBlank() && !loading && !loadingMore && hasMore &&
                results.isNotEmpty() && moreError == null && lastVisible >= results.size - 1
            ) {
                loadingMore = true
                moreError = null
                runCatching { api.searchMore(key) }
                    .onSuccess { more ->
                        if (more.isEmpty()) {
                            hasMore = false
                        } else {
                            results = (results + more).distinctBy { it.id }
                            resultCache[key] = results
                        }
                    }
                    .onFailure {
                        moreError = it.message ?: "Couldn't load more songs."
                    }
                loadingMore = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ScreenTitle("Your sound, your space", "Discover music", "Find something that feels like you.")
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search songs, artists...") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, null)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp)
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.fillMaxWidth().padding(top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmptyState(Icons.Default.CloudOff, "Search couldn't load", error!!)
                TextButton(onClick = { retryToken++ }) { Text("Tap to retry") }
            }
            results.isNotEmpty() -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.id }) { song ->
                    SongRow(song = song, dao = dao, onClick = { onPlay(song) })
                }
                if (loadingMore) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } }
                }
                if (moreError != null) {
                    item {
                        TextButton(
                            onClick = {
                                moreError = null
                                moreRetryToken++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Couldn't load more — tap to retry") }
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Default.GraphicEq,
                    if (query.isBlank()) "Your next favourite starts here" else "No tracks found",
                    if (query.isBlank()) "Search for a song or artist to get started."
                    else "The connected music provider returned no results."
                )
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, dao: com.parker.tunes.data.LibraryDao, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    var favorite by remember(song.id) { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val playlists by dao.playlists().collectAsState(initial = emptyList())
    LaunchedEffect(song.id) { favorite = dao.isFavorite(song.id) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = .16f)),
            contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            } else {
                AsyncImage(model = song.coverUrl, contentDescription = "Cover art",
                    modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Muted, fontSize = 13.sp, maxLines = 1)
            if (song.id.startsWith("addon:")) {
                Text("ADDON SOURCE", color = MaterialTheme.colorScheme.primary,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        IconButton(onClick = { showPlaylistPicker = true }) {
            Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist", tint = Muted)
        }
        IconButton(onClick = {
            scope.launch {
                if (favorite) dao.removeFavorite(song.id)
                else dao.addFavorite(FavoriteEntity(songId = song.id, title = song.title, artist = song.artist, coverUrl = song.coverUrl))
                favorite = !favorite
            }
        }) {
            Icon(
                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Toggle favourite",
                tint = if (favorite) Color(0xFFFF7D9C) else Muted
            )
        }
        Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
    }
    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text("Add to playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("Create a playlist in Your Library first.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        dao.addTrack(
                                            com.parker.tunes.data.PlaylistTrackEntity(
                                                playlistId = playlist.id,
                                                songId = song.id,
                                                position = System.currentTimeMillis().toInt(),
                                                title = song.title,
                                                artist = song.artist
                                            )
                                        )
                                    }
                                    showPlaylistPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(playlist.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistPicker = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MiniPlayer(
    title: String, artist: String, coverUrl: String?, playing: Boolean,
    onClick: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).animateContentSize(animationSpec = tween(220)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (coverUrl.isNullOrBlank()) {
            Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        } else {
            AsyncImage(model = coverUrl, contentDescription = "Current cover art",
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(artist, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onPlayPause) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next") }
    }
}

@Composable
private fun NowPlayingScreen(
    player: MediaController?,
    playerObserver: PlayerUiStateObserver?,
    playerState: PlayerUiState,
    isResolving: Boolean,
    loadError: String?,
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
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (coverImageLoaded) 1f else 0f },
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
            IconButton(onClick = {
                player?.repeatMode = when (repeat) {
                    androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (repeat > 0) MaterialTheme.colorScheme.primary else Muted)
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
fun LibraryScreen(dao: com.parker.tunes.data.LibraryDao) {
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
            TabRow(selectedTabIndex = listOf("Playlists", "Favorites", "History").indexOf(tab)) {
                listOf("Playlists", "Favorites", "History").forEach { label ->
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
                else -> if (history.isEmpty()) {
                    EmptyState(Icons.Default.History, "No listening history", "Tracks you play will appear here.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) {
                        LibrarySongRow(it.title.ifBlank { it.songId }, it.artist, Icons.Default.History)
                    }
                }
            }
        }
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
    lightMode: Boolean,
    addonApi: AddonMusicApi?,
    onLightMode: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
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
