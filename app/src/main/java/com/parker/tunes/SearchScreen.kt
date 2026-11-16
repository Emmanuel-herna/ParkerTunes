package com.parker.tunes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.RecentSearchEntity
import com.parker.tunes.model.Artist
import com.parker.tunes.model.SearchResult
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SearchBlack = Color(0xFF000000)
private val SearchSurface = Color(0xFF1A1A1A)
private val SearchAccent = Color(0xFFB69CFF)
private val SearchMuted = Color(0xFFA7A7A7)

class RecentSearchesViewModel(private val dao: LibraryDao) : ViewModel() {
    val recent = dao.recentSearches().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun recordQuery(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveRecentSearch(RecentSearchEntity(query = clean, searchedAt = System.currentTimeMillis()))
        }
    }

    fun recordFirstResult(query: String, song: Song) {
        val clean = query.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveRecentSearch(
                RecentSearchEntity(
                    query = clean,
                    searchedAt = System.currentTimeMillis(),
                    title = song.title,
                    artist = song.artist,
                    coverUrl = song.coverUrl,
                    songId = song.id
                )
            )
        }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { dao.clearRecentSearches() }
    }
}

private class RecentSearchesFactory(private val dao: LibraryDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RecentSearchesViewModel(dao) as T
}

@Composable
fun SearchScreen(
    api: MusicApi,
    dao: LibraryDao,
    onPlay: (Song) -> Unit,
    onSettings: () -> Unit
) {
    val recentVm: RecentSearchesViewModel = viewModel(
        key = "recent-searches-${System.identityHashCode(dao)}",
        factory = remember(dao) { RecentSearchesFactory(dao) }
    )
    val recent by recentVm.recent.collectAsState()
    var query by remember { mutableStateOf("") }
    var activeSource by remember { mutableStateOf("YouTube") }
    var results by remember { mutableStateOf(SearchResult()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(query, api, activeSource) {
        val clean = query.trim()
        if (activeSource != "YouTube" || clean.isBlank()) {
            results = SearchResult()
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        kotlinx.coroutines.delay(300)
        try {
            val found = withContext(Dispatchers.IO) { api.search(clean) }
            results = found
            if (found.isNotEmpty()) recentVm.recordQuery(clean)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Search is temporarily unavailable."
            results = emptyList()
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(SearchBlack).padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.size(width = 24.dp, height = 24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf(9.dp, 18.dp, 12.dp, 23.dp, 8.dp).forEachIndexed { index, h ->
                        Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(3.dp))
                            .background(if (index % 2 == 0) SearchAccent else Color.White))
                    }
                }
                Text("ParkerTunes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF292333))
                .clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                Text("P", color = SearchAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(22.dp)).background(SearchSurface).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("YouTube", "Library", "Addons").forEach { source ->
                val selected = source == activeSource
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Color(0xFF343039) else Color.Transparent)
                    .clickable { activeSource = source }, contentAlignment = Alignment.Center) {
                    Text(source, color = if (selected) Color.White else SearchMuted,
                        fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(SearchSurface)
                .border(if (searchFocused) 1.dp else 0.dp, if (searchFocused) SearchAccent.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(26.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = SearchMuted, modifier = Modifier.padding(start = 16.dp).size(21.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(SearchAccent),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).onFocusChanged { searchFocused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search songs, artists, albums", color = SearchMuted, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Icon(Icons.Default.Close, "Clear search", tint = SearchMuted,
                    modifier = Modifier.clickable { query = "" }.padding(horizontal = 16.dp).size(19.dp))
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }

        Spacer(Modifier.height(20.dp))

        if (activeSource != "YouTube") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$activeSource is not connected yet", color = SearchMuted, fontSize = 15.sp)
            }
        } else if (query.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (recent.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recent searches", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = recentVm::clear, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("Clear", color = SearchAccent, fontSize = 13.sp)
                            }
                        }
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recent, key = { it.query }) { item ->
                                Column(Modifier.width(150.dp).clickable {
                                    val id = item.songId
                                    if (!id.isNullOrBlank() && item.title.isNotBlank()) {
                                        onPlay(Song(id, item.title, item.artist, item.coverUrl))
                                    } else query = item.query
                                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).background(SearchSurface),
                                        contentAlignment = Alignment.Center) {
                                        if (item.coverUrl.isNullOrBlank()) Icon(Icons.Default.Search, null,
                                            tint = SearchMuted, modifier = Modifier.size(36.dp))
                                        else AsyncImage(model = item.coverUrl, contentDescription = "Recent search cover",
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                    Text(item.title.ifBlank { item.query }, color = Color.White, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (item.artist.isNotBlank()) item.artist else "Search",
                                        color = SearchMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Explore", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (recent.isEmpty()) 0.dp else 12.dp))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Trending", "New releases", "Popular").forEach { term ->
                            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(SearchSurface)
                                .clickable { query = term }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                                Text(term, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        } else {
            when {
                loading -> ShimmerResults()
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(error ?: "Search failed", color = SearchMuted, modifier = Modifier.padding(top = 24.dp))
                }
                results.songs.isEmpty() && results.artists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text("No artists or songs found", color = SearchMuted, modifier = Modifier.padding(top = 24.dp))
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (results.artists.isNotEmpty()) {
                        item {
                            Text("Artists", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                items(results.artists, key = { it.id }) { artist ->
                                    ArtistSearchItem(artist) { query = artist.name }
                                }
                            }
                        }
                    }
                    if (results.songs.isNotEmpty()) {
                        item {
                            Text(
                                "Songs",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = if (results.artists.isNotEmpty()) 8.dp else 0.dp)
                            )
                        }
                        items(results.songs, key = { it.id }) { song ->
                            SearchResultRow(song) {
                                recentVm.recordFirstResult(query, song)
                                onPlay(song)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(SearchSurface),
            contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = SearchMuted)
            else AsyncImage(model = song.coverUrl, contentDescription = "Song cover",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = SearchMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(start = 8.dp) .size(24.dp).clip(CircleShape).background(SearchAccent)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ShimmerResults() {
    val transition = rememberInfiniteTransition(label = "search_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "shimmer_alpha"
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(7) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF292929)).alpha(alpha))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.72f).height(13.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF292929)).alpha(alpha))
                    Box(Modifier.fillMaxWidth(0.42f).height(10.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF292929)).alpha(alpha))
                }
                Box(Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape)
                    .background(Color(0xFF292929)).alpha(alpha))
            }
        }
    }
}

 
@Composable
private fun ArtistSearchItem(artist: Artist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(84.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(SearchSurface),
            contentAlignment = Alignment.Center
        ) {
            if (artist.avatarUrl.isNullOrBlank()) {
                Icon(Icons.Default.MusicNote, null, tint = SearchMuted, modifier = Modifier.size(28.dp))
            } else {
                AsyncImage(
                    model = artist.avatarUrl,
                    contentDescription = "${artist.name} avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            artist.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (artist.subscriberCount > 0L) {
            Text(
                formatArtistSubscribers(artist.subscriberCount),
                color = SearchMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatArtistSubscribers(count: Long): String = when {
    count >= 1_000_000_000L -> String.format("%.1fB subscribers", count / 1_000_000_000.0)
    count >= 1_000_000L -> String.format("%.1fM subscribers", count / 1_000_000.0)
    count >= 1_000L -> String.format("%.1fK subscribers", count / 1_000.0)
    else -> "$count subscribers"
}
