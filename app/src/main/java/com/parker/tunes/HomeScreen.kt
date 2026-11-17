package com.parker.tunes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import coil.disk.DiskCache
import com.parker.tunes.data.HomeShelfCacheEntity
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.home.*
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

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
    onOpenPlaylist: (ShelfItem, HomeShelf) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val recommendationListState = rememberLazyListState()
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).diskCache {
            DiskCache.Builder().directory(context.cacheDir.resolve("parker-artwork"))
                .maxSizePercent(0.025).build()
        }.build()
    }

    val recentFlow = remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }
    val favoritesFlow = remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }
    val recent by recentFlow.collectAsState(initial = emptyList())
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    var historyReady by remember(dao) { mutableStateOf(false) }
    val latestTrack = recent.firstOrNull()
    val recentIds = remember(recent) { recent.map { it.songId }.toSet() }

    val recommendationViewModel = remember(api) { HomeViewModel(api) }
    val recommendationState by recommendationViewModel.state.collectAsState()
    DisposableEffect(recommendationViewModel) {
        onDispose { recommendationViewModel.close() }
    }
    LaunchedEffect(dao) {
        withContext(Dispatchers.IO) { dao.recentlyPlayed().first() }
        historyReady = true
    }
    LaunchedEffect(historyReady, latestTrack?.songId, recentIds) {
        if (historyReady) {
            recommendationViewModel.onTrackChanged(
                songId = latestTrack?.songId,
                artist = latestTrack?.artist.orEmpty(),
                recentSongIds = recentIds
            )
        }
    }
    LaunchedEffect(recommendationListState) {
        snapshotFlow {
            val info = recommendationListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) recommendationViewModel.loadMore()
        }
    }

    var uiState by remember(homeApi) { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }
    var continuation by remember(homeApi) { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var currentSongId by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSongId = mediaItem?.mediaId
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    suspend fun loadHome(isRefresh: Boolean) {
        if (isRefresh) refreshing = true
        else if (uiState !is UiState.Success) uiState = UiState.Loading
        try {
            val page = homeApi.getHome()
            val shelves = page.shelves.filter { it.items.isNotEmpty() }
            continuation = page.continuation
            uiState = UiState.Success(shelves)
            withContext(Dispatchers.IO) {
                dao.clearHomeShelfCache()
                dao.saveHomeShelfCache(HomeShelfCacheEntity(0, encodeHomeShelves(shelves)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (uiState !is UiState.Success) {
                uiState = UiState.Error(error.message ?: "Couldn't load your music feed.")
            }
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(homeApi, dao) {
        val cache = withContext(Dispatchers.IO) { dao.homeShelfCache() }
        val cachedShelves = cache.sortedBy { it.pageIndex }
            .flatMap { decodeHomeShelves(it.jsonPayload) }
            .distinctBy { it.title + it.items.firstOrNull()?.browseId }
        if (cachedShelves.isNotEmpty()) uiState = UiState.Success(cachedShelves)
        loadHome(uiState is UiState.Success)
    }

    // One stable observer, with one request per continuation token.
    val latestContinuation by rememberUpdatedState(continuation)
    val latestLoadingMore by rememberUpdatedState(loadingMore)
    val latestUiState by rememberUpdatedState(uiState)
    LaunchedEffect(listState) {
        var lastRequestedToken: String? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearEnd = info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
            nearEnd to latestContinuation
        }.distinctUntilChanged().collect { (nearEnd, token) ->
            if (nearEnd && token != null && token != lastRequestedToken &&
                !latestLoadingMore && latestUiState is UiState.Success
            ) {
                lastRequestedToken = token
                loadingMore = true
                try {
                    val page = homeApi.getHome(token)
                    val existing = (latestUiState as? UiState.Success)?.data.orEmpty()
                    val additions = page.shelves.filter { it.items.isNotEmpty() }
                    val merged = (existing + additions)
                        .distinctBy { it.title + it.items.firstOrNull()?.browseId }
                    continuation = page.continuation?.takeUnless { it == token }
                    uiState = UiState.Success(merged)
                    withContext(Dispatchers.IO) {
                        val nextIndex = dao.homeShelfCache().maxOfOrNull { it.pageIndex }?.plus(1) ?: 1
                        dao.saveHomeShelfCache(HomeShelfCacheEntity(nextIndex, encodeHomeShelves(additions)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the content already on screen if a continuation fails.
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { loadHome(true) } },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(top = 8.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "home_top_bar") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(greeting.uppercase(), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp)
                        Text("ParkerTunes", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold)
                        Text("Your music, your space.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search music")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            }

            if (latestTrack != null) {
                item(key = "continue_listening") {
                    ContinueListeningCard(
                        song = Song(latestTrack.songId, latestTrack.title.ifBlank { latestTrack.songId },
                            latestTrack.artist, latestTrack.coverUrl),
                        onPlay = onPlay
                    )
                }
            }

            item(key = "quick_access") {
                Column(Modifier.padding(top = 16.dp)) {
                    HomeSectionHeading("Your shortcuts", "Jump back into your music")
                    Column(
                        Modifier.padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            QuickAccessTile(
                                title = "Recently played", subtitle = "Pick up where you left off",
                                icon = { Icon(Icons.Default.History, null) },
                                modifier = Modifier.weight(1f),
                                onClick = { latestTrack?.let { onPlay(Song(it.songId, it.title, it.artist, it.coverUrl)) } ?: onSearch() }
                            )
                            QuickAccessTile(
                                title = "Your favorites", subtitle = "${favorites.size} saved songs",
                                icon = { Icon(Icons.Default.Favorite, null) },
                                modifier = Modifier.weight(1f),
                                onClick = { favorites.firstOrNull()?.let { onPlay(Song(it.songId, it.title, it.artist, it.coverUrl)) } ?: onSearch() }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            QuickAccessTile(
                                title = "Explore music", subtitle = "Find something new",
                                icon = { Icon(Icons.Default.Explore, null) },
                                modifier = Modifier.weight(1f), onClick = onExplore
                            )
                            QuickAccessTile(
                                title = "Search songs", subtitle = "Artists, albums and more",
                                icon = { Icon(Icons.Default.Search, null) },
                                modifier = Modifier.weight(1f), onClick = onSearch
                            )
                        }
                    }
                }
            }

            item(key = "made_for_you") {
                Column(Modifier.padding(top = 18.dp)) {
                    HomeSectionHeading("Made for you", "Recommendations based on your listening")
                    RecommendationShelf(
                        songs = recommendationState.songs,
                        loading = recommendationState.loading,
                        loadingMore = recommendationState.loadingMore,
                        listState = recommendationListState,
                        onPlay = onPlay
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "recently_played") {
                    Column(Modifier.padding(top = 12.dp)) {
                        HomeSectionHeading("Recently played", "Your listening, right where you left it")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            items(recent.take(20), key = { "recent_" + it.id }) { track ->
                                HomeSongCard(
                                    song = Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl),
                                    onClick = { onPlay(Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl)) },
                                    width = 132.dp
                                )
                            }
                        }
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                item(key = "favorites") {
                    Column(Modifier.padding(top = 14.dp)) {
                        HomeSectionHeading("Your favorites", "The songs you saved")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            items(favorites.take(20), key = { "favorite_" + it.songId }) { favorite ->
                                val song = Song(favorite.songId, favorite.title, favorite.artist, favorite.coverUrl)
                                HomeSongCard(song = song, onClick = { onPlay(song) }, width = 132.dp)
                            }
                        }
                    }
                }
            }

            when (val state = uiState) {
                UiState.Loading -> items(3, key = { "loading_shelf_" + it }) { HomeShelfSkeleton() }
                is UiState.Error -> item(key = "home_error") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)
                            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text("Discover music", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("Online recommendations couldn't load right now. Your saved music is still here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { loadHome(false) } }) { Text("Try again") }
                    }
                }
                is UiState.Success -> {
                    if (state.data.isNotEmpty()) {
                        item(key = "youtube_music_heading") {
                            HomeSectionHeading("Picked from YouTube Music", "Fresh playlists, mixes and collections")
                        }
                        items(state.data, key = { shelf ->
                            "shelf_" + shelf.title + "_" +
                                (shelf.items.firstOrNull()?.videoId ?: shelf.items.firstOrNull()?.browseId.orEmpty())
                        }) { shelf ->
                            HomeShelfSection(
                                shelf = shelf, currentSongId = currentSongId, imageLoader = imageLoader,
                                onPlay = onPlay, onQueue = onQueue,
                                onOpen = { onOpenPlaylist(it, shelf) }
                            )
                        }
                    }
                }
            }

            if (loadingMore) item(key = "continuation_loading") { HomeShelfSkeleton() }

            if (recent.isEmpty() && favorites.isEmpty() &&
                recommendationState.songs.isEmpty() && uiState !is UiState.Loading
            ) {
                item(key = "empty_home") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp))
                        Text("Your next favorite song is waiting", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("Search for an artist, album or song to get started.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onSearch) { Text("Find music") }
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
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF302344), Color(0xFF19151F))))
            .clickable { onPlay(song) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF403451)), contentAlignment = Alignment.Center) {
            if (!song.coverUrl.isNullOrBlank()) {
                AsyncImage(song.coverUrl, contentDescription = song.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else Icon(Icons.Default.Album, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PICK UP WHERE YOU LEFT OFF", color = Color(0xFFD0B9FF),
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "Unknown artist" }, color = Color(0xFFC0B8CA),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White,
            modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF8E6BD1)).padding(7.dp))
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecommendationShelf(
    songs: List<Song>, loading: Boolean, loadingMore: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState, onPlay: (Song) -> Unit
) {
    if (songs.isEmpty() && !loading) {
        Text("Play a few songs to shape your recommendations.",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        items(songs, key = { "recommendation_" + it.id }) { song ->
            HomeSongCard(song, onClick = { onPlay(song) }, width = 126.dp)
        }
        if (loading && songs.isEmpty()) items(5) { HomeCardSkeleton() }
        if (loadingMore && songs.isNotEmpty()) item(key = "recommendation_loading_more") {
            Box(Modifier.width(126.dp).height(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun HomeSongCard(song: Song, onClick: () -> Unit, width: Dp) {
    Column(Modifier.width(width).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width).clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
            else AsyncImage(song.coverUrl, contentDescription = "${song.title} cover",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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
