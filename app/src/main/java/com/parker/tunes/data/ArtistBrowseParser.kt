package com.parker.tunes.data

import android.net.Uri
import com.parker.tunes.model.Album
import com.parker.tunes.model.Artist
import com.parker.tunes.model.ArtistProfile
import com.parker.tunes.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps YouTube Music's native artist browse response into ParkerTunes models.
 * Kept independent of the UI so changes in the page layout do not affect playback.
 */
internal object ArtistBrowseParser {
    fun browseId(value: String): String {
        val clean = value.trim()
        if (!clean.startsWith("http", ignoreCase = true)) return clean.substringBefore("?")
        return runCatching {
            val uri = Uri.parse(clean)
            uri.getQueryParameter("browseId")
                ?: uri.lastPathSegment?.substringBefore("?")
                ?: clean
        }.getOrDefault(clean)
    }

    fun parse(artist: Artist, response: JSONObject): ArtistProfile {
        val header = response.optJSONObject("header")
        val headerRenderer = findFirst(header, "musicImmersiveHeaderRenderer")
            ?: findFirst(header, "musicVisualHeaderRenderer")
        val name = text(headerRenderer?.opt("title")).ifBlank { artist.name }
        val avatar = thumbnail(headerRenderer).takeIf { it.isNotBlank() } ?: artist.avatarUrl
        val description = text(headerRenderer?.opt("description"))
            .ifBlank { text(findFirst(headerRenderer, "description")) }
            .ifBlank { artist.description }

        val subscriberText = sequenceOf(
            findFirst(headerRenderer, "subscriberCountWithSubscribeText"),
            findFirst(headerRenderer, "longSubscriberCountText"),
            findFirst(headerRenderer, "shortSubscriberCountText")
        ).mapNotNull { text(it).takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
        val subscriberCount = parseCount(subscriberText).takeIf { it > 0L } ?: artist.subscriberCount

        val resolved = artist.copy(
            name = name,
            avatarUrl = avatar,
            subscriberCount = subscriberCount,
            description = description
        )

        val sections = sectionContents(response)
        val songs = mutableListOf<Song>()
        val albums = mutableListOf<Album>()
        val singles = mutableListOf<Album>()
        val recent = mutableListOf<Album>()
        val related = mutableListOf<Artist>()

        sections.forEach { section ->
            val shelf = section.optJSONObject("musicShelfRenderer")
            if (shelf != null) {
                // Only the explicitly labelled Top songs shelf is an artist's
                // top-song list. Other shelves must never be used as a fallback.
                val title = text(shelf.opt("title"))
                if (title.contains("top song", true)) {
                    val rows = shelf.optJSONArray("contents")
                    if (rows != null) for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val renderer = row.optJSONObject("musicResponsiveListItemRenderer")
                            ?: findFirst(row, "musicResponsiveListItemRenderer")
                            ?: continue
                        parseSong(renderer, resolved.name)?.let(songs::add)
                    }
                }
            }

            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            if (carousel != null) {
                val carouselHeader = findFirst(carousel.optJSONObject("header"), "musicCarouselShelfBasicHeaderRenderer")
                val title = text(carouselHeader?.opt("title")).trim()
                val isRelated = title.contains("related", true) ||
                    (title.contains("artist", true) && !title.contains("album", true))
                val isSingleOrEp = title.contains("single", true) || Regex("""\\bEPs?\\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)
                val isRecent = title.contains("recent", true) || title.contains("latest release", true)
                val isAlbumShelf = title.equals("albums", true) ||
                    title.contains("full albums", true) ||
                    title.contains("album releases", true)
                // Ignore unknown shelves (for example "All hits", playlists,
                // mixes and recommendations) rather than mislabelling them Albums.
                if (isRelated || isSingleOrEp || isRecent || isAlbumShelf) {
                    val contents = carousel.optJSONArray("contents") ?: JSONArray()
                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i) ?: continue
                        val renderer = item.optJSONObject("musicTwoRowItemRenderer")
                            ?: findFirst(item, "musicTwoRowItemRenderer")
                            ?: continue
                        if (isRelated) {
                            val id = firstBrowseId(renderer)?.takeIf { it.startsWith("UC") } ?: continue
                            val artistName = text(renderer.opt("title"))
                            if (artistName.isNotBlank() && id != browseId(artist.id)) {
                                related.add(Artist(id, artistName, thumbnail(renderer)))
                            }
                        } else {
                            val album = parseAlbum(renderer) ?: continue
                            when {
                                isSingleOrEp -> singles.add(album)
                                isRecent -> recent.add(album)
                                isAlbumShelf -> albums.add(album)
                            }
                        }
                    }
                }
            }
        }

        val uniqueAlbums = albums.distinctBy { it.id }
        val uniqueSingles = singles.distinctBy { it.id }
        val allRecent = (recent + uniqueAlbums + uniqueSingles)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Album> { it.year.toIntOrNull() ?: 0 }.thenBy { it.title.lowercase() })
            .take(30)

        return ArtistProfile(
            artist = resolved,
            topSongs = songs.distinctBy { it.id }.take(20),
            albums = uniqueAlbums.take(30),
            singles = uniqueSingles.take(30),
            recentReleases = allRecent,
            relatedArtists = related.distinctBy { it.id }.take(20)
        )
    }

    private fun sectionContents(response: JSONObject): List<JSONObject> {
        val singleColumn = findFirst(response, "singleColumnBrowseResultsRenderer")
        val tabs = singleColumn?.optJSONArray("tabs")
        val tab = tabs?.optJSONObject(0)
        val tabRenderer = tab?.optJSONObject("tabRenderer")
        val content = tabRenderer?.optJSONObject("content")
        val sectionList = content?.optJSONObject("sectionListRenderer")
        val sections = sectionList?.optJSONArray("contents")
        if (sections != null) return (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }

        val musicShelf = findAll(response, "musicShelfRenderer")
        val carousels = findAll(response, "musicCarouselShelfRenderer")
        return (musicShelf.map { JSONObject().put("musicShelfRenderer", it) } +
            carousels.map { JSONObject().put("musicCarouselShelfRenderer", it) })
    }

    private fun parseSong(renderer: JSONObject, fallbackArtist: String): Song? {
        val videoId = findFirst(renderer, "playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: findFirst(renderer, "watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null
        val columns = renderer.optJSONArray("flexColumns")
        val title = columns?.optJSONObject(0)?.let { text(findFirst(it, "text")) }
            .orEmpty().ifBlank { text(renderer.opt("title")) }
        if (title.isBlank()) return null
        val creditedArtist = columns?.optJSONObject(1)?.let { text(findFirst(it, "text")) }
            .orEmpty().substringBefore(" • ").ifBlank { fallbackArtist }
        val durationText = renderer.optJSONArray("fixedColumns")?.let { text(it) }.orEmpty()
        val duration = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)""").find(durationText)?.value
            ?.split(":")?.mapNotNull(String::toLongOrNull)
            ?.fold(0L) { total, part -> total * 60L + part } ?: 0L
        return Song(videoId, title, creditedArtist, thumbnail(renderer).takeIf { it.isNotBlank() }, duration)
    }

    private fun parseAlbum(renderer: JSONObject): Album? {
        val id = firstBrowseId(renderer)?.takeIf {
            it.startsWith("MPRE") || it.startsWith("VL") || it.startsWith("OLAK") || it.startsWith("PL")
        } ?: return null
        val title = text(renderer.opt("title")).ifBlank { return null }
        val subtitle = text(renderer.opt("subtitle"))
        val count = Regex("""\b(\d+)\s*(?:songs|tracks)\b""", RegexOption.IGNORE_CASE)
            .find(subtitle)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val year = Regex("""\b(?:19|20)\d{2}\b""").find(subtitle)?.value.orEmpty()
        return Album(id, title, thumbnail(renderer).ifBlank { null }, count, year)
    }

    private fun firstBrowseId(root: JSONObject): String? =
        findFirst(root, "browseEndpoint")?.optString("browseId")?.takeIf { it.isNotBlank() }

    private fun thumbnail(root: JSONObject?): String {
        if (root == null) return ""

        // YouTube Music's thumbnail field is normally a JSON array, not an
        // object containing another "thumbnails" array. Walk both objects and
        // arrays so artist, album and song renderers all expose their artwork.
        val urls = mutableListOf<String>()

        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key == "thumbnails" && child is JSONArray) {
                            for (index in child.length() - 1 downTo 0) {
                                val url = child.optJSONObject(index)?.optString("url").orEmpty()
                                if (url.startsWith("https://") || url.startsWith("http://")) {
                                    urls.add(url)
                                }
                            }
                        }
                        collect(child)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) collect(value.opt(index))
                }
            }
        }

        collect(root)
        return urls.firstOrNull { it.startsWith("https://") }
            ?: urls.firstOrNull().orEmpty()
    }

    private fun text(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        if (value is String) return value
        if (value is JSONArray) return (0 until value.length()).joinToString("") { text(value.opt(it)) }
        if (value is JSONObject) {
            val runs = value.optJSONArray("runs")
            if (runs != null) return (0 until runs.length()).joinToString("") { text(runs.opt(it)) }
            val simple = value.optString("simpleText")
            if (simple.isNotBlank()) return simple
            val direct = value.optString("text")
            if (direct.isNotBlank()) return direct
        }
        return ""
    }

    private fun findFirst(root: JSONObject?, key: String): JSONObject? = findAll(root, key).firstOrNull()

    private fun findAll(root: JSONObject?, key: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val name = keys.next()
                        val child = value.opt(name)
                        if (name == key && child is JSONObject) found.add(child)
                        walk(child)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        return found
    }

    private fun parseCount(value: String): Long {
        val match = Regex("""([\d,.]+)\s*([KMB])?""", RegexOption.IGNORE_CASE).find(value) ?: return 0L
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
        return (number * multiplier).toLong()
    }
}
