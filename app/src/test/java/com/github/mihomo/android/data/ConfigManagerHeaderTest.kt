package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class ConfigManagerHeaderTest {

    private val nameserversYaml = "                - https://223.5.5.5/dns-query"
    private val fallbackYaml = "                - https://1.1.1.1/dns-query"

    private fun options(
        ipv6Enabled: Boolean = false,
        allowLan: Boolean = false,
        lanPassword: String = ""
    ) = ConfigManager.RuntimeHeaderOptions(
        tunnelMode = "rule",
        logLevel = "info",
        ipv6Enabled = ipv6Enabled,
        allowLan = allowLan,
        lanPassword = lanPassword,
        dnsIpv6Enabled = false,
        fakeIpEnabled = true
    )

    private fun header(options: ConfigManager.RuntimeHeaderOptions): String =
        ConfigManager.buildRuntimeHeader(options, nameserversYaml, fallbackYaml)

    @Test
    fun `header parses as YAML with all top-level keys at root`() {
        val parsed = Yaml().load<Map<String, Any?>>(header(options(allowLan = true, lanPassword = "s3cret")))
        // If trimIndent were broken by the interpolated auth block, these keys would end up
        // indented and the root map would be empty or malformed.
        assertEquals("rule", parsed["mode"])
        assertEquals(false, parsed["ipv6"])
        assertEquals(7890, parsed["mixed-port"])
        assertEquals(true, parsed["allow-lan"])
        assertTrue(parsed.containsKey("dns"))
        assertTrue(parsed.containsKey("geox-url"))
    }

    @Test
    fun `header injects authentication only when a password is set`() {
        val withPassword = Yaml().load<Map<String, Any?>>(
            header(options(allowLan = true, lanPassword = "s3cret"))
        )
        val auth = withPassword["authentication"] as? List<*>
        assertEquals(listOf("nexus:s3cret"), auth)
        val skipAuth = withPassword["skip-auth-prefixes"] as? List<*>
        assertTrue(skipAuth!!.contains("127.0.0.1"))
        assertTrue(skipAuth.contains("::1"))

        // Same switch without a password: no auth keys at all.
        val withoutPassword = Yaml().load<Map<String, Any?>>(header(options(allowLan = true, lanPassword = "")))
        assertFalse(withoutPassword.containsKey("authentication"))
        assertFalse(withoutPassword.containsKey("skip-auth-prefixes"))

        // Password is ignored while LAN sharing is off.
        val lanOff = Yaml().load<Map<String, Any?>>(header(options(allowLan = false, lanPassword = "s3cret")))
        assertFalse(lanOff.containsKey("authentication"))
    }

    @Test
    fun `header escapes quotes and backslashes in the password`() {
        val parsed = Yaml().load<Map<String, Any?>>(
            header(options(allowLan = true, lanPassword = "pa\"ss\\word"))
        )
        assertEquals(listOf("nexus:pa\"ss\\word"), parsed["authentication"])
    }

    @Test
    fun `header reflects the ipv6 master switch`() {
        val on = Yaml().load<Map<String, Any?>>(header(options(ipv6Enabled = true)))
        assertEquals(true, on["ipv6"])
        val off = Yaml().load<Map<String, Any?>>(header(options(ipv6Enabled = false)))
        assertEquals(false, off["ipv6"])
    }

    @Test
    fun `buildLanAuthBlock is empty without a password even when enabled`() {
        assertEquals("", ConfigManager.buildLanAuthBlock(allowLan = true, lanPassword = "  "))
        assertEquals("", ConfigManager.buildLanAuthBlock(allowLan = false, lanPassword = "x"))
        assertTrue(ConfigManager.buildLanAuthBlock(allowLan = true, lanPassword = "x").startsWith("            authentication:"))
    }
}
