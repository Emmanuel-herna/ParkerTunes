package com.parker.tunes

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.parker.tunes.data.BackupManager
import com.parker.tunes.data.LibraryDao
import com.parker.tunes.data.ListeningEventEntity
import com.parker.tunes.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SettingsMuted = Color(0xFFA7A7A7)
private val SettingsAccents = listOf(
    "purple" to Color(0xFFB69CFF), "blue" to Color(0xFF64B5F6),
    "cyan" to Color(0xFF4DD0E1), "green" to Color(0xFF81C784),
    "yellow" to Color(0xFFFFD54F), "orange" to Color(0xFFFFAB91),
    "pink" to Color(0xFFF48FB1), "red" to Color(0xFFEF5350)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dao: LibraryDao,
    repository: SettingsRepository,
    settingsPrefs: android.content.SharedPreferences,
    onOpenEqualizer: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preferredRefreshRate by remember(settingsPrefs) {
        mutableIntStateOf(settingsPrefs.getInt("preferred_refresh_rate", 0))
    }
    val refreshRateOptions = remember(context) {
        val display = (context as? Activity)?.window?.decorView?.display
        val supported = display?.supportedModes.orEmpty().map { it.refreshRate }
        listOf(60, 90, 120).filter { target ->
            supported.any { kotlin.math.abs(it - target.toFloat()) <= 1.5f }
        }.map { "$it Hz" }.let { listOf("System default") + it }
    }
    val refreshRateLabel = if (preferredRefreshRate <= 0) "System default" else "${preferredRefreshRate} Hz"
    val backup = remember(dao, context) { BackupManager(context, dao, settingsPrefs) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearActivity by remember { mutableStateOf(false) }
    var showActivity by remember { mutableStateOf(false) }
    var activityItems by remember { mutableStateOf<List<ListeningEventEntity>>(emptyList()) }
    val quality by repository.audioQuality.collectAsState(initial = "Auto")
    val crossfade by repository.crossfadeSeconds.collectAsState(initial = 0)
    val automix by repository.automix.collectAsState(initial = false)
    val gapless by repository.gaplessPlayback.collectAsState(initial = true)
    val autoplay by repository.autoplaySimilar.collectAsState(initial = true)
    val skipSilence by repository.skipSilence.collectAsState(initial = false)
    val resume by repository.resumePlayback.collectAsState(initial = true)
    val bass by repository.bassBoost.collectAsState(initial = false)
    val virtualizer by repository.virtualizer.collectAsState(initial = false)
    val soundCheck by repository.soundCheck.collectAsState(initial = false)
    val lyrics by repository.syncedLyrics.collectAsState(initial = true)
    val sources by repository.lyricsSources.collectAsState(initial = "LRCLIB,BetterLyrics,KuGou")
    val motion by repository.motionArtwork.collectAsState(initial = false)
    val queueButton by repository.showQueueButton.collectAsState(initial = true)
    val nerds by repository.statsForNerds.collectAsState(initial = false)
    val accent by repository.accentColor.collectAsState(initial = "purple")
    val amoled by repository.amoledBlack.collectAsState(initial = true)
    val playerBackground by repository.playerBackground.collectAsState(initial = "Artwork colors")
    val size by repository.interfaceSize.collectAsState(initial = 100)
    val reduceMotion by repository.reduceMotion.collectAsState(initial = false)
    val reduceTransparency by repository.reduceTransparency.collectAsState(initial = false)
    val lockscreen by repository.lockscreen.collectAsState(initial = true)
    val albumArt by repository.showAlbumArt.collectAsState(initial = true)
    val expandControls by repository.expandableControls.collectAsState(initial = true)
    val lastFm by repository.lastFmEnabled.collectAsState(initial = false)
    val listenBrainz by repository.listenBrainzEnabled.collectAsState(initial = false)
    val lastFmKey by repository.lastFmApiKey.collectAsState(initial = "")
    val listenBrainzToken by repository.listenBrainzToken.collectAsState(initial = "")
    val personalization by repository.recommendationPersonalization.collectAsState(initial = true)
    val reduceSimilar by repository.reduceSimilarMusic.collectAsState(initial = false)
    val diversity by repository.discoveryDiversity.collectAsState(initial = 50)
    val accentColor = SettingsAccents.firstOrNull { it.first == accent }?.second ?: SettingsAccents.first().second

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { backup.exportTo(uri) }
                .onSuccess { status = "Library backup exported." }
                .onFailure { status = it.message ?: "Backup failed." }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { backup.importFrom(uri) }
                .onSuccess { status = "Backup restored. $it library items merged." }
                .onFailure { status = it.message ?: "Restore failed." }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }

        item { SettingsSection("PLAYBACK") }
        item { SettingRow(Icons.Default.AutoAwesome, "Automix", "Beat-matched crossfade · Stage 2 alignment TODO", if (automix) "On" else "Off", { scope.launch { repository.setAutomix(!automix) } }, toggle = automix, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Tune, "Crossfade", if (crossfade == 0) "Crossfade between tracks" else "Equal-power transition", listOf(0,3,6,10).let { values -> when(crossfade) { 0 -> "Off"; 3 -> "3 s"; 6 -> "6 s"; 10 -> "10 s"; else -> "Off" } }, { scope.launch { repository.setCrossfadeSeconds(when(crossfade) { 0 -> 3; 3 -> 6; 6 -> 10; else -> 0 }); settingsPrefs.edit().putInt("crossfade_seconds", when(crossfade) { 0 -> 3; 3 -> 6; 6 -> 10; else -> 0 }).apply() } }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.AllInclusive, "Gapless playback", "Avoid pauses between queued tracks", if (gapless) "On" else "Off", { scope.launch { repository.setGaplessPlayback(!gapless) } }, toggle = gapless, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Radio, "Autoplay similar songs", "Continue with related music", if (autoplay) "On" else "Off", { scope.launch { repository.setAutoplaySimilar(!autoplay) } }, toggle = autoplay, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.ContentCut, "Skip silence", "Skip quiet sections in tracks", if (skipSilence) "On" else "Off", { scope.launch { repository.setSkipSilence(!skipSilence) } }, toggle = skipSilence, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.HighQuality, "Audio quality", "Preferred streaming quality", quality, { sheet = "quality" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.PlayCircle, "Resume where you left off", "Continue from your last position", if (resume) "On" else "Off", { scope.launch { repository.setResumePlayback(!resume) } }, toggle = resume, reduceMotion = reduceMotion) }

        item { SettingsSection("MUSIC INTELLIGENCE") }
        item { SettingRow(Icons.Default.AutoAwesome, "Personalize recommendations", "Use your on-device listening signals", if (personalization) "On" else "Off", { scope.launch { repository.setRecommendationPersonalization(!personalization) } }, toggle = personalization, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Explore, "Reduce similar music", "Favor variety over repeating familiar artists", if (reduceSimilar) "On" else "Off", { scope.launch { repository.setReduceSimilarMusic(!reduceSimilar) } }, toggle = reduceSimilar, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Tune, "Discovery diversity", "Balance familiar music and new artists", diversity.toString() + "%", { sheet = "discovery_diversity" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.History, "View listening activity", "See recommendation signals stored on this device", "View", { scope.launch { activityItems = withContext(kotlinx.coroutines.Dispatchers.IO) { dao.listeningEvents(100) }; showActivity = true } }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.DeleteSweep, "Reset Music DNA", "Clear local listening signals; keep favorites and play history", "Reset", { confirmClearActivity = true }, reduceMotion = reduceMotion) }

        item { SettingsSection("SOUND") }
        item { SettingRow(Icons.Default.Equalizer, "Equalizer", "Presets and frequency controls", "Open", onOpenEqualizer, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.GraphicEq, "Bass Boost", null, if (bass) "On" else "Off", { scope.launch { repository.setBassBoost(!bass) } }, toggle = bass, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.SurroundSound, "Virtualizer", null, if (virtualizer) "On" else "Off", { scope.launch { repository.setVirtualizer(!virtualizer) } }, toggle = virtualizer, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.VolumeUp, "Sound Check", "Loudness normalization · requires audio session integration", if (soundCheck) "On" else "Off", { scope.launch { repository.setSoundCheck(!soundCheck) } }, toggle = soundCheck, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.SurroundSound, "Spatial Audio", "Uses the device virtualizer when available", if (virtualizer) "On" else "Off", { scope.launch { repository.setVirtualizer(!virtualizer) } }, toggle = virtualizer, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Headphones, "Silence in audio", "Headphones recommended · effects vary by device", "Info", { sheet = "silence" }, reduceMotion = reduceMotion) }

        item { SettingsSection("NOW PLAYING") }
        item { SettingRow(Icons.Default.MusicNote, "Synced lyrics", "Show timed lyrics when available", if (lyrics) "On" else "Off", { scope.launch { repository.setSyncedLyrics(!lyrics) } }, toggle = lyrics, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.LibraryMusic, "Lyrics sources", "${sources.split(',').count { it.isNotBlank() }} sources enabled", "${sources.split(',').count { it.isNotBlank() }} sources on", { sheet = "sources" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Animation, "Motion artwork", "Animated artwork background · experimental", if (motion) "On" else "Off", { scope.launch { repository.setMotionArtwork(!motion) } }, toggle = motion, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.QueueMusic, "Show queue button", null, if (queueButton) "On" else "Off", { scope.launch { repository.setShowQueueButton(!queueButton) } }, toggle = queueButton, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Speed, "Stats for nerds", "Codec, bitrate and sample-rate overlay · UI pending", if (nerds) "On" else "Off", { scope.launch { repository.setStatsForNerds(!nerds) } }, toggle = nerds, reduceMotion = reduceMotion) }

        item { SettingsSection("DISPLAY") }
        item { SettingRow(Icons.Default.Speed, "Refresh rate", "Choose the display smoothness ParkerTunes requests", refreshRateLabel, { sheet = "refresh_rate" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Palette, "Accent color", "Choose ParkerTunes highlight color", accent.replaceFirstChar { it.uppercase() }, { sheet = "theme" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.DarkMode, "AMOLED black", "Use pure black surfaces", if (amoled) "On" else "Off", { scope.launch { repository.setAmoledBlack(!amoled) } }, toggle = amoled, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Wallpaper, "Player background", null, playerBackground, { sheet = "background" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.FormatSize, "Interface size", "Adjust text and controls", "$size%", { sheet = "size" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Animation, "Reduce motion", null, if (reduceMotion) "On" else "Off", { scope.launch { repository.setReduceMotion(!reduceMotion) } }, toggle = reduceMotion, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.BlurOn, "Reduce transparency", "Reduce blur and translucent layers", if (reduceTransparency) "On" else "Off", { scope.launch { repository.setReduceTransparency(!reduceTransparency) } }, toggle = reduceTransparency, reduceMotion = reduceMotion) }

        item { SettingsSection("NOTIFICATIONS") }
        item { SettingRow(Icons.Default.Lock, "Show on lockscreen", null, if (lockscreen) "On" else "Off", { scope.launch { repository.setLockscreen(!lockscreen) } }, toggle = lockscreen, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Album, "Show album art", null, if (albumArt) "On" else "Off", { scope.launch { repository.setShowAlbumArt(!albumArt) } }, toggle = albumArt, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Expand, "Expandable playback controls", null, if (expandControls) "On" else "Off", { scope.launch { repository.setExpandableControls(!expandControls) } }, toggle = expandControls, reduceMotion = reduceMotion) }

        item { SettingsSection("CONNECTIONS") }
        item { SettingRow(Icons.Default.Sync, "Scrobbling", "Last.fm and ListenBrainz", if (lastFm || listenBrainz) "Configured" else "Set up", { sheet = "scrobble" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.MusicNote, "Spotify metadata", "Search fallback and album artwork", "Configure", { sheet = "spotify" }, reduceMotion = reduceMotion) }

        item { SettingsSection("LIBRARY & STORAGE") }
        item { SettingRow(Icons.Default.Download, "Downloads", "Manage offline library", "Open", onOpenDownloads, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Delete, "Clear cache", "Image and temporary cache", "Clear", { confirmClear = true }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Backup, "Backup library", "Export playlists, favorites and history as JSON", "Export", { export.launch("ParkerTunes-backup.json") }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Restore, "Restore library", "Merge a JSON backup", "Import", { import.launch(arrayOf("application/json", "text/*")) }, reduceMotion = reduceMotion) }
        item { SettingsSection("ABOUT") }
        item { SettingRow(Icons.Default.SystemUpdate, "Software update", "Check GitHub releases for a newer build", "Check", { status = "Update checking is not connected yet." }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Info, "Version", "ParkerTunes release", "1.0.1 (build 12)", {}, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Code, "GitHub", "View source code", "Open", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Emmanuel-herna/ParkerTunes"))) } }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Description, "License", "GPL-3.0", "View", { sheet = "license" }, reduceMotion = reduceMotion) }
        item { SettingRow(Icons.Default.Share, "Rate & share", "Share ParkerTunes", "Share", { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "ParkerTunes — https://github.com/Emmanuel-herna/ParkerTunes"), "Share ParkerTunes")) } }, reduceMotion = reduceMotion) }
        item { Text("Search metadata & artwork © Spotify. Spotify is a trademark of Spotify AB. ParkerTunes is not affiliated with Spotify.", color = SettingsMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp)) }
        item { SettingRow(Icons.Default.Favorite, "Made by", "ParkerTunes · Built with BitChord as reference", "", {}, reduceMotion = reduceMotion) }
        item { status?.let { Text(it, color = SettingsMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) } }
    }

    if (confirmClearActivity) AlertDialog(
        onDismissRequest = { confirmClearActivity = false },
        title = { Text("Clear listening activity?") },
        text = { Text("This deletes local listening-event signals used by Music DNA. Favorites, playlists, downloads and play history will remain. Favorites can still influence recommendations.") },
        confirmButton = { TextButton(onClick = {
            confirmClearActivity = false
            scope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dao.clearListeningEvents()
                    dao.clearHomeShelfCache()
                    dao.pruneRecommendationCache(Long.MAX_VALUE)
                }
                activityItems = emptyList()
                status = "Listening activity cleared. Your library and play history are unchanged."
            }
        }) { Text("Clear activity") } },
        dismissButton = { TextButton(onClick = { confirmClearActivity = false }) { Text("Cancel") } }
    )

    if (showActivity) AlertDialog(
        onDismissRequest = { showActivity = false },
        title = { Text("Listening activity") },
        text = {
            if (activityItems.isEmpty()) Text("No local recommendation activity has been recorded yet.")
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activityItems.take(12).forEach { event ->
                    Column {
                        Text(event.title.ifBlank { event.songId }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(event.artist.ifBlank { "Unknown artist" } + " · " + event.eventType.replace("_", " ").lowercase(), color = SettingsMuted, fontSize = 12.sp)
                    }
                }
                if (activityItems.size > 12) Text("Showing the latest 12 of " + activityItems.size + " events.", color = SettingsMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showActivity = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { showActivity = false; confirmClearActivity = true }) { Text("Clear activity") } }
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear cache?") },
        text = { Text("This clears cached artwork and temporary image files. Downloaded songs and your library will remain.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            runCatching { context.imageLoader.memoryCache?.clear() }
            status = "Image cache cleared."
        }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    if (sheet != null) ModalBottomSheet(
        onDismissRequest = { sheet = null },
        containerColor = Color(0xFF171717),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        when (sheet) {
            "discovery_diversity" -> {
                Text("Discovery diversity", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                Text("Lower values favor familiar artists. Higher values introduce more variety.", color = SettingsMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp))
                Slider(value = diversity.toFloat(), onValueChange = { value -> scope.launch { repository.setDiscoveryDiversity(value.toInt()) } }, valueRange = 0f..100f, modifier = Modifier.padding(horizontal = 20.dp))
                Text(diversity.toString() + "%", color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
                TextButton(onClick = { sheet = null }, modifier = Modifier.padding(12.dp)) { Text("Done") }
            }
            "quality" -> ChoiceSheet("Audio quality", listOf("Auto","High","Medium","Low"), quality) { scope.launch { repository.setAudioQuality(it) }; sheet = null }
            "refresh_rate" -> ChoiceSheet("Refresh rate", refreshRateOptions, refreshRateLabel) { choice ->
                val rate = when (choice) {
                    "60 Hz" -> 60
                    "90 Hz" -> 90
                    "120 Hz" -> 120
                    else -> 0
                }
                preferredRefreshRate = rate
                settingsPrefs.edit().putInt("preferred_refresh_rate", rate).apply()
                (context as? Activity)?.let { applyPreferredRefreshRate(it, rate) }
                sheet = null
            }
            "background" -> ChoiceSheet("Player background", listOf("Artwork colors","AMOLED black","Gradient"), playerBackground) { scope.launch { repository.setPlayerBackground(it) }; sheet = null }
            "size" -> ChoiceSheet("Interface size", listOf("90%","100%","110%","120%"), "$size%") { scope.launch { repository.setInterfaceSize(it.removeSuffix("%").toInt()) }; sheet = null }
            "theme" -> {
                Text("Accent color", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    SettingsAccents.forEach { (name, color) ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(color).clickable { scope.launch { repository.setAccentColor(name) }; sheet = null },
                            contentAlignment = Alignment.Center) { if (accent == name) Icon(Icons.Default.Check, null, tint = Color.Black) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AMOLED black", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = amoled, onCheckedChange = { scope.launch { repository.setAmoledBlack(it) } })
                }
                TextButton(onClick = { sheet = null }, modifier = Modifier.padding(12.dp)) { Text("Done") }
            }
            "sources" -> {
                Text("Lyrics sources", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                val all = listOf("LRCLIB","BetterLyrics","KuGou","Musixmatch","Apple Music")
                all.forEach { provider ->
                    val enabled = provider in sources.split(',')
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragHandle, null, tint = SettingsMuted)
                        Text(provider, color = Color.White, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Switch(checked = enabled, onCheckedChange = { on ->
                            val next = if (on) (sources.split(',') + provider).distinct() else sources.split(',').filterNot { it == provider }
                            scope.launch { repository.setLyricsSources(next.joinToString(",")) }
                        })
                    }
                }
                Text("Source priority follows the saved order. Drag reordering is not enabled in this build.", color = SettingsMuted, fontSize = 12.sp, modifier = Modifier.padding(22.dp))
                TextButton(onClick = { sheet = null }, modifier = Modifier.padding(12.dp)) { Text("Done") }
            }
            "spotify" -> {
                val spotifyEnabled by repository.spotifyMetadataEnabled.collectAsState(initial = true)
                val savedId by repository.spotifyClientId.collectAsState(initial = "")
                val savedSecret by repository.spotifyClientSecret.collectAsState(initial = "")
                var clientId by remember(savedId) { mutableStateOf(savedId) }
                var clientSecret by remember(savedSecret) { mutableStateOf(savedSecret) }
                Text("Spotify metadata", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Spotify metadata", color = Color.White)
                        Text("Search fallback and artwork only. Audio stays on YouTube/NewPipe.", color = SettingsMuted, fontSize = 11.sp)
                    }
                    Switch(checked = spotifyEnabled, onCheckedChange = { enabled -> scope.launch { repository.setSpotifyMetadataEnabled(enabled) } })
                }
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text("Spotify Client ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("Spotify Client Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                )
                Text("Personal-use configuration. A secret stored in an Android app can be extracted; move credentials to a backend before public distribution.", color = SettingsMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { sheet = null }) { Text("Cancel") }
                    Button(onClick = {
                        scope.launch {
                            repository.setSpotifyClientId(clientId)
                            repository.setSpotifyClientSecret(clientSecret)
                        }
                        sheet = null
                    }) { Text("Save") }
                }
            }
            "scrobble" -> {
                Text("Scrobbling", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                var fm by remember(lastFmKey) { mutableStateOf(lastFmKey) }
                var lb by remember(listenBrainzToken) { mutableStateOf(listenBrainzToken) }
                OutlinedTextField(fm, { fm = it }, label = { Text("Last.fm API key") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true)
                OutlinedTextField(lb, { lb = it }, label = { Text("ListenBrainz user token") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("Last.fm", color = Color.White, modifier = Modifier.weight(1f)); Switch(lastFm, { scope.launch { repository.setLastFmEnabled(it) } }) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("ListenBrainz", color = Color.White, modifier = Modifier.weight(1f)); Switch(listenBrainz, { scope.launch { repository.setListenBrainzEnabled(it) } }) }
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { sheet = null }) { Text("Cancel") }
                    Button(onClick = { scope.launch { repository.setLastFmApiKey(fm); repository.setListenBrainzToken(lb) }; sheet = null }) { Text("Save") }
                }
            }
            "silence" -> {
                Text("Audio effects", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                Text("Some audio effects depend on the phone's audio-effect implementation. Headphones are recommended; effects may not be audible on every speaker or Bluetooth route.", color = SettingsMuted, modifier = Modifier.padding(horizontal = 22.dp))
                TextButton(onClick = { sheet = null }, modifier = Modifier.padding(16.dp)) { Text("Close") }
            }
            "license" -> {
                Text("GPL-3.0", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
                Text("ParkerTunes is distributed under the GNU General Public License version 3. See the repository for the complete license text.", color = SettingsMuted, modifier = Modifier.padding(horizontal = 22.dp))
                TextButton(onClick = { sheet = null }, modifier = Modifier.padding(16.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector, title: String, subtitle: String?, value: String,
    onClick: () -> Unit, toggle: Boolean? = null, reduceMotion: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (reduceMotion) tween(durationMillis = 80) else spring(dampingRatio = 0.08f, stiffness = 650f),
        label = "settingRowPress"
    )
    Row(
        Modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = SettingsMuted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = SettingsMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (toggle != null) Switch(checked = toggle, onCheckedChange = { onClick() })
        else {
            Text(value, color = SettingsMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value.isNotBlank() && value !in listOf("1.0.1 (build 12)")) Icon(Icons.Default.ChevronRight, null, tint = SettingsMuted)
        }
    }
}

@Composable
private fun ChoiceSheet(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
    options.forEach { option ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(option, color = Color.White, modifier = Modifier.weight(1f))
            RadioButton(selected == option, onClick = { onSelect(option) })
        }
    }
    Spacer(Modifier.height(20.dp))
}
