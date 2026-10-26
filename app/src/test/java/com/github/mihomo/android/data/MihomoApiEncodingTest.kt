package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REST path segments must not use `+` for spaces: the controller's router treats `+` as a
 * literal character, so any node/provider name containing a space failed to resolve.
 */
class MihomoApiEncodingTest {

    @Test
    fun `spaces in path segments use percent 20`() {
        val encoded = encodePathSegment("🇭🇰 香港 01")
        assertFalse("URLEncoder's '+' breaks path lookups", encoded.contains('+'))
        assertTrue(encoded.contains("%20"))
    }

    @Test
    fun `slashes inside a name are encoded, not left as separators`() {
        assertEquals("a%2Fb%20c", encodePathSegment("a/b c"))
    }

    @Test
    fun `plain ascii names are unchanged`() {
        assertEquals("DIRECT", encodePathSegment("DIRECT"))
        assertEquals("node-01", encodePathSegment("node-01"))
    }
}
