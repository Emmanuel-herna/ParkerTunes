package com.parker.tunes.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.StatFs
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Sequential, app-private audio downloader. A single mutex guarantees that
 * only one stream is written at a time, even when several rows are tapped.
 */
class SongDownloadManager(
    context: Context,
    private val dao: LibraryDao,
    private val api: MusicApi
) {
    private val appContext = context.applicationContext
    private val folder = File(appContext.filesDir, "downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    init {
        scope.launch {
            dao.downloads().first()
                .filter { it.status == DownloadEntity.STATUS_DOWNLOADING }
                .forEach { stale ->
                    dao.saveDownload(stale.copy(
                        status = DownloadEntity.STATUS_FAILED,
                        errorMessage = "Download interrupted. Tap retry."
                    ))
                }
        }
    }

    fun hasLowStorage(): Boolean =
        StatFs(appContext.filesDir.absolutePath).availableBytes < MIN_FREE_BYTES

    fun enqueue(song: Song) {
        if (jobs[song.id]?.isActive == true) return
        ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadKeepAliveService::class.java))
        val job = scope.launch {
            try {
                queueMutex.withLock { downloadOne(song) }
            } finally {
                jobs.remove(song.id)
                _progress.value = _progress.value - song.id
                if (jobs.isEmpty()) appContext.stopService(Intent(appContext, DownloadKeepAliveService::class.java))
            }
        }
        jobs[song.id] = job
    }

    fun cancel(songId: String) {
        jobs[songId]?.cancel()
    }

    suspend fun remove(songId: String) {
        jobs[songId]?.cancelAndJoin()
        val existing = dao.download(songId)
        existing?.filePath?.let { File(it).delete() }
        dao.removeDownload(songId)
        _progress.value = _progress.value - songId
    }

    suspend fun removeAll() {
        val running = jobs.values.toList()
        running.forEach { it.cancel() }
        running.joinAll()
        dao.downloadsSnapshot().forEach { item -> File(item.filePath).delete() }
        dao.clearDownloads()
        _progress.value = emptyMap()
    }

    private suspend fun downloadOne(song: Song) {
        val existing = dao.download(song.id)
        if (existing?.status == DownloadEntity.STATUS_COMPLETE &&
            File(existing.filePath).exists() && File(existing.filePath).length() > 0L
        ) return

        val freeBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (freeBytes < MIN_FREE_BYTES) {
            dao.saveDownload(
                DownloadEntity(
                    songId = song.id, title = song.title, artist = song.artist,
                    coverUrl = song.coverUrl, filePath = File(folder, safeName(song.id)).absolutePath,
                    fileSizeBytes = 0L, downloadedAt = 0L, durationSec = song.durationSec,
                    status = DownloadEntity.STATUS_FAILED, errorMessage = "Low storage"
                )
            )
            return
        }

        val destination = File(folder, safeName(song.id))
        val temporary = File(folder, safeName(song.id) + ".part")
        dao.saveDownload(
            DownloadEntity(
                songId = song.id, title = song.title, artist = song.artist,
                coverUrl = song.coverUrl, filePath = destination.absolutePath,
                fileSizeBytes = 0L, downloadedAt = 0L, durationSec = song.durationSec,
                status = DownloadEntity.STATUS_DOWNLOADING
            )
        )

        try {
            val streamUrl = api.getStreamUrl(song.id)
            require(streamUrl.startsWith("http", ignoreCase = true)) { "No playable stream URL was returned." }
            val request = Request.Builder().url(streamUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed (HTTP ${response.code}).")
                val body = response.body ?: error("The server returned an empty response.")
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        val expected = body.contentLength()
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                            if (expected > 0L) {
                                _progress.value = _progress.value + (song.id to
                                    ((total * 100L / expected).toInt().coerceIn(0, 99)))
                            }
                        }
                        output.flush()
                    }
                }
            }
            require(temporary.exists() && temporary.length() > 0L) { "The downloaded file is empty." }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Couldn't save the downloaded audio file." }
            dao.saveDownload(
                DownloadEntity(
                    songId = song.id, title = song.title, artist = song.artist,
                    coverUrl = song.coverUrl, filePath = destination.absolutePath,
                    fileSizeBytes = destination.length(), downloadedAt = System.currentTimeMillis(),
                    durationSec = song.durationSec, status = DownloadEntity.STATUS_COMPLETE
                )
            )
            _progress.value = _progress.value + (song.id to 100)
        } catch (cancelled: CancellationException) {
            temporary.delete()
            dao.saveDownload(
                DownloadEntity(
                    songId = song.id, title = song.title, artist = song.artist,
                    coverUrl = song.coverUrl, filePath = destination.absolutePath,
                    fileSizeBytes = 0L, downloadedAt = 0L, durationSec = song.durationSec,
                    status = DownloadEntity.STATUS_FAILED, errorMessage = "Download cancelled"
                )
            )
            throw cancelled
        } catch (failure: Exception) {
            temporary.delete()
            dao.saveDownload(
                DownloadEntity(
                    songId = song.id, title = song.title, artist = song.artist,
                    coverUrl = song.coverUrl, filePath = destination.absolutePath,
                    fileSizeBytes = 0L, downloadedAt = 0L, durationSec = song.durationSec,
                    status = DownloadEntity.STATUS_FAILED,
                    errorMessage = failure.message ?: "Download failed"
                )
            )
        }
    }

    private fun safeName(songId: String): String =
        songId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".m4a"

    companion object {
        const val MIN_FREE_BYTES = 200L * 1024L * 1024L
    }
}
