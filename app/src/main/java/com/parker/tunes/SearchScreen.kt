package com.parker.tunes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
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
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.data.sources.AddonEntity
import kotlinx.coroutines.flow.flowOf
import com.parker.tunes.data.RecentSearchEntity
import com.parker.tunes.data.DownloadEntity
import com.parker.tunes.data.SongDownloadManager
import com.parker.tunes.data.SettingsRepository
import com.parker.tunes.data.SpotifyMetadataProvider
import com.parker.tunes.player.isLikelyMix
import com.parker.tunes.data.PlaylistEntity
import com.parker.tunes.data.PlaylistTrackEntity
import com.parker.tunes.data.FavoriteEntity
import dev.chrisbanes.haze.HazeState
import com.parker.tunes.model.Album
import com.parker.tunes.model.Artist
import com.parker.tunes.model.SearchResult
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

private val SearchBlack = Color(0xFF000000)
private val SearchSurface = Color(0xFF1A1A1A)
private val SearchAccent = Color(0xFFB69CFF)
private val SearchMuted = Color(0xFFA7A7A7)

class RecentSearchesViewModel(private val dao: LibraryDao) : ViewModel() {
    val recent = dao.recentSearches().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun recordQuery(query: String, kind: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.saveRecentSearch(RecentSearchEntity(query = clean, searchedAt = System.currentTimeMillis(), title = clean, artist = kind))
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
                    artist = "Song",
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
    settingsRepository: SettingsRepository,
    onPlay: (Song) -> Unit,
    onQueue: (Song, Boolean) -> Unit,
    onSettings: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    downloads: SongDownloadManager,
    onLowStorage: () -> Unit,
    hazeState: HazeState
) {
    val recentVm: RecentSearchesViewModel = viewModel(
        key = "recent-searches-${System.identityHashCode(dao)}",
        factory = remember(dao) { RecentSearchesFactory(dao) }
    )
    val recent by recentVm.recent.collectAsState()
    val savedDownloads by remember(dao) { dao.downloads().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val downloadProgress by downloads.progress.collectAsState()
    val playlists by remember(dao) { dao.playlists().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val favorites by remember(dao) { dao.favorites().flowOn(Dispatchers.IO) }.collectAsState(initial = emptyList())
    val glassIntensity by settingsRepository.glassIntensity.collectAsState(initial = 55)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spotifyProvider = remember { SpotifyMetadataProvider() }
    val spotifyEnabled by settingsRepository.spotifyMetadataEnabled.collectAsState(initial = true)
    val spotifyClientId by settingsRepository.spotifyClientId.collectAsState(initial = "")
    val spotifyClientSecret by settingsRepository.spotifyClientSecret.collectAsState(initial = "")
    var query by remember { mutableStateOf("") }
    var activeSource by remember { mutableStateOf("YouTube") }
    val addonApi = api as? AddonMusicApi
    val addonSources by remember(addonApi) {
        addonApi?.addonSources() ?: flowOf(emptyList<AddonEntity>())
    }.collectAsState(initial = emptyList())
    val sourceOptions = remember(addonSources) {
        listOf("YouTube" to "YouTube") + addonSources.map { it.name to it.id }
    }
    var results by remember { mutableStateOf(SearchResult()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    var headerVisible by remember { mutableStateOf(true) }
    val headerScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5f) headerVisible = false
                else if (available.y > 5f) headerVisible = true
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(query, api, activeSource, addonSources, spotifyEnabled, spotifyClientId, spotifyClientSecret) {
        val clean = query.trim()
        if (clean.isBlank()) {
            results = SearchResult()
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        kotlinx.coroutines.delay(400)
        try {
            val found = withContext(Dispatchers.IO) {
                if (activeSource == "YouTube") {
                    val youtubeResults = api.search(clean)
                    val clientId = spotifyClientId
                    val clientSecret = spotifyClientSecret
                    if (!spotifyEnabled || clientId.isBlank() || clientSecret.isBlank()) {
                        youtubeResults
                    } else {
                        // Spotify is an optional metadata fallback. Any failure leaves the
                        // original YouTube Music response untouched.
                        val spotifyTracks = spotifyProvider.searchTracks(
                            query = clean, limit = 10, clientId = clientId, clientSecret = clientSecret
                        )
                        val youtubeSongs = youtubeResults.songs.toMutableList()
                        val spotifyOnly = mutableListOf<com.parker.tunes.model.Song>()
                        spotifyTracks.forEach { track ->
                            val artistText = track.artists.joinToString(", ")
                            val key = normalizeTrackMetadata(track.name, artistText)
                            val existingIndex = youtubeSongs.indexOfFirst { song ->
                                normalizeTrackMetadata(song.title, song.artist) == key
                            }
                            if (existingIndex >= 0) {
                                youtubeSongs[existingIndex] = youtubeSongs[existingIndex].copy(
                                    coverUrl = track.artworkUrl ?: youtubeSongs[existingIndex].coverUrl,
                                    spotifyUrl = track.spotifyUrl
                                )
                            } else if (spotifyOnly.size < 5) {
                                val resolved = try {
                                    withTimeout(5_000L) {
                                        val ytCandidates = api.search("${track.name} $artistText").songs
                                        ytCandidates.firstOrNull { candidate ->
                                            !isLikelyMix(candidate.title, candidate.durationSec) &&
                                                normalizeTrackMetadata(candidate.title, candidate.artist) == key
                                        } ?: ytCandidates.firstOrNull { candidate ->
                                            !isLikelyMix(candidate.title, candidate.durationSec) &&
                                                candidate.durationSec in 30L..720L
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                if (resolved != null && youtubeSongs.none { it.id == resolved.id } &&
                                    spotifyOnly.none { it.id == resolved.id }
                                ) {
                                    spotifyOnly += resolved.copy(
                                        title = track.name,
                                        artist = artistText.ifBlank { resolved.artist },
                                        coverUrl = track.artworkUrl ?: resolved.coverUrl,
                                        durationSec = if (track.durationMs > 0L) track.durationMs / 1000L else resolved.durationSec,
                                        album = track.albumName,
                                        spotifyUrl = track.spotifyUrl
                                    )
                                }
                            }
                        }
                        youtubeResults.copy(songs = (youtubeSongs + spotifyOnly).distinctBy { it.id })
                    }
                } else SearchResult(songs = addonApi?.searchAddon(activeSource, clean).orEmpty())
            }
            results = found
            if (found.songs.isNotEmpty() || found.artists.isNotEmpty()) recentVm.recordQuery(clean, if (found.songs.isNotEmpty()) "Song" else "Artist")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Search is temporarily unavailable."
            results = SearchResult()
        } finally {
            if (query.trim() == clean) loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(SearchBlack).padding(horizontal = 16.dp)
    ) {
        AnimatedVisibility(
            visible = headerVisible,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            Column {
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
            sourceOptions.forEach { (label, sourceId) ->
                val selected = sourceId == activeSource
                Box(Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Color(0xFF343039) else Color.Transparent)
                    .clickable { activeSource = sourceId }.padding(horizontal = 15.dp)
                    .fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) Color.White else SearchMuted,
                        fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            }
        }

        if (query.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(headerScrollConnection),
                contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                results.songs.isEmpty() && results.artists.isEmpty() && results.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text("No artists or songs found", color = SearchMuted, modifier = Modifier.padding(top = 24.dp))
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(headerScrollConnection),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (results.artists.isNotEmpty()) {
                        item {
                            Text("Artists", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                items(results.artists, key = { it.id }) { artist ->
                                    ArtistSearchItem(artist, onClick = { onArtist(artist) })
                                }
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item { Text("Albums", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(results.albums, key = { it.id }) { album ->
                                    Column(Modifier.width(132.dp).clickable { onAlbum(album) }) {
                                        AsyncImage(model = album.coverUrl, contentDescription = album.title,
                                            modifier = Modifier.size(132.dp).clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop)
                                        Text(album.title, color = Color.White, fontSize = 13.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
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
                            val songDownload = savedDownloads.firstOrNull { it.songId == song.id }
                            SearchResultRow(
                                song = song,
                                download = songDownload,
                                progress = downloadProgress[song.id],
                                playlists = playlists,
                                isFavorite = favorites.any { it.songId == song.id },
                                glassIntensity = glassIntensity,
                                hazeState = hazeState,
                                onAction = { action ->
                                    when (action) {
                                        SongAction.Play -> onPlay(song)
                                        SongAction.PlayNext -> onQueue(song, true)
                                        SongAction.AddToQueue -> onQueue(song, false)
                                        SongAction.Download -> when (songDownload?.status) {
                                            DownloadEntity.STATUS_COMPLETE -> scope.launch(Dispatchers.IO) { downloads.remove(song.id) }
                                            DownloadEntity.STATUS_DOWNLOADING -> downloads.cancel(song.id)
                                            else -> if (downloads.hasLowStorage()) onLowStorage() else downloads.enqueue(song)
                                        }
                                        SongAction.ToggleFavorite -> scope.launch(Dispatchers.IO) {
                                            if (dao.isFavorite(song.id)) dao.removeFavorite(song.id)
                                            else dao.addFavorite(FavoriteEntity(songId = song.id, title = song.title, artist = song.artist, coverUrl = song.coverUrl))
                                        }
                                        is SongAction.AddToPlaylist -> scope.launch(Dispatchers.IO) {
                                            val existing = dao.playlistTracksSnapshot(action.playlistId)
                                            if (existing.none { it.songId == song.id }) {
                                                dao.addTrack(PlaylistTrackEntity(action.playlistId, song.id, existing.size, song.title, song.artist))
                                            }
                                        }
                                        SongAction.GoToArtist -> scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { api.search(song.artist).artists.firstOrNull() } }
                                                .onSuccess { artist -> if (artist != null) onArtist(artist) }
                                        }
                                        SongAction.GoToAlbum -> scope.launch {
                                            val albumQuery = song.album?.takeIf { it.isNotBlank() } ?: song.title
                                            runCatching { withContext(Dispatchers.IO) { api.search(albumQuery).albums.firstOrNull() } }
                                                .onSuccess { album -> if (album != null) onAlbum(album) }
                                        }
                                        SongAction.StartRadio -> scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { api.getRelatedSongs(song.id, song.title, song.artist) } }
                                                .onSuccess { related -> related.forEach { onQueue(it, false) } }
                                        }
                                        SongAction.Share -> {
                                            val link = song.spotifyUrl?.takeIf { it.isNotBlank() }
                                                ?: "https://music.youtube.com/watch?v=\${song.id}"
                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "\${song.title} · \${song.artist}\\n$link")
                                            }
                                            runCatching { context.startActivity(android.content.Intent.createChooser(shareIntent, "Share song")) }
                                        }
                                        SongAction.ShowInfo, SongAction.Remove -> Unit
                                    }
                                },
                                onCreatePlaylist = { name ->
                                    scope.launch(Dispatchers.IO) {
                                        val playlist = PlaylistEntity(id = java.util.UUID.randomUUID().toString(), name = name)
                                        dao.savePlaylist(playlist)
                                        dao.addTrack(PlaylistTrackEntity(playlist.id, song.id, 0, song.title, song.artist))
                                    }
                                },
                                onClick = {
                                    recentVm.recordFirstResult(query, song)
                                    onPlay(song)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun normalizeTrackMetadata(title: String, artist: String): String {
    fun clean(value: String): String = value.lowercase()
        .replace(Regex("\\s*[\\[(](official( music)? video|lyrics?|audio|visuali[sz]er)[^\\])]*[\\])]\\s*$"), " ")
        .replace(Regex("\\s*[-–—|:]\\s*(official( music)? video|lyrics?|audio)$"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim().replace(Regex("\\s+"), " ")
    return "${clean(title)}|${clean(artist)}"
}

@Composable
private fun SearchResultRow(
    song: Song,
    download: DownloadEntity?,
    progress: Int?,
    playlists: List<PlaylistEntity>,
    isFavorite: Boolean,
    glassIntensity: Int,
    hazeState: HazeState,
    onAction: (SongAction) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var showOptions by remember(song.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(SearchSurface),
            contentAlignment = Alignment.Center) {
            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = SearchMuted)
            else AsyncImage(model = song.coverUrl, contentDescription = "Song cover",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null || download?.status == DownloadEntity.STATUS_DOWNLOADING) {
                if (progress == null) CircularProgressIndicator(modifier = Modifier.size(30.dp), color = SearchAccent, strokeWidth = 3.dp)
                else CircularProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.size(30.dp), color = SearchAccent, strokeWidth = 3.dp)
            } else if (download?.status == DownloadEntity.STATUS_COMPLETE) {
                Icon(Icons.Default.CheckCircle, "Downloaded", tint = SearchAccent,
                    modifier = Modifier.align(Alignment.BottomEnd).size(19.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (song.id.startsWith("src:")) listOfNotNull(song.artist, song.sourceQuality).joinToString(" · ") else song.artist,
                    color = SearchMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!song.spotifyUrl.isNullOrBlank()) {
                    Text("Spotify ↗", color = Color(0xFF1DB954), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 7.dp).clickable {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(song.spotifyUrl))) }
                        })
                }
            }
        }
        IconButton(onClick = { showOptions = true }) {
            Icon(Icons.Default.MoreVert, "Song options", tint = SearchMuted)
        }
        Box(Modifier.padding(start = 2.dp).size(24.dp).clip(CircleShape).background(SearchAccent)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(19.dp))
        }
    }
    if (showOptions) {
        SongOptionsSheet(
            song = song,
            source = SongOptionsSource.SEARCH,
            onAction = onAction,
            hazeState = hazeState,
            glassIntensity = glassIntensity,
            isFavorite = isFavorite,
            playlists = playlists,
            downloadStatus = download?.status,
            onCreatePlaylist = onCreatePlaylist,
            onDismiss = { showOptions = false }
        )
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
        modifier = Modifier.width(92.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(SearchSurface),
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
