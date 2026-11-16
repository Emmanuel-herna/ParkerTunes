package com.parker.tunes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackSettingsDataStore by preferencesDataStore(name = "parker_tunes_playback")

data class PlaybackSettings(
    val crossfadeSeconds: Int = 0,
    val smartAutoplay: Boolean = true,
    val gaplessPlayback: Boolean = true
)

/** Durable playback preferences backed by Preferences DataStore. */
class PlaybackSettingsStore(private val context: Context) {
    val settings: Flow<PlaybackSettings> = context.playbackSettingsDataStore.data.map { prefs ->
        PlaybackSettings(
            crossfadeSeconds = (prefs[CROSSFADE_SECONDS] ?: 0).coerceIn(0, 12),
            smartAutoplay = prefs[SMART_AUTOPLAY] ?: true,
            gaplessPlayback = prefs[GAPLESS_PLAYBACK] ?: true
        )
    }

    suspend fun setCrossfadeSeconds(value: Int) {
        context.playbackSettingsDataStore.edit { it[CROSSFADE_SECONDS] = value.coerceIn(0, 12) }
    }

    suspend fun setSmartAutoplay(enabled: Boolean) {
        context.playbackSettingsDataStore.edit { it[SMART_AUTOPLAY] = enabled }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        context.playbackSettingsDataStore.edit { it[GAPLESS_PLAYBACK] = enabled }
    }

    private companion object {
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val SMART_AUTOPLAY = booleanPreferencesKey("smart_autoplay")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
    }
}
