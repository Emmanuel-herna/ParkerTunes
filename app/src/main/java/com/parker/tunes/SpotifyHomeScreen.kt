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
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val SpotifyBlack = Color(0xFF000000)
private val SpotifyGray = Color(0xFFA7A7A7)
private val SpotifyAccent = Color(0xFFB69CFF)

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
    var recommendationsLoading by remember(api) { mutableStateOf(false) }
    val latestTrack = recent.firstOrNull()
    val recommendationKey = latestTrack?.songId?.let { "related:$it" } ?: "trending"

    LaunchedEffect(dao) {
        withContext(Dispatchers.IO) { dao.recentlyPlayed().first() }
        historyReady = true
    }

    LaunchedEffect(api, historyReady, recommendationKey) {
        if (!historyReady) return@LaunchedEffect
        val cached = HomeRecommendationCache.get(recommendationKey)
        if (cached != null) {
            recommendations = cached
            recommendationsLoading = false
        } else {
            recommendationsLoading = true
            recommendations = emptyList()
            val loaded = withContext(Dispatchers.IO) {
                val lastSongId = latestTrack?.songId
                if (lastSongId != null) api.getRelatedSongs(lastSongId) else api.getTrending()
            }
            recommendations = loaded
            HomeRecommendationCache.put(recommendationKey, loaded)
            recommendationsLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack),
        contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp)
    ) {
        item(key = "top_bar") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
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
                        if (latestTrack != null && latestTrack.artist.isNotBlank()) {
                            "Because you listened to ${latestTrack.artist}"
                        } else if (latestTrack != null) {
                            "Made for you"
                        } else {
                            "Trending now"
                        }
                    )
                    if (recommendationsLoading) {
                        RecommendationShimmer()
                    } else if (recommendations.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(recommendations, key = { "recommendation_" + it.id }) { song ->
                                Column(
                                    Modifier.width(184.dp).clickable { onPlay(song) },
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Box(
                                        Modifier.size(184.dp).clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF202020)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (song.coverUrl.isNullOrBlank()) {
                                            Icon(Icons.Default.MusicNote, null, tint = SpotifyGray,
                                                modifier = Modifier.size(42.dp))
                                        } else {
                                            AsyncImage(
                                                model = song.coverUrl,
                                                contentDescription = "Recommended track cover",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    Text(song.title, color = Color.White, fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray,
                                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
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
                            Column(Modifier.width(200.dp).clickable { onPlay(song) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(width = 200.dp, height = 176.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                                    if (track.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = SpotifyGray, modifier = Modifier.size(42.dp))
                                    else AsyncImage(model = track.coverUrl, contentDescription = "Track cover",
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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
                            Column(Modifier.width(160.dp).clickable { onPlay(song) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF202020)),
                                    contentAlignment = Alignment.Center) {
                                    if (favorite.coverUrl.isNullOrBlank()) Icon(Icons.Default.Favorite, null, tint = SpotifyGray)
                                    else AsyncImage(model = favorite.coverUrl, contentDescription = "Favorite cover",
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(favorite.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(favorite.artist, color = SpotifyGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (recent.isEmpty() && mostPlayed.isEmpty() && favorites.isEmpty()) {
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

@Composable
private fun RecommendationShimmer() {
    val transition = rememberInfiniteTransition(label = "recommendationShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recommendationShimmerAlpha"
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(3) {
            Column(Modifier.width(184.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(184.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF303030).copy(alpha = alpha))
                )
                Box(
                    Modifier.fillMaxWidth(0.82f).height(14.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF303030).copy(alpha = alpha))
                )
                Box(
                    Modifier.fillMaxWidth(0.55f).height(11.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF303030).copy(alpha = alpha * 0.8f))
                )
            }
        }
    }
}

private object HomeRecommendationCache {
    private val entries = object : LinkedHashMap<String, List<Song>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean =
            size > 12
    }

    @Synchronized
    fun get(key: String): List<Song>? = entries[key]

    @Synchronized
    fun put(key: String, songs: List<Song>) {
        entries[key] = songs
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp))
}
