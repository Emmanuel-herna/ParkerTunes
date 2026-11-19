package com.parker.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.parker.tunes.data.MusicApi
import com.parker.tunes.model.Album
import com.parker.tunes.model.Artist
import com.parker.tunes.model.ArtistProfile
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ArtistBg = Color(0xFF080808)
private val ArtistPanel = Color(0xFF1B1B1B)
private val ArtistAccent = Color(0xFFB69CFF)
private val ArtistMuted = Color(0xFFA7A7A7)

@Composable
fun ArtistProfileScreen(
    artist: Artist,
    api: MusicApi,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit
) {
    var profile by remember(artist.id) { mutableStateOf<ArtistProfile?>(null) }
    var loading by remember(artist.id) { mutableStateOf(true) }
    var error by remember(artist.id) { mutableStateOf<String?>(null) }
    var retryToken by remember(artist.id) { mutableIntStateOf(0) }
    var expandedSection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artist.id, api, retryToken) {
        loading = true
        error = null
        try {
            profile = withContext(Dispatchers.IO) { api.getArtistProfile(artist) }
        } catch (failure: Exception) {
            error = failure.message?.takeIf { it.isNotBlank() } ?: "Couldn't load this artist."
        } finally {
            loading = false
        }
    }

    val data = profile
    LazyColumn(
        Modifier.fillMaxSize().background(ArtistBg),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                if (!artist.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = data?.artist?.avatarUrl ?: artist.avatarUrl,
                        contentDescription = "${artist.name} artist photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.56f)))
                } else Box(Modifier.fillMaxSize().background(ArtistPanel))
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(12.dp).align(Alignment.TopStart)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                ) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text("ARTIST", color = ArtistAccent, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(data?.artist?.name ?: artist.name, color = Color.White,
                        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val count = data?.artist?.subscriberCount ?: artist.subscriberCount
                    if (count > 0L) Text(
                        "${formatArtistCount(count)} subscribers",
                        color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp
                    )
                    if (!data?.artist?.description.isNullOrBlank()) {
                        Text(data?.artist?.description.orEmpty(), color = Color.White.copy(alpha = .78f),
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        if (loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArtistAccent) }
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(5) {
                        Box(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(12.dp))
                            .background(ArtistPanel))
                    }
                }
            }
        } else if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error.orEmpty(), color = ArtistMuted)
                    Button(onClick = { retryToken++ }, colors = ButtonDefaults.buttonColors(
                        containerColor = ArtistAccent, contentColor = Color.Black
                    )) { Text("Retry") }
                }
            }
        } else {
            val loaded = data ?: ArtistProfile(artist)
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { loaded.topSongs.firstOrNull()?.let(onPlay) },
                        enabled = loaded.topSongs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = ArtistAccent, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                    OutlinedButton(
                        onClick = { loaded.topSongs.randomOrNull()?.let(onPlay) },
                        enabled = loaded.topSongs.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
            }
            if (loaded.topSongs.isNotEmpty()) {
                item {
                    ArtistSectionTitle("Top songs", onShowMore = { expandedSection = "songs" })
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(loaded.topSongs.take(10), key = { it.id }) { song ->
                            Column(Modifier.width(142.dp).clickable { onPlay(song) }) {
                                ArtistCover(song.coverUrl, song.title, Modifier.size(142.dp))
                                Text(song.title, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                                Text(song.artist, color = ArtistMuted, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (loaded.albums.isNotEmpty()) {
                item { ArtistSectionTitle("Albums", onShowMore = { expandedSection = "albums" }) }
                item {
                    AlbumShelf(loaded.albums, onAlbum)
                }
            }
            if (loaded.singles.isNotEmpty()) {
                item { ArtistSectionTitle("Singles & EPs", onShowMore = { expandedSection = "singles" }) }
                item { AlbumShelf(loaded.singles, onAlbum) }
            }
            if (loaded.recentReleases.isNotEmpty()) {
                item { ArtistSectionTitle("Recent releases", onShowMore = { expandedSection = "recent" }) }
                item { AlbumShelf(loaded.recentReleases, onAlbum) }
            }
            if (loaded.relatedArtists.isNotEmpty()) {
                item { ArtistSectionTitle("Related artists", onShowMore = { expandedSection = "related" }) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(loaded.relatedArtists.take(10), key = { it.id }) { related ->
                            Column(Modifier.width(104.dp).clickable { onArtist(related) },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(model = related.avatarUrl, contentDescription = related.name,
                                    modifier = Modifier.size(88.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop)
                                Text(related.name, color = Color.White, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                }
            }
            if (loaded.topSongs.isEmpty() && loaded.albums.isEmpty() &&
                loaded.singles.isEmpty() && loaded.relatedArtists.isEmpty()) {
                item { Text("No artist releases are available right now.", color = ArtistMuted,
                    modifier = Modifier.padding(22.dp)) }
            }
        }
    }

    if (expandedSection != null && data != null) {
        Dialog(onDismissRequest = { expandedSection = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f),
                shape = RoundedCornerShape(20.dp), color = ArtistBg
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (expandedSection) {
                            "songs" -> "Top songs"
                            "albums" -> "Albums"
                            "singles" -> "Singles & EPs"
                            "recent" -> "Recent releases"
                            else -> "Related artists"
                        }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { expandedSection = null }) { Text("Close", color = ArtistAccent) }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (expandedSection) {
                            "songs" -> items(data.topSongs, key = { it.id }) { song ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { onPlay(song) }
                                        .clip(RoundedCornerShape(12.dp)).background(ArtistPanel).padding(10.dp)
                                ) {
                                    ArtistCover(song.coverUrl, song.title,
                                        Modifier.fillMaxWidth().height(132.dp))
                                    Text(song.title, color = Color.White, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp))
                                    Text(song.artist, color = ArtistMuted, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            "albums", "singles", "recent" -> {
                                val list = when (expandedSection) {
                                    "albums" -> data.albums
                                    "singles" -> data.singles
                                    else -> data.recentReleases
                                }
                                items(list, key = { it.id }) { album ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable { onAlbum(album) }
                                            .clip(RoundedCornerShape(12.dp)).background(ArtistPanel).padding(10.dp)
                                    ) {
                                        ArtistCover(album.coverUrl, album.title,
                                            Modifier.fillMaxWidth().height(132.dp))
                                        Text(album.title, color = Color.White, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 8.dp))
                                        Text(if (album.year.isNotBlank()) album.year else "Album",
                                            color = ArtistMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                            else -> items(data.relatedArtists, key = { it.id }) { related ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        expandedSection = null
                                        onArtist(related)
                                    }.clip(RoundedCornerShape(12.dp)).background(ArtistPanel).padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AsyncImage(model = related.avatarUrl, contentDescription = related.name,
                                        modifier = Modifier.size(112.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop)
                                    Text(related.name, color = Color.White, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSectionTitle(title: String, onShowMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onShowMore) { Text("Show more", color = ArtistAccent) }
    }
}

@Composable
private fun AlbumShelf(albums: List<Album>, onAlbum: (Album) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums.take(10), key = { it.id }) { album ->
            Column(Modifier.width(148.dp).clickable { onAlbum(album) }) {
                ArtistCover(album.coverUrl, album.title, Modifier.size(148.dp))
                Text(album.title, color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                Text(if (album.year.isNotBlank()) album.year else "Album",
                    color = ArtistMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ArtistCover(url: String?, description: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(ArtistPanel),
        contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = description,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Icon(Icons.Default.MusicNote, null, tint = ArtistMuted)
    }
}

@Composable
fun AlbumScreen(album: Album, api: MusicApi, onBack: () -> Unit, onPlay: (Song) -> Unit) {
    var tracks by remember(album.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(album.id) { mutableStateOf(true) }
    var error by remember(album.id) { mutableStateOf<String?>(null) }
    var retryToken by remember(album.id) { mutableIntStateOf(0) }
    LaunchedEffect(album.id, api, retryToken) {
        loading = true
        error = null
        try { tracks = withContext(Dispatchers.IO) { api.getAlbumTracks(album.id) } }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load this album." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().background(ArtistBg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text("Album", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtistCover(album.coverUrl, album.title, Modifier.size(124.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(album.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (album.trackCount > 0) "${album.trackCount} tracks" else "Album",
                            color = ArtistMuted, fontSize = 13.sp)
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArtistAccent) }
            if (error != null) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = ArtistMuted)
                    TextButton(onClick = { retryToken++ }) { Text("Retry", color = ArtistAccent) }
                }
            }
            items(tracks, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth().clickable { onPlay(song) }
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(song.artist, color = ArtistMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = ArtistAccent)
                }
            }
        }
    }
}

private fun formatArtistCount(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}
