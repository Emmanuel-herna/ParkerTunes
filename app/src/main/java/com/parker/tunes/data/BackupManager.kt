package com.parker.tunes.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** JSON backup/restore for ParkerTunes library and local preferences. */
class BackupManager(
    context: Context,
    private val dao: LibraryDao,
    private val preferences: android.content.SharedPreferences
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val playlists = dao.playlists().first()
        val favorites = dao.favorites().first()
        val history = dao.historySnapshot()
        val equalizer = dao.equalizerSettings().first()
        val playlistJson = JSONArray()
        val trackJson = JSONArray()
        playlists.forEach { playlist ->
            playlistJson.put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                .put("createdAt", playlist.createdAt))
            dao.playlistTracksSnapshot(playlist.id).forEach { track ->
                trackJson.put(JSONObject().put("playlistId", track.playlistId)
                    .put("songId", track.songId).put("position", track.position)
                    .put("title", track.title).put("artist", track.artist))
            }
        }
        val favoriteJson = JSONArray()
        favorites.forEach { favorite ->
            favoriteJson.put(JSONObject().put("songId", favorite.songId)
                .put("addedAt", favorite.addedAt).put("title", favorite.title)
                .put("artist", favorite.artist).put("coverUrl", favorite.coverUrl))
        }
        val historyJson = JSONArray()
        history.forEach { play ->
            historyJson.put(JSONObject().put("id", play.id).put("songId", play.songId)
                .put("playedAt", play.playedAt).put("title", play.title).put("artist", play.artist)
                .put("coverUrl", play.coverUrl).put("playDurationMs", play.playDurationMs))
        }
        val root = JSONObject()
            .put("format", "ParkerTunesBackup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("playlists", playlistJson)
            .put("playlistTracks", trackJson)
            .put("favorites", favoriteJson)
            .put("history", historyJson)
            .put("equalizer", equalizer?.let {
                JSONObject().put("preset", it.preset).put("bandLevels", it.bandLevels)
                    .put("bassEnabled", it.bassEnabled).put("bassStrength", it.bassStrength)
                    .put("virtualizerEnabled", it.virtualizerEnabled).put("virtualizerStrength", it.virtualizerStrength)
                    .put("loudnessEnabled", it.loudnessEnabled).put("loudnessGain", it.loudnessGain)
                    .put("monoEnabled", it.monoEnabled)
            })
            .put("preferences", JSONObject()
                .put("lightMode", preferences.getBoolean("light_mode", false))
                .put("refreshRate", preferences.getInt("preferred_refresh_rate", 120))
                .put("crossfadeSeconds", preferences.getInt("crossfade_seconds", 0))
                .put("smartAutoplay", preferences.getBoolean("smart_autoplay", true))
                .put("accentColor", preferences.getInt("accent_color", 0xFFB69CFF.toInt())))
        val output = resolver.openOutputStream(uri) ?: error("Could not open the backup destination.")
        output.bufferedWriter().use { it.write(root.toString(2)) }
    }

    suspend fun importFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: error("Could not open the backup file.")
        val root = JSONObject(input.bufferedReader().use { it.readText() })
        require(root.optString("format") == "ParkerTunesBackup") { "This is not a ParkerTunes backup." }
        require(root.optInt("version") in 1..1) { "This backup version is not supported." }
        var imported = 0

        val knownPlaylists = dao.playlists().first().map { it.id }.toMutableSet()
        val playlists = root.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlists.length()) {
            val item = playlists.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || !knownPlaylists.add(id)) continue
            dao.savePlaylist(PlaylistEntity(id, item.optString("name", "Imported playlist"),
                item.optLong("createdAt", System.currentTimeMillis())))
            imported++
        }

        val trackArray = root.optJSONArray("playlistTracks") ?: JSONArray()
        val knownTracks = mutableMapOf<String, MutableSet<String>>()
        knownPlaylists.forEach { id ->
            knownTracks[id] = dao.playlistTracksSnapshot(id).map { it.songId }.toMutableSet()
        }
        for (i in 0 until trackArray.length()) {
            val item = trackArray.optJSONObject(i) ?: continue
            val playlistId = item.optString("playlistId")
            val songId = item.optString("songId")
            if (playlistId !in knownPlaylists || songId.isBlank()) continue
            val seen = knownTracks.getOrPut(playlistId) { mutableSetOf() }
            if (!seen.add(songId)) continue
            dao.addTrack(PlaylistTrackEntity(playlistId, songId, item.optInt("position"),
                item.optString("title"), item.optString("artist")))
            imported++
        }

        val favoriteArray = root.optJSONArray("favorites") ?: JSONArray()
        for (i in 0 until favoriteArray.length()) {
            val item = favoriteArray.optJSONObject(i) ?: continue
            val songId = item.optString("songId")
            if (songId.isBlank() || dao.isFavorite(songId)) continue
            dao.addFavorite(FavoriteEntity(songId, item.optLong("addedAt", System.currentTimeMillis()),
                item.optString("title"), item.optString("artist"), item.optNullableString("coverUrl")))
            imported++
        }

        val historyArray = root.optJSONArray("history") ?: JSONArray()
        for (i in 0 until historyArray.length()) {
            val item = historyArray.optJSONObject(i) ?: continue
            val songId = item.optString("songId")
            if (songId.isBlank()) continue
            dao.restorePlayHistory(PlayHistoryEntity(
                id = item.optLong("id"), songId = songId,
                playedAt = item.optLong("playedAt", System.currentTimeMillis()),
                title = item.optString("title"), artist = item.optString("artist"),
                coverUrl = item.optNullableString("coverUrl"),
                playDurationMs = item.optLong("playDurationMs")
            ))
        }

        root.optJSONObject("equalizer")?.let { eq ->
            dao.saveEqualizerSettings(EqualizerSettingsEntity(
                preset = eq.optInt("preset", -1), bandLevels = eq.optString("bandLevels"),
                bassEnabled = eq.optBoolean("bassEnabled"), bassStrength = eq.optInt("bassStrength"),
                virtualizerEnabled = eq.optBoolean("virtualizerEnabled"), virtualizerStrength = eq.optInt("virtualizerStrength"),
                loudnessEnabled = eq.optBoolean("loudnessEnabled"), loudnessGain = eq.optInt("loudnessGain"),
                monoEnabled = eq.optBoolean("monoEnabled")
            ))
        }
        root.optJSONObject("preferences")?.let { prefs ->
            preferences.edit()
                .putBoolean("light_mode", prefs.optBoolean("lightMode", false))
                .putInt("preferred_refresh_rate", prefs.optInt("refreshRate", 120))
                .putInt("crossfade_seconds", prefs.optInt("crossfadeSeconds", 0))
                .putBoolean("smart_autoplay", prefs.optBoolean("smartAutoplay", true))
                .putInt("accent_color", prefs.optInt("accentColor", 0xFFB69CFF.toInt()))
                .apply()
        }
        imported
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)
}
