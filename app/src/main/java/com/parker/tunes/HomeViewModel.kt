package com.parker.tunes

import com.parker.tunes.data.MusicApi
import com.parker.tunes.model.Song
import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.ListeningEventEntity
import com.parker.tunes.recommendation.MusicTasteProfile
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

data class HomeRecommendationState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val topArtists: List<String> = emptyList()
)

/** Builds recommendations from the listener's complete play history. */
class HomeViewModel(private val api: MusicApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(HomeRecommendationState())
    val state: StateFlow<HomeRecommendationState> = mutableState.asStateFlow()

    private var historyIds: Set<String> = emptySet()
    private var historyKey: List<String> = emptyList()
    private var hasInitializedHistory = false
    private var artistQueue: ArrayDeque<Song> = ArrayDeque()
    private var discoveryQueue: ArrayDeque<String> = ArrayDeque()
    private var activeLoad: Job? = null
    private var loadMoreJob: Job? = null
    private var musicProfile: MusicTasteProfile = MusicTasteProfile.EMPTY
    private var discoveryDiversity: Int = 50
    private var reduceSimilarMusic: Boolean = false

    /** Rebuild the private taste profile from this device's listening signals. */
    fun onMusicSignals(
        events: List<ListeningEventEntity>, favorites: List<FavoriteEntity>,
        personalizationEnabled: Boolean = true, diversity: Int = 50, reduceSimilar: Boolean = false
    ) {
        musicProfile = if (personalizationEnabled) MusicTasteProfile.build(events, favorites) else MusicTasteProfile.EMPTY
        discoveryDiversity = diversity.coerceIn(0, 100)
        reduceSimilarMusic = reduceSimilar
        val current = mutableState.value.songs
        val topArtists = musicProfile.artistAffinity.entries.sortedByDescending { it.value }
            .take(8).map { it.key }
        mutableState.update { it.copy(topArtists = topArtists, songs = if (current.isNotEmpty()) rank(current) else current) }
    }

    private fun rank(candidates: List<Song>): List<Song> =
        musicProfile.rank(candidates, historyIds, limit = MAX_RECOMMENDATIONS, diversityPercent = discoveryDiversity, reduceSimilarMusic = reduceSimilarMusic)

    fun onListeningHistory(history: List<Song>) {
        val cleaned = history.filter { it.id.isNotBlank() }
        val newIds = cleaned.map { it.id }.toSet()
        val newKey = cleaned.map { it.id }
        if (hasInitializedHistory && newKey == historyKey) return
        hasInitializedHistory = true
        historyKey = newKey
        historyIds = newIds

        if (cleaned.isEmpty()) {
            activeLoad?.cancel()
            artistQueue.clear()
            discoveryQueue = ArrayDeque(DEFAULT_DISCOVERY_QUERIES)
            // A new listener should still see a useful discovery shelf immediately.
            // Once play history exists, the personalized artist radio replaces it.
            mutableState.update { it.copy(loading = true, loadingMore = false) }
            activeLoad = scope.launch {
                try {
                    var discovery = api.getTrending().filter(::isUsableRecommendation)
                    if (discovery.size < TARGET_INITIAL && discoveryQueue.isNotEmpty()) {
                        val query = discoveryQueue.removeFirst()
                        discovery = merge(discovery, api.getMoodSongs(query).filter(::isUsableRecommendation))
                    }
                    mutableState.update { it.copy(songs = rank(discovery), loading = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(loading = false) }
                }
            }
            return
        }

        // Frequency is the main signal; most-recent appearance breaks ties.
        val stats = LinkedHashMap<String, Pair<Int, Song>>()
        cleaned.forEach { song ->
            val key = song.artist.trim().lowercase()
            val previous = stats[key]
            stats[key] = Pair((previous?.first ?: 0) + 1, previous?.second ?: song)
        }
        val artistSeeds = stats.values.sortedByDescending { it.first }
            .map { it.second }.distinctBy { it.artist.trim().lowercase() }
        artistQueue = ArrayDeque(artistSeeds.drop(INITIAL_ARTISTS))
        discoveryQueue = ArrayDeque(DEFAULT_DISCOVERY_QUERIES)

        activeLoad?.cancel()
        mutableState.update { it.copy(loading = artistSeeds.isNotEmpty()) }
        activeLoad = scope.launch {
            try {
                val batches = mutableListOf<Song>()
                for (seed in artistSeeds.take(INITIAL_ARTISTS)) {
                    val related = if (seed.artist.isNotBlank()) {
                        api.getRelatedSongs(seed.id, seed.title, seed.artist)
                    } else emptyList()
                    batches += related.filter(::isUsableRecommendation)

                    // If artist radio is sparse, query YouTube Music using the listener's
                    // known artist/title metadata before considering general discovery.
                    if (merge(emptyList(), batches).size < TARGET_INITIAL) {
                        val artistQuery = seed.artist.ifBlank { seed.title }
                        if (artistQuery.isNotBlank()) {
                            batches += api.getMoodSongs("music by $artistQuery")
                                .filter(::isUsableRecommendation)
                        }
                    }
                    if (merge(emptyList(), batches).size >= TARGET_INITIAL) break
                }

                var recommendations = merge(emptyList(), batches)
                // Blend related-artist radio with broad discovery so the feed is not
                // dominated by one source. These are search candidates, not claims
                // that every result is a verified new release or hidden gem.
                if (recommendations.size < TARGET_INITIAL) {
                    recommendations = merge(recommendations, api.getTrending().filter(::isUsableRecommendation))
                }
                while (recommendations.size < TARGET_INITIAL && discoveryQueue.isNotEmpty()) {
                    val query = discoveryQueue.removeFirst()
                    recommendations = merge(recommendations,
                        api.getMoodSongs(query).filter(::isUsableRecommendation))
                }
                recommendations = rank(recommendations)
                mutableState.update {
                    it.copy(songs = recommendations, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    // Kept for the restored legacy SpotifyHomeScreen; the active Home screen
    // uses the richer full-history entry point above.
    fun onTrackChanged(songId: String?, artist: String, recentSongIds: Set<String>) {
        val seed = if (!songId.isNullOrBlank() && artist.isNotBlank()) {
            listOf(Song(songId, songId, artist, null))
        } else emptyList()
        onListeningHistory(seed)
    }

    fun loadMore() {
        if ((artistQueue.isEmpty() && discoveryQueue.isEmpty()) || loadMoreJob?.isActive == true) return
        loadMoreJob = scope.launch {
            mutableState.update { it.copy(loadingMore = true) }
            try {
                while (artistQueue.isNotEmpty() || discoveryQueue.isNotEmpty()) {
                    val before = mutableState.value.songs.size
                    val additions = if (artistQueue.isNotEmpty()) {
                        val seed = artistQueue.removeFirst()
                        api.getRelatedSongs(seed.id, seed.title, seed.artist)
                    } else {
                        api.getMoodSongs(discoveryQueue.removeFirst())
                    }
                    mutableState.update { current ->
                        val combined = merge(current.songs, additions.filter(::isUsableRecommendation))
                        current.copy(songs = rank(combined))
                    }
                    if (mutableState.value.songs.size > before) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the existing shelf intact.
            } finally {
                mutableState.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun isUsableRecommendation(song: Song): Boolean {
        if (song.id.isBlank() || song.id in historyIds) return false
        if (song.durationSec !in 35L..900L) return false
        val title = song.title.lowercase()
        val blocked = listOf(
            "1 hour", "1-hour", "2 hour", "2-hour", "dj mix", "full album",
            "nonstop", "live stream", "playlist", "karaoke", "8d audio",
            "reaction", "sped up", "slowed + reverb", "instrumental"
        )
        return blocked.none(title::contains)
    }

    private fun merge(existing: List<Song>, incoming: List<Song>): List<Song> {
        val result = LinkedHashMap<String, Song>()
        existing.forEach { result[it.id] = it }
        incoming.forEach { song ->
            if (song.id.isNotBlank() && song.id !in historyIds) result.putIfAbsent(song.id, song)
        }
        return result.values.take(MAX_RECOMMENDATIONS)
    }

    fun close() {
        activeLoad?.cancel()
        loadMoreJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val INITIAL_ARTISTS = 4
        const val TARGET_INITIAL = 24
        const val MIN_RECOMMENDATIONS = 9
        const val MAX_RECOMMENDATIONS = 120
        val DEFAULT_DISCOVERY_QUERIES = listOf(
            "new music 2026",
            "independent music 2026",
            "popular songs 2026"
        )
    }
}
