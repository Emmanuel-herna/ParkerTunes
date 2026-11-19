package com.parker.tunes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import com.parker.tunes.data.HomeShelfCacheEntity
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.SettingsRepository
import com.parker.tunes.data.home.*
import com.parker.tunes.model.Song
import com.parker.tunes.recommendation.AdaptiveHomeFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dao: LibraryDao,
    api: MusicApi,
    player: MediaController?,
    onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onExplore: () -> Unit,
    homeApi: YtMusicHomeApi,
    settingsRepository: SettingsRepository,
    downloads: com.parker.tunes.data.SongDownloadManager,
    onOpenPlaylist: (ShelfItem, HomeShelf) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val imageLoader = context.imageLoader

    val recent by remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val favorites by remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val mostPlayed by remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val listeningEvents by remember(dao) { dao.observeListeningEvents().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val personalizationEnabled by settingsRepository.recommendationPersonalization.collectAsState(initial = true)
    val reduceSimilar by settingsRepository.reduceSimilarMusic.collectAsState(initial = false)
    val discoveryDiversity by settingsRepository.discoveryDiversity.collectAsState(initial = 50)
    val reduceMotion by settingsRepository.reduceMotion.collectAsState(initial = false)
    val recommendationViewModel = remember(api) { HomeViewModel(api) }
    val recommendationState by recommendationViewModel.state.collectAsState()
    var historyReady by remember(dao) { mutableStateOf(false) }
    var currentSongId by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId) }

    DisposableEffect(recommendationViewModel) {
        onDispose { recommendationViewModel.close() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSongId = mediaItem?.mediaId
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(dao) {
        withContext(Dispatchers.IO) { dao.recentlyPlayed().first() }
        historyReady = true
    }
    LaunchedEffect(historyReady, recent, favorites, listeningEvents, personalizationEnabled, reduceSimilar, discoveryDiversity) {
        if (historyReady) {
            val history = withContext(Dispatchers.IO) { dao.historySnapshot() }
            val historySongs = history.map { Song(it.songId, it.title.ifBlank { it.songId }, it.artist, it.coverUrl) }
            val recentSongs = recent.map { Song(it.songId, it.title.ifBlank { it.songId }, it.artist, it.coverUrl) }
            val favoriteSongs = favorites.map { Song(it.songId, it.title.ifBlank { it.songId }, it.artist, it.coverUrl) }
            recommendationViewModel.onMusicSignals(listeningEvents, favorites, personalizationEnabled, discoveryDiversity, reduceSimilar)
            val historyIds = historySongs.map { it.id }.toSet()
            recommendationViewModel.onListeningHistory(
                historySongs.ifEmpty { recentSongs } + favoriteSongs.filterNot { it.id in historyIds }
            )
        }
    }

    var discovery by remember { mutableStateOf<List<Song>>(emptyList()) }
    var newReleases by remember { mutableStateOf<List<Song>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Song>>(emptyList()) }
    var discoveryLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        coroutineScope {
            val discoveryTask = async { runCatching { api.getMoodSongs("discover new music") }.getOrDefault(emptyList()) }
            val releasesTask = async { runCatching { api.getMoodSongs("new music releases") }.getOrDefault(emptyList()) }
            val trendingTask = async { runCatching { api.getTrending() }.getOrDefault(emptyList()) }
            discovery = discoveryTask.await().distinctBy { it.id }.take(5)
            newReleases = releasesTask.await().distinctBy { it.id }.take(5)
            trending = trendingTask.await().distinctBy { it.id }.take(5)
            discoveryLoaded = true
        }
    }

    val favoriteIds = remember(favorites) { favorites.map { it.songId }.toSet() }
    val toggleFavorite: (Song) -> Unit = { song ->
        scope.launch(Dispatchers.IO) {
            if (dao.isFavorite(song.id)) dao.removeFavorite(song.id)
            else dao.addFavorite(com.parker.tunes.data.FavoriteEntity(
                songId = song.id, title = song.title, artist = song.artist, coverUrl = song.coverUrl
            ))
        }
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { scope.launch {
            discoveryLoaded = false
            discovery = runCatching { api.getMoodSongs("discover new music") }.getOrDefault(emptyList()).distinctBy { it.id }.take(5)
            newReleases = runCatching { api.getMoodSongs("new music releases") }.getOrDefault(emptyList()).distinctBy { it.id }.take(5)
            trending = runCatching { api.getTrending() }.getOrDefault(emptyList()).distinctBy { it.id }.take(5)
            discoveryLoaded = true
        } },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentPadding = PaddingValues(top = 8.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "home_top_bar") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ParkerTunes", color = Color(0xFFD6C2B9), fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.size(38.dp).clip(CircleShape)
                            .background(Color(0xFFB8B8B8).copy(alpha = 0.10f))
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings",
                            tint = Color(0xFFD9D9D9), modifier = Modifier.size(23.dp))
                    }
                }
            }

            item(key = "quick_picks") {
                QuickPicksSection(
                    songs = recommendationState.songs.take(5),
                    loading = recommendationState.loading,
                    onPlay = onPlay, onQueue = onQueue, onDownload = downloads::enqueue,
                    isFavorite = { it.id in favoriteIds }, onFavorite = toggleFavorite
                )
            }

            item(key = "made_for_you") {
                Column(Modifier.padding(top = 5.dp)) {
                    HomeSectionHeading("Made for you")
                    RecommendationShelf(
                        songs = recommendationState.songs.drop(5).take(3),
                        loading = recommendationState.loading,
                        loadingMore = false,
                        listState = rememberLazyListState(),
                        onPlay = onPlay,
                        onLongPress = { },
                        onQueue = onQueue,
                        onDownload = downloads::enqueue,
                        isFavorite = { it.id in favoriteIds },
                        onFavorite = toggleFavorite,
                        reduceMotion = reduceMotion
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "recently_played") {
                    SongShelfSection("Recently played", recent.take(5).map {
                        Song(it.songId, it.title.ifBlank { it.songId }, it.artist, it.coverUrl)
                    }, currentSongId, onPlay, onQueue, downloads::enqueue, { it.id in favoriteIds }, toggleFavorite)
                }
            }

            if (mostPlayed.isNotEmpty()) {
                item(key = "heavy_rotation") {
                    SongShelfSection("Heavy Rotation", mostPlayed.take(5).map {
                        Song(it.songId, it.title.ifBlank { it.songId }, it.artist, it.coverUrl)
                    }, currentSongId, onPlay, onQueue, downloads::enqueue, { it.id in favoriteIds }, toggleFavorite)
                }
            }

            item(key = "daily_discovery") {
                SongShelfSection("Daily Discovery", discovery, currentSongId, onPlay, onQueue,
                    downloads::enqueue, { it.id in favoriteIds }, toggleFavorite,
                    emptyMessage = if (discoveryLoaded) "No discovery results available right now." else "Finding something new…")
            }

            item(key = "new_releases") {
                SongShelfSection("New Releases", newReleases, currentSongId, onPlay, onQueue,
                    downloads::enqueue, { it.id in favoriteIds }, toggleFavorite,
                    emptyMessage = if (discoveryLoaded) "New release results aren't available right now." else "Loading new releases…")
            }

            item(key = "trending") {
                SongShelfSection("Trending", trending, currentSongId, onPlay, onQueue,
                    downloads::enqueue, { it.id in favoriteIds }, toggleFavorite,
                    emptyMessage = if (discoveryLoaded) "Trending tracks aren't available right now." else "Loading trending tracks…")
            }

            if (favorites.isNotEmpty()) {
                item(key = "your_favorites") {
                    SongShelfSection("Your Favorites", favorites.take(5).map {
                        Song(it.songId, it.title, it.artist, it.coverUrl)
                    }, currentSongId, onPlay, onQueue, downloads::enqueue, { true }, toggleFavorite)
                }
            }
        }
    }
}


