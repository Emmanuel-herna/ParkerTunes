package com.parker.tunes.data

import android.net.Uri
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.LinkedHashMap

interface MusicApi {
    suspend fun search(query: String): List<Song>
    /** Fetches the next page only when the UI asks for it. */
    suspend fun searchMore(query: String): List<Song> = emptyList()
    suspend fun getStreamUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
    suspend fun getCoverArt(songId: String): String?
}

/**
 * YouTube Music search through NewPipe's MUSIC_SONGS content filter.
 * NewPipe models YouTube and YouTube Music as one service (service ID 0);
 * this filter routes the query to YoutubeMusicSearchExtractor, not video search.
 */
class NewPipeMusicApi : MusicApi {
    private data class SearchSession(
        val extractor: SearchExtractor,
        var nextPage: Page?
    )

    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<String, List<Song>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean =
            size > 20
    }
    private val sessions = object : LinkedHashMap<String, SearchSession>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchSession>?): Boolean =
            size > 10
    }

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty()) return@withContext emptyList()
        synchronized(cacheLock) { resultCache[cleanedQuery] }?.let { return@withContext it }

        try {
            NewPipeRuntime.ensureInitialized()
            val searchHandler: SearchQueryHandler =
                YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                    cleanedQuery,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    ""
                )

            // Exactly one extractor and one initial-page fetch per uncached query.
            val extractor = ServiceList.YouTube.getSearchExtractor(searchHandler)
            extractor.fetchPage()
            val firstPage = extractor.initialPage
            val songs = firstPage.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toSong)

            synchronized(cacheLock) {
                resultCache[cleanedQuery] = songs
                sessions[cleanedQuery] = SearchSession(extractor, firstPage.nextPage)
            }
            songs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SearchUnavailableException(
                failure.message?.takeIf { it.isNotBlank() } ?: "Search timed out or the music service is unavailable.",
                failure
            )
        }
    }

    override suspend fun searchMore(query: String): List<Song> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty()) return@withContext emptyList()
        val session = synchronized(cacheLock) { sessions[cleanedQuery] }
            ?: return@withContext emptyList()
        val page = session.nextPage ?: return@withContext emptyList()

        try {
            val next = session.extractor.getPage(page)
            val songs = next.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toSong)
            synchronized(cacheLock) {
                session.nextPage = next.nextPage
                val merged = (resultCache[cleanedQuery].orEmpty() + songs)
                    .distinctBy { it.id }
                resultCache[cleanedQuery] = merged
            }
            songs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SearchUnavailableException(
                failure.message?.takeIf { it.isNotBlank() } ?: "Couldn't load more songs. Tap to retry.",
                failure
            )
        }
    }

    private fun toSong(item: StreamInfoItem): Song? {
        val videoId = extractVideoId(item.url) ?: return null
        return Song(
            id = videoId,
            title = item.name,
            artist = item.uploaderName.orEmpty().ifBlank { "Unknown artist" },
            coverUrl = youtubeThumbnail(videoId),
            durationSec = item.duration.coerceAtLeast(0L)
        )
    }

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: songId.trim()
        if (videoId.isBlank()) return@withContext ""

        try {
            NewPipeRuntime.ensureInitialized()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
            extractor.fetchPage()

            val selectedStream = extractor.audioStreams
                .filter { stream ->
                    val suffix = stream.format?.suffix?.lowercase()
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
        ) return trimmed.takeIf { VIDEO_ID.matches(it) }

        return runCatching {
            val uri = Uri.parse(trimmed)
            uri.getQueryParameter("v")
                ?: uri.lastPathSegment?.takeIf { VIDEO_ID.matches(it) }
        }.getOrNull()
    }

    private fun youtubeThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    private companion object {
        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}

class SearchUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Keeps NewPipe's process-wide downloader initialized exactly once. */
private object NewPipeRuntime {
    @Volatile private var initialized = false

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
