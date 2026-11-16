package com.parker.tunes.data.sources

import android.net.Uri
import com.parker.tunes.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class AddonClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()) {
    private data class Cached<T>(val value: T, val at: Long)
    private val streams = object : LinkedHashMap<String, Cached<AddonStream>>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<AddonStream>>?) = size > 64
    }
    private val rowStreams = LinkedHashMap<String, String>()

    suspend fun manifest(baseUrl: String): AddonManifest? = withContext(Dispatchers.IO) {
        val base = SourceConfig.normalizeBase(baseUrl)
        val response = execute("$base/manifest.json") ?: return@withContext null
        if (response.code !in 200..299) return@withContext null
        parseManifest(response.body ?: return@withContext null)
    }

    suspend fun health(baseUrl: String): SourceHealth = withContext(Dispatchers.IO) {
        val base = SourceConfig.normalizeBase(baseUrl)
        if (base.isBlank()) return@withContext SourceHealth.Rejected("Enter an addon URL")
        val response = try { execute("$base/manifest.json") } catch (e: Exception) {
            return@withContext SourceHealth.Unreachable(e.message ?: "Network unavailable")
        }
        if (response == null) {
            // Some compatible servers expose /search without a manifest.
            val probe = runCatching { execute("$base/search?q=parker-tunes-health&limit=1") }.getOrNull()
            if (probe?.code in 200..299) return@withContext SourceHealth.Ok("Compatible addon")
            return@withContext SourceHealth.Unreachable("Could not reach addon")
        }
        when {
            response.code == 404 -> SourceHealth.Rejected("No addon manifest at this URL")
            response.code >= 500 -> SourceHealth.Unreachable("Server error ${response.code}")
            response.code !in 200..299 -> SourceHealth.Rejected("HTTP ${response.code}")
            else -> {
                val manifest = runCatching { parseManifest(response.body.orEmpty()) }.getOrNull()
                    ?: return@withContext SourceHealth.Rejected("Invalid manifest")
                if ("search" !in manifest.resources) SourceHealth.Rejected("needs search")
                else if (manifest.bitchordAuth) SourceHealth.Rejected("Requires BitChord authentication")
                else SourceHealth.Ok(listOf(manifest.name, manifest.version).filter { it.isNotBlank() }.joinToString(" v"))
            }
        }
    }

    suspend fun search(
        sourceId: String, baseUrl: String, query: String, limit: Int = 25,
        request: StreamRequest = StreamRequest.Best, stereoOnly: Boolean = false,
        settings: Map<String, String> = emptyMap()
    ): List<Song> = withContext(Dispatchers.IO) {
        val base = SourceConfig.normalizeBase(baseUrl)
        val manifest = runCatching { manifest(base) }.getOrNull()
        val quality = negotiateQuality(manifest, request, settings["quality"])
        val builder = Uri.parse("$base/search").buildUpon()
            .appendQueryParameter("q", query).appendQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .appendQueryParameter("quality", quality)
        if (settings["atmos"] == "auto") builder.appendQueryParameter("atmos", "auto")
        settings.filterKeys { it !in setOf("quality", "atmos") }.forEach { (k,v) -> builder.appendQueryParameter(k,v) }
        val response = requestWithRetry(builder.build().toString()) ?: return@withContext emptyList()
        if (response.code !in 200..299) return@withContext emptyList()
        val payload = runCatching { JSONObject(response.body.orEmpty()) }.getOrNull() ?: return@withContext emptyList()
        val tracks = payload.optJSONArray("tracks") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until minOf(tracks.length(), limit.coerceAtLeast(1))) {
                val row = tracks.optJSONObject(i) ?: continue
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = row.optString("title").takeIf { it.isNotBlank() } ?: continue
                val streamUrl = row.optString("streamURL").takeIf { it.isNotBlank() }
                if (streamUrl != null) synchronized(rowStreams) { rowStreams["src:$sourceId::$id"] = streamUrl }
                add(Song(
                    id = "src:$sourceId::$id",
                    title = title,
                    artist = row.optString("artist").ifBlank { "Unknown artist" },
                    coverUrl = row.optString("thumbnailUrl").ifBlank { row.optString("albumArtworkURL") }.ifBlank { null },
                    durationSec = row.optDouble("durationSec", row.optDouble("duration", 0.0)).toLong().coerceAtLeast(0),
                    album = row.optString("albumName").ifBlank { null }
                ))
            }
        }
    }

    suspend fun stream(
        sourceId: String, baseUrl: String, trackId: String, request: StreamRequest = StreamRequest.Best,
        stereoOnly: Boolean = false, settings: Map<String, String> = emptyMap()
    ): AddonStream? = withContext(Dispatchers.IO) {
        val cacheKey = "$sourceId::$trackId::${request.kind}::${request.maxKbps}"
        synchronized(streams) {
            streams[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < 600_000L }?.value
        }?.let { return@withContext it }
        val manifest = runCatching { manifest(baseUrl) }.getOrNull()
        val quality = negotiateQuality(manifest, request, settings["quality"])
        val encodedId = URLEncoder.encode(trackId, "UTF-8").replace("+", "%20")
        val builder = Uri.parse("${SourceConfig.normalizeBase(baseUrl)}/stream/$encodedId")
            .buildUpon().appendQueryParameter("quality", quality)
        if (settings["atmos"] == "auto") builder.appendQueryParameter("atmos", "auto")
        settings.filterKeys { it !in setOf("quality", "atmos") }.forEach { (k,v) -> builder.appendQueryParameter(k,v) }
        var response = runCatching { execute(builder.build().toString()) }.getOrNull()
        if (response?.code == 404) {
            val fallback = synchronized(rowStreams) { rowStreams["src:$sourceId::$trackId"] }
            if (!fallback.isNullOrBlank() && isAbsoluteHttp(fallback)) {
                return@withContext AddonStream(fallback, AudioFormat(null,false,false,null,null,null), null)
            }
        }
        if (response == null || response.code !in 200..299) return@withContext null
        val json = runCatching { JSONObject(response.body.orEmpty()) }.getOrNull() ?: return@withContext null
        if (json.has("encrypted") && json.optBoolean("encrypted", true)) return@withContext null
        val url = json.optString("url")
        if (!isAbsoluteHttp(url)) return@withContext null
        val qualityText = json.optString("quality")
        val codec = json.optString("codec").ifBlank {
            val fmt = json.optString("format")
            if (fmt.lowercase() in setOf("mp3","flac","aac","alac","wav","opus","eac3-joc","eac3_joc")) fmt else
                url.substringBefore('?').substringAfterLast('.', "").takeIf { it.isNotBlank() }
        }.lowercase().ifBlank { null }
        val modes = json.optJSONArray("audioModes")
        val atmos = codec in setOf("eac3-joc","eac3_joc") ||
            (0 until (modes?.length() ?: 0)).any { modes?.optString(it)?.contains("DOLBY_ATMOS", true) == true }
        val manifestType = json.optString("manifest").lowercase()
        val mime = when (manifestType) { "dash" -> "application/dash+xml"; "hls" -> "application/x-mpegURL"; else -> null }
        val bitDepth = json.optInt("bitDepth").takeIf { it > 0 } ?: Regex("(\\d+)-bit", RegexOption.IGNORE_CASE).find(qualityText)?.groupValues?.get(1)?.toIntOrNull()
        val sampleRate = json.optInt("sampleRate").takeIf { it > 0 } ?: Regex("(\\d+)\\s*kHz", RegexOption.IGNORE_CASE).find(qualityText)?.groupValues?.get(1)?.toIntOrNull()?.times(1000)
        val result = AddonStream(url, AudioFormat(codec, codec in setOf("flac","alac","wav") || qualityText.contains("lossless",true), atmos, bitDepth, sampleRate, mime), json.optLong("duration",0L).takeIf { it > 0 }?.times(1000))
        synchronized(streams) { streams[cacheKey] = Cached(result, System.currentTimeMillis()) }
        result
    }

    private fun negotiateQuality(manifest: AddonManifest?, request: StreamRequest, picked: String?): String {
        val setting = manifest?.settings?.firstOrNull { it.key == "quality" }
        val options = setting?.options.orEmpty()
        val available = options.map { it.value }
        if (request.kind == StreamRequest.Kind.Capped && request.maxKbps != null) {
            return options.filter { (it.maxKbps ?: Int.MAX_VALUE) <= request.maxKbps }
                .minByOrNull { it.maxKbps ?: Int.MAX_VALUE }?.value ?: options.firstOrNull()?.value ?: "LOSSLESS"
        }
        if (!picked.isNullOrBlank() && (available.isEmpty() || picked in available)) return picked
        if (request.kind == StreamRequest.Kind.Lossless) return available.firstOrNull { it.contains("LOSSLESS",true) || it.contains("FLAC",true) } ?: "LOSSLESS"
        return setting?.defaultValue?.takeIf { it.isNotBlank() && (available.isEmpty() || it in available) }
            ?: options.firstOrNull()?.value ?: "LOSSLESS"
    }

    private data class HttpResult(val code: Int, val body: String?)
    private fun execute(url: String): HttpResult? {
        val req = Request.Builder().url(url).header("Accept","application/json").get().build()
        http.newCall(req).execute().use { return HttpResult(it.code, it.body?.string()) }
    }
    private suspend fun requestWithRetry(url: String): HttpResult? {
        val first = runCatching { execute(url) }.getOrNull() ?: return null
        if (first.code != 429) return first
        delay(1_000L)
        return runCatching { execute(url) }.getOrNull()
    }
    private fun parseManifest(raw: String): AddonManifest {
        val j = JSONObject(raw)
        val resources = j.optJSONArray("resources")
        val resourceSet = (0 until (resources?.length() ?: 0)).map { resources!!.optString(it) }.toSet()
        val settingsJson = j.optJSONArray("settings")
        val settings = (0 until (settingsJson?.length() ?: 0)).mapNotNull { i ->
            val s = settingsJson!!.optJSONObject(i) ?: return@mapNotNull null
            val opts = s.optJSONArray("options")
            AddonSetting(s.optString("key"), s.optString("default"), (0 until (opts?.length() ?: 0)).mapNotNull { n ->
                val o = opts!!.optJSONObject(n)
                if (o != null) AddonSettingOption(o.optString("value"), o.optString("label").ifBlank { o.optString("value") }, o.optInt("maxKbps").takeIf { it > 0 })
                else opts.optString(n).takeIf { it.isNotBlank() }?.let { AddonSettingOption(it) }
            })
        }
        return AddonManifest(j.optString("id"), j.optString("name"), j.optString("version"), resourceSet, settings, j.optBoolean("bitchordAuth"))
    }
    private fun isAbsoluteHttp(url: String) = runCatching { java.net.URI(url).let { it.isAbsolute && it.scheme in listOf("https","http") && !it.host.isNullOrBlank() } }.getOrDefault(false)
}
