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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.*
import com.google.common.util.concurrent.ListenableFuture
import com.parker.tunes.data.PlayHistoryEntity
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.MusicApi
import com.parker.tunes.model.Song
import com.parker.tunes.player.PlaybackService
import com.parker.tunes.ui.theme.ParkerTunesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Search") }
    var lightMode by remember {
        mutableStateOf(context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("light_mode", false))
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
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
        val isPlaying = controller?.isPlaying == true
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(controller) {
            while (true) {
                refresh++
                delay(500)
            }
        }
        val currentTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        val currentArtist = controller?.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "Choose a track"

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    AnimatedVisibility(
                        visible = controller?.currentMediaItem != null,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        MiniPlayer(
                            title = currentTitle,
                            artist = currentArtist,
                            coverUrl = controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
                            playing = isPlaying,
                            onClick = { nav.navigate("nowplaying") },
                            onPlayPause = {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            },
                            onNext = { controller?.seekToNextMediaItem() }
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("Search", "Library", "Settings").forEach { item ->
                            NavigationBarItem(
                                selected = selectedTab == item,
                                onClick = {
                                    selectedTab = item
                                    nav.navigate(item.lowercase()) { launchSingleTop = true }
                                },
                                icon = {
                                    Icon(
                                        when (item) {
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
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "search",
                modifier = Modifier.padding(padding)
            ) {
                composable("search") {
                    SearchScreen(query, { query = it }, api, dao) { song ->
                        scope.launch {
                            runCatching {
                                val url = api.getStreamUrl(song.id)
                                val item = MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(song.title)
                                            .setArtist(song.artist)
                                            .setArtworkUri(song.coverUrl?.let(Uri::parse))
                                            .build()
                                    )
                                    .build()
                                controller?.setMediaItem(item)
                                controller?.prepare()
                                controller?.play()
                                dao.recordPlay(PlayHistoryEntity(songId = song.id, title = song.title, artist = song.artist))
                                nav.navigate("nowplaying")
                            }
                        }
                    }
                }
                composable("library") { LibraryScreen(dao) }
                composable("settings") {
                    SettingsScreen(lightMode) {
                        lightMode = it
                        context.getSharedPreferences("parker_tunes_settings", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("light_mode", it).apply()
                    }
                }
                composable("nowplaying") {
                    NowPlayingScreen(controller, refresh, currentTitle, currentArtist, controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()) {
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
fun SearchScreen(
    query: String, onQuery: (String) -> Unit, api: MusicApi,
    dao: com.parker.tunes.data.LibraryDao, onPlay: (Song) -> Unit
) {
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, api) {
        if (query.isBlank()) {
            results = emptyList()
            error = null
            loading = false
        } else {
            delay(350)
            loading = true
            error = null
            runCatching { api.search(query.trim()) }
                .onSuccess { results = it }
                .onFailure { error = it.message ?: "Search is temporarily unavailable." }
            loading = false
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
            error != null -> EmptyState(Icons.Default.CloudOff, "Search couldn't load", error!!)
            results.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.id }) { song ->
                    SongRow(song = song, dao = dao, onClick = { onPlay(song) })
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
        }
        IconButton(onClick = { showPlaylistPicker = true }) {
            Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist", tint = Muted)
        }
        IconButton(onClick = {
            scope.launch {
                if (favorite) dao.removeFavorite(song.id)
                else dao.addFavorite(FavoriteEntity(songId = song.id, title = song.title, artist = song.artist))
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
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
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
    player: MediaController?, refresh: Int, title: String, artist: String, coverUrl: String?, onBack: () -> Unit
) {
    val duration = (player?.duration ?: 0L).coerceAtLeast(0L)
    val position = (player?.currentPosition ?: 0L).coerceAtLeast(0L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    val playing = player?.isPlaying == true
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
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
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 26.dp)
                .clip(RoundedCornerShape(28.dp)).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF30264B), Color(0xFF171D31), Color(0xFF44345B))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl.isNullOrBlank()) {
                Icon(Icons.Default.Album, null, tint = Color.White.copy(alpha = .82f), modifier = Modifier.size(112.dp))
            } else {
                AsyncImage(model = coverUrl, contentDescription = "Album cover",
                    modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Muted, fontSize = 15.sp)
            Slider(
                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { player?.seekTo(it.toLong()) },
                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Muted, fontSize = 11.sp)
                Text(formatTime(duration), color = Muted, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { shuffle = !shuffle; player?.shuffleModeEnabled = shuffle }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else Muted)
            }
            IconButton(onClick = { player?.seekToPreviousMediaItem() }) {
                Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                modifier = Modifier.size(68.dp)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause",
                    modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { player?.seekToNextMediaItem() }) {
                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = {
                repeat = (repeat + 1) % 3
                player?.repeatMode = when (repeat) {
                    1 -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    2 -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (repeat > 0) MaterialTheme.colorScheme.primary else Muted)
            }
        }
    }
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
    val favorites by dao.favorites().collectAsState(initial = emptyList())
    val history by dao.history().collectAsState(initial = emptyList())
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
fun SettingsScreen(lightMode: Boolean, onLightMode: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
