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

data class HomeRecommendationState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false
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
    private var activeLoad: Job? = null
    private var loadMoreJob: Job? = null

    fun onListeningHistory(history: List<Song>) {
        val cleaned = history.filter { it.id.isNotBlank() && it.artist.isNotBlank() }
        val newIds = cleaned.map { it.id }.toSet()
        val newKey = cleaned.map { it.id }
        if (hasInitializedHistory && newKey == historyKey) return
        hasInitializedHistory = true
        historyKey = newKey
        historyIds = newIds

        // Frequency is the main signal; most-recent appearance breaks ties.
        val stats = LinkedHashMap<String, Pair<Int, Song>>()
        cleaned.forEach { song ->
            val key = song.artist.trim().lowercase()
            val previous = stats[key]
            stats[key] = Pair((previous?.first ?: 0) + 1, previous?.second ?: song)
        }
        val artistSeeds = stats.values.sortedByDescending { it.first }
            .map { it.second }.distinctBy { it.artist.trim().lowercase() }
        artistQueue = ArrayDeque(artistSeeds)

        activeLoad?.cancel()
        mutableState.update { it.copy(loading = artistSeeds.isNotEmpty()) }
        activeLoad = scope.launch {
            try {
                val batches = mutableListOf<Song>()
                for (seed in artistSeeds.take(INITIAL_ARTISTS)) {
                    batches += api.getRelatedSongs(seed.id)
                    if (merge(emptyList(), batches).size >= TARGET_INITIAL) break
                }
                if (merge(emptyList(), batches).size < MIN_INITIAL) {
                    val artist = artistSeeds.firstOrNull()?.artist
                    batches += api.getMoodSongs(
                        if (artist.isNullOrBlank()) "popular music" else "music similar to $artist"
                    )
                }
                if (merge(emptyList(), batches).size < MIN_INITIAL) {
                    batches += api.getTrending()
                }
                mutableState.update {
                    it.copy(songs = merge(emptyList(), batches), loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun loadMore() {
        if (artistQueue.isEmpty() || loadMoreJob?.isActive == true) return
        loadMoreJob = scope.launch {
            mutableState.update { it.copy(loadingMore = true) }
            try {
                while (artistQueue.isNotEmpty()) {
                    val seed = artistQueue.removeFirst()
                    val before = mutableState.value.songs.size
                    val related = api.getRelatedSongs(seed.id)
                    mutableState.update { current ->
                        current.copy(songs = merge(current.songs, related))
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
        const val MIN_INITIAL = 8
        const val MAX_RECOMMENDATIONS = 60
    }
}
