package com.parker.tunes.data

import android.content.Context
import com.parker.tunes.data.sources.AddonClient
import com.parker.tunes.data.sources.SourceConfig
import com.parker.tunes.data.sources.StreamRequest
import com.parker.tunes.model.Artist
import com.parker.tunes.model.ArtistProfile
import com.parker.tunes.model.SearchResult
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** Combines YouTube Music with enabled, user-configured compatible addon servers. */
class AddonMusicApi(
    context: Context,
    private val youtube: MusicApi = NewPipeMusicApi()
) : MusicApi {
    private val appContext = context.applicationContext
    private val registry by lazy { LibraryDatabase.get(appContext).sourceRegistryDao() }
    private val client = AddonClient()

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val baseResults = youtube.search(query)
        val sources = runCatching { registry.enabledSnapshot() }.getOrDefault(emptyList())
        if (sources.isEmpty()) return@withContext baseResults
        val extra = sources.flatMap { source ->
            runCatching {
                val settings = registry.settings(source.id).associate { it.key to it.value }
                client.search(source.id, source.baseUrl, query, 30, StreamRequest.Best, false, settings)
            }.getOrDefault(emptyList())
        }
        baseResults.copy(songs = (baseResults.songs + extra).distinctBy { it.id })
    }

    override suspend fun searchMore(query: String): List<Song> = youtube.searchMore(query)
    override suspend fun getRelatedSongs(songId: String): List<Song> =
        if (songId.startsWith("src:")) emptyList() else youtube.getRelatedSongs(songId)
    override suspend fun getMoodSongs(moodQuery: String): List<Song> = youtube.getMoodSongs(moodQuery)
    override suspend fun getTrending(): List<Song> = youtube.getTrending()
    override suspend fun getArtistProfile(artist: Artist): ArtistProfile = youtube.getArtistProfile(artist)
    override suspend fun getAlbumTracks(albumId: String): List<Song> = youtube.getAlbumTracks(albumId)

    override suspend fun getStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        if (!songId.startsWith("src:")) return@withContext youtube.getStreamUrl(songId)
        val packed = songId.removePrefix("src:")
        val separator = packed.indexOf("::")
        if (separator <= 0 || separator == packed.lastIndex) return@withContext ""
        val sourceId = packed.substring(0, separator)
        val trackId = packed.substring(separator + 2)
        val source = runCatching { registry.get(sourceId) }.getOrNull() ?: return@withContext ""
        if (!source.enabled) return@withContext ""
        val settings = runCatching { registry.settings(sourceId).associate { it.key to it.value } }.getOrDefault(emptyMap())
        client.stream(sourceId, source.baseUrl, trackId, StreamRequest.Best, false, settings)?.url.orEmpty()
    }

    override suspend fun getLyrics(title: String, artist: String): Lyrics? =
        youtube.getLyrics(title, artist)
    override suspend fun getLyrics(songId: String): String? =
        if (songId.startsWith("src:")) null else youtube.getLyrics(songId)
    override suspend fun getCoverArt(songId: String): String? =
        if (songId.startsWith("src:")) null else youtube.getCoverArt(songId)

    suspend fun addAddon(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = SourceConfig.normalizeBase(rawUrl)
            val health = client.health(base)
            val ok = health as? com.parker.tunes.data.sources.SourceHealth.Ok
                ?: error(when (health) {
                    is com.parker.tunes.data.sources.SourceHealth.Rejected -> health.reason
                    is com.parker.tunes.data.sources.SourceHealth.Unreachable -> health.reason
                    else -> "Addon could not be verified"
                })
            val manifest = client.manifest(base) ?: error("Addon manifest is missing")
            require(manifest.id.isNotBlank()) { "Addon manifest has no id" }
            registry.insert(AddonEntity(
                id = manifest.id, baseUrl = base, name = manifest.name.ifBlank { manifest.id },
                version = manifest.version, bitchordAuth = manifest.bitchordAuth,
                lastSeenAt = System.currentTimeMillis(), enabled = true
            ))
            manifest.settings.forEach { setting ->
                if (setting.defaultValue.isNotBlank()) registry.updateSettings(listOf(
                    AddonSettingsEntity(manifest.id, setting.key, setting.defaultValue)
                ))
            }
            ok.detail
        }
    }

    suspend fun removeAddon(id: String) = withContext(Dispatchers.IO) { registry.delete(id) }
    fun addons() = registry.list()
}
