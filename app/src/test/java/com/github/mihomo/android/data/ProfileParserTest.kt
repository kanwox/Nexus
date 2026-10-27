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
}
