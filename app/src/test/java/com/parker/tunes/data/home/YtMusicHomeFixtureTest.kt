package com.parker.tunes.data.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YtMusicHomeFixtureTest {
    @Test fun parsesBrowseHomeFixture() {
        val json = requireNotNull(javaClass.classLoader?.getResourceAsStream("browseHome.json")).bufferedReader().use { it.readText() }
        val page = YtMusicHomeApi().parseResponseForTests(json)
        assertEquals("Made for you", page.shelves.single().title)
        assertEquals("Your daily mix", page.shelves.single().strapline)
        assertEquals("Example Song", page.shelves.single().items.single().title)
        assertEquals("MPREexample", page.shelves.single().items.single().browseId)
        assertFalse(page.shelves.single().items.single().isVideo)
        assertEquals("Focus", page.chips.single().name)
    }
}
