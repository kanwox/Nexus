package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the subscription update transaction's structural pre-validation
 * ([ConfigManager.validateConfigStructure]) and the base64 payload gate. These run on the
 * JVM: the validation is deliberately a pure function of the payload text so the transaction
 * can hard-fail BEFORE the live profile file is touched.
 */
class ConfigManagerTransactionTest {

    // ---- validateConfigStructure: passing payloads ----------------------------------

    @Test
    fun `valid config passes structural validation`() {
        val yaml = """
            proxies:
              - name: "HK-01"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: secret
              - name: "US-01"
                type: trojan
                server: example.com
                port: 443
                password: secret
            proxy-groups:
              - name: PROXY
                type: select
                proxies:
                  - HK-01
                  - US-01
            rules:
              - DOMAIN-SUFFIX,example.com,PROXY
              - MATCH,PROXY
        """.trimIndent()
        assertNull(ConfigManager.validateConfigStructure(yaml))
    }

    @Test
    fun `empty proxies with provider references passes`() {
        // Members referencing nodes delivered via proxy-providers look "unknown" to a
        // static check — the core accepts them, so the validator must not reject.
        val yaml = """
            proxies: []
            proxy-groups:
              - name: PROXY
                type: select
                use:
                  - my-provider
            rules:
              - MATCH,PROXY
        """.trimIndent()
        assertNull(ConfigManager.validateConfigStructure(yaml))
    }

    @Test
    fun `config without optional sections passes`() {
        assertNull(ConfigManager.validateConfigStructure("mode: rule\n"))
    }

    // ---- validateConfigStructure: failing payloads -----------------------------------

    @Test
    fun `invalid yaml is rejected`() {
        // A genuinely unparseable document: an unclosed flow sequence. (":::: not yaml ["
        // would NOT work — YAML 1.2 legitimately parses it as a one-key mapping.)
        assertEquals(
            "配置不是有效的 YAML 映射",
            ConfigManager.validateConfigStructure("proxies: [{name: a\n  broken: [")
        )
    }

    @Test
    fun `top-level non-map is rejected`() {
        assertEquals(
            "配置顶层不是键值映射",
            ConfigManager.validateConfigStructure("- just\n- a\n- list\n")
        )
    }

    @Test
    fun `proxy without name is rejected`() {
        val yaml = """
            proxies:
              - type: ss
                server: 1.2.3.4
                port: 8388
        """.trimIndent()
        assertNotNull(ConfigManager.validateConfigStructure(yaml))
        assertTrue(ConfigManager.validateConfigStructure(yaml)!!.contains("name"))
    }

    @Test
    fun `duplicate proxy name is rejected`() {
        val yaml = """
            proxies:
              - name: A
                type: ss
                server: 1.1.1.1
                port: 1
              - name: A
                type: ss
                server: 2.2.2.2
                port: 2
        """.trimIndent()
        val problem = ConfigManager.validateConfigStructure(yaml)
        assertNotNull(problem)
        assertTrue(problem!!.contains("A"))
    }

    @Test
    fun `duplicate group name is rejected`() {
        val yaml = """
            proxies: []
            proxy-groups:
              - name: G
                type: select
                proxies: []
              - name: G
                type: url-test
                proxies: []
        """.trimIndent()
        val problem = ConfigManager.validateConfigStructure(yaml)
        assertNotNull(problem)
        assertTrue(problem!!.contains("G"))
    }

    @Test
    fun `proxies section that is not a list is rejected`() {
        assertEquals(
            "proxies 段不是列表",
            ConfigManager.validateConfigStructure("proxies: not-a-list\n")
        )
    }

    @Test
    fun `rules section that is not a list is rejected`() {
        val yaml = """
            proxies: []
            rules: oops
        """.trimIndent()
        assertEquals("rules 段不是列表", ConfigManager.validateConfigStructure(yaml))
    }

    @Test
    fun `rule without target is rejected`() {
        val yaml = """
            proxies: []
            rules:
              - "DOMAIN-SUFFIX,example.com"
              - "MATCH,"
        """.trimIndent()
        assertNotNull(ConfigManager.validateConfigStructure(yaml))
    }

    @Test
    fun `group missing type is rejected`() {
        val yaml = """
            proxies: []
            proxy-groups:
              - name: G
                proxies: []
        """.trimIndent()
        val problem = ConfigManager.validateConfigStructure(yaml)
        assertNotNull(problem)
        assertTrue(problem!!.contains("type"))
    }

    // ---- base64 gate still rejects node lists (existing behavior, kept in the transaction) ----

    @Test
    fun `base64 node list is not a valid subscription payload`() {
        val nodeList = "vmess://eyJhZGQiOiIxLjIuMy40In0=\nvmess://eyJhZGQiOiI1LjYuNy44In0="
        val encoded = java.util.Base64.getEncoder().encodeToString(nodeList.toByteArray())
        // A base64 node list must be rejected: the transaction cancels instead of
        // replacing the working profile with an unusable file.
        assertEquals(false, ConfigManager.isValidSubscriptionPayload(encoded))
    }

    @Test
    fun `plain yaml payload is valid`() {
        val yaml = "proxies: []\nrules:\n  - MATCH,DIRECT\n"
        assertEquals(true, ConfigManager.isValidSubscriptionPayload(yaml))
    }
}
