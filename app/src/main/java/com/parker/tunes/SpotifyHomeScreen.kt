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
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    onSearch: () -> Unit
) {
    val recent by remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val mostPlayed by remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val favorites by remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    var historyReady by remember(dao) { mutableStateOf(false) }
    var recommendations by remember(api) { mutableStateOf(emptyList<Song>()) }
    var recommendationsLoading by remember(api) { mutableStateOf(true) }
    val latestTrack = recent.firstOrNull()
    val recommendationKey = latestTrack?.songId?.let { "related:$it" } ?: "trending"
    val recommendationSubtitle = latestTrack?.artist?.takeIf { it.isNotBlank() }
        ?.let { "Because you listened to $it" } ?: "Trending now"

    LaunchedEffect(dao) {
        withContext(Dispatchers.IO) { dao.recentlyPlayed().first() }
        historyReady = true
    }

    LaunchedEffect(api, dao, historyReady, recommendationKey) {
        if (!historyReady) return@LaunchedEffect

        // Room cache is rendered first, even when stale; the network refresh never blocks it.
        val cached = withContext(Dispatchers.IO) { dao.recommendationCache(recommendationKey) }
        if (cached != null) {
            val cachedSongs = decodeSongs(cached.songIdList)
            if (cachedSongs.isNotEmpty()) recommendations = cachedSongs.take(30)
        }
        recommendationsLoading = recommendations.isEmpty()

        val refreshed = withContext(Dispatchers.IO) {
            val related = latestTrack?.songId?.let { api.getRelatedSongs(it) }.orEmpty()
            val trending = if (latestTrack == null || related.size < 10) api.getTrending() else emptyList()
            val fetched = (related + trending).distinctBy { it.id }
            val recentIds = recent.map { it.songId }.toSet()
            var recentDuplicates = 0
            val deduped = fetched.distinctBy { it.id }.filter { song ->
                if (song.id !in recentIds) true
                else if (recentDuplicates < 3) {
                    recentDuplicates++
                    true
                } else false
            }.take(30)
            android.util.Log.i("ParkerTunes", "Home recs: fetched=${fetched.size}, deduped=${deduped.size}")
            deduped
        }

        if (refreshed.isNotEmpty()) {
            recommendations = refreshed
            withContext(Dispatchers.IO) {
                dao.saveRecommendationCache(
                    RecommendationCacheEntity(
                        queryOrSongId = recommendationKey,
                        songIdList = encodeSongs(refreshed),
                        fetchedAt = System.currentTimeMillis()
                    )
                )
                dao.pruneRecommendationCache(System.currentTimeMillis() - RECOMMENDATION_TTL_MS * 2)
            }
        }
        // Keep any usable cached rows when offline. Empty results become 10 shimmer placeholders.
        recommendationsLoading = false
    }

    LazyColumn(
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
                        onPlay = onPlay
                    )
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

        if (mostPlayed.isNotEmpty()) {
            item(key = "most_played_section") {
                Column {
                    SectionHeading("Most played")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        mostPlayed.take(10).forEach { track ->
                            val song = Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl)
                            Row(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(10.dp)).clickable { onPlay(song) },
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF202020)),
                                    contentAlignment = Alignment.Center) {
                                    if (track.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = SpotifyGray)
                                    else AsyncImage(model = track.coverUrl, contentDescription = "Track cover",
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(track.playCount.toString() + " plays", color = SpotifyGray, fontSize = 11.sp)
                            }
                        }
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

        if (recent.isEmpty() && mostPlayed.isEmpty() && favorites.isEmpty() && recommendations.isEmpty() && !recommendationsLoading) {
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
private fun RecommendationRow(songs: List<Song>, loading: Boolean, onPlay: (Song) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(songs.take(30), key = { "recommendation_" + it.id }) { song ->
            RecommendationCard(song = song, onClick = { onPlay(song) }, width = 180.dp)
        }
        val placeholders = (10 - songs.size).coerceAtLeast(0)
        if (loading || placeholders > 0) {
            items(if (loading && songs.isEmpty()) 10 else placeholders) { index ->
                RecommendationPlaceholder(keyIndex = index)
            }
        }
    }
}

@Composable
private fun RecommendationCard(song: Song, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(width).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.size(width = width, height = 180.dp).clip(RoundedCornerShape(12.dp))
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
        Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray, fontSize = 13.sp,
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
    Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF303030).copy(alpha = alpha)))
        Box(Modifier.fillMaxWidth(0.82f).height(14.dp).clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF303030).copy(alpha = alpha)))
        Box(Modifier.fillMaxWidth(0.55f).height(11.dp).clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF303030).copy(alpha = alpha * 0.8f)))
    }
}

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
