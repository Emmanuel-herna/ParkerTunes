package com.parker.tunes.data.sources

object SourceConfig {
    fun normalizeBase(url: String): String {
        var value = url.trim().trimEnd('/')
        if (value.endsWith("/manifest.json", ignoreCase = true)) {
            value = value.dropLast("/manifest.json".length).trimEnd('/')
        }
        if (value.isBlank()) return ""
        if (!value.contains("://")) value = "https://$value"
        return value
    }

    /** Hides path tokens while retaining the host and any non-token path prefix. */
    fun redact(url: String): String {
        val normalized = normalizeBase(url)
        val parsed = runCatching { java.net.URI(normalized) }.getOrNull() ?: return "***"
        val segments = parsed.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val safePath = when {
            segments.isEmpty() -> ""
            segments.size == 1 -> "/***"
            else -> "/" + segments.dropLast(1).joinToString("/") + "/***"
        }
        return "${parsed.scheme}://${parsed.rawAuthority}$safePath"
    }
}
