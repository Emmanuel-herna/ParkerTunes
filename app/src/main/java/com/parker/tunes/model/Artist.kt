package com.parker.tunes.model

data class Artist(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val subscriberCount: Long = 0L,
    val description: String = ""
)

data class Album(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val trackCount: Long = 0L,
    val year: String = ""
)

data class ArtistProfile(
    val artist: Artist,
    val topSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList()
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList()
)
