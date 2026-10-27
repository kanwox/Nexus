package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests for the subscription-header parsing and the subscription-payload validation.
 */
class ConfigManagerParsingTest {

    // ------------------------------------------------------------------
    // split("=", limit = 2)
    // ------------------------------------------------------------------

    @Test
    fun `a query value containing an equals sign is parsed`() {
        // The value is base64-ish and contains "=", which a plain split("=") dropped.
        val name = ConfigManager.extractProfileName(
            name = null,
            response = null,
            body = "",
            url = "https://example.com/sub?token=abc&name=HK%3D%3D"
        )
        assertEquals("HK==", name)
    }

    @Test
    fun `a subscription header value containing an equals sign is parsed`() {
        val info = ConfigManager.parseSubscriptionUserInfo("upload=100; download=200; total=1000; expire=999")
        assertNotNull(info)
        assertEquals(100L, info!!.upload)
        assertEquals(200L, info.download)
        assertEquals(1000L, info.total)
        assertEquals(999L, info.expire)
    }

    // ------------------------------------------------------------------
    // parseSubscriptionUserInfo: garbage is not zero
    // ------------------------------------------------------------------

    @Test
    fun `an unparsable value invalidates the whole header`() {
        // "total=abc" used to be read as 0, which silently reported a wrong quota.
        assertNull(ConfigManager.parseSubscriptionUserInfo("upload=100; total=abc"))
        assertNull(ConfigManager.parseSubscriptionUserInfo("total=not-a-number"))
        assertNull(ConfigManager.parseSubscriptionUserInfo("expire=soon"))
    }

    @Test
    fun `a header without a quota or expiry reports nothing`() {
        // Only upload/download: there is nothing meaningful to show.
        assertNull(ConfigManager.parseSubscriptionUserInfo("upload=100; download=200"))
    }

    @Test
    fun `a valid header is still parsed`() {
        val info = ConfigManager.parseSubscriptionUserInfo(
            "upload=1048576; download=20971520; total=107374182400; expire=1735689600"
        )
        assertNotNull(info)
        assertEquals(1048576L, info!!.upload)
        assertEquals(20971520L, info.download)
        assertEquals(107374182400L, info.total)
        assertEquals(1735689600L, info.expire)
    }

    @Test
    fun `an unknown key is ignored but does not invalidate the header`() {
        val info = ConfigManager.parseSubscriptionUserInfo("total=1000; futurekey=xyz")
        assertNotNull(info)
        assertEquals(1000L, info!!.total)
    }

    // ------------------------------------------------------------------
    // isValidSubscriptionPayload: base64 node lists are not configs
    // ------------------------------------------------------------------

    @Test
    fun `a base64 encoded node list is rejected`() {
        // The app cannot turn a node list into a config: the YAML parser would see a
        // plain string and the core would fail to load it.
        val nodeList = "vmess://eyJhZGQiOiIxLjIuMy40In0=\nvless://uuid@1.2.3.4:443\n"
        val encoded = Base64.getEncoder().encodeToString(nodeList.toByteArray(Charsets.UTF_8))

        assertFalse(ConfigManager.isValidSubscriptionPayload(encoded))
    }

    @Test
    fun `a base64 encoded clash config is accepted`() {
        val config = "proxies:\n  - name: node\n    type: ss\nrules:\n  - MATCH,DIRECT\n"
        val encoded = Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8))

        assertTrue(ConfigManager.isValidSubscriptionPayload(encoded))
    }

    @Test
    fun `a plain clash config is accepted`() {
        assertTrue(
            ConfigManager.isValidSubscriptionPayload(
                """
                proxies:
                  - name: node
                    type: ss
                rules:
                  - MATCH,DIRECT
                """.trimIndent()
            )
        )
    }

    @Test
    fun `an html error page is rejected`() {
        assertFalse(ConfigManager.isValidSubscriptionPayload("<!DOCTYPE html><html><body>Error</body></html>"))
    }
}
