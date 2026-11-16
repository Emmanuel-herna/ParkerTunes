package com.parker.tunes.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Optional BitChord-compatible HTTP addon source, layered beside YouTube. */
class AddonMusicApi(
    context: Context,
    private val youtube: MusicApi = NewPipeMusicApi()
) : MusicApi {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    val configuredAddonUrl: String
        get() = preferences.getString(KEY_ADDON_URL, "").orEmpty()

    fun configureAddon(url: String) {
        preferences.edit().putString(KEY_ADDON_URL, normalizeBase(url)).apply()
    }

    suspend fun validateAddon(rawUrl: String): String = withContext(Dispatchers.IO) {
        val base = normalizeBase(rawUrl)
        require(base.startsWith("https://", ignoreCase = true)) { "Addons must use HTTPS." }
        val manifest = getJson(endpoint(base, "manifest.json"))
        val id = manifest.optString("id")
        require(id.isNotBlank()) { "This server did not return a valid addon manifest." }
        val resources = manifest.optJSONArray("resources")
        if (resources != null && resources.length() > 0) {
            val supportsSearch = (0 until resources.length()).any {
                resources.optString(it).equals("search", true)
            }
            require(supportsSearch) { "This addon does not declare the search resource." }
        }
        require(!manifest.optBoolean("bitchordAuth", false)) {
            "This addon requires BitChord-only authentication, which ParkerTunes does not support."
        }
        manifest.optString("name").ifBlank { id }
    }

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val addonBase = configuredAddonUrl
        val youtubeResults = runCatching { youtube.search(query) }.getOrDefault(emptyList())
        if (addonBase.isBlank()) return@withContext youtubeResults

        val addonResults = runCatching {
            validateAddon(addonBase)
            val url = Uri.parse(endpoint(addonBase, "search")).buildUpon()
                .appendQueryParameter("q", query.trim())
                .appendQueryParameter("quality", "LOSSLESS")
                .build().toString()
            val payload = getJson(url)
            val tracks = payload.optJSONArray("tracks") ?: return@runCatching emptyList<Song>()
            buildList {
                for (index in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(index) ?: continue
                    val id = track.optString("id")
                    val title = track.optString("title")
                    if (id.isBlank() || title.isBlank()) continue
                    val artist = track.optString("artist").ifBlank { "Unknown artist" }
                    val artwork = track.optString("artworkURL")
                        .ifBlank { track.optString("albumArtworkURL") }.takeIf { it.isNotBlank() }
                    val directStream = track.optString("streamURL").takeIf { it.isNotBlank() }
                    add(Song(
                        id = addonSongId(addonBase, id, directStream),
                        title = title,
                        artist = artist,
                        coverUrl = artwork,
                        durationSec = track.optDouble("duration", 0.0)
                            .takeIf { it > 0.0 }?.toLong() ?: 0L
                    ))
                }
            }
        }.getOrDefault(emptyList())

        youtubeResults + addonResults
    }

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        val parsed = parseAddonSongId(songId)
        if (parsed == null) return@withContext youtube.getStreamUrl(songId)
        val (base, trackId, directStream) = parsed
        if (!directStream.isNullOrBlank()) return@withContext directStream

        val url = Uri.parse(endpoint(base, "stream", trackId)).buildUpon()
            .appendQueryParameter("quality", "LOSSLESS").build().toString()
        getJson(url).optString("url").also {
            require(it.startsWith("https://", true) || it.startsWith("http://", true)) {
                "The addon did not return a playable stream URL."
            }
        }
    }

    override suspend fun getLyrics(songId: String): String? =
        if (parseAddonSongId(songId) == null) youtube.getLyrics(songId) else null

    override suspend fun getCoverArt(songId: String): String? =
        if (parseAddonSongId(songId) == null) youtube.getCoverArt(songId) else null

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(
                "Addon request failed (${response.code})."
            )
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "The addon returned an empty response." }
            return JSONObject(body)
        }
    }

    private fun endpoint(base: String, vararg segments: String): String {
        val builder = Uri.parse(normalizeBase(base)).buildUpon()
        segments.forEach(builder::appendPath)
        return builder.build().toString()
    }

    private fun normalizeBase(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.endsWith("/manifest.json", ignoreCase = true)) {
            value = value.dropLast("/manifest.json".length).trimEnd('/')
        }
        return value
    }

    private fun addonSongId(base: String, id: String, stream: String?): String =
        listOf("addon", encode(base), encode(id), encode(stream.orEmpty())).joinToString(":")

    private fun parseAddonSongId(value: String): Triple<String, String, String?>? {
        if (!value.startsWith("addon:")) return null
        val parts = value.split(':')
        if (parts.size != 4) return null
        return runCatching {
            Triple(decode(parts[1]), decode(parts[2]), decode(parts[3]).ifBlank { null })
        }.getOrNull()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)

    private companion object {
        const val PREFS = "parker_tunes_settings"
        const val KEY_ADDON_URL = "addon_source_url"
    }
}
