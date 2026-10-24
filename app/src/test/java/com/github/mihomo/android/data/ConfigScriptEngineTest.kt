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
}