@Composable
private fun QuickPicksSection(
    songs: List<Song>, loading: Boolean, onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit, onDownload: (Song) -> Unit,
    isFavorite: (Song) -> Boolean, onFavorite: (Song) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Quick Picks", Modifier.weight(1f), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                songs.firstOrNull()?.let(onPlay)
                songs.drop(1).forEach { onQueue(it, false) }
            }, enabled = songs.isNotEmpty()) { Text("Play all") }
        }
        if (songs.isEmpty()) {
            Text(if (loading) "Finding songs for you…" else "Your quick picks will appear here.",
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = Color(0xFF999999), fontSize = 13.sp)
        } else songs.take(5).forEachIndexed { index, song ->
            QuickPickRow(index + 1, song, onPlay, onQueue, onDownload, isFavorite, onFavorite)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPickRow(
    number: Int, song: Song, onPlay: (Song) -> Unit, onQueue: (Song, Boolean) -> Unit,
    onDownload: (Song) -> Unit, isFavorite: (Song) -> Boolean, onFavorite: (Song) -> Unit,
    reduceMotion: Boolean = false
) {
    var menu by remember(song.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClick = { onPlay(song) }, onLongClick = { menu = true })
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number.toString(), Modifier.width(23.dp), color = Color(0xFF888888), fontSize = 12.sp)
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF171717)),
            contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = Color.LightGray)
            else AsyncImage(model = song.coverUrl, contentDescription = song.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "Unknown artist" }, color = Color(0xFF999999), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text("⋮", color = Color.White, fontSize = 21.sp)
            }
            SongActionMenu(menu, { menu = false }, song, onPlay, onQueue, onDownload,
                isFavorite(song)) { onFavorite(song) }
        }
    }
}

