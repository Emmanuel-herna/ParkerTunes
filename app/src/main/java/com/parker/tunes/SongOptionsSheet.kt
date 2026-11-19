package com.parker.tunes

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parker.tunes.data.DownloadEntity
import com.parker.tunes.data.PlaylistEntity
import com.parker.tunes.model.Song
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState

enum class SongOptionsSource { SEARCH, QUEUE, PLAYLIST }

sealed class SongAction {
    data object Play : SongAction()
    data object PlayNext : SongAction()
    data object AddToQueue : SongAction()
    data object Download : SongAction()
    data object ToggleFavorite : SongAction()
    data class AddToPlaylist(val playlistId: String) : SongAction()
    data object GoToArtist : SongAction()
    data object GoToAlbum : SongAction()
    data object StartRadio : SongAction()
    data object Share : SongAction()
    data object ShowInfo : SongAction()
    data object Remove : SongAction()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeApi::class)
@Composable
fun SongOptionsSheet(
    song: Song,
    source: SongOptionsSource,
    onAction: (SongAction) -> Unit,
    hazeState: HazeState,
    glassIntensity: Int,
    isFavorite: Boolean,
    playlists: List<PlaylistEntity>,
    downloadStatus: String? = null,
    onCreatePlaylist: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val fraction = glassIntensity.coerceIn(0, 100) / 100f
    val blurRadius = (4f + 36f * fraction).dp
    val tintAlpha = 0.08f + 0.37f * fraction
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var mainVisible by remember { mutableStateOf(true) }
    var infoVisible by remember { mutableStateOf(false) }
    var playlistPickerVisible by remember { mutableStateOf(false) }
    var createPlaylistVisible by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var favorite by remember(song.id, isFavorite) { mutableStateOf(isFavorite) }
    val contentScale by animateFloatAsState(
        targetValue = if (mainVisible) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "song_options_sheet_scale"
    )

    fun perform(action: SongAction) {
        onAction(action)
        when (action) {
            SongAction.ToggleFavorite -> favorite = !favorite
            else -> Unit
        }
    }

    if (mainVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                mainVisible = false
                onDismiss()
            },
            sheetState = sheetState,
            shape = shape,
            containerColor = Color.Transparent,
            scrimColor = Color.Black.copy(alpha = 0.34f),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape).background(Color.White.copy(alpha = 0.48f))
                )
            },
            contentWindowInsets = { WindowInsets.navigationBars }
        ) {
            val glassModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.liquidGlass(hazeState, blurRadius, tintAlpha, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            } else {
                Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Color.White.copy(alpha = tintAlpha))
                    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f))), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            }

            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                }.then(glassModifier).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.10f))) {
                        if (!song.coverUrl.isNullOrBlank()) {
                            AsyncImage(song.coverUrl, contentDescription = "Cover art for ${song.title}", modifier = Modifier.fillMaxSize())
                        } else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(song.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        favorite = !favorite
                        onAction(SongAction.ToggleFavorite)
                    }) {
                        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Toggle favorite",
                            tint = if (favorite) Color(0xFFFF6B91) else Color.White.copy(alpha = 0.82f))
                    }
                    IconButton(onClick = { playlistPickerVisible = true }) {
                        Icon(Icons.Default.MoreHoriz, "More song actions", tint = Color.White.copy(alpha = 0.75f))
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickGlassAction("Play next", Icons.Default.SkipNext, hazeState, blurRadius, tintAlpha, Modifier.weight(1f)) {
                        perform(SongAction.PlayNext)
                    }
                    QuickGlassAction("Add to queue", Icons.Default.QueueMusic, hazeState, blurRadius, tintAlpha, Modifier.weight(1f)) {
                        perform(SongAction.AddToQueue)
                    }
                    QuickGlassAction(
                        when (downloadStatus) {
                            DownloadEntity.STATUS_COMPLETE -> "Downloaded"
                            DownloadEntity.STATUS_DOWNLOADING -> "Downloading"
                            else -> "Download"
                        },
                        if (downloadStatus == DownloadEntity.STATUS_COMPLETE) Icons.Default.CheckCircle else Icons.Default.Download,
                        hazeState, blurRadius, tintAlpha, Modifier.weight(1f)
                    ) { perform(SongAction.Download) }
                }

                Spacer(Modifier.height(18.dp))
                SongOptionsGroup("Playback") {
                    SongOptionsRow(Icons.Default.PlayArrow, "Play") { perform(SongAction.Play); mainVisible = false; onDismiss() }
                }
                SongOptionsDivider()
                SongOptionsGroup("Save") {
                    SongOptionsRow(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (favorite) "Remove from favorites" else "Add to favorites") {
                        favorite = !favorite
                        onAction(SongAction.ToggleFavorite)
                    }
                    SongOptionsRow(Icons.Default.PlaylistAdd, "Add to playlist") { playlistPickerVisible = true }
                }
                SongOptionsDivider()
                SongOptionsGroup("Explore") {
                    SongOptionsRow(Icons.Default.Person, "Go to artist") { perform(SongAction.GoToArtist); mainVisible = false; onDismiss() }
                    SongOptionsRow(Icons.Default.Album, "Go to album") { perform(SongAction.GoToAlbum); mainVisible = false; onDismiss() }
                    SongOptionsRow(Icons.Default.Radio, "Start radio") { perform(SongAction.StartRadio); mainVisible = false; onDismiss() }
                }
                SongOptionsDivider()
                SongOptionsGroup("Other") {
                    SongOptionsRow(Icons.Default.Share, "Share") { perform(SongAction.Share); mainVisible = false; onDismiss() }
                    SongOptionsRow(Icons.Default.Info, "Song info") {
                        perform(SongAction.ShowInfo)
                        mainVisible = false
                        onDismiss()
                        infoVisible = true
                    }
                }
                if (source != SongOptionsSource.SEARCH) {
                    SongOptionsDivider()
                    SongOptionsRow(Icons.Default.Delete, if (source == SongOptionsSource.QUEUE) "Remove from queue" else "Remove from playlist",
                        tint = Color(0xFFFF6B6B)) {
                        perform(SongAction.Remove)
                        mainVisible = false
                        onDismiss()
                    }
                }
            }
        }
    }

    if (playlistPickerVisible) {
        AlertDialog(
            onDismissRequest = { playlistPickerVisible = false },
            title = { Text("Add to playlist") },
            text = {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            playlistPickerVisible = false
                            createPlaylistVisible = true
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Text("New playlist", Modifier.padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(playlists.size) { index ->
                        val playlist = playlists[index]
                        TextButton(onClick = {
                            onAction(SongAction.AddToPlaylist(playlist.id))
                            playlistPickerVisible = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(playlist.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistPickerVisible = false }) { Text("Close") } }
        )
    }

    if (createPlaylistVisible) {
        AlertDialog(
            onDismissRequest = { createPlaylistVisible = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it },
                    singleLine = true, label = { Text("Playlist name") })
            },
            confirmButton = {
                TextButton(enabled = newPlaylistName.isNotBlank(), onClick = {
                    onCreatePlaylist(newPlaylistName.trim())
                    createPlaylistVisible = false
                    newPlaylistName = ""
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createPlaylistVisible = false }) { Text("Cancel") } }
        )
    }

    if (infoVisible) {
        ModalBottomSheet(
            onDismissRequest = { infoVisible = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            scrimColor = Color.Black.copy(alpha = 0.28f)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text("Song info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                SongInfoLine("Title", song.title)
                SongInfoLine("Artist", song.artist)
                SongInfoLine("Album", song.album ?: "Not available")
                SongInfoLine("Duration", if (song.durationSec > 0) "%d:%02d".format(song.durationSec / 60, song.durationSec % 60) else "Not available")
                SongInfoLine("Audio quality", song.sourceQuality ?: "Not available")
                SongInfoLine("Release date", "Not available")
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun QuickGlassAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hazeState: HazeState,
    blurRadius: androidx.compose.ui.unit.Dp,
    tintAlpha: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier.height(76.dp)
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Modifier.liquidGlass(hazeState, blurRadius, tintAlpha, shape)
            else Modifier.clip(shape).background(Color.White.copy(alpha = tintAlpha))
                .border(1.dp, Color.White.copy(alpha = 0.20f), shape))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
            Text(label, color = Color.White.copy(alpha = 0.90f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SongOptionsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title.uppercase(), color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, modifier = Modifier.padding(bottom = 3.dp, top = 3.dp))
        content()
    }
}

@Composable
private fun SongOptionsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint.copy(alpha = 0.92f), modifier = Modifier.size(21.dp))
        Text(label, color = tint, fontSize = 14.sp, modifier = Modifier.padding(start = 15.dp).weight(1f))
    }
}

@Composable
private fun SongOptionsDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.7.dp,
        modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SongInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 13.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(max = 190.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
