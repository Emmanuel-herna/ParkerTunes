package com.parker.tunes.recommendation

import com.parker.tunes.data.home.HomeShelf

/**
 * Reorders only shelves actually returned by YouTube Music.
 * It never invents a shelf or changes the provider's content/metadata.
 */
object AdaptiveHomeFeed {
    fun rank(
        shelves: List<HomeShelf>,
        preferredArtists: List<String>,
        diversityPercent: Int
    ): List<HomeShelf> {
        if (shelves.size < 2) return shelves
        val artists = preferredArtists.map { it.trim().lowercase() }
            .filter { it.length >= 3 }.distinct()
        if (artists.isEmpty() && diversityPercent == 50) return shelves

        val diversity = diversityPercent.coerceIn(0, 100) / 100.0
        return shelves.withIndex().sortedWith(
            compareByDescending<IndexedValue<HomeShelf>> { indexed ->
                val shelf = indexed.value
                val title = shelf.title.lowercase()
                val description = shelf.strapline.orEmpty().lowercase()
                val itemText = shelf.items.joinToString(" ") {
                    it.title + " " + it.subtitle.orEmpty()
                }.lowercase()

                val artistMatch = artists.withIndex().sumOf { (index, artist) ->
                    if (itemText.contains(artist) || title.contains(artist) || description.contains(artist)) {
                        1.0 / (index + 1.0)
                    } else 0.0
                }

                val discoveryShelf = listOf(
                    "new music", "new release", "fresh", "discover",
                    "hidden gem", "independent", "explore"
                ).any(title::contains)
                val familiarShelf = listOf(
                    "made for you", "because you listen", "similar",
                    "your mix", "radio", "based on"
                ).any(title::contains)
                val trendingShelf = listOf("trending", "popular", "top hits", "charts")
                    .any(title::contains)

                artistMatch * (1.0 - diversity * 0.55) +
                    (if (discoveryShelf) diversity * 2.0 else 0.0) +
                    (if (familiarShelf) (1.0 - diversity) * 1.5 else 0.0) +
                    (if (trendingShelf) 0.35 else 0.0)
            }.thenBy { it.index }
        ).map { it.value }
    }
}
