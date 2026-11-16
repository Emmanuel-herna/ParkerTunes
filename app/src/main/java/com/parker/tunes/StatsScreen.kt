package com.parker.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.parker.tunes.data.LibraryDao
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.util.Locale

@Composable
fun StatsScreen(dao: LibraryDao, onBack: () -> Unit) {
    val total by remember(dao) { dao.totalListeningTime().flowOn(Dispatchers.IO) }.collectAsState(0L)
    val weekStart = remember { System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
    val week by remember(dao) { dao.listeningTimeSince(weekStart).flowOn(Dispatchers.IO) }.collectAsState(0L)
    val activeHour by remember(dao) { dao.mostActiveHour().flowOn(Dispatchers.IO) }.collectAsState(null)
    val artists by remember(dao) { dao.topArtistsByTime().flowOn(Dispatchers.IO) }.collectAsState(emptyList())
    val songs by remember(dao) { dao.mostPlayed().flowOn(Dispatchers.IO) }.collectAsState(emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("YOUR LISTENING", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Listening stats", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Last 7 days", formatListeningTime(week), Modifier.weight(1f))
                StatCard("All time", formatListeningTime(total), Modifier.weight(1f))
            }
        }
        item {
            StatCard("Most active hour", activeHour?.let { String.format(Locale.getDefault(), "%02d:00 – %02d:00", it.hour, (it.hour + 1) % 24) } ?: "Not enough data", Modifier.fillMaxWidth())
        }
        item { Text("TOP ARTISTS", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        if (artists.isEmpty()) item { Text("Your most-played artists will appear here as you listen.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f)) }
        items(artists) { artist ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                Text(artist.artist, Modifier.weight(1f).padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(formatListeningTime(artist.durationMs), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), fontSize = 12.sp)
            }
        }
        item { Text("MOST PLAYED SONGS", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        if (songs.isEmpty()) item { Text("Play some music to build your stats.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f)) }
        items(songs) { song ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                Text(song.title.ifBlank { song.songId }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${song.artist} • ${song.playCount} plays", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatListeningTime(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) / 60_000L)
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${minutes}m"
}