@Composable
private fun SongShelfSection(
    title: String, songs: List<Song>, currentSongId: String?,
    onPlay: (Song) -> Unit, onQueue: (Song, Boolean) -> Unit, onDownload: (Song) -> Unit,
    isFavorite: (Song) -> Boolean, onFavorite: (Song) -> Unit, emptyMessage: String? = null
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        HomeSectionHeading(title)
        if (songs.isEmpty()) {
            if (emptyMessage != null) Text(emptyMessage, Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color(0xFF888888), fontSize = 12.sp)
        } else LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(songs.take(5), key = { title + "_" + it.id }) { song ->
                ActionSongCard(song, currentSongId == song.id, onPlay, onQueue, onDownload,
                    isFavorite(song)) { onFavorite(song) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionSongCard(
    song: Song, playing: Boolean, onPlay: (Song) -> Unit, onQueue: (Song, Boolean) -> Unit,
    onDownload: (Song) -> Unit, favorite: Boolean, onFavorite: (Song) -> Unit
) {
    var menu by remember(song.id) { mutableStateOf(false) }
    Column(Modifier.width(132.dp).combinedClickable(
        onClick = { onPlay(song) }, onLongClick = { menu = true }
    ), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF171717)),
            contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null,
                tint = Color.LightGray, modifier = Modifier.size(30.dp))
            else AsyncImage(model = song.coverUrl, contentDescription = song.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (playing) PlayingBars(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            SongActionMenu(menu, { menu = false }, song, onPlay, onQueue, onDownload, favorite) {
                onFavorite(song)
            }
        }
        Text(song.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist.ifBlank { "Unknown artist" }, color = Color(0xFF999999), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SongActionMenu(
    expanded: Boolean, dismiss: () -> Unit, song: Song, onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit, onDownload: (Song) -> Unit,
    favorite: Boolean, onFavorite: () -> Unit, reduceMotion: Boolean = false
) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(text = { Text("Play") }, onClick = { dismiss(); onPlay(song) })
        DropdownMenuItem(text = { Text("Play next") }, onClick = { dismiss(); onQueue(song, true) })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { dismiss(); onQueue(song, false) })
        DropdownMenuItem(text = { Text("Download") }, onClick = { dismiss(); onDownload(song) })
        DropdownMenuItem(text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") },
            onClick = { dismiss(); onFavorite() })
    }
}

@Composable
private fun FeaturedRecentCard(
    song: Song?,
    sectionLabel: String,
    onPlay: (Song) -> Unit,
    onSearch: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 5.dp)
            .height(178.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(sectionLabel, color = Color(0xFFE6E6E6), fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.graphicsLayer { rotationZ = -90f })
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth(0.78f).fillMaxHeight().align(Alignment.CenterStart)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF171717))
                .clickable { if (song != null) onPlay(song) else onSearch() },
                contentAlignment = Alignment.Center
            ) {
                if (!song?.coverUrl.isNullOrBlank()) {
                    AsyncImage(model = song?.coverUrl, contentDescription = song?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)))
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                    ))
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(song?.title.orEmpty(), color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song?.artist?.ifBlank { "Unknown artist" }.orEmpty(),
                            color = Color(0xFFD0D0D0), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (song != null) Icons.Default.MusicNote else Icons.Default.Explore,
                            contentDescription = null, tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            if (song != null) song.title else "Discover something new",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (song != null) song.artist.ifBlank { "Tap to play" }
                            else "Explore music picked for you",
                            color = Color(0xFFAAAAAA), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueListeningCard(song: Song, onPlay: (Song) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF686868), RoundedCornerShape(18.dp))
            .background(Color(0xFF080808))
            .clickable { onPlay(song) }.padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape)
            
            .background(Color(0xFF151515)), contentAlignment = Alignment.Center) {
            if (!song.coverUrl.isNullOrBlank()) {
                AsyncImage(song.coverUrl, contentDescription = song.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else Icon(Icons.Default.Album, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CONTINUE LISTENING", color = Color(0xFFB7B7B7),
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "Unknown artist" }, color = Color(0xFFC0B8CA),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black,
            modifier = Modifier.size(38.dp).clip(CircleShape).background(Color.White).padding(7.dp))
    }
}

