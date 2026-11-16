package com.parker.tunes.model

data class Artist(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val subscriberCount: Long = 0L,
    val description: String = ""
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList()
)
