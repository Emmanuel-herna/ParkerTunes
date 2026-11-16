package com.parker.tunes.model

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationSec: Long = 0L,
    val album: String? = null,
    val trackNumber: Int? = null,
    val sourceQuality: String? = null
)
