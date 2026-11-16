package com.parker.tunes.data

import android.net.Uri
import com.parker.tunes.model.Artist
import com.parker.tunes.model.ArtistProfile
import com.parker.tunes.model.Album
import com.parker.tunes.model.SearchResult
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.LinkedHashMap

interface MusicApi {
    suspend fun search(query: String): SearchResult
    /** Fetches the next song page only when the UI asks for it. */
    suspend fun searchMore(query: String): List<Song> = emptyList()
    suspend fun getRelatedSongs(songId: String): List<Song>
    suspend fun getMoodSongs(moodQuery: String): List<Song>
    suspend fun getTrending(): List<Song>
    suspend fun getArtistProfile(artist: Artist): ArtistProfile
    suspend fun getAlbumTracks(albumId: String): List<Song>
    suspend fun getStreamUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
    suspend fun getCoverArt(songId: String): String?
}

/**
 * YouTube Music search through NewPipe's dedicated YoutubeMusicSearchExtractor.
 * NewPipe v0.27.1 exposes the YouTube service (ID 0); it does not register a
 * separate ServiceList.YouTubeMusic/YoutubeMusicService.
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
    private val relatedCache = object : LinkedHashMap<String, List<Song>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean =
            size > 12
    }
    private val streamCache = object : LinkedHashMap<String, String>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 48
    }
    private var trendingCache: List<Song>? = null
    private val moodCache = object : LinkedHashMap<String, List<Song>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean = size > 24
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
            val songsExtractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, songsHandler)
            songsExtractor.fetchPage()
            val songsPage = songsExtractor.initialPage
            val songs = songsPage.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toSong)

            // YouTube Music exposes artist/channel results through its MUSIC_ARTISTS filter.
            val artistsHandler: SearchQueryHandler = factory.fromQuery(
                cleanedQuery,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
                ""
            )
            val artistsExtractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, artistsHandler)
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
        } catch (_: Exception) {
            SearchResult()
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

    override suspend fun getArtistProfile(artist: Artist): ArtistProfile = withContext(Dispatchers.IO) {
        NewPipeRuntime.ensureInitialized()
        val profileInfo = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, artist.id) }.getOrNull()
        val resolvedArtist = artist.copy(
            name = profileInfo?.name?.ifBlank { artist.name } ?: artist.name,
            avatarUrl = profileInfo?.avatars?.firstOrNull()?.url ?: artist.avatarUrl,
            subscriberCount = profileInfo?.subscriberCount?.takeIf { it > 0L } ?: artist.subscriberCount,
            description = profileInfo?.description?.ifBlank { artist.description } ?: artist.description
        )

        // The native music-song search gives us the artist's currently surfaced top tracks.
        val topSongs = runCatching {
            search(artist.name).songs
                .filter { it.artist.contains(artist.name, ignoreCase = true) ||
                    artist.name.contains(it.artist, ignoreCase = true) }
                .distinctBy { it.id }
                .take(20)
        }.getOrDefault(emptyList())

        val albums = runCatching {
            val albumTab = profileInfo?.tabs?.firstOrNull { tab ->
                tab.contentFilters.any { it.contains("album", ignoreCase = true) } ||
                    tab.url.contains("/albums", ignoreCase = true)
            }
            if (albumTab == null) emptyList() else {
                val extractor = ServiceList.YouTube.getChannelTabExtractor(albumTab)
                extractor.fetchPage()
                extractor.initialPage.items.filterIsInstance<PlaylistInfoItem>()
                    .mapNotNull { item ->
                        val albumId = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Album(
                            id = albumId,
                            title = item.name.orEmpty(),
                            coverUrl = item.thumbnails.firstOrNull()?.url,
                            trackCount = item.streamCount.coerceAtLeast(0L),
                            year = ""
                        )
                    }
                    .distinctBy { it.id }
                    .take(30)
            }
        }.getOrDefault(emptyList())

        ArtistProfile(artist = resolvedArtist, topSongs = topSongs, albums = albums)
    }

    override suspend fun getAlbumTracks(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        val url = albumId.trim()
        if (url.isBlank()) return@withContext emptyList()
        runCatching {
            NewPipeRuntime.ensureInitialized()
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            val info = PlaylistInfo.getInfo(extractor)
            info.relatedItems.distinctBy { it.url }.mapNotNull(::toSong)
        }.getOrDefault(emptyList())
    }

    override suspend fun getRelatedSongs(songId: String): List<Song> = withContext(Dispatchers.IO) {
        val key = songId.trim()
        if (key.isBlank()) return@withContext emptyList()
        synchronized(cacheLock) { relatedCache[key] }?.let { return@withContext it }

        try {
            NewPipeRuntime.ensureInitialized()
            val videoId = extractVideoId(key) ?: key
            val url = if (key.startsWith("http://", true) || key.startsWith("https://", true)) {
                key
            } else {
                "https://www.youtube.com/watch?v=$videoId"
            }
            val extractor = ServiceList.YouTube.getStreamExtractor(url) as YoutubeStreamExtractor
            val info = StreamInfo.getInfo(extractor)
            val related = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull(::toSong)
                .filterNot { it.id == videoId }
                .distinctBy { it.id }
                .take(20)
            synchronized(cacheLock) { relatedCache[key] = related }
            related
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMoodSongs(moodQuery: String): List<Song> = withContext(Dispatchers.IO) {
        val mood = moodQuery.trim()
        if (mood.isBlank()) return@withContext emptyList()
        val cacheKey = mood.lowercase()
        synchronized(cacheLock) { moodCache[cacheKey] }?.let { return@withContext it }
        try {
            NewPipeRuntime.ensureInitialized()
            val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                "$mood playlist songs", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
            )
            val extractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, handler)
            extractor.fetchPage()
            val songs = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
                .mapNotNull(::toSong).distinctBy { it.id }.take(30)
            synchronized(cacheLock) { moodCache[cacheKey] = songs }
            songs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getTrending(): List<Song> = withContext(Dispatchers.IO) {
        synchronized(cacheLock) { trendingCache }?.let { return@withContext it }
        val songs = runCatching { search("Top Hits 2026").songs }
            .getOrDefault(emptyList())
            .ifEmpty {
                runCatching { search("Popular music").songs }.getOrDefault(emptyList())
            }
            .distinctBy { it.id }
            .take(20)
        synchronized(cacheLock) { trendingCache = songs }
        songs
    }

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: songId.trim()
        if (videoId.isBlank()) return@withContext ""
        synchronized(cacheLock) { streamCache[videoId] }?.let { return@withContext it }

        try {
            NewPipeRuntime.ensureInitialized()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl) as YoutubeStreamExtractor
            extractor.fetchPage()

            val selectedStream = extractor.audioStreams
                .filter { stream ->
                    val suffix = stream.format?.suffix?.lowercase()
                    suffix == "m4a" || suffix == "webm"
                }
                .sortedWith(
                    compareByDescending<AudioStream> { it.averageBitrate }
                        .thenBy {
                            when (it.format?.suffix?.lowercase()) {
                                "m4a" -> 0
                                "webm" -> 1
                                else -> 2
                            }
                        }
                )
                .firstOrNull()
            selectedStream?.content.orEmpty().also { url ->
                if (url.isNotBlank()) synchronized(cacheLock) { streamCache[videoId] = url }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun getLyrics(songId: String): String? = null

    override suspend fun getCoverArt(songId: String): String? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: return@withContext null
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
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
