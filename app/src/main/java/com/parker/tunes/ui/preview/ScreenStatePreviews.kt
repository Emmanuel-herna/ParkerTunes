package com.parker.tunes.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DarkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PreviewState { Loading, Empty, Loaded }

private val previewScreens: Map<String, ImageVector> = linkedMapOf(
    "Home" to Icons.Default.Home,
    "Explore" to Icons.Default.Explore,
    "Search" to Icons.Default.Search,
    "Library" to Icons.Default.LibraryMusic,
    "Now Playing" to Icons.Default.MusicNote,
    "Equalizer" to Icons.Default.Equalizer,
    "Settings" to Icons.Default.Settings,
    "Addons" to Icons.Default.CloudDownload,
    "Artist" to Icons.Default.Person,
    "Album" to Icons.Default.Album,
    "Listening Stats" to Icons.Default.ShowChart
)

@Composable
fun ParkerTunesPreviewScreen(screen: String, state: PreviewState) {
    val scheme = darkColorScheme(
        primary = Color(0xFFB69CFF),
        background = Color(0xFF080808),
        surface = Color(0xFF19171D),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(previewScreens[screen] ?: Icons.Default.MusicNote, null,
                        tint = scheme.primary, modifier = Modifier.size(28.dp))
                    Column {
                        Text("PARKERTUNES", color = scheme.primary, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(screen, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                }
                when (state) {
                    PreviewState.Loading -> {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                CircularProgressIndicator(color = scheme.primary)
                                Text("Loading your music…", color = Color.LightGray, fontSize = 14.sp)
                            }
                        }
                    }
                    PreviewState.Empty -> {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.MusicNote, null, tint = scheme.primary, modifier = Modifier.size(48.dp))
                                Text("Nothing here yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text("Your music will appear here when available.", color = Color.Gray, fontSize = 13.sp)
                                Button(onClick = {}) { Text("Explore music") }
                            }
                        }
                    }
                    PreviewState.Loaded -> {
                        Text("MADE FOR YOU", color = scheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                        PreviewTrackRow("Midnight City", "M83", scheme)
                        PreviewTrackRow("Sunset Lover", "Petit Biscuit", scheme)
                        PreviewTrackRow("A Moment Apart", "ODESZA", scheme)
                        Spacer(Modifier.height(4.dp))
                        Text("Recently played", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        PreviewTrackRow("Innerbloom", "RÜFÜS DU SOL", scheme)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTrackRow(title: String, artist: String, scheme: androidx.compose.material3.ColorScheme) {
    Row(
        Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(14.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).background(Color(0xFF30283A), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, tint = scheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(artist, color = Color.LightGray, fontSize = 12.sp)
        }
        Icon(Icons.Default.MusicNote, null, tint = scheme.primary)
    }
}

@Preview(name = "ParkerTunes loading", showBackground = true, backgroundColor = 0xFF080808)
@Composable
fun ParkerTunesLoadingPreview() { ParkerTunesPreviewScreen("Home", PreviewState.Loading) }

@Preview(name = "ParkerTunes empty", showBackground = true, backgroundColor = 0xFF080808)
@Composable
fun ParkerTunesEmptyPreview() { ParkerTunesPreviewScreen("Library", PreviewState.Empty) }

@Preview(name = "ParkerTunes loaded", showBackground = true, backgroundColor = 0xFF080808)
@Composable
fun ParkerTunesLoadedPreview() { ParkerTunesPreviewScreen("Explore", PreviewState.Loaded) }
