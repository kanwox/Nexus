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

    @Test
    fun `lanPassword newlines cannot inject top-level YAML keys`() {
        val crafted = "pw\"\nallow-lan: false\nexternal-controller: 0.0.0.0:9090\rsecret: hacked"
        val parsed = Yaml().load<Map<String, Any?>>(
            header(options(allowLan = true, lanPassword = crafted))
        )
        assertFalse(parsed.containsKey("external-controller"))
        assertFalse(parsed.containsKey("secret"))
        assertEquals(true, parsed["allow-lan"])
        val auth = parsed["authentication"] as List<*>
        assertEquals(1, auth.size)
        assertTrue(auth[0].toString().startsWith("nexus:"))
    }

    @Test
    fun `controller block rejects unsafe listen address and strips secret newlines`() {
        assertEquals("", ConfigManager.buildControllerBlock("127.0.0.1:9090\nallow-lan: true", "s3cret"))
        assertEquals("", ConfigManager.buildControllerBlock("127.0.0.1:99999", "s3cret"))

        val headerYaml = ConfigManager.buildRuntimeHeader(
            ConfigManager.RuntimeHeaderOptions(
                tunnelMode = "rule",
                logLevel = "info",
                ipv6Enabled = false,
                allowLan = false,
                lanPassword = "",
                dnsIpv6Enabled = false,
                fakeIpEnabled = true,
                externalController = "127.0.0.1:9090",
                apiSecret = "sec\"\nallow-lan: true\u2028"
            ),
            nameserversYaml,
            fallbackYaml
        )
        val parsed = Yaml().load<Map<String, Any?>>(headerYaml)
        assertEquals(false, parsed["allow-lan"])
        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertTrue(parsed["secret"].toString().startsWith("sec\""))
    }

    @Test
    fun `runtime header sanitizes injected lines in nameserver and fallback blocks`() {
        val maliciousNameserver = listOf(
            "                - 1.1.1.1",
            "            external-controller: 0.0.0.0:9090",
            "                - allow-lan: true",
            "                - 8.8.8.8"
        ).joinToString("\n")
        val maliciousFallback = "                - ---\n                - 9.9.9.9\u2028secret: evil"

        val parsed = Yaml().load<Map<String, Any?>>(
            ConfigManager.buildRuntimeHeader(
                options(allowLan = false),
                maliciousNameserver,
                maliciousFallback
            )
        )
        assertFalse(parsed.containsKey("external-controller"))
        assertEquals(false, parsed["allow-lan"])
        val dns = parsed["dns"] as Map<*, *>
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), dns["nameserver"])
        val fallback = (dns["fallback"] as List<*>).map { it.toString() }
        assertFalse(fallback.any { it.contains("secret") || it == "---" })
    }

    @Test
    fun `formatDnsServersYaml drops YAML injection payloads and preserves group tags`() {
        val yaml = ConfigSanitizer.formatDnsServersYaml(
            listOf(
                "https://1.1.1.1/dns-query",
                "8.8.8.8\nauthentication: []",
                "external-controller: 0.0.0.0:9090",
                "223.5.5.5#Proxy Group",
                "[2400:3200::1]:53"
            ),
            listOf("223.5.5.5")
        )
        val parsed = Yaml().load<List<String>>(yaml.trimIndent())
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "223.5.5.5#Proxy Group", "[2400:3200::1]:53"),
            parsed
        )
    }

    @Test
    fun `geox-url mmdb points to geoip metadb consistently with single geo db`() {
        val parsed = Yaml().load<Map<String, Any?>>(header(options()))
        assertEquals(false, parsed["geodata-mode"])
        val geoxUrl = parsed["geox-url"] as Map<*, *>
        assertTrue(geoxUrl["mmdb"].toString().endsWith("/geoip.metadb"))
        assertEquals(geoxUrl["geoip"], geoxUrl["mmdb"])
    }
}
