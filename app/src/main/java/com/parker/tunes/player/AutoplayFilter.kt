package com.parker.tunes.player

/**
 * Rejects obvious DJ mixes and unusually long videos from automatic next-up.
 *
 * We inspect the stream title only, not the artist/uploader field. This avoids
 * rejecting normal songs just because an artist or DJ has "DJ" in their name.
 */
fun isLikelyMix(title: String, durationSeconds: Long): Boolean {
    if (durationSeconds > 12 * 60L) return true

    val normalizedTitle = title.lowercase()
    val mixMarkers = listOf(
        "mix",
        "megamix",
        "nonstop",
        "non-stop",
        "1 hour",
        "2 hours",
        "extended",
        "compilation",
        "dj set",
        "live set",
        "bootleg",
        "medley",
        "party",
        "back to back"
    )
    return mixMarkers.any(normalizedTitle::contains)
}
