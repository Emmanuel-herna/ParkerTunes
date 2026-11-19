package com.parker.tunes.recommendation

import com.parker.tunes.data.home.HomeShelf
import com.parker.tunes.data.home.ShelfItem
import com.parker.tunes.data.home.ShelfLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveHomeFeedTest {
    @Test
    fun artistAffinityCanPromoteShelvesThatContainPreferredArtists() {
        val shelves = listOf(
            shelf("Trending", item("Popular track", "Various artists")),
            shelf("For your listening", item("Track", "Artist A"))
        )

        val ranked = AdaptiveHomeFeed.rank(shelves, listOf("artist a"), 20)

        assertEquals("For your listening", ranked.first().title)
    }

    @Test
    fun diversityChangesTheBalanceBetweenDiscoveryAndFamiliarShelves() {
        val shelves = listOf(
            shelf("Made for you", item("Familiar mix", "Artist A")),
            shelf("New releases", item("Fresh track", "Artist B"))
        )

        val discoveryFirst = AdaptiveHomeFeed.rank(shelves, emptyList(), 100)
        val familiarFirst = AdaptiveHomeFeed.rank(shelves, emptyList(), 0)

        assertEquals("New releases", discoveryFirst.first().title)
        assertEquals("Made for you", familiarFirst.first().title)
    }

    private fun shelf(title: String, item: ShelfItem) =
        HomeShelf(title, null, ShelfLayout.CARD, listOf(item))

    private fun item(title: String, subtitle: String) =
        ShelfItem(title, subtitle, null, null, null, null, false)
}
