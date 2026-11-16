package com.parker.tunes.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.parkerSettingsDataStore by preferencesDataStore(name = "parker_tunes_settings")

/** Single source of truth for user-facing app settings. Every preference is observable and updated immediately. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.parkerSettingsDataStore

    private fun bool(key: Preferences.Key<Boolean>, default: Boolean = false): Flow<Boolean> =
        store.data.map { it[key] ?: default }
    private fun int(key: Preferences.Key<Int>, default: Int): Flow<Int> =
        store.data.map { it[key] ?: default }
    private fun string(key: Preferences.Key<String>, default: String): Flow<String> =
        store.data.map { it[key] ?: default }

    val automix: Flow<Boolean> = bool(Keys.automix)
    val crossfadeSeconds: Flow<Int> = int(Keys.crossfade, 0)
    val gaplessPlayback: Flow<Boolean> = bool(Keys.gapless, true)
    val autoplaySimilar: Flow<Boolean> = bool(Keys.autoplay, true)
    val skipSilence: Flow<Boolean> = bool(Keys.skipSilence)
    val audioQuality: Flow<String> = string(Keys.quality, "Auto")
    val resumePlayback: Flow<Boolean> = bool(Keys.resume, true)
    val bassBoost: Flow<Boolean> = bool(Keys.bass)
    val virtualizer: Flow<Boolean> = bool(Keys.virtualizer)
    val soundCheck: Flow<Boolean> = bool(Keys.soundCheck)
    val syncedLyrics: Flow<Boolean> = bool(Keys.syncedLyrics, true)
    val lyricsSources: Flow<String> = string(Keys.lyricsSources, "LRCLIB,BetterLyrics,KuGou")
    val motionArtwork: Flow<Boolean> = bool(Keys.motionArtwork)
    val showQueueButton: Flow<Boolean> = bool(Keys.queueButton, true)
    val statsForNerds: Flow<Boolean> = bool(Keys.stats)
    val accentColor: Flow<String> = string(Keys.accent, "purple")
    val amoledBlack: Flow<Boolean> = bool(Keys.amoled, true)
    val playerBackground: Flow<String> = string(Keys.playerBackground, "Artwork colors")
    val interfaceSize: Flow<Int> = int(Keys.interfaceSize, 100)
    val reduceMotion: Flow<Boolean> = bool(Keys.reduceMotion)
    val reduceTransparency: Flow<Boolean> = bool(Keys.reduceTransparency)
    val lockscreen: Flow<Boolean> = bool(Keys.lockscreen, true)
    val showAlbumArt: Flow<Boolean> = bool(Keys.albumArt, true)
    val expandableControls: Flow<Boolean> = bool(Keys.expandControls, true)
    val lastFmEnabled: Flow<Boolean> = bool(Keys.lastFmEnabled)
    val listenBrainzEnabled: Flow<Boolean> = bool(Keys.listenBrainzEnabled)
    val lastFmApiKey: Flow<String> = string(Keys.lastFmKey, "")
    val listenBrainzToken: Flow<String> = string(Keys.listenBrainzToken, "")

    suspend fun setAutomix(v:Boolean)=put(Keys.automix,v)
    suspend fun setCrossfadeSeconds(v:Int)=put(Keys.crossfade,v.coerceIn(0,12))
    suspend fun setGaplessPlayback(v:Boolean)=put(Keys.gapless,v)
    suspend fun setAutoplaySimilar(v:Boolean)=put(Keys.autoplay,v)
    suspend fun setSkipSilence(v:Boolean)=put(Keys.skipSilence,v)
    suspend fun setAudioQuality(v:String)=put(Keys.quality,v)
    suspend fun setResumePlayback(v:Boolean)=put(Keys.resume,v)
    suspend fun setBassBoost(v:Boolean)=put(Keys.bass,v)
    suspend fun setVirtualizer(v:Boolean)=put(Keys.virtualizer,v)
    suspend fun setSoundCheck(v:Boolean)=put(Keys.soundCheck,v)
    suspend fun setSyncedLyrics(v:Boolean)=put(Keys.syncedLyrics,v)
    suspend fun setLyricsSources(v:String)=put(Keys.lyricsSources,v)
    suspend fun setMotionArtwork(v:Boolean)=put(Keys.motionArtwork,v)
    suspend fun setShowQueueButton(v:Boolean)=put(Keys.queueButton,v)
    suspend fun setStatsForNerds(v:Boolean)=put(Keys.stats,v)
    suspend fun setAccentColor(v:String)=put(Keys.accent,v)
    suspend fun setAmoledBlack(v:Boolean)=put(Keys.amoled,v)
    suspend fun setPlayerBackground(v:String)=put(Keys.playerBackground,v)
    suspend fun setInterfaceSize(v:Int)=put(Keys.interfaceSize,v.coerceIn(90,120))
    suspend fun setReduceMotion(v:Boolean)=put(Keys.reduceMotion,v)
    suspend fun setReduceTransparency(v:Boolean)=put(Keys.reduceTransparency,v)
    suspend fun setLockscreen(v:Boolean)=put(Keys.lockscreen,v)
    suspend fun setShowAlbumArt(v:Boolean)=put(Keys.albumArt,v)
    suspend fun setExpandableControls(v:Boolean)=put(Keys.expandControls,v)
    suspend fun setLastFmEnabled(v:Boolean)=put(Keys.lastFmEnabled,v)
    suspend fun setListenBrainzEnabled(v:Boolean)=put(Keys.listenBrainzEnabled,v)
    suspend fun setLastFmApiKey(v:String)=put(Keys.lastFmKey,v)
    suspend fun setListenBrainzToken(v:String)=put(Keys.listenBrainzToken,v)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) { store.edit { it[key] = value } }

    private object Keys {
        val automix=booleanPreferencesKey("automix")
        val crossfade=intPreferencesKey("crossfade_seconds")
        val gapless=booleanPreferencesKey("gapless_playback")
        val autoplay=booleanPreferencesKey("autoplay_similar")
        val skipSilence=booleanPreferencesKey("skip_silence")
        val quality=stringPreferencesKey("audio_quality")
        val resume=booleanPreferencesKey("resume_playback")
        val bass=booleanPreferencesKey("bass_boost")
        val virtualizer=booleanPreferencesKey("virtualizer")
        val soundCheck=booleanPreferencesKey("sound_check")
        val syncedLyrics=booleanPreferencesKey("synced_lyrics")
        val lyricsSources=stringPreferencesKey("lyrics_sources")
        val motionArtwork=booleanPreferencesKey("motion_artwork")
        val queueButton=booleanPreferencesKey("show_queue_button")
        val stats=booleanPreferencesKey("stats_for_nerds")
        val accent=stringPreferencesKey("accent_color")
        val amoled=booleanPreferencesKey("amoled_black")
        val playerBackground=stringPreferencesKey("player_background")
        val interfaceSize=intPreferencesKey("interface_size")
        val reduceMotion=booleanPreferencesKey("reduce_motion")
        val reduceTransparency=booleanPreferencesKey("reduce_transparency")
        val lockscreen=booleanPreferencesKey("lockscreen")
        val albumArt=booleanPreferencesKey("show_album_art")
        val expandControls=booleanPreferencesKey("expandable_controls")
        val lastFmEnabled=booleanPreferencesKey("lastfm_enabled")
        val listenBrainzEnabled=booleanPreferencesKey("listenbrainz_enabled")
        val lastFmKey=stringPreferencesKey("lastfm_api_key")
        val listenBrainzToken=stringPreferencesKey("listenbrainz_token")
    }
}
