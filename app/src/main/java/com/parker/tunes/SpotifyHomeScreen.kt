package com.parker.tunes

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.RecommendationCacheEntity
import com.parker.tunes.data.HomeShelfCacheEntity
import com.parker.tunes.data.home.HomeShelf
import com.parker.tunes.data.home.HomeChip
import com.parker.tunes.data.home.ShelfItem
import com.parker.tunes.data.home.ShelfLayout
import com.parker.tunes.data.home.YtMusicHomeApi
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import org.json.JSONArray
import org.json.JSONObject

private val SpotifyBlack = Color(0xFF000000)
private val SpotifyGray = Color(0xFFA7A7A7)
private val SpotifyAccent = Color(0xFFB69CFF)
private const val RECOMMENDATION_TTL_MS = 6L * 60L * 60L * 1000L

@Composable
fun SpotifyHomeScreen(
    dao: LibraryDao,
    api: MusicApi,
    player: MediaController?,
    onPlay: (Song) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    homeApi: YtMusicHomeApi,
    onOpenPlaylist: (ShelfItem, HomeShelf) -> Unit
) {
    val recent by remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val favorites by remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    var historyReady by remember(dao) { mutableStateOf(false) }
    val recommendationViewModel = remember(api) { HomeViewModel(api) }
    val recommendationState by recommendationViewModel.state.collectAsState()
    val recommendations = recommendationState.songs
    val recommendationsLoading = recommendationState.loading
    val recommendationListState = rememberLazyListState()
    var homeShelves by remember(homeApi) { mutableStateOf(emptyList<HomeShelf>()) }
    var homeChips by remember(homeApi) { mutableStateOf(emptyList<HomeChip>()) }
    var homeContinuation by remember(homeApi) { mutableStateOf<String?>(null) }
    var homeLoading by remember(homeApi) { mutableStateOf(true) }
    var homePageLoading by remember(homeApi) { mutableStateOf(false) }
    val homeListState = rememberLazyListState()
    val latestTrack = recent.firstOrNull()
    val recommendationSubtitle = latestTrack?.artist?.takeIf { it.isNotBlank() }
        ?.let { "Inspired by $it and similar sounds" } ?: "Trending now"

    LaunchedEffect(dao) {
        withContext(Dispatchers.IO) { dao.recentlyPlayed().first() }
        historyReady = true
    }
    LaunchedEffect(homeApi, dao) {
        val cachedPages = withContext(Dispatchers.IO) { dao.homeShelfCache() }
        if (cachedPages.isNotEmpty()) {
            homeShelves = cachedPages.sortedBy { it.pageIndex }.flatMap { decodeShelves(it.jsonPayload) }
                .distinctBy { it.title to it.items.firstOrNull()?.browseId }
            homeLoading = false
        }
        runCatching { homeApi.getHome() }.onSuccess { page ->
            homeShelves = page.shelves.filter { it.items.size >= 2 }
            homeChips = page.chips
            homeContinuation = page.continuation
            withContext(Dispatchers.IO) {
                dao.clearHomeShelfCache()
                dao.saveHomeShelfCache(HomeShelfCacheEntity(0, encodeShelves(page.shelves)))
                dao.pruneHomeShelfCache(System.currentTimeMillis() - 30L * 60L * 1000L)
            }
        }
        homeLoading = false
    }

    LaunchedEffect(homeListState, homeContinuation, homeShelves.size) {
        snapshotFlow {
            val info = homeListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            val token = homeContinuation
            if (nearEnd && token != null && !homePageLoading) {
                homePageLoading = true
                runCatching { homeApi.getHome(token) }.onSuccess { page ->
                    val pageIndex = withContext(Dispatchers.IO) { dao.homeShelfCache().size }
                    val fresh = page.shelves.filter { it.items.size >= 2 }
                    homeShelves = (homeShelves + fresh).distinctBy { it.title to it.items.firstOrNull()?.browseId }
                    homeContinuation = page.continuation
                    withContext(Dispatchers.IO) {
                        dao.saveHomeShelfCache(HomeShelfCacheEntity(pageIndex, encodeShelves(page.shelves)))
                    }
                }
                homePageLoading = false
            }
        }
    }


    DisposableEffect(recommendationViewModel) {
        onDispose { recommendationViewModel.close() }
    }
    LaunchedEffect(historyReady, latestTrack?.songId, recent.map { it.songId }) {
        if (historyReady) {
            recommendationViewModel.onTrackChanged(
                songId = latestTrack?.songId,
                artist = latestTrack?.artist.orEmpty(),
                recentSongIds = recent.map { it.songId }.toSet()
            )
        }
    }
    LaunchedEffect(recommendationListState) {
        snapshotFlow {
            val info = recommendationListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) recommendationViewModel.loadMore()
        }
    }

    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize().background(SpotifyBlack),
        contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp)
    ) {
        item(key = "top_bar") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.size(width = 27.dp, height = 25.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf(10.dp, 19.dp, 13.dp, 24.dp, 9.dp).forEachIndexed { index, h ->
                            Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(4.dp))
                                .background(if (index % 2 == 0) SpotifyAccent else Color.White))
                        }
                    }
                    Text("ParkerTunes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF292333)).padding(2.dp)
                    .clip(CircleShape).background(SpotifyAccent).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center) {
                    Text("P", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent_section") {
                Column {
                    SectionHeading("Recently played")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recent.take(20), key = { "recent_card_" + it.id }) { track ->
                            val song = Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl)
                            RecommendationCard(song = song, onClick = { onPlay(song) }, width = 180.dp)
                        }
                    }
                }
            }
        }

        if (historyReady) {
            item(key = "recommendations_section") {
                Column {
                    SectionHeading(
                        if (latestTrack == null) "Trending now" else "Made for you",
                        recommendationSubtitle
                    )
                    RecommendationRow(
                        songs = recommendations,
                        loading = recommendationsLoading,
                        loadingMore = recommendationState.loadingMore,
                        listState = recommendationListState,
                        onPlay = onPlay
                    )
                }
            }
        }


        if (homeShelves.isNotEmpty()) {
            items(homeShelves.filter { it.items.size >= 2 }, key = { "yt_home_" + it.title + "_" + it.items.firstOrNull()?.browseId }) { shelf ->
                Column {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(shelf.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        shelf.strapline?.let { Text(it, color = SpotifyGray, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shelf.items, key = { "yt_" + (it.browseId ?: it.videoId ?: it.title) }) { item ->
                            HomeShelfCard(item) { onOpenPlaylist(item, shelf) }
                        }
                    }
                }
            }
        } else if (homeLoading) {
            items(2) { index ->
                Column {
                    Box(Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp)
                        .width(150.dp).height(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF252525)))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(4) { RecommendationPlaceholder(index * 4 + it) }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "favorites_section") {
                Column {
                    SectionHeading("Favorites")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(favorites, key = { "favorite_" + it.songId }) { favorite ->
                            val song = Song(favorite.songId, favorite.title, favorite.artist, favorite.coverUrl)
                            RecommendationCard(song = song, onClick = { onPlay(song) }, width = 160.dp)
                        }
                    }
                }
            }
        }

        if (recent.isEmpty() && favorites.isEmpty() && recommendations.isEmpty() && !recommendationsLoading) {
            item(key = "empty_cta") {
                Column(Modifier.fillParentMaxSize().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = SpotifyAccent, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Search for music to get started", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onSearch, shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyAccent, contentColor = Color.Black)) {
                        Text("Explore music", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationRow(
    songs: List<Song>,
    loading: Boolean,
    loadingMore: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: (Song) -> Unit
) {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(songs, key = { "made_for_you_" + it.id }) { song ->
            RecommendationCard(song = song, onClick = { onPlay(song) }, width = 82.dp)
        }
        if (loading && songs.isEmpty()) {
            items(6) { RecommendationPlaceholder(it) }
        }
        if (loadingMore && songs.isNotEmpty()) {
            item(key = "recommendations_loading_more") {
                Box(Modifier.width(82.dp).height(125.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SpotifyAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(song: Song, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(width).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(width = width, height = width).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF202020)),
            contentAlignment = Alignment.Center
        ) {
            if (song.coverUrl.isNullOrBlank()) {
                Icon(Icons.Default.MusicNote, null, tint = SpotifyGray, modifier = Modifier.size(42.dp))
            } else {
                AsyncImage(model = song.coverUrl, contentDescription = "${song.title} cover",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(song.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecommendationPlaceholder(keyIndex: Int) {
    val transition = rememberInfiniteTransition(label = "recommendationShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.30f, targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "recommendationShimmerAlpha"
    )
    Column(Modifier.width(82.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(82.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF303030).copy(alpha = alpha)))
        Box(Modifier.fillMaxWidth(0.82f).height(14.dp).clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF303030).copy(alpha = alpha)))
        Box(Modifier.fillMaxWidth(0.55f).height(11.dp).clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF303030).copy(alpha = alpha * 0.8f)))
    }
}


@Composable
private fun HomeShelfCard(item: ShelfItem, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF202020))) {
            if (!item.thumbnailUrl.isNullOrBlank()) AsyncImage(
                model = item.thumbnailUrl, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            ) else Icon(Icons.Default.Album, null, tint = SpotifyGray, modifier = Modifier.align(Alignment.Center))
        }
        Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle.orEmpty(), color = SpotifyGray, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun encodeShelves(shelves: List<HomeShelf>): String = JSONArray().apply {
    shelves.forEach { shelf ->
        put(JSONObject().apply {
            put("title", shelf.title); put("strapline", shelf.strapline ?: JSONObject.NULL)
            put("layout", shelf.layout.name)
            put("items", JSONArray().apply { shelf.items.forEach { item ->
                put(JSONObject().apply {
                    put("title", item.title); put("subtitle", item.subtitle ?: JSONObject.NULL)
                    put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                    put("browseId", item.browseId ?: JSONObject.NULL); put("params", item.params ?: JSONObject.NULL)
                    put("videoId", item.videoId ?: JSONObject.NULL); put("isVideo", item.isVideo)
                })
            }})
        })
    }
}.toString()

private fun decodeShelves(json: String): List<HomeShelf> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val items = obj.optJSONArray("items") ?: JSONArray()
        val parsed = (0 until items.length()).mapNotNull { j ->
            val item = items.optJSONObject(j) ?: return@mapNotNull null
            fun nullable(key: String) = item.optString(key).takeIf { it.isNotBlank() && it != "null" }
            ShelfItem(item.optString("title"), nullable("subtitle"), nullable("thumbnailUrl"),
                nullable("browseId"), nullable("params"), nullable("videoId"), item.optBoolean("isVideo"))
        }
        HomeShelf(obj.optString("title"), obj.optString("strapline").takeIf { it.isNotBlank() && it != "null" },
            runCatching { ShelfLayout.valueOf(obj.optString("layout")) }.getOrDefault(ShelfLayout.SQUARE), parsed)
    }
}.getOrDefault(emptyList())

private fun encodeSongs(songs: List<Song>): String = JSONArray().apply {
    songs.forEach { song ->
        put(JSONObject().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("coverUrl", song.coverUrl ?: JSONObject.NULL)
            put("durationSec", song.durationSec)
            put("album", song.album ?: JSONObject.NULL)
            put("trackNumber", song.trackNumber ?: JSONObject.NULL)
        })
    }
}.toString()

private fun decodeSongs(json: String): List<Song> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Song(
            id = id,
            title = item.optString("title", id),
            artist = item.optString("artist", "Unknown artist"),
            coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
            durationSec = item.optLong("durationSec"),
            album = item.optString("album").takeIf { it.isNotBlank() && it != "null" },
            trackNumber = if (item.isNull("trackNumber")) null else item.optInt("trackNumber")
        )
    }
}.getOrDefault(emptyList())

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = SpotifyGray, fontSize = 12.sp)
    }
}
