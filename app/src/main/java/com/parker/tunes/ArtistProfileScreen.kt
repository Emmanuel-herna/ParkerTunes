package com.parker.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
fun ArtistProfileScreen(artist: Artist, api: MusicApi, onBack: () -> Unit, onPlay: (Song) -> Unit, onAlbum: (Album) -> Unit) {
    var profile by remember(artist.id) { mutableStateOf<ArtistProfile?>(null) }
    var loading by remember(artist.id) { mutableStateOf(true) }
    var error by remember(artist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist.id, api) {
        loading = true
        error = null
        try { profile = withContext(Dispatchers.IO) { api.getArtistProfile(artist) } }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load this artist." }
        finally { loading = false }
    }
    LazyColumn(Modifier.fillMaxSize().background(ArtistBg), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                if (!artist.avatarUrl.isNullOrBlank()) {
                    AsyncImage(model = artist.avatarUrl, contentDescription = "${artist.name} artist photo",
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)))
                } else Box(Modifier.fillMaxSize().background(ArtistPanel))
                IconButton(onClick = onBack, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text("ARTIST", color = ArtistAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(profile?.artist?.name ?: artist.name, color = Color.White, fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val count = profile?.artist?.subscriberCount ?: artist.subscriberCount
                    if (count > 0L) Text("${formatArtistCount(count)} subscribers", color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp)
                }
            }
        }
        if (loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArtistAccent) }
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(5) { Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp)).background(ArtistPanel)) }
                }
            }
        } else if (error != null) {
            item { Text(error ?: "Couldn't load artist", color = ArtistMuted, modifier = Modifier.padding(22.dp)) }
        } else {
            val data = profile ?: ArtistProfile(artist)
            item { Text("Top songs", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp)) }
            if (data.topSongs.isEmpty()) {
                item { Text("Top songs aren't available right now.", color = ArtistMuted, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(data.topSongs, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().height(72.dp).clickable { onPlay(song) }.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(ArtistPanel)) {
                            if (song.coverUrl != null) AsyncImage(song.coverUrl, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Default.MusicNote, null, tint = ArtistMuted, modifier = Modifier.align(Alignment.Center))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = ArtistMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.PlayArrow, null, tint = ArtistAccent, modifier = Modifier.size(25.dp))
                    }
                }
            }
            if (data.albums.isNotEmpty()) {
                item { Text("Albums", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 14.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(data.albums, key = { it.id }) { album ->
                            Column(Modifier.width(154.dp).clickable { onAlbum(album) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(154.dp).clip(RoundedCornerShape(12.dp)).background(ArtistPanel)) {
                                    if (album.coverUrl != null) AsyncImage(album.coverUrl, "${album.title} cover",
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else Icon(Icons.Default.MusicNote, null, tint = ArtistMuted, modifier = Modifier.align(Alignment.Center))
                                }
                                Text(album.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (album.trackCount > 0) "${album.trackCount} tracks" else "Album", color = ArtistMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(album: Album, api: MusicApi, onBack: () -> Unit, onPlay: (Song) -> Unit) {
    var tracks by remember(album.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(album.id) { mutableStateOf(true) }
    LaunchedEffect(album.id, api) {
        loading = true
        tracks = withContext(Dispatchers.IO) { api.getAlbumTracks(album.id) }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(ArtistBg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text("Album", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(album.coverUrl, "${album.title} cover", modifier = Modifier.size(124.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(album.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (album.trackCount > 0) "${album.trackCount} tracks" else "Album", color = ArtistMuted, fontSize = 13.sp)
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArtistAccent) }
            items(tracks, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth().clickable { onPlay(song) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
