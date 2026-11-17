package com.parker.tunes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import com.parker.tunes.data.HomeShelfCacheEntity
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.home.*
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dao: LibraryDao,
    player: MediaController?,
    onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit,
    onSettings: () -> Unit,
    homeApi: YtMusicHomeApi,
    onOpenPlaylist: (ShelfItem, HomeShelf) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).diskCache {
            DiskCache.Builder().directory(context.cacheDir.resolve("parker-artwork"))
                .maxSizePercent(0.025).build()
        }.build()
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
        if (isRefresh) refreshing = true else uiState = UiState.Loading
        try {
            val page = homeApi.getHome()
            val shelves = page.shelves.filter { it.items.isNotEmpty() }
            continuation = page.continuation
            uiState = UiState.Success(shelves)
            withContext(Dispatchers.IO) {
                dao.clearHomeShelfCache()
                dao.saveHomeShelfCache(HomeShelfCacheEntity(0, encodeHomeShelves(shelves)))
            }
        } catch (error: Exception) {
            if (uiState !is UiState.Success) {
                uiState = UiState.Error(error.message ?: "Couldn't load your Home feed.")
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

    LaunchedEffect(listState, continuation, loadingMore, uiState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            val token = continuation
            if (nearEnd && token != null && !loadingMore && uiState is UiState.Success) {
                loadingMore = true
                try {
                    val page = homeApi.getHome(token)
                    val old = (uiState as? UiState.Success)?.data.orEmpty()
                    val merged = (old + page.shelves.filter { it.items.isNotEmpty() })
                        .distinctBy { it.title + it.items.firstOrNull()?.browseId }
                    continuation = page.continuation
                    uiState = UiState.Success(merged)
                    withContext(Dispatchers.IO) {
                        dao.saveHomeShelfCache(
                            HomeShelfCacheEntity(dao.homeShelfCache().size, encodeHomeShelves(page.shelves))
                        )
                    }
                } catch (_: Exception) {
                    // Existing shelves stay visible; a later scroll can retry.
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { loadHome(true) } },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
        ) {
            item(key = "home_header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("ParkerTunes", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("Your music, your space", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
            }

            when (val state = uiState) {
                UiState.Loading -> items(4, key = { "loading_shelf_" + it }) {
                    HomeShelfSkeleton()
                }
                is UiState.Error -> item(key = "home_error") {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Couldn't load Home", style = MaterialTheme.typography.titleMedium)
                        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { loadHome(false) } }) { Text("Retry") }
                    }
                }
                is UiState.Success -> {
                    items(state.data, key = { shelf ->
                        "shelf_" + shelf.title + "_" + (shelf.items.firstOrNull()?.videoId ?: shelf.items.firstOrNull()?.browseId.orEmpty())
                    }) { shelf ->
                        HomeShelfSection(
                            shelf, currentSongId, imageLoader, onPlay, onQueue,
                            onOpen = { onOpenPlaylist(it, shelf) }
                        )
                    }
                    if (loadingMore) item(key = "continuation_loading") { HomeShelfSkeleton() }
                }
            }
        }
    }
}

@Composable
private fun HomeShelfSection(
    shelf: HomeShelf,
    currentSongId: String?,
    imageLoader: ImageLoader,
    onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit,
    onOpen: (ShelfItem) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
            Text(shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            shelf.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
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
            ShelfLayout.VIDEO -> LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                    HomeVideoCard(item, currentSongId, imageLoader,
                        onClick = { if (item.videoId != null) onPlay(item.toSong()) else onOpen(item) },
                        onQueue = onQueue)
                }
            }
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            .clip(RoundedCornerShape(9.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(7.dp))
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
            Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
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
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
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
        AsyncImage(
            model = url.artworkAt(sizePx), imageLoader = loader,
            contentDescription = null, contentScale = scale, modifier = modifier
        )
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
    val color = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(150.dp).height(17.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(color))
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
