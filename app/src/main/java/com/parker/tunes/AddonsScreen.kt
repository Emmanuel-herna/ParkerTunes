package com.parker.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parker.tunes.data.sources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(registry: SourceRegistryDao, onBack: () -> Unit) {
    val sources by registry.list().collectAsState(initial = emptyList())
    val client = remember { AddonClient() }
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AddonEntity?>(null) }
    var url by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var health by remember { mutableStateOf<SourceHealth?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AddonEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Addons", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null; url = ""; displayName = ""; health = null; error = null; showEditor = true
            }) { Icon(Icons.Default.Add, "Add addon") }
        },
        containerColor = Color.Black
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text("Compatible music sources", color = Color.White, fontSize = 23.sp)
                Text("Add a server you trust. Plyra connects to its public addon API.", color = Color.LightGray, fontSize = 13.sp)
            }
            if (sources.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Extension, null, tint = Color(0xFFB69CFF), modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No addons added", color = Color.White)
                    Text("Tap + to add a compatible server.", color = Color.Gray, fontSize = 12.sp)
                }
            }
            items(sources, key = { it.id }) { source ->
                var rowHealth by remember(source.id) { mutableStateOf<SourceHealth?>(null) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF191919))
                        .clickable {
                            editing = source; url = source.baseUrl; displayName = source.name
                            health = null; error = null; showEditor = true
                        }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(SourceConfig.redact(source.baseUrl), color = Color.Gray, fontSize = 11.sp)
                        Text(source.version.ifBlank { "Compatible addon" }, color = Color.Gray, fontSize = 11.sp)
                        rowHealth?.let { HealthLabel(it) }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            rowHealth = withContext(Dispatchers.IO) { client.health(source.baseUrl) }
                        }
                    }) { Text("Test") }
                    IconButton(onClick = { pendingDelete = source }) { Icon(Icons.Default.MoreVert, "Addon options", tint = Color.LightGray) }
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editing == null) "Add addon" else "Edit addon") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                        label = { Text("Name (optional)") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("Addon server URL") }, placeholder = { Text("https://server.example/manifest.json") })
                    health?.let { HealthLabel(it) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    OutlinedButton(
                        enabled = url.isNotBlank() && !testing,
                        onClick = {
                            scope.launch {
                                testing = true; error = null
                                val result = withContext(Dispatchers.IO) { client.health(url) }
                                health = result
                                if (result is SourceHealth.Rejected) error = result.reason
                                if (result is SourceHealth.Unreachable) error = result.reason
                                testing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (testing) "Testing…" else "Test connection") }
                }
            },
            confirmButton = {
                TextButton(enabled = health is SourceHealth.Ok && !testing, onClick = {
                    scope.launch {
                        val manifest = withContext(Dispatchers.IO) { client.manifest(url) }
                        if (manifest == null) { error = "Could not read addon manifest"; return@launch }
                        val finalName = displayName.trim().ifBlank { manifest.name.ifBlank { manifest.id } }
                        registry.insert(AddonEntity(
                            id = manifest.id, baseUrl = SourceConfig.normalizeBase(url), name = finalName,
                            version = manifest.version, bitchordAuth = manifest.bitchordAuth,
                            lastSeenAt = System.currentTimeMillis(), enabled = true
                        ))
                        manifest.settings.forEach { setting ->
                            registry.updateSettings(listOf(AddonSettingsEntity(manifest.id, setting.key, setting.defaultValue)))
                        }
                        showEditor = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }
    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${source.name}?") },
            text = { Text("This removes the addon from Plyra. It does not affect the server.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { registry.delete(source.id); pendingDelete = null }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HealthLabel(health: SourceHealth) {
    val (label, tint) = when (health) {
        is SourceHealth.Ok -> health.detail to Color(0xFF73D69A)
        is SourceHealth.Unreachable -> health.reason to Color(0xFFFFC857)
        is SourceHealth.Rejected -> health.reason to Color(0xFFFF7777)
    }
    Text(label, color = tint, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
}
