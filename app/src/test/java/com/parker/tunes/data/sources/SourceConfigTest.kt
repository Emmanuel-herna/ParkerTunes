package com.parker.tunes.data.sources

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceConfigTest {
    @Test fun normalizeKeepsTokenPathAndStripsManifest() {
        assertEquals("https://example.org/abc123", SourceConfig.normalizeBase(" example.org/abc123/manifest.json/ "))
    }

    @Test fun normalizeAcceptsManifestCaseInsensitively() {
        assertEquals("https://example.org/abc123", SourceConfig.normalizeBase("https://example.org/abc123/MANIFEST.JSON"))
    }

    @Test fun redactHidesTokenPath() {
        assertEquals("https://example.org/***", SourceConfig.redact("https://example.org/abc123/"))
    }

    @Test fun redactKeepsHostAndPrefix() {
        assertEquals("https://example.org/addons/***", SourceConfig.redact("https://example.org/addons/private-token"))
    }
}
