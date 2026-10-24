package com.github.mihomo.android.data

import org.junit.Assert.*
import org.junit.Test

class ConfigScriptEngineTest {

    @Test
    fun `normalizeYamlData converts whole numbers and expected-status correctly`() {
        val input = mapOf(
            "port" to 7890.0,
            "socks-port" to 7891.0f,
            "ratio" to 1.5,
            "expected-status" to 204.0,
            "list" to listOf(1.0, 2.5, 3.0),
            "nested" to mapOf(
                "expected-status" to 302,
                "timeout" to 5000.0
            )
        )

        @Suppress("UNCHECKED_CAST")
        val normalized = ConfigScriptEngine.normalizeYamlData(input) as Map<String, Any?>

        assertEquals(7890L, normalized["port"])
        assertEquals(7891L, normalized["socks-port"])
        assertEquals(1.5, normalized["ratio"])
        assertEquals("204", normalized["expected-status"])
        assertEquals(listOf(1L, 2.5, 3L), normalized["list"])

        @Suppress("UNCHECKED_CAST")
        val nested = normalized["nested"] as Map<String, Any?>
        assertEquals("302", nested["expected-status"])
        assertEquals(5000L, nested["timeout"])
    }

    @Test
    fun `previewScript rejects blank script code`() {
        val result = ConfigScriptEngine.previewScript(
            rawYaml = "proxies: []",
            code = "   "
        )
        assertFalse(result.success)
        assertEquals("脚本代码不能为空", result.errorMessage)
    }

    @Test
    fun `previewScript rejects invalid YAML input for JS scripts`() {
        val result = ConfigScriptEngine.previewScript(
            rawYaml = "\t::not yaml[[[",
            code = "function main(config) { return config; }"
        )
        assertFalse(result.success)
        assertEquals("输入配置不是合法的 YAML 格式", result.errorMessage)
    }

    @Test
    fun `previewScript merges YAML override snippets without an engine`() {
        val baseYaml = """
            mode: rule
            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val snippet = """
            mode: global
            rules:
              - DOMAIN-SUFFIX,example.com,DIRECT
        """.trimIndent()

        val result = ConfigScriptEngine.previewScript(rawYaml = baseYaml, code = snippet)
        assertTrue(result.success)
        assertEquals("YAML Merge", result.engineName)
        assertTrue(result.outputYaml.contains("global"))
        // List values are appended, not replaced.
        assertTrue(result.outputYaml.contains("MATCH,DIRECT"))
        assertTrue(result.outputYaml.contains("example.com"))
    }

    // ----------------------------------------------------------------------
    // Real-engine tests. The unit-test classpath substitutes quickjs-kt-jvm
    // (see app/build.gradle.kts), so these execute actual JavaScript.
    // ----------------------------------------------------------------------

    private val sampleYaml = """
        proxies:
          - name: "node-1"
            type: ss
            server: s1.example.com
            port: 8388
          - name: "node-2"
            type: ss
            server: s2.example.com
            port: 8388
        proxy-groups:
          - name: "select"
            type: select
            proxies:
              - "node-1"
              - "node-2"
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    @Test
    fun `JS main function rewrites the config`() {
        val code = """
            function main(config) {
                config.rules = ['MATCH,🚀 节点选择'];
                return config;
            }
        """.trimIndent()

        val result = ConfigScriptEngine.previewScript(rawYaml = sampleYaml, code = code)
        assertTrue("engine failed: ${result.errorMessage}", result.success)
        assertEquals("QuickJS (ES2020)", result.engineName)
        assertTrue(result.outputYaml.contains("MATCH,🚀 节点选择"))
        // Untouched sections survive the rewrite.
        assertTrue(result.outputYaml.contains("node-1"))
        assertEquals(2, result.afterProxies)
    }

    @Test
    fun `infinite loop script is interrupted by the engine timeout`() {
        val originalTimeout = ConfigScriptEngine.scriptTimeoutMs
        ConfigScriptEngine.scriptTimeoutMs = 1000L
        try {
            val start = System.currentTimeMillis()
            val result = ConfigScriptEngine.previewScript(
                rawYaml = sampleYaml,
                code = "function main(config) { while (true) {} }"
            )
            val elapsed = System.currentTimeMillis() - start
            assertFalse(result.success)
            assertTrue(
                "expected timeout error, got: ${result.errorMessage}",
                result.errorMessage?.contains("超时") == true
            )
            // The old engine leaked a spinning thread here and the caller blocked forever;
            // the new engine must return shortly after the timeout.
            assertTrue("interruption took ${elapsed}ms", elapsed < 15_000L)
        } finally {
            ConfigScriptEngine.scriptTimeoutMs = originalTimeout
        }
    }

    @Test
    fun `promise-returning main is reported instead of silently ignored`() {
        val code = """
            function main(config) {
                return Promise.resolve(config);
            }
        """.trimIndent()

        val result = ConfigScriptEngine.previewScript(rawYaml = sampleYaml, code = code)
        assertTrue(result.success)
        assertTrue(
            "expected promise warning in logs: ${result.consoleLogs}",
            result.consoleLogs.any { it.contains("Promise") }
        )
    }

    @Test
    fun `console output is captured into preview logs`() {
        val code = """
            function main(config) {
                console.log('rewriting', config.proxies.length, 'proxies');
                return config;
            }
        """.trimIndent()

        val result = ConfigScriptEngine.previewScript(rawYaml = sampleYaml, code = code)
        assertTrue(result.success)
        assertTrue(
            "expected console log: ${result.consoleLogs}",
            result.consoleLogs.any { it.contains("[LOG]") && it.contains("rewriting") }
        )
    }
}
