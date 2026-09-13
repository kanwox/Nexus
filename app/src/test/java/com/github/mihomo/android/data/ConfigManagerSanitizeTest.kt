package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the protected top-level key filter. A subscription must not be able to
 * reintroduce runtime-owned keys, including through quoted keys that defeat text matching.
 */
class ConfigManagerSanitizeTest {

    private fun sanitized(raw: String) = ConfigManager.sanitizeUserConfig(raw)

    @Test
    fun `removes plain protected keys`() {
        val out = sanitized(
            """
            external-controller: 0.0.0.0:9090
            secret: "hunter2"
            allow-lan: true
            dns:
              enable: true
            proxies: []
            """.trimIndent()
        )
        assertFalse(out.contains("external-controller"))
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("allow-lan"))
        assertTrue(out.contains("proxies"))
    }

    @Test
    fun `removes quoted protected keys`() {
        val out = sanitized(
            """
            "external-controller": 0.0.0.0:9090
            'allow-lan': true
            proxies: []
            """.trimIndent()
        )
        assertFalse(out.contains("external-controller"))
        assertFalse(out.contains("allow-lan"))
        assertTrue(out.contains("proxies"))
    }

    @Test
    fun `keeps user owned keys intact`() {
        val out = sanitized(
            """
            proxies:
              - name: node-a
                type: ss
            proxy-groups:
              - name: PROXY
                type: select
            rules:
              - MATCH,DIRECT
            """.trimIndent()
        )
        assertTrue(out.contains("node-a"))
        assertTrue(out.contains("proxy-groups"))
        assertTrue(out.contains("MATCH,DIRECT"))
    }

    @Test
    fun `protected keys do not leak through duplicate declarations`() {
        val out = sanitized(
            """
            proxies: []
            external-controller: 127.0.0.1:9090
            mode: global
            """.trimIndent()
        )
        assertFalse(out.contains("external-controller"))
        assertFalse(out.contains("mode"))
        assertEquals(false, out.contains("global"))
    }

    @Test
    fun `invalid yaml falls back to text filter without exception`() {
        val out = sanitized("external-controller: x\n  : : :\nproxies: []")
        assertFalse(out.contains("external-controller"))
    }
}
