package com.parker.tunes.data

import com.parker.tunes.model.Song

interface MusicApi {
    suspend fun search(query: String): List<Song>
    suspend fun getStreamUrl(songId: String): String
    suspend fun getLyrics(songId: String): String?
    suspend fun getCoverArt(songId: String): String?
}

/** Safe empty implementation: connect a licensed provider to enable catalogue search. */
class DemoMusicApi : MusicApi {
    override suspend fun search(query: String): List<Song> = emptyList()
    override suspend fun getStreamUrl(songId: String): String =
        throw UnsupportedOperationException("Connect a music provider to enable playback.")
    override suspend fun getLyrics(songId: String): String? = null
    override suspend fun getCoverArt(songId: String): String? = null
}
