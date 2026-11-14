package com.github.mihomo.android.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProfileParserTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        ProfileParser.clearCache()
        tempFile = File.createTempFile("test_profile", ".yaml")
    }

    @After
    fun tearDown() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
        ProfileParser.clearCache()
    }

    @Test
    fun `parses standard clash yaml correctly`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - name: "HK-01"
                type: vmess
                server: 1.2.3.4
                port: 443
              - name: "JP-01"
                type: shadowsocks
                server: 5.6.7.8
                port: 8388
              - name: "US-01"
                type: hysteria2
                server: 9.10.11.12
                port: 443

            proxy-groups:
              - name: "PROXY"
                type: select
                proxies:
                  - "HK-01"
                  - "JP-01"
                  - "DIRECT"
              - name: "AUTO"
                type: url-test
                filter: "^HK"
                url: http://www.gstatic.com/generate_204
                interval: 300

            rules:
              - DOMAIN-SUFFIX,google.com,PROXY
              - DOMAIN-KEYWORD,github,PROXY
              - MATCH,DIRECT
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertEquals(3, parsed.totalNodes)
        assertEquals(3, parsed.totalRules)
        assertEquals(2, parsed.groupNames.size)
        assertTrue(parsed.groupNames.contains("PROXY"))
        assertTrue(parsed.groupNames.contains("AUTO"))

        // PROXY group check
        val proxyGroup = parsed.groups["PROXY"]
        assertNotNull(proxyGroup)
        assertEquals("select", proxyGroup!!.type)
        assertEquals(3, proxyGroup.proxies.size)
        assertEquals("HK-01", proxyGroup.now)

        // AUTO group check with filter ^HK
        val autoGroup = parsed.groups["AUTO"]
        assertNotNull(autoGroup)
        assertEquals(1, autoGroup!!.proxies.size)
        assertEquals("HK-01", autoGroup.proxies[0].name)
    }

    @Test
    fun `applies savedNodes selection to active node`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - name: "HK-01"
                type: vmess
              - name: "JP-01"
                type: ss
            proxy-groups:
              - name: "节点选择"
                type: select
                proxies:
                  - "HK-01"
                  - "JP-01"
            rules: []
            """.trimIndent()
        )

        val savedNodes = mapOf("节点选择" to "JP-01")
        val parsed = ProfileParser.parse(tempFile, savedNodes)

        val group = parsed.groups["节点选择"]
        assertNotNull(group)
        assertEquals("JP-01", group!!.now)
        assertEquals("JP-01", parsed.activeNode)
    }

    @Test
    fun `supports include-all and exclude-filter in proxy-groups`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - name: "HK-Premium-01"
                type: ss
              - name: "HK-Expire-02"
                type: ss
              - name: "SG-01"
                type: ss
            proxy-groups:
              - name: "ALL-HK"
                type: select
                include-all: true
                exclude-filter: "Expire"
            rules: []
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        val group = parsed.groups["ALL-HK"]
        assertNotNull(group)
        val proxyNames = group!!.proxies.map { it.name }
        assertTrue(proxyNames.contains("HK-Premium-01"))
        assertTrue(proxyNames.contains("SG-01"))
        assertFalse(proxyNames.contains("HK-Expire-02"))
    }

    @Test
    fun `auto-generates fallback group if no proxy-groups specified`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - name: "Standalone-1"
                type: ss
              - name: "Standalone-2"
                type: vmess
            rules: []
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertEquals(2, parsed.totalNodes)
        assertEquals(1, parsed.groupNames.size)
        assertEquals("节点选择", parsed.groupNames[0])
        val group = parsed.groups["节点选择"]
        assertNotNull(group)
        assertEquals(2, group!!.proxies.size)
    }

    @Test
    fun `falls back to robust line parser on corrupted yaml`() = runBlocking {
        // Malformed YAML with syntax error indentation that SnakeYAML cannot parse
        tempFile.writeText(
            """
            proxies:
              - name: "Corrupted-Node"
              type: ss
               bad indentation : :
            proxy-groups:
              - name: "Fallback-Group"
                type: select
                - Corrupted-Node
            rules:
              - MATCH,DIRECT
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertTrue(parsed.totalNodes >= 1)
        assertTrue(parsed.groups.containsKey("Fallback-Group"))
    }

    @Test
    fun `returns empty ParsedProfile for missing or null file`() = runBlocking {
        val nonExistent = File("non_existent_file.yaml")
        val parsedNull = ProfileParser.parse(null)
        val parsedMissing = ProfileParser.parse(nonExistent)

        assertEquals(0, parsedNull.totalNodes)
        assertEquals(0, parsedMissing.totalNodes)
    }

    @Test
    fun `parses legacy uppercase YAML keys Proxy, Proxy Group, and Rule`() = runBlocking {
        tempFile.writeText(
            """
            Proxy:
              - { Name: "Legacy-HK", Type: ss, server: 1.2.3.4, port: 443 }
            Proxy Group:
              - Name: "Legacy-Select"
                Type: select
                Proxies:
                  - Legacy-HK
                  - DIRECT
            Rule:
              - DOMAIN-SUFFIX,example.com,Legacy-Select
              - MATCH,DIRECT
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertEquals(1, parsed.totalNodes)
        assertEquals(listOf("Legacy-Select"), parsed.groupNames)
        assertEquals(
            listOf("Legacy-HK", "DIRECT"),
            parsed.groups["Legacy-Select"]?.proxies?.map { it.name }
        )
        assertEquals(2, parsed.totalRules)
        assertEquals("example.com", parsed.rules[0].payload)
        assertEquals("Legacy-Select", parsed.rules[0].target)
    }

    @Test
    fun `fallback parser handles legacy uppercase sections and inline type before name`() = runBlocking {
        tempFile.writeText(
            """
            Proxy:
              - { type: vmess, name: "Inline-First" }
               broken : : yaml
            Proxy Group:
              - name: "Fallback-Legacy"
                type: select
                - Inline-First
            Rule:
              - DOMAIN,example.org,Fallback-Legacy
              - MATCH,DIRECT
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertEquals(1, parsed.totalNodes)
        assertTrue(parsed.groups.containsKey("Fallback-Legacy"))
        assertEquals(listOf("Inline-First"), parsed.groups["Fallback-Legacy"]?.proxies?.map { it.name })
        assertEquals(2, parsed.totalRules)
        assertEquals("DOMAIN", parsed.rules[0].type)
        assertEquals("MATCH", parsed.rules[1].type)
    }

    @Test
    fun `parseWithOverrides prepends override rules and invalidates cache when overrides change`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - { name: "N1", type: ss }
            proxy-groups:
              - name: "Select"
                type: select
                proxies: [N1]
            rules:
              - MATCH,Select
            """.trimIndent()
        )

        val first = ProfileParser.parseWithOverrides(
            tempFile,
            overridePrependRules = listOf("DOMAIN,first.test,REJECT")
        )
        assertEquals(2, first.totalRules)
        assertEquals("first.test", first.rules[0].payload)
        assertEquals("MATCH", first.rules[1].type)

        val second = ProfileParser.parseWithOverrides(
            tempFile,
            overridePrependRules = listOf(
                "DOMAIN,a.test,DIRECT",
                "IP-CIDR,1.1.1.0/24,REJECT,no-resolve"
            )
        )
        assertEquals(3, second.totalRules)
        assertEquals("a.test", second.rules[0].payload)
        assertTrue(second.rules[1].noResolve)
        assertEquals("MATCH", second.rules[2].type)
    }

    @Test
    fun `parses rule payloads with nested parentheses without splitting inner commas`() = runBlocking {
        tempFile.writeText(
            """
            proxies:
              - { name: "N1", type: ss }
            rules:
              - AND,((DOMAIN,a.com),(NETWORK,tcp)),N1,no-resolve
            """.trimIndent()
        )

        val parsed = ProfileParser.parse(tempFile)
        assertEquals(1, parsed.totalRules)
        val rule = parsed.rules.single()
        assertEquals("AND", rule.type)
        assertEquals("((DOMAIN,a.com),(NETWORK,tcp))", rule.payload)
        assertEquals("N1", rule.target)
        assertTrue(rule.noResolve)
    }

    @Test
    fun `compileSafeRegex rejects oversized or malformed patterns`() {
        org.junit.Assert.assertNull(ProfileParser.compileSafeRegex("a".repeat(600)))
        org.junit.Assert.assertNull(ProfileParser.compileSafeRegex("[unclosed"))
        org.junit.Assert.assertNull(ProfileParser.compileSafeRegex("   "))
        assertNotNull(ProfileParser.compileSafeRegex("HK|SG"))
    }

    @Test
    fun `safeRegexContains aborts catastrophic backtracking without hanging`() {
        val evilRegex = Regex("^(a+)+$")
        val evilInput = "a".repeat(32) + "!"

        val startMs = System.currentTimeMillis()
        val matched = ProfileParser.safeRegexContains(
            regex = evilRegex,
            input = evilInput,
            timeoutMs = 25L,
            maxCharAccesses = 10_000
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        assertFalse(matched)
        assertTrue("ReDoS guard took unexpectedly long: ${elapsedMs}ms", elapsedMs < 2_000L)

        val safeRegex = requireNotNull(ProfileParser.compileSafeRegex("^HK"))
        assertTrue(ProfileParser.safeRegexContains(safeRegex, "HK" + "x".repeat(5000)))
    }
}
