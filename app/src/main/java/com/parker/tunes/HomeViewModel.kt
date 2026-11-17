package com.parker.tunes

import com.parker.tunes.data.MusicApi
import com.parker.tunes.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home recommendation state is deliberately cumulative: a newly played artist
 * adds another recommendation source instead of replacing the previous shelf.
 *
 * This class is screen-scoped by SpotifyHomeScreen and does not require a
 * separate dependency-injection setup.
 */
data class HomeRecommendationState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false
)

class HomeViewModel(private val api: MusicApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(HomeRecommendationState())
    val state: StateFlow<HomeRecommendationState> = mutableState.asStateFlow()

    private var lastTrackId: String? = null
    private var lastArtist: String = ""
    private var recentIds: Set<String> = emptySet()
    private var pendingGenreQueries: ArrayDeque<String> = ArrayDeque()
    private var activeLoad: Job? = null
    private var loadMoreJob: Job? = null
    private val seenIds = LinkedHashSet<String>()

    fun onTrackChanged(songId: String?, artist: String, recentSongIds: Set<String>) {
        recentIds = recentSongIds
        val normalizedArtist = artist.trim()
        if (songId == lastTrackId && normalizedArtist.equals(lastArtist, ignoreCase = true)) {
            return
        }

        lastTrackId = songId
        lastArtist = normalizedArtist
        val genreQueries = genreQueriesFor(normalizedArtist)
        pendingGenreQueries = ArrayDeque(genreQueries)

        // Do not clear the current shelf when the listener changes artist.
        mutableState.update { it.copy(loading = true) }

        activeLoad?.cancel()
        activeLoad = scope.launch {
            try {
                val related = if (!songId.isNullOrBlank()) {
                    api.getRelatedSongs(songId)
                } else {
                    emptyList()
                }

                val firstGenre = pendingGenreQueries.removeFirstOrNull()
                val genreSongs = firstGenre?.let { api.getMoodSongs(it) }.orEmpty()

                // A second genre query gives the first shelf enough variety to
                // reach a useful size even when artist radio is sparse.
                val secondGenre = pendingGenreQueries.removeFirstOrNull()
                val secondGenreSongs = secondGenre?.let { api.getMoodSongs(it) }.orEmpty()

                val current = mutableState.value.songs
                val combined = merge(current, related + genreSongs + secondGenreSongs)
                if (combined.size < MIN_INITIAL_SONGS) {
                    val trending = api.getTrending()
                    mergeIntoState(trending)
                }

                // Keep a usable shelf even when one of the sources is offline.
                mutableState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    /**
     * Called when the horizontal shelf is swiped close to its end. Each call
     * consumes the next genre query and appends its unique tracks.
     */
    fun loadMore() {
        if (pendingGenreQueries.isEmpty() || loadMoreJob?.isActive == true) return
        loadMoreJob = scope.launch {
            mutableState.update { it.copy(loadingMore = true) }
            try {
                var added = false
                while (pendingGenreQueries.isNotEmpty() && !added) {
                    val query = pendingGenreQueries.removeFirst()
                    val batch = api.getMoodSongs(query)
                    val before = mutableState.value.songs.size
                    mergeIntoState(batch)
                    added = mutableState.value.songs.size > before
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the current shelf intact; a later swipe can retry the
                // remaining genre queries.
            } finally {
                mutableState.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun mergeIntoState(incoming: List<Song>) {
        mutableState.update { current ->
            current.copy(songs = merge(current.songs, incoming))
        }
    }

    private fun merge(existing: List<Song>, incoming: List<Song>): List<Song> {
        val result = LinkedHashMap<String, Song>(existing.size + incoming.size)
        existing.forEach { song ->
            result[song.id] = song
            seenIds.add(song.id)
        }
        incoming.forEach { song ->
            if (song.id.isNotBlank() && song.id !in recentIds) {
                result.putIfAbsent(song.id, song)
                seenIds.add(song.id)
            }
        }
        return result.values.take(MAX_RECOMMENDATIONS)
    }

    private fun genreQueriesFor(artist: String): List<String> {
        val name = artist.lowercase()
        val afrobeatsArtists = listOf(
            "asake", "rema", "fireboy", "burna boy", "wizkid", "davido",
            "tems", "ayra starr", "odumodublvck", "olamide", "tiwa savage",
            "joeboy", "oxlade", "ckay", "kizz daniel", "king promise",
            "stonebwoy", "black sherif", "sarkodie", "kuami eugene", "camidoh"
        )
        val ukRapArtists = listOf(
            "central cee", "dave", "stormzy", "headie one", "skepta",
            "aj tracey", "fredo", "digga d", "arrdee", "aitch", "tion wayne"
        )
        val usHipHopArtists = listOf(
            "drake", "kendrick lamar", "j. cole", "travis scott",
            "21 savage", "future", "metro boomin", "lil baby"
        )

        return when {
            afrobeatsArtists.any(name::contains) ->
                listOf("Afrobeats", "Nigerian Afrobeats", "African pop", "Afropop", "Ghanaian Afrobeats")
            ukRapArtists.any(name::contains) ->
                listOf("UK rap", "UK drill", "British hip hop", "UK rap hits")
            usHipHopArtists.any(name::contains) ->
                listOf("Hip hop", "US rap", "Trap music", "Melodic rap")
            name.contains("dancehall") || name.contains("shatta") ->
                listOf("Dancehall", "Afro-dancehall", "Caribbean hits")
            name.isBlank() ->
                listOf("Afrobeats", "Hip hop", "Pop", "Trending music")
            else ->
                listOf("songs like $artist", "Pop", "Hip hop", "Afrobeats", "Trending music")
        }
    }

    fun close() {
        activeLoad?.cancel()
        loadMoreJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val MIN_INITIAL_SONGS = 20
        const val MAX_RECOMMENDATIONS = 60
    }
}
