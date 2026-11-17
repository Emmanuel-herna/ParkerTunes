package com.parker.tunes.data.home

import org.junit.Assert.*
import org.junit.Test

class HomeShelfFilterTest {
    private fun item(name: String, video: Boolean = false) =
        ShelfItem(name, null, null, if (video) null else "MPRE123", null,
            if (video) "abcdefghijk" else null, video)

    @Test fun videosComeOutOfAMixedShelf() {
        val shelf = HomeShelf("Mix", null, ShelfLayout.SQUARE, listOf(item("Song"), item("Video", true)))
        val result = listOf(shelf).withoutVideos()
        assertEquals(1, result.size)
        assertEquals(listOf("Song"), result.single().items.map { it.title })
    }

    @Test fun shelfWithOnlyVideosIsDropped() {
        val shelf = HomeShelf("Videos", null, ShelfLayout.SQUARE, listOf(item("Clip", true)))
        assertTrue(listOf(shelf).withoutVideos().isEmpty())
    }

    @Test fun videoLayoutShelfIsDroppedEvenIfItemIsNotMarkedVideo() {
        val shelf = HomeShelf("Video", null, ShelfLayout.VIDEO, listOf(item("Song")))
        assertTrue(listOf(shelf).withoutVideos().isEmpty())
    }

    @Test fun shelfWithoutVideosIsUnchanged() {
        val shelf = HomeShelf("Songs", "For you", ShelfLayout.SQUARE, listOf(item("A"), item("B")))
        assertEquals(listOf(shelf), listOf(shelf).withoutVideos())
    }
}
