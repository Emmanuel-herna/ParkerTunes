package com.parker.tunes.data

import android.net.Uri
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem

interface MusicApi {
    suspend fun search(query: String): List<Song>
    suspend fun getStreamUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
    suspend fun getCoverArt(songId: String): String?
}

/**
 * YouTube Music catalogue and audio-stream provider backed by NewPipe Extractor.
 *
 * All extractor/network work is dispatched to IO. The MusicApi contract remains
 * unchanged so existing screens and repositories do not need modifications.
 */
class NewPipeMusicApi : MusicApi {

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty()) return@withContext emptyList()

        try {
            NewPipeRuntime.ensureInitialized()

            // YouTube's service ID in NewPipe is 0 (not 4).
            val searchHandler: SearchQueryHandler =
                YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                    cleanedQuery,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    ""
                )

            val extractor = ServiceList.YouTube.getSearchExtractor(searchHandler)
            extractor.fetchPage()

            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { item ->
                    val videoId = extractVideoId(item.url) ?: return@mapNotNull null
                    Song(
                        id = videoId,
                        title = item.name,
                        artist = item.uploaderName.orEmpty().ifBlank { "Unknown artist" },
                        coverUrl = item.thumbnails.firstOrNull()?.url
                            ?: youtubeThumbnail(videoId),
                        durationSec = item.duration.coerceAtLeast(0L)
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: songId.trim()
        if (videoId.isBlank()) return@withContext ""

        try {
            NewPipeRuntime.ensureInitialized()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            val selectedStream = audioStreams
                .filter { stream ->
                    val suffix = stream.format?.suffix?.lowercase().orEmpty()
                    suffix == "m4a" || suffix == "webm"
                }
                .sortedWith(
                    compareBy<AudioStream> {
                        when (it.format?.suffix?.lowercase()) {
                            "m4a" -> 0
                            "webm" -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.averageBitrate }
                )
                .firstOrNull()

            selectedStream?.content.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun getLyrics(songId: String): String? = null

    override suspend fun getCoverArt(songId: String): String? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: return@withContext null
        youtubeThumbnail(videoId)
    }

    private fun extractVideoId(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed.takeIf { VIDEO_ID.matches(it) }
        }

        return runCatching {
            val uri = Uri.parse(trimmed)
            uri.getQueryParameter("v")
                ?: uri.lastPathSegment?.takeIf { VIDEO_ID.matches(it) }
        }.getOrNull()
    }

    private fun youtubeThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private companion object {
        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}

/**
 * Keeps NewPipe's process-wide downloader initialized exactly once.
 */
private object NewPipeRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (!initialized) {
            NewPipe.init(ParkerTunesDownloader())
            initialized = true
        }
    }
}

/** Kept as a compatibility name for existing app wiring. */
class DemoMusicApi : MusicApi by NewPipeMusicApi()
