package com.parker.tunes

import android.net.Uri
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

private val SpotifyBlack = Color(0xFF000000)
private val SpotifyGray = Color(0xFFA7A7A7)
private val SpotifyAccent = Color(0xFFB69CFF)

@Composable
fun SpotifyHomeScreen(
    dao: LibraryDao,
    player: MediaController?,
    onPlay: (Song) -> Unit,
    onSettings: () -> Unit
) {
    val recent by remember(dao) { dao.recentlyPlayed().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val topPicks by remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack),
        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item(key = "spotify_topbar") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.size(width = 34.dp, height = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(12.dp, 22.dp, 16.dp, 27.dp, 13.dp).forEachIndexed { index, h ->
                            Box(Modifier.width(5.dp).height(h).clip(RoundedCornerShape(5.dp))
                                .background(if (index % 2 == 0) SpotifyAccent else Color.White))
                        }
                    }
                    Text("ParkerTunes", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF292333)).padding(2.dp)
                    .clip(CircleShape).background(SpotifyAccent).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center) {
                    Text("P", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (topPicks.isNotEmpty()) {
            item(key = "spotify_hero") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Top Picks for You", color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(topPicks.take(8), key = { "hero_" + it.songId }) { pick ->
                            val song = Song(pick.songId, pick.title.ifBlank { pick.songId }, pick.artist, pick.coverUrl)
                            Box(Modifier.width(300.dp).height(380.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF25212E)).clickable { onPlay(song) }) {
                                if (!pick.coverUrl.isNullOrBlank()) {
                                    AsyncImage(model = pick.coverUrl, contentDescription = "Cover artwork",
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else {
                                    Icon(Icons.Default.Album, null, tint = Color.White.copy(alpha = .65f),
                                        modifier = Modifier.align(Alignment.Center).size(80.dp))
                                }
                                Box(Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .84f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("NEW FROM " + pick.artist.ifBlank { "YOUR LIBRARY" }.uppercase(),
                                        color = Color(0xFFD0D0D0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(pick.title.ifBlank { pick.songId }, color = Color.White, fontSize = 25.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Single · 2026", color = SpotifyGray, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "spotify_recents_heading") {
            Text("Recents", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (recent.isEmpty()) {
            item(key = "spotify_empty") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.History, null, tint = SpotifyGray, modifier = Modifier.size(25.dp))
                    Text("Your recents will appear here", color = SpotifyGray, fontSize = 14.sp)
                }
            }
        } else {
            items(recent, key = { "recent_" + it.id }) { track ->
                val song = Song(track.songId, track.title.ifBlank { track.songId }, track.artist, track.coverUrl)
                var menuExpanded by remember(track.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(8.dp)).clickable { onPlay(song) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF242424)),
                        contentAlignment = Alignment.Center) {
                        if (track.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = SpotifyGray)
                        else AsyncImage(model = track.coverUrl, contentDescription = "Track artwork",
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist.ifBlank { "Unknown artist" }, color = SpotifyGray, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Track options", tint = SpotifyGray)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                onClick = {
                                    menuExpanded = false
                                    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                                        .setArtworkUri(song.coverUrl?.let(Uri::parse)).build()
                                    player?.addMediaItem(MediaItem.Builder().setMediaId(song.id).setMediaMetadata(metadata).build())
                                })
                            DropdownMenuItem(text = { Text("Add to favorites") }, leadingIcon = { Icon(Icons.Default.FavoriteBorder, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        dao.addFavorite(FavoriteEntity(songId = song.id, title = song.title,
                                            artist = song.artist, coverUrl = song.coverUrl))
                                    }
                                })
                            DropdownMenuItem(text = { Text("Remove from recents") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch(Dispatchers.IO) { dao.removeHistory(track.id) }
                                })
                        }
                    }
                }
            }
        }
    }
}
