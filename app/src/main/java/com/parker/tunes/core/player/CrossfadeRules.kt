package com.parker.tunes.core.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Song(
    val album: String?,
    val trackNumber: Int?,
    val durationMs: Long?
)

object CrossfadeRules {
    fun applies(crossfadeSec: Int, current: Song, next: Song?, repeatOne: Boolean): Boolean {
        if (crossfadeSec <= 0 || next == null || repeatOne) return false
        val currentDuration = current.durationMs ?: return false
        val nextDuration = next.durationMs ?: return false
        val fadeMs = crossfadeSec.toLong() * 1_000L
        if (currentDuration < fadeMs * 2L || nextDuration < fadeMs * 2L) return false
        val sameAlbum = !current.album.isNullOrBlank() && current.album.equals(next.album, ignoreCase = true)
        if (sameAlbum && current.trackNumber != null && next.trackNumber == current.trackNumber + 1) return false
        return true
    }

    fun fadeOut(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)
    fun fadeIn(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)
}
