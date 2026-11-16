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
import java.util.LinkedHashMap

/** Optional BitChord-compatible HTTP addon source, layered beside YouTube. */
class AddonMusicApi(
    context: Context,
    private val youtube: MusicApi = NewPipeMusicApi()
) : MusicApi {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var manifestCache: CachedManifest? = null

    private val streamCache = object : LinkedHashMap<String, String>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 100
    }

    private val searchCache = object : LinkedHashMap<String, List<Song>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>?): Boolean =
            size > 20
    }

    val configuredAddonUrl: String
        get() = preferences.getString(KEY_ADDON_URL, "").orEmpty()

    fun configureAddon(url: String) {
        preferences.edit().putString(KEY_ADDON_URL, normalizeBase(url)).apply()
        synchronized(searchCache) { searchCache.clear() }
    }

    suspend fun validateAddon(rawUrl: String): String = withContext(Dispatchers.IO) {
        val base = normalizeBase(rawUrl)
        require(base.startsWith("https://", ignoreCase = true)) { "Addons must use HTTPS." }
        val manifest = manifestFor(base)
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
        val key = query.trim()
        if (key.isBlank()) return@withContext emptyList()
        synchronized(searchCache) { searchCache[key] }?.let { return@withContext it }
        val addonBase = configuredAddonUrl
        val youtubeResults = youtube.search(key)
        if (addonBase.isBlank()) return@withContext youtubeResults

        val addonResults = runCatching {
            validateAddon(addonBase)
            val url = Uri.parse(endpoint(addonBase, "search")).buildUpon()
                .appendQueryParameter("q", query.trim())
                .appendQueryParameter("quality", qualityFor(addonBase))
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

        (youtubeResults + addonResults).also { combined ->
            synchronized(searchCache) { searchCache[key] = combined }
        }
    }

    override suspend fun searchMore(query: String): List<Song> = withContext(Dispatchers.IO) {
        val key = query.trim()
        if (key.isBlank()) return@withContext emptyList()
        val more = youtube.searchMore(key)
        if (more.isNotEmpty()) {
            synchronized(searchCache) {
                searchCache[key] = (searchCache[key].orEmpty() + more).distinctBy { it.id }
            }
        }
        more
    }

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        synchronized(streamCache) { streamCache[songId] }?.let { return@withContext it }
        val parsed = parseAddonSongId(songId)
        val resolved = if (parsed == null) {
            youtube.getStreamUrl(songId)
        } else {
            val (base, trackId, directStream) = parsed
            if (!directStream.isNullOrBlank()) directStream else {
                val url = Uri.parse(endpoint(base, "stream", trackId)).buildUpon()
                    .appendQueryParameter("quality", qualityFor(base)).build().toString()
                getJson(url).optString("url").also {
                    require(it.startsWith("https://", true)) {
                        "The addon did not return a playable stream URL."
                    }
                }
            }
        }
        require(resolved.startsWith("http", true)) { "Could not resolve an audio stream for this song." }
        synchronized(streamCache) { streamCache[songId] = resolved }
        resolved
    }

    override suspend fun getLyrics(songId: String): String? =
        if (parseAddonSongId(songId) == null) youtube.getLyrics(songId) else null

    override suspend fun getCoverArt(songId: String): String? =
        if (parseAddonSongId(songId) == null) youtube.getCoverArt(songId) else null

    private suspend fun manifestFor(base: String): JSONObject = withContext(Dispatchers.IO) {
        val cached = manifestCache
        if (cached != null && cached.base == base &&
            System.currentTimeMillis() - cached.savedAtMs < MANIFEST_CACHE_MS
        ) return@withContext cached.manifest

        val manifest = getJson(endpoint(base, "manifest.json"))
        manifestCache = CachedManifest(base, System.currentTimeMillis(), manifest)
        manifest
    }

    /** Honor the addon's declared quality default/options instead of guessing. */
    private suspend fun qualityFor(base: String): String {
        val manifest = manifestFor(base)
        val settings = manifest.optJSONArray("settings") ?: return "LOSSLESS"
        for (index in 0 until settings.length()) {
            val setting = settings.optJSONObject(index) ?: continue
            if (setting.optString("key") != "quality") continue
            setting.optString("default").takeIf { it.isNotBlank() }?.let { return it }
            val options = setting.optJSONArray("options")
            if (options != null && options.length() > 0) {
                val first = options.optJSONObject(0)?.optString("value").orEmpty()
                if (first.isNotBlank()) return first
            }
        }
        return "LOSSLESS"
    }

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
        segments.forEach { builder.appendPath(it) }
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

    private data class CachedManifest(
        val base: String,
        val savedAtMs: Long,
        val manifest: JSONObject
    )

    private companion object {
        const val PREFS = "parker_tunes_settings"
        const val KEY_ADDON_URL = "addon_source_url"
        const val MANIFEST_CACHE_MS = 10 * 60 * 1000L
    }
}
