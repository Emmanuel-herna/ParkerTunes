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
    /** Available overlap lengths, in seconds. Zero disables crossfade. */
    val choices = listOf(0, 2, 4, 6, 8, 12)

    fun applies(crossfadeSec: Int, current: Song, next: Song?, repeatOne: Boolean): Boolean {
        if (crossfadeSec <= 0 || next == null || repeatOne) return false
        val overlapMs = crossfadeSec.toLong() * 1_000L
        // Leave enough of each track outside the overlap to avoid swallowing short songs.
        if ((current.durationMs ?: 0L) < overlapMs * 3L ||
            (next.durationMs ?: 0L) < overlapMs * 3L
        ) return false
        return !gapless(current, next)
    }

    /** Consecutive tracks from the same album are left untouched so intended transitions remain gapless. */
    fun gapless(current: Song, next: Song): Boolean {
        val album = current.album?.takeIf { it.isNotBlank() } ?: return false
        if (!album.equals(next.album, ignoreCase = true)) return false
        val currentNumber = current.trackNumber ?: return false
        val nextNumber = next.trackNumber ?: return false
        return nextNumber == currentNumber + 1
    }

    /** Equal-power curves keep perceived loudness steadier during an overlap. */
    fun fadeOut(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fadeIn(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)
}
