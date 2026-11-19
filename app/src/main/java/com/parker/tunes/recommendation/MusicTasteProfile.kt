package com.parker.tunes.recommendation

import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.ListeningEventEntity
import com.parker.tunes.model.Song
import kotlin.math.exp

/**
 * A private, on-device summary of one listener's music taste.
 *
 * Only artist signals are inferred because the current Song model does not
 * provide dependable genre or mood metadata. Missing metadata is never guessed.
 */
data class MusicTasteProfile(
    val artistAffinity: Map<String, Double>,
    val favoriteArtists: Set<String>,
    val recentArtistOrder: List<String>,
    val signalCount: Int
) {
    val isPersonalized: Boolean
        get() = artistAffinity.isNotEmpty() || favoriteArtists.isNotEmpty()

    fun rank(
        candidates: List<Song>,
        excludedSongIds: Set<String>,
        limit: Int = 60,
        diversityPercent: Int = 50,
        reduceSimilarMusic: Boolean = false
    ): List<Song> {
        val unique = candidates.filter { it.id.isNotBlank() && it.id !in excludedSongIds }
            .distinctBy { it.id }
        if (unique.isEmpty()) return emptyList()
        val diversity = diversityPercent.coerceIn(0, 100) / 100.0
        if (!isPersonalized && diversityPercent == 50 && !reduceSimilarMusic) {
            return unique.take(limit.coerceAtLeast(0))
        }
        val diversityOffset = (diversity - 0.5) * 2.0
        val affinityWeight = 0.66 * (1.0 - diversityOffset * 0.325)
        val repetitionWeight = (0.16 + diversityOffset * 0.17).coerceAtLeast(0.02) +
            if (reduceSimilarMusic) 0.22 else 0.0
        val discoveryWeight = (0.12 + diversityOffset * 0.14).coerceAtLeast(0.02)
        val maxAffinity = (artistAffinity.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val recentPosition = recentArtistOrder.withIndex().associate { it.value to it.index }
        val remaining = unique.toMutableList()
        val selected = mutableListOf<Song>()
        val selectedArtists = mutableMapOf<String, Int>()

        while (remaining.isNotEmpty() && selected.size < limit.coerceAtLeast(0)) {
            val next = remaining.maxByOrNull { song ->
                val artist = normalize(song.artist)
                val affinity = (artistAffinity[artist] ?: 0.0).coerceAtLeast(0.0) / maxAffinity
                val favoriteBoost = if (artist in favoriteArtists) 0.22 else 0.0
                val recentBoost = recentPosition[artist]?.let { (0.12 / (it + 1)).coerceAtMost(0.12) } ?: 0.0
                val repetitionPenalty = (selectedArtists[artist] ?: 0) * repetitionWeight
                val originalOrder = (unique.indexOfFirst { it.id == song.id }).coerceAtLeast(0)
                val discoveryPrior = discoveryWeight / (originalOrder + 1)
                affinity * affinityWeight + favoriteBoost * (1.0 - diversityOffset * 0.45) + recentBoost + discoveryPrior - repetitionPenalty
            } ?: break
            remaining.remove(next)
            selected += next
            val key = normalize(next.artist)
            if (key.isNotEmpty()) selectedArtists[key] = (selectedArtists[key] ?: 0) + 1
        }
        return selected
    }

    companion object {
        val EMPTY = MusicTasteProfile(emptyMap(), emptySet(), emptyList(), 0)

        fun build(
            events: List<ListeningEventEntity>,
            favorites: List<FavoriteEntity>,
            now: Long = System.currentTimeMillis()
        ): MusicTasteProfile {
            val weights = linkedMapOf<String, Double>()
            val recent = linkedSetOf<String>()
            var signals = 0

            events.sortedByDescending { it.occurredAt }.forEach { event ->
                val artist = normalize(event.artist)
                if (artist.isEmpty()) return@forEach
                val ageDays = ((now - event.occurredAt).coerceAtLeast(0L) / DAY_MS).toDouble()
                val recency = exp(-ageDays / 30.0)
                val eventWeight = when (event.eventType) {
                    "PLAY_QUALIFIED" -> 3.0
                    "PLAY_COMPLETED" -> 2.0
                    "PLAY_STARTED" -> 0.25
                    // A skip is a weak negative signal, never a hard dislike.
                    "SKIPPED" -> if (event.positionMs >= 15_000L) -0.35 else -0.1
                    "LIKED" -> 4.0
                    "NOT_INTERESTED" -> -1.5
                    else -> 0.0
                }
                if (eventWeight != 0.0) {
                    weights[artist] = (weights[artist] ?: 0.0) + eventWeight * recency
                    signals++
                    if (eventWeight > 0.0) recent.add(artist)
                }
            }

            val favoriteArtists = favorites.mapNotNull { normalize(it.artist).takeIf(String::isNotEmpty) }.toSet()
            favoriteArtists.forEach { artist ->
                weights[artist] = (weights[artist] ?: 0.0) + 4.0
            }
            return MusicTasteProfile(
                artistAffinity = weights.filterValues { it > 0.0 },
                favoriteArtists = favoriteArtists,
                recentArtistOrder = recent.toList(),
                signalCount = signals + favorites.size
            )
        }

        private const val DAY_MS = 86_400_000.0
        private fun normalize(value: String): String = value.trim().lowercase()
    }
}
