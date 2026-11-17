package com.parker.tunes.data.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ShelfLayout { LIST, CARD, VIDEO, SQUARE, CIRCLE, TRACKS }

data class ShelfItem(
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val browseId: String?,
    val params: String?,
    val videoId: String?,
    val isVideo: Boolean
)

data class HomeShelf(
    val title: String,
    val strapline: String?,
    val layout: ShelfLayout,
    val items: List<ShelfItem>
) {
    val subtitle: String? get() = strapline
}

data class HomeChip(val name: String, val browseId: String? = null, val params: String? = null)

data class YtMusicHomePage(
    val shelves: List<HomeShelf>,
    val chips: List<HomeChip>,
    val continuation: String?
)

fun List<HomeShelf>.withoutVideos(): List<HomeShelf> = mapNotNull { shelf ->
    if (shelf.layout == ShelfLayout.VIDEO) return@mapNotNull null
    val kept = shelf.items.filterNot { it.isVideo }
    if (kept.isEmpty()) null else shelf.copy(items = kept)
}

class YtMusicHomeApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS).build()
) {
    suspend fun getHome(continuation: String? = null): YtMusicHomePage = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                val body = if (continuation.isNullOrBlank()) {
                    JSONObject().put("browseId", "FEmusic_home").put("context", context())
                } else {
                    JSONObject().put("continuation", continuation).put("context", context())
                }
                val request = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://music.youtube.com")
                    .header("Referer", "https://music.youtube.com/")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                return@withContext client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("YouTube Music browse failed: HTTP ${response.code}")
                    parse(JSONObject(response.body?.string().orEmpty()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastError = failure
                if (attempt < 2) delay(500L * (1L shl attempt))
            }
        }
        throw lastError ?: IllegalStateException("YouTube Music Home unavailable")
    }

    private fun context() = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB_REMIX")
        .put("clientVersion", "1.20241001.01.00")
        .put("hl", "en").put("gl", "US"))

    internal fun parseResponseForTests(json: String): YtMusicHomePage = parse(JSONObject(json))

    private fun parse(root: JSONObject): YtMusicHomePage {
        val tabs = root.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        val tab = tabs?.optJSONObject(0)?.path("tabRenderer", "content")
        val sections = tab?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: root.path("continuationContents", "sectionListContinuation")?.optJSONArray("contents")
            ?: JSONArray()
        val shelves = mutableListOf<HomeShelf>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val renderer = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
            val header = renderer.path("header", "musicCarouselShelfBasicHeaderRenderer")
            val title = header?.textAt("title")?.takeIf { it.isNotBlank() } ?: continue
            val strapline = header?.textAt("strapline")
            val rawItems = renderer.optJSONArray("contents") ?: JSONArray()
            val parsed = mutableListOf<ShelfItem>()
            var wideCount = 0
            for (j in 0 until rawItems.length()) {
                val itemRenderer = rawItems.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                val itemTitle = itemRenderer.textAt("title")?.takeIf { it.isNotBlank() } ?: continue
                val subtitle = itemRenderer.textAt("subtitle")
                val thumbnails = itemRenderer.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbnail = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))
                    ?.optString("url")?.takeIf { it.isNotBlank() }
                val endpoint = itemRenderer.path("navigationEndpoint")
                val browse = endpoint?.path("browseEndpoint")
                val watch = endpoint?.path("watchEndpoint")
                val browseId = browse?.optString("browseId")?.takeIf { it.isNotBlank() }
                val params = browse?.optString("params")?.takeIf { it.isNotBlank() }
                val videoId = watch?.optString("videoId")?.takeIf { it.isNotBlank() }
                val ratio = itemRenderer.optString("thumbnailRatio")
                if (ratio == "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_16_9") wideCount++
                parsed += ShelfItem(itemTitle, subtitle, thumbnail, browseId, params, videoId,
                    isVideo = (videoId != null && browseId == null) ||
                        ratio == "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_16_9")
            }
            val layout = when {
                parsed.isNotEmpty() && wideCount == parsed.size -> ShelfLayout.VIDEO
                parsed.any { it.isVideo } -> ShelfLayout.CARD
                else -> ShelfLayout.CARD
            }
            shelves += HomeShelf(title, strapline, layout, parsed)
        }
        val chipArray = root.optJSONObject("header")?.optJSONObject("chipCloudRenderer")?.optJSONArray("chips")
        val chips = buildList {
            if (chipArray != null) for (i in 0 until chipArray.length()) {
                val chip = chipArray.optJSONObject(i) ?: continue
                val text = chip.path("chipCloudChipRenderer")?.optString("text")?.takeIf { it.isNotBlank() } ?: continue
                val endpoint = chip.path("chipCloudChipRenderer", "navigationEndpoint", "browseEndpoint")
                add(HomeChip(text, endpoint?.optString("browseId")?.takeIf { it.isNotBlank() },
                    endpoint?.optString("params")?.takeIf { it.isNotBlank() }))
            }
        }
        val continuation = root.findContinuation()
        return YtMusicHomePage(shelves, chips, continuation)
    }
}

private fun JSONObject.path(vararg keys: String): JSONObject? {
    var current: JSONObject = this
    for (key in keys) current = current.optJSONObject(key) ?: return null
    return current
}
private fun JSONObject.textAt(key: String): String? {
    val runs = optJSONObject(key)?.optJSONArray("runs") ?: return null
    return runs.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
}
private fun JSONObject.findContinuation(): String? {
    val contents = optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
        ?.optJSONArray("continuations")
        ?: optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.path("tabRenderer", "content", "sectionListRenderer")
            ?.optJSONArray("continuations")
    return contents?.optJSONObject(0)?.path("nextContinuationData")
        ?.optString("continuation")?.takeIf { it.isNotBlank() }
}
