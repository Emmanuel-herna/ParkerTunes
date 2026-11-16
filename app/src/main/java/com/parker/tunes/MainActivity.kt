package com.parker.tunes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.*
import com.parker.tunes.ui.theme.ParkerTunesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ParkerTunesTheme { ParkerTunesRoot() } }
    }
}

private val Ink = Color(0xFF090B12)
private val Panel = Color(0xFF151925)
private val Accent = Color(0xFFB69CFF)
private val Muted = Color(0xFF9298AA)

@Composable
fun ParkerTunesRoot() {
    val nav = rememberNavController()
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Search") }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = Color.White) {
                listOf("Search", "Library", "Settings").forEach { item ->
                    NavigationBarItem(
                        selected = selectedTab == item,
                        onClick = {
                            selectedTab = item
                            nav.navigate(item.lowercase()) { launchSingleTop = true }
                        },
                        icon = {
                            Icon(
                                when (item) {
                                    "Search" -> Icons.Default.Search
                                    "Library" -> Icons.Default.LibraryMusic
                                    else -> Icons.Default.Settings
                                }, contentDescription = item
                            )
                        },
                        label = { Text(item) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            indicatorColor = Accent.copy(alpha = .14f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "search", modifier = Modifier.padding(padding)) {
            composable("search") { SearchScreen(query, { query = it }) }
            composable("library") { LibraryScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

@Composable
private fun ScreenTitle(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(kicker.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
fun SearchScreen(query: String, onQuery: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        ScreenTitle("Your sound, your space", "Discover music", "Find something that feels like you.")
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search songs, artists...", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Accent) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, null, tint = Muted)
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Panel,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                cursorColor = Accent, focusedContainerColor = Panel, unfocusedContainerColor = Panel
            )
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(48.dp))
                Text(if (query.isBlank()) "Your next favourite starts here" else "No tracks found yet",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    if (query.isBlank()) "Search for a song or artist to get started."
                    else "Connect a music catalogue provider to enable search.",
                    color = Muted, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun LibraryScreen() {
    var tab by remember { mutableStateOf("Playlists") }
    Column(Modifier.fillMaxSize().background(Ink).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenTitle("Keep it close", "Your library", "Everything you want to come back to.")
        TabRow(selectedTabIndex = listOf("Playlists", "Favorites", "History").indexOf(tab),
            containerColor = Ink, contentColor = Accent) {
            listOf("Playlists", "Favorites", "History").forEachIndexed { index, label ->
                Tab(selected = tab == label, onClick = { tab = label }, text = { Text(label, fontSize = 12.sp) })
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when (tab) {
                        "Playlists" -> Icons.Default.QueueMusic
                        "Favorites" -> Icons.Default.FavoriteBorder
                        else -> Icons.Default.History
                    }, null, tint = Accent, modifier = Modifier.size(42.dp)
                )
                Text("Nothing here yet", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Your $tab will show up here.", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    var light by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Ink).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenTitle("Make it yours", "Settings", "A few things to shape your listening.")
        Row(Modifier.fillMaxWidth().background(Panel, MaterialTheme.shapes.large).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Light appearance", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Switch between dark and light mode", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = light, onCheckedChange = { light = it })
        }
        Text("ParkerTunes  •  Version 1.0.0", color = Muted, fontSize = 12.sp)
    }
}
