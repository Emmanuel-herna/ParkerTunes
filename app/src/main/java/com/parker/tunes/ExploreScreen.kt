package com.parker.tunes

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parker.tunes.data.MusicApi
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MoodCategory(val name: String, val emoji: String, val colors: List<Color>)
private val moodCategories = listOf(
    MoodCategory("Chill", "🧘", listOf(Color(0xFF235C68), Color(0xFF142C46))),
    MoodCategory("Workout", "💪", listOf(Color(0xFFB9472F), Color(0xFF642A48))),
    MoodCategory("Party", "🎉", listOf(Color(0xFF8737A8), Color(0xFF3F276D))),
    MoodCategory("Focus", "🎯", listOf(Color(0xFF315C9C), Color(0xFF1D3157))),
    MoodCategory("Afrobeats", "🔥", listOf(Color(0xFFB65A24), Color(0xFF62351E))),
    MoodCategory("Hip Hop", "🎤", listOf(Color(0xFF55515E), Color(0xFF24212C))),
    MoodCategory("Late Night", "🌙", listOf(Color(0xFF343D78), Color(0xFF171B3A))),
    MoodCategory("Love", "❤️", listOf(Color(0xFFB83E65), Color(0xFF642B55))),
    MoodCategory("Gospel", "🙏", listOf(Color(0xFF397C69), Color(0xFF1C4545))),
    MoodCategory("Dance", "💃", listOf(Color(0xFFBD4D8B), Color(0xFF6C2E79))),
    MoodCategory("Sad", "😢", listOf(Color(0xFF526B8B), Color(0xFF28364E))),
    MoodCategory("Trending", "🚀", listOf(Color(0xFF7A6330), Color(0xFF3C3524)))
)

@Composable
fun ExploreScreen(onMood: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text("Explore", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(moodCategories, key = { it.name }) { mood ->
                Box(
                    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(mood.colors)).clickable { onMood(mood.name) }
                ) {
                    Text(mood.emoji, fontSize = 34.sp, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
                    Text(mood.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                }
            }
        }
    }
}

private object MoodSongCache {
    private val values = LinkedHashMap<String, List<Song>>(16, 0.75f, true)
    @Synchronized fun get(key: String): List<Song>? = values[key]
    @Synchronized fun put(key: String, songs: List<Song>) {
        values[key] = songs
        while (values.size > 24) values.remove(values.keys.first())
    }
}

@Composable
fun MoodResultsScreen(mood: String, api: MusicApi, onBack: () -> Unit, onPlay: (Song) -> Unit) {
    var songs by remember(mood) { mutableStateOf(MoodSongCache.get(mood).orEmpty()) }
    var loading by remember(mood) { mutableStateOf(songs.isEmpty()) }
    var error by remember(mood) { mutableStateOf<String?>(null) }
    LaunchedEffect(mood, api) {
        val cached = MoodSongCache.get(mood)
        if (cached != null) {
            songs = cached
            loading = false
        } else {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) { api.getMoodSongs(mood) }
                songs = result
                MoodSongCache.put(mood, result)
                if (result.isEmpty()) error = "No tracks found for this mood yet."
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't load these tracks."
            } finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text(mood, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        when {
            loading -> MoodLoading()
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(error ?: "Couldn't load tracks", color = Color(0xFFA7A7A7), modifier = Modifier.padding(24.dp))
            }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Songs for your ${mood.lowercase()} mood", color = Color(0xFFA7A7A7), fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)) }
                items(songs, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { onPlay(song) }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF202020)),
                            contentAlignment = Alignment.Center) {
                            if (song.coverUrl.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = Color.Gray)
                            else AsyncImage(model = song.coverUrl, contentDescription = "Track cover",
                                modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color(0xFFA7A7A7), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFB69CFF), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodLoading() {
    val transition = rememberInfiniteTransition(label = "mood_shimmer")
    val alpha by transition.animateFloat(0.3f, 0.8f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "mood_shimmer_alpha")
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(8) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF292929).copy(alpha = alpha)))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF292929).copy(alpha = alpha)))
                    Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF292929).copy(alpha = alpha)))
                }
            }
        }
    }
}