@Composable
private fun QuickAccessTile(
    title: String, subtitle: String, icon: @Composable () -> Unit,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF30243F)), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides Color(0xFFD1B8FF)) { icon() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeSectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color(0xFFE5E5E5), fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8F8F8F))
    }
}

@Composable
private fun RecommendationShelf(
    songs: List<Song>, loading: Boolean, loadingMore: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState, onPlay: (Song) -> Unit,
    onLongPress: (Song) -> Unit, onQueue: (Song, Boolean) -> Unit,
    onDownload: (Song) -> Unit, isFavorite: (Song) -> Boolean, onFavorite: (Song) -> Unit
) {
    if (songs.isEmpty() && !loading) {
        Text(if (loading) "Finding music based on your listening…" else "No close matches yet. Keep listening to build your radio.",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = maxWidth - 36.dp
        val cardGap = 10.dp
        val cardWidth = (pageWidth - cardGap * 2) / 3
        val pageCount = if (songs.isEmpty()) 1 else (songs.size + 8) / 9
        val activePage by remember(listState, pageCount) {
            derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1) }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pageCount, key = { "recommendation_page_$it" }) { page ->
                Column(
                    Modifier.width(pageWidth),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(3) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                            repeat(3) { column ->
                                val index = page * 9 + row * 3 + column
                                if (index < songs.size) {
                                    RecommendationGridTile(
                                        song = songs[index],
                                        width = cardWidth,
                                        onClick = { onPlay(songs[index]) },
                                        onLongPress = onLongPress,
                                        onQueue = onQueue,
                                        onDownload = onDownload,
                                        favorite = isFavorite(songs[index]),
                                        onFavorite = { onFavorite(songs[index]) },
                                        reduceMotion = reduceMotion
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (loadingMore) {
                item(key = "recommendation_loading_more") {
                    Box(Modifier.width(44.dp).height(pageWidth), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        if (pageCount > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount) { page ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (page == activePage) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (page == activePage) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendationGridTile(
    song: Song, width: Dp, onClick: () -> Unit, onLongPress: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit, onDownload: (Song) -> Unit,
    favorite: Boolean, onFavorite: () -> Unit
) {
    var artworkFailed by remember(song.coverUrl) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = if (reduceMotion) tween(durationMillis = 80) else spring(dampingRatio = 0.08f, stiffness = 650f),
        label = "recommendationTilePress"
    )
    var menu by remember(song.id) { mutableStateOf(false) }
    Column(
        Modifier.width(width).graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = { menu = true }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier.fillMaxWidth(0.94f).aspectRatio(1f)
                .clip(CircleShape)
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
        ) {
            if (!song.coverUrl.isNullOrBlank() && !artworkFailed) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = "${song.title} cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    onError = { artworkFailed = true }
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null,
                    tint = Color(0xFFBDBDBD), modifier = Modifier.size(30.dp))
            }
            SongActionMenu(menu, { menu = false }, song, { onClick() }, onQueue,
                onDownload, favorite, onFavorite)
        }
        Text(
            song.title.ifBlank { "Unknown track" },
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
        Text(
            song.artist.ifBlank { "Unknown artist" },
            color = Color(0xFF8F8F8F), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun HomeSongCard(song: Song, onClick: () -> Unit, width: Dp, reduceMotion: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = if (reduceMotion) tween(durationMillis = 80) else spring(dampingRatio = 0.08f, stiffness = 650f),
        label = "homeSongPress"
    )
    Column(Modifier.width(width).graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width * 0.86f).clip(CircleShape)
            
            .background(Color(0xFF151515)), contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null,
                tint = Color(0xFFBDBDBD), modifier = Modifier.size(34.dp))
            else AsyncImage(song.coverUrl, contentDescription = "${song.title} cover",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
        Text(song.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(song.artist.ifBlank { "Unknown artist" }, color = Color(0xFF8F8F8F), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeCardSkeleton() {
    Column(Modifier.width(126.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(126.dp).clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth(0.82f).height(12.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth(0.55f).height(10.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun HomeShelfSection(
    shelf: HomeShelf, currentSongId: String?, imageLoader: ImageLoader,
    onPlay: (Song) -> Unit, onQueue: (Song, Boolean) -> Unit, onOpen: (ShelfItem) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 9.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
            Text(shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            shelf.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        when (shelf.layout) {
            ShelfLayout.LIST, ShelfLayout.TRACKS -> Column {
                shelf.items.forEach { item ->
                    HomeTrackRow(item, currentSongId, imageLoader,
                        onClick = { if (item.videoId != null) onPlay(item.toSong()) else onOpen(item) },
                        onQueue = onQueue)
                }
            }
            ShelfLayout.VIDEO -> LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                    HomeVideoCard(item, currentSongId, imageLoader,
                        onClick = { if (item.videoId != null) onPlay(item.toSong()) else onOpen(item) },
                        onQueue = onQueue)
                }
            }
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                    HomeCard(item, currentSongId, imageLoader,
                        onClick = { if (item.videoId != null) onPlay(item.toSong()) else onOpen(item) },
                        onQueue = onQueue)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTrackRow(
    item: ShelfItem, currentSongId: String?, loader: ImageLoader,
    onClick: () -> Unit, onQueue: (Song, Boolean) -> Unit
) {
    var menu by remember(item.videoId) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(11.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            HomeArtwork(item.thumbnailUrl, 272, loader, Modifier.fillMaxSize(), ContentScale.Crop)
            if (item.videoId != null && item.videoId == currentSongId)
                PlayingBars(Modifier.align(Alignment.BottomEnd).padding(3.dp))
        }
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Box {
            Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
            TrackContextMenu(menu, { menu = false }, item, onClick, onQueue)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCard(
    item: ShelfItem, currentSongId: String?, loader: ImageLoader,
    onClick: () -> Unit, onQueue: (Song, Boolean) -> Unit
) {
    var menu by remember(item.videoId) { mutableStateOf(false) }
    Column(Modifier.width(160.dp).combinedClickable(
        onClick = onClick, onLongClick = { menu = true }
    ), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            HomeArtwork(item.thumbnailUrl, 544, loader, Modifier.fillMaxSize(), ContentScale.Crop)
            if (item.videoId != null && item.videoId == currentSongId)
                PlayingBars(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            TrackContextMenu(menu, { menu = false }, item, onClick, onQueue)
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeVideoCard(
    item: ShelfItem, currentSongId: String?, loader: ImageLoader,
    onClick: () -> Unit, onQueue: (Song, Boolean) -> Unit
) {
    var menu by remember(item.videoId) { mutableStateOf(false) }
    Column(Modifier.width(248.dp).combinedClickable(
        onClick = onClick, onLongClick = { menu = true }
    ), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            HomeArtwork(item.thumbnailUrl, 544, loader, Modifier.fillMaxSize(), ContentScale.Fit)
            if (item.videoId != null && item.videoId == currentSongId)
                PlayingBars(Modifier.align(Alignment.BottomEnd).padding(6.dp))
            TrackContextMenu(menu, { menu = false }, item, onClick, onQueue)
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackContextMenu(
    expanded: Boolean, dismiss: () -> Unit, item: ShelfItem,
    onPlay: () -> Unit, onQueue: (Song, Boolean) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(text = { Text("Play") }, onClick = { dismiss(); onPlay() })
        DropdownMenuItem(text = { Text("Play next") }, onClick = {
            dismiss(); if (item.videoId != null) onQueue(item.toSong(), true)
        })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
            dismiss(); if (item.videoId != null) onQueue(item.toSong(), false)
        })
    }
}

@Composable
private fun HomeArtwork(
    url: String?, sizePx: Int, loader: ImageLoader,
    modifier: Modifier, scale: ContentScale
) {
    if (url.isNullOrBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AsyncImage(model = url.artworkAt(sizePx), imageLoader = loader,
            contentDescription = null, contentScale = scale, modifier = modifier)
    }
}

internal fun String.artworkAt(sizePx: Int): String {
    val base = if (contains("=")) substringBeforeLast("=") else this
    return base + "=w" + sizePx + "-h" + sizePx + "-l90-rj"
}

@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playing_bars")
    val first by transition.animateFloat(0.25f, 1f,
        infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "eq1")
    val second by transition.animateFloat(1f, 0.3f,
        infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "eq2")
    val third by transition.animateFloat(0.4f, 0.9f,
        infiniteRepeatable(tween(490), RepeatMode.Reverse), label = "eq3")
    Row(modifier.clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(first, second, third).forEach {
            Box(Modifier.width(3.dp).height((5 + 12 * it).dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

@Composable
private fun HomeShelfSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(150.dp).height(17.dp).clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

private fun ShelfItem.toSong() = Song(
    id = videoId.orEmpty(), title = title, artist = subtitle.orEmpty(), coverUrl = thumbnailUrl
)

private fun encodeHomeShelves(shelves: List<HomeShelf>) = JSONArray().apply {
    shelves.forEach { shelf ->
        put(JSONObject().apply {
            put("title", shelf.title)
            put("strapline", shelf.strapline ?: JSONObject.NULL)
            put("layout", shelf.layout.name)
            put("items", JSONArray().apply {
                shelf.items.forEach { item ->
                    put(JSONObject().apply {
                        put("title", item.title)
                        put("subtitle", item.subtitle ?: JSONObject.NULL)
                        put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                        put("browseId", item.browseId ?: JSONObject.NULL)
                        put("params", item.params ?: JSONObject.NULL)
                        put("videoId", item.videoId ?: JSONObject.NULL)
                        put("isVideo", item.isVideo)
                    })
                }
            })
        })
    }
}.toString()

private fun decodeHomeShelves(json: String): List<HomeShelf> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { index ->
        val shelf = array.optJSONObject(index) ?: return@mapNotNull null
        val raw = shelf.optJSONArray("items") ?: JSONArray()
        val items = (0 until raw.length()).mapNotNull { itemIndex ->
            val item = raw.optJSONObject(itemIndex) ?: return@mapNotNull null
            fun value(key: String) = item.optString(key).takeIf { it.isNotBlank() && it != "null" }
            ShelfItem(item.optString("title"), value("subtitle"), value("thumbnailUrl"),
                value("browseId"), value("params"), value("videoId"), item.optBoolean("isVideo"))
        }
        HomeShelf(shelf.optString("title"),
            shelf.optString("strapline").takeIf { it.isNotBlank() && it != "null" },
            runCatching { ShelfLayout.valueOf(shelf.optString("layout")) }.getOrDefault(ShelfLayout.CARD),
            items)
    }
}.getOrDefault(emptyList())
