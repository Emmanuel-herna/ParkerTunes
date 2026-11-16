package com.parker.tunes.data

import android.net.Uri
import com.parker.tunes.model.Artist
import com.parker.tunes.model.SearchResult
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.LinkedHashMap

interface MusicApi {
    suspend fun search(query: String): SearchResult
    /** Fetches the next song page only when the UI asks for it. */
    suspend fun searchMore(query: String): List<Song> = emptyList()
    suspend fun getStreamUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
    suspend fun getCoverArt(songId: String): String?
}

/**
 * YouTube Music search through NewPipe's native MUSIC_SONGS and MUSIC_ARTISTS filters.
 * NewPipe uses service ID 0 for both YouTube and YouTube Music.
 */
class NewPipeMusicApi : MusicApi {
    private data class SearchSession(
        val extractor: SearchExtractor,
        var nextPage: Page?
    )

    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<String, SearchResult>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResult>?): Boolean =
            size > 20
    }
    private val sessions = object : LinkedHashMap<String, SearchSession>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchSession>?): Boolean =
            size > 10
    }

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty()) return@withContext SearchResult()
        synchronized(cacheLock) { resultCache[cleanedQuery] }?.let { return@withContext it }

        try {
            NewPipeRuntime.ensureInitialized()
            val factory = YoutubeSearchQueryHandlerFactory.getInstance()

            // Keep the song extractor as the paging session used by searchMore().
            val songsHandler: SearchQueryHandler = factory.fromQuery(
                cleanedQuery,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                ""
            )
            val songsExtractor = ServiceList.YouTube.getSearchExtractor(songsHandler)
            songsExtractor.fetchPage()
            val songsPage = songsExtractor.initialPage
            val songs = songsPage.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toSong)

            // YouTube Music exposes artist/channel results through its MUSIC_ARTISTS filter.
            val artistsHandler: SearchQueryHandler = factory.fromQuery(
                cleanedQuery,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
                ""
            )
            val artistsExtractor = ServiceList.YouTube.getSearchExtractor(artistsHandler)
            artistsExtractor.fetchPage()
            val artists = artistsExtractor.initialPage.items
                .filterIsInstance<ChannelInfoItem>()
                .mapNotNull(::toArtist)
                .distinctBy { it.id }

            SearchResult(songs = songs, artists = artists).also { combined ->
                synchronized(cacheLock) {
                    resultCache[cleanedQuery] = combined
                    sessions[cleanedQuery] = SearchSession(songsExtractor, songsPage.nextPage)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SearchUnavailableException(
                failure.message?.takeIf { it.isNotBlank() }
                    ?: "Search timed out or the music service is unavailable.",
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
                val previous = resultCache[cleanedQuery] ?: SearchResult()
                resultCache[cleanedQuery] = previous.copy(
                    songs = (previous.songs + songs).distinctBy { it.id }
                )
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

    private fun toArtist(item: ChannelInfoItem): Artist? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        val name = item.name?.takeIf { it.isNotBlank() } ?: return null
        return Artist(
            id = url,
            name = name,
            avatarUrl = item.thumbnails.firstOrNull()?.url,
            subscriberCount = item.subscriberCount.coerceAtLeast(0L),
            description = item.description.orEmpty()
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
