package com.github.mihomo.android.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageCacheTest {

    @Before
    fun setUp() {
        ImageCache.clearNegativeCache()
    }

    @After
    fun tearDown() {
        ImageCache.clearNegativeCache()
    }

    @Test
    fun `negative cache hits before ttl and expires at ttl`() {
        val url = "https://example.com/icon.png"
        val failedAt = 1_000_000L
        val fiveMinutesMs = 5 * 60 * 1000L

        ImageCache.markFailed(url, nowMs = failedAt)

        assertTrue(ImageCache.isNegativelyCached(url, nowMs = failedAt))
        assertTrue(ImageCache.isNegativelyCached(url, nowMs = failedAt + fiveMinutesMs - 1L))
        assertFalse(ImageCache.isNegativelyCached(url, nowMs = failedAt + fiveMinutesMs))
        // Expired entry was removed when TTL elapsed.
        assertFalse(ImageCache.isNegativelyCached(url, nowMs = failedAt + 1_000L))
    }

    @Test
    fun `looksLikeNonBitmapText rejects svg xml html json and blank payloads`() {
        val rejectedSamples = listOf(
            ByteArray(0),
            "   \n\t\r  ".toByteArray(Charsets.UTF_8),
            "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray(Charsets.UTF_8),
            "  \n<?xml version=\"1.0\"?><svg/>".toByteArray(Charsets.UTF_8),
            "\t<!DOCTYPE html><html></html>".toByteArray(Charsets.UTF_8),
            "<HTML><body>404</body></HTML>".toByteArray(Charsets.UTF_8),
            "{\"error\":\"not found\"}".toByteArray(Charsets.UTF_8),
            "  [\"not\",\"an\",\"image\"]".toByteArray(Charsets.UTF_8)
        )

        for (sample in rejectedSamples) {
            assertTrue(ImageCache.looksLikeNonBitmapText(sample))
        }
    }

    @Test
    fun `looksLikeNonBitmapText allows binary image signatures`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webpHeader = "RIFF\u0024\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)
        val gifHeader = "GIF89a".toByteArray(Charsets.US_ASCII)

        for (bitmapBytes in listOf(pngHeader, jpegHeader, webpHeader, gifHeader)) {
            assertFalse(ImageCache.looksLikeNonBitmapText(bitmapBytes))
        }
    }
}
