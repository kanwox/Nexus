package com.github.mihomo.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MihomoApiEncodingTest {

    @Before
    fun setUp() {
        ApiController.configureForTest()
        ApiController.configureForTest(9099, "test-secret")
    }

    @After
    fun tearDown() {
        ApiController.configureForTest()
    }

    @Test
    fun `buildEndpointUrl encodes spaces as percent-20 never plus`() {
        val url = MihomoApi.buildEndpointUrl("proxies", "Proxy Select")
        assertEquals("http://127.0.0.1:9099/proxies/Proxy%20Select", url.toString())
        assertFalse(url.encodedPath.contains("+"))
    }

    @Test
    fun `buildEndpointUrl keeps embedded slash, query, fragment, and plus in one path segment`() {
        val rawSegment = "HK + TW/Node #1?x=1"
        val url = MihomoApi.buildEndpointUrl("proxies", rawSegment)

        assertEquals(listOf("proxies", rawSegment), url.pathSegments)
        assertEquals(2, url.encodedPathSegments.size)
        assertNull(url.query)
        assertNull(url.fragment)

        val encoded = url.encodedPathSegments[1]
        assertTrue(encoded.contains("%20"))
        assertTrue(encoded.contains("%20+%20"))
        assertTrue(encoded.contains("%2F"))
        assertTrue(encoded.contains("%23"))
        assertTrue(encoded.contains("%3F"))
    }

    @Test
    fun `buildEndpointUrl preserves plain ASCII proxy names`() {
        val url = MihomoApi.buildEndpointUrl("proxies", "GLOBAL")
        assertEquals("http://127.0.0.1:9099/proxies/GLOBAL", url.toString())
    }

    @Test
    fun `buildEndpointUrl fails fast when controller is not ready`() {
        ApiController.configureForTest()
        assertThrows(IllegalStateException::class.java) {
            MihomoApi.buildEndpointUrl("version")
        }
    }
}
