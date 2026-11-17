package com.parker.tunes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import okhttp3.Credentials
import java.util.concurrent.TimeUnit

data class SpotifyTrack(
    val name: String,
    val artists: List<String>,
    val albumName: String,
    val artworkUrl: String?,
    val spotifyId: String,
    val durationMs: Long,
    val spotifyUrl: String
)

/**
 * Spotify catalogue metadata only. Audio playback always remains on the existing
 * YouTube/NewPipe path. Client credentials are stored locally for this personal
 * build; move this provider behind a backend before any public distribution,
 * because secrets embedded in an Android app can be extracted from its APK.
 */
class SpotifyMetadataProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    @Volatile private var cachedClientId: String? = null

    suspend fun searchTracks(
        query: String,
        limit: Int = 10,
        clientId: String,
        clientSecret: String
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return@withContext emptyList()
        runCatching {
            val token = accessToken(clientId, clientSecret)
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            // Spotify's February 2026 Web API migration caps Search limit at 10.
            val url = "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=${limit.coerceIn(1, 10)}"
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Spotify search HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val items = root.optJSONObject("tracks")?.optJSONArray("items")
                    ?: return@use emptyList<SpotifyTrack>()
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val album = item.optJSONObject("album") ?: JSONObject()
                        val artistsJson = item.optJSONArray("artists")
                        val artists = buildList {
                            if (artistsJson != null) for (j in 0 until artistsJson.length()) {
                                artistsJson.optJSONObject(j)?.optString("name")
                                    ?.takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                        }
                        val images = album.optJSONArray("images")
                        var artwork: String? = null
                        var largest = -1
                        if (images != null) for (j in 0 until images.length()) {
                            val image = images.optJSONObject(j) ?: continue
                            val size = image.optInt("width", 0) * image.optInt("height", 0)
                            if (size > largest) {
                                largest = size
                                artwork = image.optString("url").takeIf { it.isNotBlank() }
                            }
                        }
                        val external = item.optJSONObject("external_urls")?.optString("spotify").orEmpty()
                        val id = item.optString("id")
                        if (id.isNotBlank() && external.isNotBlank()) add(
                            SpotifyTrack(
                                name = item.optString("name"),
                                artists = artists,
                                albumName = album.optString("name"),
                                artworkUrl = artwork,
                                spotifyId = id,
                                durationMs = item.optLong("duration_ms", 0L),
                                spotifyUrl = external
                            )
                        )
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            emptyList()
        }
    }

    suspend fun getTrack(
        id: String,
        clientId: String,
        clientSecret: String
    ): SpotifyTrack? = withContext(Dispatchers.IO) {
        if (id.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return@withContext null
        runCatching {
            val token = accessToken(clientId, clientSecret)
            val request = Request.Builder().url("https://api.spotify.com/v1/tracks/${java.net.URLEncoder.encode(id, "UTF-8")}")
                .header("Authorization", "Bearer $token").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Spotify track HTTP ${response.code}")
                val item = JSONObject(response.body?.string().orEmpty())
                val artistsJson = item.optJSONArray("artists")
                val artists = buildList {
                    if (artistsJson != null) for (i in 0 until artistsJson.length()) {
                        artistsJson.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                val album = item.optJSONObject("album") ?: JSONObject()
                val images = album.optJSONArray("images")
                var artwork: String? = null
                var largest = -1
                if (images != null) for (i in 0 until images.length()) {
                    val image = images.optJSONObject(i) ?: continue
                    val size = image.optInt("width", 0) * image.optInt("height", 0)
                    if (size > largest) { largest = size; artwork = image.optString("url").takeIf { it.isNotBlank() } }
                }
                SpotifyTrack(
                    item.optString("name"), artists, album.optString("name"), artwork,
                    item.optString("id"), item.optLong("duration_ms", 0L),
                    item.optJSONObject("external_urls")?.optString("spotify").orEmpty()
                )
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            null
        }
    }

    private fun accessToken(clientId: String, clientSecret: String): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { cachedClientId == clientId && now < tokenExpiresAt - 60_000L }?.let { return it }
        synchronized(this) {
            cachedToken?.takeIf { cachedClientId == clientId && System.currentTimeMillis() < tokenExpiresAt - 60_000L }?.let { return it }
            val body = FormBody.Builder().add("grant_type", "client_credentials").build()
            val request = Request.Builder().url("https://accounts.spotify.com/api/token")
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Spotify token HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                val token = json.optString("access_token")
                if (token.isBlank()) throw IOException("Spotify token missing")
                cachedToken = token
                cachedClientId = clientId
                tokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
                return token
            }
        }
    }
}
