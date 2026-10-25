package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * Tests for the v1.1 config pipeline: per-profile overrides (prepend rules / hosts / DNS
 * override), the third-party plaintext DNS reject rule, the external-controller block and
 * the private-host guard for subscription URLs.
 */
class ConfigManagerOverridesTest {

    private val nameserversYaml = "                - https://223.5.5.5/dns-query"
    private val fallbackYaml = "                - https://1.1.1.1/dns-query"

    private fun header(
        mixedPort: Int = 7890,
        controller: String? = null,
        secret: String? = null
    ) = ConfigManager.buildRuntimeHeader(
        ConfigManager.RuntimeHeaderOptions(
            tunnelMode = "rule",
            logLevel = "info",
            ipv6Enabled = false,
            allowLan = false,
            lanPassword = "",
            dnsIpv6Enabled = false,
            fakeIpEnabled = true,
            mixedPort = mixedPort,
            externalController = controller,
            apiSecret = secret
        ),
        nameserversYaml,
        fallbackYaml
    )

    @Test
    fun `header injects mixed port and controller with secret`() {
        val out = header(mixedPort = 7891, controller = "127.0.0.1:9090", secret = "abc123")
        assertTrue(out.contains("mixed-port: 7891"))
        assertTrue(out.contains("external-controller: 127.0.0.1:9090"))
        assertTrue(out.contains("secret: \"abc123\""))
    }

    @Test
    fun `header omits controller block when not configured`() {
        val out = header()
        assertFalse(out.contains("external-controller:"))
        assertFalse(out.contains("secret:"))
        assertTrue(out.contains("mixed-port: 7890"))
    }

    @Test
    fun `fake-ip filter no longer contains the generate_204 path entry`() {
        val out = header()
        assertFalse(out.contains("generate_204"))
        assertTrue(out.contains("connectivitycheck.gstatic.com"))
    }

    @Test
    fun `prepend rules are inserted at the top of the rule list`() {
        val out = ConfigManager.sanitizeUserConfig(
            """
            proxies: []
            rules:
              - MATCH,DIRECT
            """.trimIndent(),
            overrides = ConfigOverrides(prependRules = listOf("DOMAIN-SUFFIX,example.com,DIRECT"))
        )
        val rules = (Yaml().load<Map<String, Any?>>(out)["rules"] as? List<*>).orEmpty()
        assertEquals(2, rules.size)
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", rules.first())
    }

    @Test
    fun `prepend rules create the rules section when missing`() {
        val out = ConfigManager.sanitizeUserConfig(
            "proxies: []",
            overrides = ConfigOverrides(prependRules = listOf("MATCH,DIRECT"))
        )
        val rules = (Yaml().load<Map<String, Any?>>(out)["rules"] as? List<*>).orEmpty()
        assertEquals(listOf("MATCH,DIRECT"), rules)
    }

    @Test
    fun `hosts entries merge with profile entries winning conflicts`() {
        val out = ConfigManager.sanitizeUserConfig(
            """
            hosts:
              router.local: 10.0.0.1
            proxies: []
            """.trimIndent(),
            overrides = ConfigOverrides(hostsEntries = listOf("router.local: 192.168.1.1", "nas.local: 192.168.1.2"))
        )
        val hosts = Yaml().load<Map<String, Any?>>(out)["hosts"] as? Map<*, *>
        assertEquals("192.168.1.1", hosts?.get("router.local"))
        assertEquals("192.168.1.2", hosts?.get("nas.local"))
    }

    @Test
    fun `third-party dns reject rule is prepended when enabled`() {
        val out = ConfigManager.sanitizeUserConfig(
            """
            rules:
              - MATCH,DIRECT
            """.trimIndent(),
            blockThirdPartyDns = true
        )
        val rules = (Yaml().load<Map<String, Any?>>(out)["rules"] as? List<*>).orEmpty()
        assertEquals(2, rules.size)
        assertTrue((rules.first() as String).contains("DST-PORT,53"))
        assertTrue((rules.first() as String).endsWith("REJECT"))
    }

    @Test
    fun `block dns rule also covers the ipv6 portal address`() {
        val out = ConfigManager.sanitizeUserConfig("rules:\n  - MATCH,DIRECT", blockThirdPartyDns = true)
        assertTrue(out.contains("fdfe:dcba:9876::2"))
    }

    @Test
    fun `subscription urls allow private hosts by default`() {
        // Does not throw
        requireSecureRemoteUrl("http://192.168.1.1:9090/sub?token=x", allowPrivateHosts = true)
        requireSecureRemoteUrl("https://10.0.0.5/sub", allowPrivateHosts = true)
        requireSecureRemoteUrl("http://localhost:25500/sub", allowPrivateHosts = true)
    }

    @Test
    fun `subscription urls reject private hosts when the switch is off`() {
        assertFailsWithMessage("http://192.168.1.1/sub", "不允许的地址")
        assertFailsWithMessage("https://10.1.2.3/sub", "不允许的地址")
        assertFailsWithMessage("http://172.16.0.9/sub", "不允许的地址")
        assertFailsWithMessage("http://[fd00::1]/sub", "不允许的地址")
        // Public hosts keep working
        requireSecureRemoteUrl("https://example.com/sub", allowPrivateHosts = false)
        // Link-local is rejected in both modes
        assertFailsWithMessage("http://169.254.169.254/latest/meta-data", "不允许的地址")
    }

    @Test
    fun `non http schemes are always rejected`() {
        assertFailsWithMessage("ftp://example.com/sub", "不支持的地址协议")
        assertFailsWithMessage("file:///etc/passwd", "不支持的地址协议")
    }

    private fun assertFailsWithMessage(url: String, expectedPart: String) {
        val error = runCatching { requireSecureRemoteUrl(url, allowPrivateHosts = false) }
            .exceptionOrNull()
        assertTrue("expected failure for $url", error != null)
        assertTrue(
            "message '$error' should contain '$expectedPart'",
            error?.message?.contains(expectedPart) == true
        )
    }
}
