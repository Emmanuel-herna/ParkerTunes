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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(val timeMs: Long, val text: String)
data class SyncedLyrics(val lines: List<LyricLine>)
data class Lyrics(val syncedLyrics: SyncedLyrics?, val plainText: String?)

interface MusicApi {
    suspend fun search(query: String): SearchResult
    /** Fetches the next song page only when the UI asks for it. */
    suspend fun searchMore(query: String): List<Song> = emptyList()
    suspend fun getRelatedSongs(songId: String): List<Song>
    suspend fun getRelatedSongs(songId: String, title: String, artist: String): List<Song> =
        getRelatedSongs(songId)
    suspend fun getMoodSongs(moodQuery: String): List<Song>
    suspend fun getTrending(): List<Song>
    suspend fun getArtistProfile(artist: Artist): ArtistProfile
    suspend fun getAlbumTracks(albumId: String): List<Song>
    suspend fun getStreamUrl(songId: String, quality: String = "Auto"): String
    suspend fun getLyrics(title: String, artist: String): Lyrics? = null
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

    private val lyricsClient = OkHttpClient()
    private val lyricsCache = LinkedHashMap<String, Lyrics?>()
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

            val albums = runCatching {
                val albumHandler = factory.fromQuery(
                    cleanedQuery, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), ""
                )
                val albumExtractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, albumHandler)
                albumExtractor.fetchPage()
                albumExtractor.initialPage.items.filterIsInstance<PlaylistInfoItem>()
                    .mapNotNull { item ->
                        val id = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Album(
                            id = id,
                            title = item.name.orEmpty(),
                            coverUrl = item.thumbnails.firstOrNull()?.url,
                            trackCount = item.streamCount.coerceAtLeast(0L),
                            year = extractReleaseYear(
                                item.name.orEmpty() + " " + item.description?.content.orEmpty()
                            )
                        )
                    }.distinctBy { it.id }.take(20)
            }.getOrDefault(emptyList())

            SearchResult(songs = songs, artists = artists, albums = albums).also { combined ->
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
        val browseId = ArtistBrowseParser.browseId(artist.id)
        val response = try {
            val body = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20250101.01.00")))
                put("browseId", browseId)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(body)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Content-Type", "application/json")
                .build()
            lyricsClient.newCall(request).execute().use { result ->
                if (result.isSuccessful) JSONObject(result.body?.string().orEmpty()) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        val nativeProfile = response?.let {
            try {
                ArtistBrowseParser.parse(artist, it)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

        // Prefer the artist's own YouTube Music browse page. Keep the existing
        // NewPipe/search fallback for channels whose browse page is unavailable
        // or whose response contains no usable artist sections.
        if (nativeProfile != null && (
            nativeProfile.topSongs.isNotEmpty() ||
            nativeProfile.albums.isNotEmpty() ||
            nativeProfile.singles.isNotEmpty() ||
            nativeProfile.relatedArtists.isNotEmpty()
        )) {
            // The native artist page may expose only a short preview shelf.
            // Supplement it with dedicated YouTube Music song-filter results,
            // retaining only tracks credited to this artist.
            val normalizedArtist = artist.name.lowercase()
                .replace(Regex("""\s*[-|]\s*topic$"""), "").trim()
            val expandedSongs = runCatching {
                searchMusicSongs("${artist.name} songs", 30).filter { song ->
                    val credited = song.artist.lowercase()
                        .replace(Regex("""\s*[-|]\s*topic$"""), "").trim()
                    credited == normalizedArtist ||
                        credited.contains(normalizedArtist) ||
                        normalizedArtist.contains(credited)
                }
            }.getOrDefault(emptyList())
            nativeProfile.copy(
                topSongs = (nativeProfile.topSongs + expandedSongs)
                    .distinctBy { it.id }.take(20)
            )
        } else {
            getArtistProfileFallback(artist)
        }
    }

    private suspend fun getArtistProfileFallback(artist: Artist): ArtistProfile = withContext(Dispatchers.IO) {
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

        suspend fun loadTab(keywords: List<String>): List<org.schabi.newpipe.extractor.InfoItem> =
            runCatching {
                val tab = profileInfo?.tabs?.firstOrNull { candidate ->
                    val descriptor = (candidate.contentFilters.joinToString(" ") + " " + candidate.url)
                    keywords.any { descriptor.contains(it, ignoreCase = true) }
                } ?: return@runCatching emptyList()
                val extractor = ServiceList.YouTube.getChannelTabExtractor(tab)
                extractor.fetchPage()
                extractor.initialPage.items
            }.getOrDefault(emptyList())

        suspend fun searchAlbumItems(query: String): List<org.schabi.newpipe.extractor.InfoItem> =
            runCatching {
                val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                    query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), ""
                )
                val extractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, handler)
                extractor.fetchPage()
                extractor.initialPage.items
            }.getOrDefault(emptyList())

        fun playlistAlbums(items: List<org.schabi.newpipe.extractor.InfoItem>): List<Album> =
            items.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
                val id = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Album(
                    id = id,
                    title = item.name.orEmpty(),
                    coverUrl = item.thumbnails.firstOrNull()?.url,
                    trackCount = item.streamCount.coerceAtLeast(0L),
                    year = extractReleaseYear(
                        item.name.orEmpty() + " " + (item.description?.content.orEmpty())
                    )
                )
            }.distinctBy { it.id }.take(30)

        // Some artist channels expose no release tabs through ChannelInfo.
        // Fall back to YouTube Music's dedicated Albums search filter.
        val albumItems = loadTab(listOf("album", "albums"))
        val searchedAlbumItems = if (albumItems.isEmpty()) searchAlbumItems(artist.name) else emptyList()
        val singleItems = loadTab(listOf("single", "singles", "ep"))
        val searchedSingleItems = if (singleItems.isEmpty()) {
            searchAlbumItems("${artist.name} singles EP")
        } else emptyList()
        // Related artists are exposed as channel tabs by some artist/channel pages.
        val relatedItems = loadTab(listOf("artist", "related", "channel", "channels"))
        val searchedRelatedItems = if (relatedItems.isEmpty()) runCatching {
            val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
                "${artist.name} similar artists",
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
                ""
            )
            val extractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, handler)
            extractor.fetchPage()
            extractor.initialPage.items
        }.getOrDefault(emptyList()) else emptyList()

        val albums = playlistAlbums(albumItems.ifEmpty { searchedAlbumItems })
        val releaseItems = singleItems.ifEmpty { searchedSingleItems }
        val singles = releaseItems.filterIsInstance<PlaylistInfoItem>()
            .filter { item ->
                Regex("""\b(single|ep)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(item.name.orEmpty() + " " + item.description?.content.orEmpty())
            }.let(::playlistAlbums)
        val recent = (albums + playlistAlbums(releaseItems))
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<Album> { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.title.lowercase() }
            )
            .take(30)
        val related = (relatedItems.ifEmpty { searchedRelatedItems }).filterIsInstance<ChannelInfoItem>()
            .mapNotNull(::toArtist).filterNot { it.id == artist.id }
            .distinctBy { it.id }.take(20)

        ArtistProfile(
            artist = resolvedArtist,
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            recentReleases = recent,
            relatedArtists = related
        )
    }

    override suspend fun getAlbumTracks(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        val rawId = albumId.trim()
        if (rawId.isBlank()) return@withContext emptyList()

        val parsed = runCatching { Uri.parse(rawId) }.getOrNull()
        val listId = parsed?.getQueryParameter("list")
        val browseId = when {
            rawId.startsWith("MPRE", ignoreCase = true) -> rawId
            parsed?.lastPathSegment?.startsWith("MPRE", ignoreCase = true) == true ->
                parsed.lastPathSegment.orEmpty()
            else -> null
        }

        // YouTube Music album pages use browse IDs (MPRE...) rather than normal
        // playlist IDs. Resolve those through the Music browse endpoint first.
        if (!browseId.isNullOrBlank()) {
            val musicTracks = loadYouTubeMusicAlbumTracks(browseId)
            if (musicTracks.isNotEmpty()) return@withContext musicTracks
        }

        // Search results and older saved albums may contain a playlist URL/list ID.
        val playlistUrl = when {
            !listId.isNullOrBlank() -> "https://www.youtube.com/playlist?list=$listId"
            rawId.startsWith("OLAK", ignoreCase = true) ||
                rawId.startsWith("PL", ignoreCase = true) ||
                rawId.startsWith("UU", ignoreCase = true) ->
                "https://www.youtube.com/playlist?list=$rawId"
            rawId.startsWith("http", ignoreCase = true) -> rawId
            else -> "https://music.youtube.com/browse/$rawId"
        }
        try {
            NewPipeRuntime.ensureInitialized()
            val extractor = ServiceList.YouTube.getPlaylistExtractor(playlistUrl)
            val info = PlaylistInfo.getInfo(extractor)
            info.relatedItems.distinctBy { it.url }.mapNotNull { item ->
                (item as? StreamInfoItem)?.let(::toSong)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw failure
        }
    }

    private fun loadYouTubeMusicAlbumTracks(browseId: String): List<Song> {
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250101.01.00")))
            put("browseId", browseId)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(body)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Content-Type", "application/json")
            .build()

        val json = lyricsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            JSONObject(response.body?.string().orEmpty())
        }
        val found = mutableListOf<Song>()

        fun textFromRuns(value: JSONObject?): String {
            val runs = value?.optJSONArray("runs") ?: return value?.optString("text").orEmpty()
            return (0 until runs.length()).mapNotNull { index ->
                runs.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("").trim()
        }

        fun collect(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                        ?: node.optJSONObject("playlistPanelVideoRenderer")
                    if (renderer != null) {
                        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                            ?.takeIf { it.isNotBlank() }
                            ?: renderer.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")?.optString("videoId")
                                ?.takeIf { it.isNotBlank() }
                        if (videoId != null) {
                            val columns = renderer.optJSONArray("flexColumns")
                            val title = if (columns != null && columns.length() > 0) {
                                val column = columns.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                textFromRuns(column?.optJSONObject("text"))
                            } else renderer.optString("title")
                            val artist = if (columns != null && columns.length() > 1) {
                                val column = columns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                textFromRuns(column?.optJSONObject("text"))
                                    .replace(Regex("\\s*[·•]\\s*.*$"), "").trim()
                            } else renderer.optString("shortBylineText")
                            val thumbnailContainer = renderer.optJSONObject("thumbnail")
                            val thumbnailRenderer = thumbnailContainer
                                ?.optJSONObject("musicThumbnailRenderer")
                            val thumbnails = thumbnailRenderer?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                                ?: thumbnailContainer?.optJSONArray("thumbnails")
                            val cover = thumbnails?.let { array ->
                                array.optJSONObject((array.length() - 1).coerceAtLeast(0))
                                    ?.optString("url")?.takeIf { it.isNotBlank() }
                            }
                            val fixed = renderer.optJSONArray("fixedColumns")
                            val durationText = fixed?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                                ?.optJSONObject("text")?.let(::textFromRuns).orEmpty()
                            val duration = parseDurationSeconds(durationText)
                            if (title.isNotBlank()) {
                                found += Song(
                                    id = videoId,
                                    title = title,
                                    artist = artist.ifBlank { "Unknown artist" },
                                    coverUrl = cover ?: youtubeThumbnail(videoId),
                                    durationSec = duration,
                                )
                            }
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) collect(node.opt(keys.next()))
                }
                is JSONArray -> for (index in 0 until node.length()) collect(node.opt(index))
            }
        }

        collect(json)
        return found.distinctBy { it.id }
    }

    private fun parseDurationSeconds(value: String): Long {
        val parts = value.trim().split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return parts.fold(0L) { total, part -> total * 60L + part }
    }

    /**
     * Recommendations are searched and ranked through YouTube Music only.
     * The selected track's title and artist are supplied by the app; regular
     * YouTube is not queried to infer the artist.
     */
    override suspend fun getRelatedSongs(songId: String): List<Song> =
        getRelatedSongs(songId, "", "")

    override suspend fun getRelatedSongs(songId: String, title: String, artist: String): List<Song> =
        withContext(Dispatchers.IO) {
            val videoId = extractVideoId(songId) ?: songId.trim()
            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            if (videoId.isBlank() || cleanTitle.isBlank() || cleanArtist.isBlank()) {
                return@withContext emptyList()
            }
            val cacheKey = "$videoId|$cleanTitle|$cleanArtist".lowercase()
            synchronized(cacheLock) { relatedCache[cacheKey] }?.let { return@withContext it }

            try {
                NewPipeRuntime.ensureInitialized()
                val queries = listOf(
                    "\"$cleanTitle\" $cleanArtist",
                    "$cleanArtist top songs",
                    "songs like $cleanTitle by $cleanArtist"
                )
                val titleTokens = cleanTitle.lowercase()
                    .split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
                fun usable(song: Song): Boolean {
                    val t = song.title.lowercase()
                    return song.id != videoId && song.durationSec in 45L..900L &&
                        listOf("1 hour", "1-hour", "2 hour", "2-hour", "dj mix",
                            "full album", "nonstop", "live stream", "playlist",
                            "karaoke", "8d audio", "reaction", "sped up")
                            .none(t::contains)
                }
                fun artistMatches(song: Song): Boolean {
                    val resultArtist = song.artist.lowercase()
                        .replace(Regex("\\s*[-|]\\s*(topic|vevo)$"), "").trim()
                    val targetArtist = cleanArtist.lowercase()
                        .replace(Regex("\\s*[-|]\\s*(topic|vevo)$"), "").trim()
                    return resultArtist == targetArtist ||
                        resultArtist.contains(targetArtist) || targetArtist.contains(resultArtist)
                }

                val searched = queries.flatMap { query -> searchMusicSongs(query, 30) }
                    .filter(::usable)
                    .distinctBy { it.id }
                val ranked = searched.map { song ->
                    val resultTokens = song.title.lowercase()
                        .split(Regex("[^a-z0-9]+")).toSet()
                    val titleOverlap = titleTokens.intersect(resultTokens).size
                    song to ((if (artistMatches(song)) 5 else 0) + titleOverlap)
                }
                // Prefer exact-artist radio. If YouTube Music omits the uploader
                // match, retain credible song results rather than returning a blank shelf.
                val strictMatches = ranked.filter { it.second >= 5 }
                    .sortedByDescending { it.second }.map { it.first }
                val candidates = if (strictMatches.isNotEmpty()) strictMatches else {
                    val artistFallback = searchMusicSongs("$cleanArtist songs", 30)
                        .filter(::usable)
                        .map { song ->
                            if (song.artist.isBlank() ||
                                song.artist.equals("unknown artist", ignoreCase = true)
                            ) song.copy(artist = cleanArtist) else song
                        }
                        .distinctBy { it.id }
                    (artistFallback + ranked.filter { it.second > 0 }.map { it.first })
                        .distinctBy { it.id }.take(30)
                }.take(30)
                synchronized(cacheLock) { relatedCache[cacheKey] = candidates }
                candidates
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** One page of the dedicated YouTube Music song filter, not general video search. */
    private fun searchMusicSongs(query: String, limit: Int): List<Song> = runCatching {
        val handler = YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
        )
        val extractor = YoutubeMusicSearchExtractor(ServiceList.YouTube, handler)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { item ->
                val title = item.name.orEmpty()
                val url = item.url.orEmpty()
                item.duration > 0L &&
                    !url.contains("/shorts/", ignoreCase = true) &&
                    !title.contains("advertisement", ignoreCase = true) &&
                    !title.contains("sponsored", ignoreCase = true)
            }
            .mapNotNull(::toSong)
            .distinctBy { it.id }
            .take(limit)
    }.getOrDefault(emptyList())

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
        NewPipeRuntime.ensureInitialized()
        val songs = searchMusicSongs("Top Hits 2026", 30)
            .ifEmpty { searchMusicSongs("Popular songs 2026", 30) }
            .distinctBy { it.id }
            .take(30)
        synchronized(cacheLock) { trendingCache = songs }
        songs
    }

    override suspend fun getStreamUrl(songId: String, quality: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: songId.trim()
        if (videoId.isBlank()) return@withContext ""
        val cacheKey = "$videoId|${quality.lowercase()}"
        synchronized(cacheLock) { streamCache[cacheKey] }?.let { return@withContext it }

        try {
            NewPipeRuntime.ensureInitialized()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl) as YoutubeStreamExtractor
            extractor.fetchPage()

            val streams = extractor.audioStreams.filter { stream ->
                val suffix = stream.format?.suffix?.lowercase()
                (suffix == "m4a" || suffix == "webm") && stream.averageBitrate > 0
            }
            val selectedStream = when (quality.trim().lowercase()) {
                "low" -> streams.minByOrNull { it.averageBitrate }
                "medium" -> streams.minByOrNull { kotlin.math.abs(it.averageBitrate - 128_000) }
                "high" -> streams.maxByOrNull { it.averageBitrate }
                else -> streams.maxByOrNull { it.averageBitrate }
            } ?: streams.maxByOrNull { it.averageBitrate }
            selectedStream?.content.orEmpty().also { url ->
                if (url.isNotBlank()) synchronized(cacheLock) { streamCache[cacheKey] = url }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun getLyrics(title: String, artist: String): Lyrics? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@withContext null
        val key = "$cleanTitle|$cleanArtist".lowercase()
        synchronized(cacheLock) {
            if (lyricsCache.containsKey(key)) return@withContext lyricsCache[key]
        }
        val result = runCatching {
            val url = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", cleanTitle)
                .appendQueryParameter("artist_name", cleanArtist).build().toString()
            val request = Request.Builder().url(url).header("User-Agent", "ParkerTunes/1.0 (Android)").build()
            lyricsClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val array = JSONArray(response.body?.string().orEmpty())
                val item = (0 until array.length()).map { array.getJSONObject(it) }
                    .firstOrNull { it.optString("syncedLyrics").isNotBlank() || it.optString("plainLyrics").isNotBlank() }
                    ?: return@use null
                val lrc = item.optString("syncedLyrics").takeIf { it.isNotBlank() }
                val plain = item.optString("plainLyrics").takeIf { it.isNotBlank() }
                val lines = lrc?.lineSequence()?.mapNotNull { raw ->
                    val match = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]\s*(.*)""").matchEntire(raw.trim())
                        ?: return@mapNotNull null
                    val ms = (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 +
                        match.groupValues[3].padEnd(3, '0').take(3).toLong()
                    LyricLine(ms, match.groupValues[4])
                }?.toList().orEmpty()
                Lyrics(lines.takeIf { it.isNotEmpty() }?.let { SyncedLyrics(it) }, plain)
            }
        }.getOrNull()
        synchronized(cacheLock) {
            lyricsCache[key] = result
            if (lyricsCache.size > 100) lyricsCache.remove(lyricsCache.keys.first())
        }
        result
    }

    override suspend fun getLyrics(songId: String): String? = null

    override suspend fun getCoverArt(songId: String): String? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(songId) ?: return@withContext null
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
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
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

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


/** Extracts a release year only when the source metadata actually contains one. */
private fun extractReleaseYear(metadata: String): String {
    val match = Regex("""\b(19\d{2}|20\d{2})\b""").find(metadata) ?: return ""
    val year = match.value.toIntOrNull() ?: return ""
    return year.takeIf { it in 1900..2100 }?.toString().orEmpty()
}
