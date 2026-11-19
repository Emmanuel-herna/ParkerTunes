package com.parker.tunes.recommendation

import com.parker.tunes.data.FavoriteEntity
import com.parker.tunes.data.ListeningEventEntity
import com.parker.tunes.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MusicTasteProfileTest {
    @Test
    fun qualifiedListeningRanksKnownArtistAheadOfWeakSignal() {
        val now = 1_800_000_000_000L
        val profile = MusicTasteProfile.build(
            events = listOf(
                event("Artist A", "PLAY_QUALIFIED", now),
                event("Artist B", "PLAY_STARTED", now)
            ),
            favorites = emptyList(),
            now = now
        )

        val ranked = profile.rank(
            listOf(song("b", "Artist B"), song("a", "Artist A")),
            emptySet()
        )

        assertEquals("a", ranked.first().id)
    }

    @Test
    fun skipIsWeakNegativeAndDoesNotBecomeAnAutomaticDislike() {
        val now = 1_800_000_000_000L
        val profile = MusicTasteProfile.build(
            events = listOf(
                event("Artist A", "PLAY_QUALIFIED", now),
                event("Artist A", "SKIPPED", now, positionMs = 20_000L),
                event("Artist B", "PLAY_QUALIFIED", now)
            ),
            favorites = emptyList(),
            now = now
        )

        val ranked = profile.rank(
            listOf(song("a", "Artist A"), song("b", "Artist B")),
            emptySet()
        )

        assertEquals(2, ranked.size)
        assertEquals(setOf("a", "b"), ranked.map { it.id }.toSet())
    }

    @Test
    fun favoritesBoostTheirArtistAndPlayedSongsAreExcluded() {
        val now = 1_800_000_000_000L
        val profile = MusicTasteProfile.build(
            events = emptyList(),
            favorites = listOf(FavoriteEntity(songId = "saved", title = "Saved", artist = "Artist A")),
            now = now
        )

        val ranked = profile.rank(
            listOf(song("b", "Artist B"), song("saved", "Artist A"), song("a", "Artist A")),
            excludedSongIds = setOf("saved")
        )

        assertEquals("a", ranked.first().id)
        assertFalse(ranked.any { it.id == "saved" })
    }

    private fun event(
        artist: String,
        type: String,
        time: Long,
        positionMs: Long = 0L
    ) = ListeningEventEntity(
        songId = "track-$artist",
        eventType = type,
        occurredAt = time,
        artist = artist,
        positionMs = positionMs
    )

    private fun song(id: String, artist: String) =
        Song(id = id, title = id, artist = artist)
}
