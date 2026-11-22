package com.parker.tunes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

private const val RELEASE_MANIFEST_URL = "https://github.com/Emmanuel-herna/ParkerTunes/releases/latest/download/latest.json"
private const val TRUSTED_APK_URL = "https://github.com/Emmanuel-herna/ParkerTunes/releases/latest/download/ParkerTunes.apk"

private data class ParkerTunesRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String
)

object ParkerTunesUpdateEvents {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val mutableStatus = MutableStateFlow<String?>(null)
    val checkRequests = requests.asSharedFlow()
    val status = mutableStatus.asStateFlow()

    fun requestCheck() {
        mutableStatus.value = "Checking for updates…"
        requests.tryEmit(Unit)
    }

    internal fun setStatus(message: String?) { mutableStatus.value = message }
}

@Composable
internal fun ParkerTunesUpdateController() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ParkerTunesRelease?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var received by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = downloadedApk
        if (file == null || !file.isFile) {
            errorMessage = "The downloaded update is no longer available. Please download it again."
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            errorMessage = "Allow ParkerTunes to install apps in Android settings, then tap Install update again."
        } else {
            runCatching { openAndroidInstaller(context, file) }
                .onFailure { errorMessage = it.message ?: "Android couldn't open the installer." }
        }
    }

    val requestInstall: (File) -> Unit = { file ->
        downloadedApk = file
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                permissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            }.onFailure {
                errorMessage = "Enable permission to install apps from ParkerTunes in Android settings."
            }
        } else {
            runCatching { openAndroidInstaller(context, file) }
                .onFailure { errorMessage = it.message ?: "Android couldn't open the installer." }
        }
    }

    LaunchedEffect(Unit) {
        suspend fun checkForUpdates(manual: Boolean) {
            if (manual) ParkerTunesUpdateEvents.setStatus("Checking for updates…")
            val installedCode = runCatching {
                withContext(Dispatchers.IO) {
                    PackageInfoCompat.getLongVersionCode(
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    )
                }
            }.getOrElse {
                if (manual) ParkerTunesUpdateEvents.setStatus("Couldn't read the installed app version.")
                return
            }
            val latest = runCatching { fetchLatestParkerTunesRelease() }.getOrElse {
                if (manual) ParkerTunesUpdateEvents.setStatus(
                    "Couldn't check GitHub Releases. Check your internet connection and try again."
                )
                return
            }
            if (latest.versionCode > installedCode) {
                release = latest
                showDialog = true
                errorMessage = null
                statusMessage = "Version ${latest.versionName} is ready to install."
                ParkerTunesUpdateEvents.setStatus("Update ${latest.versionName} is available.")
            } else if (manual) {
                ParkerTunesUpdateEvents.setStatus("ParkerTunes is up to date.")
            }
        }

        launch { checkForUpdates(manual = false) }
        ParkerTunesUpdateEvents.checkRequests.collect { checkForUpdates(manual = true) }
    }

    val latest = release
    if (showDialog && latest != null) {
        AlertDialog(
            onDismissRequest = { if (!downloading) showDialog = false },
            title = { Text("ParkerTunes update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Version ${latest.versionName} is ready.")
                    if (latest.releaseNotes.isNotBlank()) Text(latest.releaseNotes)
                    if (downloading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        val label = if (total > 0L) {
                            "${(progress * 100).toInt()}% · ${formatUpdateBytes(received)} of ${formatUpdateBytes(total)}"
                        } else "${formatUpdateBytes(received)} downloaded"
                        Text(label)
                    }
                    statusMessage?.let { Text(it) }
                    errorMessage?.let { Text(it) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !downloading,
                    onClick = {
                        val ready = downloadedApk?.takeIf { it.isFile }
                        if (ready != null) {
                            requestInstall(ready)
                        } else {
                            scope.launch {
                                downloading = true
                                progress = 0f
                                received = 0L
                                total = 0L
                                errorMessage = null
                                statusMessage = "Downloading update…"
                                val destination = File(
                                    File(context.cacheDir, "updates").apply { mkdirs() },
                                    "ParkerTunes-${latest.versionName}.apk"
                                )
                                runCatching {
                                    downloadAndVerifyRelease(latest, destination) { bytes, size ->
                                        withContext(Dispatchers.Main.immediate) {
                                            received = bytes
                                            total = size
                                            if (size > 0L) progress = (bytes.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                                        }
                                    }
                                }.onSuccess { file ->
                                    downloadedApk = file
                                    progress = 1f
                                    statusMessage = "Download verified. Opening Android installer…"
                                    requestInstall(file)
                                }.onFailure { failure ->
                                    destination.delete()
                                    errorMessage = failure.message ?: "The update download failed. Please try again."
                                    statusMessage = null
                                }
                                downloading = false
                            }
                        }
                    }
                ) {
                    Text(
                        when {
                            downloading -> "Downloading…"
                            downloadedApk?.isFile == true -> "Install update"
                            errorMessage != null -> "Try again"
                            else -> "Download update"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !downloading, onClick = { showDialog = false }) {
                    Text("Not now")
                }
            }
        )
    }
}

private fun fetchLatestParkerTunesRelease(): ParkerTunesRelease {
    val connection = (URL(RELEASE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "ParkerTunes-Android")
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub Releases returned HTTP ${connection.responseCode}.")
        }
        val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val code = json.optLong("versionCode", -1L)
        val name = json.optString("versionName").trim()
        val apk = json.optString("apkUrl").trim()
        val checksum = json.optString("sha256").trim().lowercase(Locale.US)
        val notes = json.optString("releaseNotes").trim()
        if (code <= 0L || name.isBlank()) throw IOException("The latest release is missing version information.")
        if (apk != TRUSTED_APK_URL) throw IOException("The latest release contains an untrusted APK address.")
        if (!checksum.matches(Regex("^[a-f0-9]{64}$"))) throw IOException("The release has no valid SHA-256 checksum.")
        return ParkerTunesRelease(code, name, apk, checksum, notes)
    } finally {
        connection.disconnect()
    }
}

private suspend fun downloadAndVerifyRelease(
    release: ParkerTunesRelease,
    destination: File,
    onProgress: suspend (Long, Long) -> Unit
): File = withContext(Dispatchers.IO) {
    destination.parentFile?.mkdirs()
    destination.delete()
    val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "ParkerTunes-Android")
        setRequestProperty("Accept", "application/vnd.android.package-archive")
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("APK download returned HTTP ${connection.responseCode}.")
        }
        val expected = connection.contentLengthLong.coerceAtLeast(0L)
        var received = 0L
        var lastProgress = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        onProgress(0L, expected)
        connection.inputStream.buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    received += count
                    if (received - lastProgress >= 128 * 1024L || (expected > 0L && received == expected)) {
                        onProgress(received, expected)
                        lastProgress = received
                    }
                }
            }
        }
        if (received <= 0L || (expected > 0L && received != expected)) {
            throw IOException("The APK download was incomplete.")
        }
        val actual = digest.digest().joinToString("") {
            String.format(Locale.US, "%02x", it.toInt() and 0xFF)
        }
        if (!actual.equals(release.sha256, ignoreCase = true)) {
            throw IOException("The downloaded APK failed its SHA-256 integrity check.")
        }
        destination
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    } finally {
        connection.disconnect()
    }
}

private fun openAndroidInstaller(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun formatUpdateBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
