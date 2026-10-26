package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the YAML hardening in [YamlSupport]:
 *  - large subscriptions must still go through the structured path (the old 3 MB default
 *    silently switched to the text fallback and dropped every override);
 *  - YAML 1.1 boolean lookalikes (`on/off/yes/no`) must survive as plain strings.
 */
class ConfigManagerYamlSafetyTest {

    @Test
    fun `yaml factory can be created`() {
        YamlSupport.create()
    }

    @Test
    fun `large subscription still receives structured overrides and dns rule`() {
        val body = StringBuilder("proxies:\n")
        // ~3.5 MB: comfortably above SnakeYAML's old 3 MB default code point limit.
        for (i in 0 until 62_000) {
            body.append("  - {name: node").append(i)
                .append(", type: ss, server: a.example.com, port: 1}\n")
        }
        val raw = body.toString()
        assertTrue("fixture must exceed the old 3 MB limit", raw.length > 3_145_728)

        val out = ConfigManager.sanitizeUserConfig(
            raw,
            overrides = ConfigOverrides(prependRules = listOf("DOMAIN-SUFFIX,example.com,DIRECT")),
            blockThirdPartyDns = true
        )

        val parsed = YamlSupport.create().load<Map<String, Any?>>(out)
        assertNotNull(parsed)
        val rules = (parsed!!["rules"] as? List<*>).orEmpty().map { it.toString() }
        assertTrue("prepend rule missing", rules.any { it.contains("example.com") })
        assertTrue("plaintext DNS reject rule missing", rules.any { it.contains("DST-PORT,53") })
        assertTrue((parsed["proxies"] as? List<*>)?.size == 62_000)
    }

    @Test
    fun `yaml 1_1 boolean lookalikes stay strings after sanitation`() {
        val out = ConfigManager.sanitizeUserConfig(
            """
            proxies:
              - name: on
                type: ss
                password: yes
              - name: off
                type: ss
                password: no
              - name: real-bool
                type: ss
                udp: true
            """.trimIndent()
        )
        val parsed = YamlSupport.create().load<Map<String, Any?>>(out)!!
        val proxies = (parsed["proxies"] as List<*>).map { it as Map<*, *> }
        assertEquals("on", proxies[0]["name"])
        assertEquals("yes", proxies[0]["password"])
        assertEquals("off", proxies[1]["name"])
        assertEquals("no", proxies[1]["password"])
        assertEquals(true, proxies[2]["udp"])
        assertFalse(out.contains("name: true"))
    }

    @Test
    fun `long scalars are not folded across lines`() {
        val longRule = "DOMAIN-REGEX," + "a".repeat(2_000) + ",DIRECT"
        val out = ConfigManager.sanitizeUserConfig("rules:\n  - $longRule\n")
        val parsed = YamlSupport.create().load<Map<String, Any?>>(out)!!
        assertEquals(longRule, (parsed["rules"] as List<*>).first())
    }
}
