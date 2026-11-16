package com.parker.tunes.data.sources

import com.parker.tunes.model.Song

data class AddonSetting(
    val key: String,
    val defaultValue: String = "",
    val options: List<AddonSettingOption> = emptyList()
)
data class AddonSettingOption(val value: String, val label: String = value, val maxKbps: Int? = null)
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val resources: Set<String>,
    val settings: List<AddonSetting> = emptyList(),
    val bitchordAuth: Boolean = false
)
data class StreamRequest(val kind: Kind = Kind.Best, val maxKbps: Int? = null) {
    enum class Kind { Best, Lossless, Capped }
    companion object {
        val Best = StreamRequest(Kind.Best)
        val Lossless = StreamRequest(Kind.Lossless)
        fun Capped(maxKbps: Int) = StreamRequest(Kind.Capped, maxKbps)
    }
}
data class AudioFormat(
    val codec: String?,
    val isLossless: Boolean,
    val isDolbyAtmos: Boolean,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val mimeType: String?
)
data class AddonStream(
    val url: String,
    val format: AudioFormat,
    val durationMs: Long?
)
data class AddonSearchResult(val tracks: List<Song>, val albums: List<org.json.JSONObject>, val artists: List<org.json.JSONObject>)
