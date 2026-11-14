@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.Collections
import java.util.IdentityHashMap

internal object ConfigScriptSupport {
    private const val TAG = "ConfigScriptEngine"
    private const val SCRIPT_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024 // 64 MB heap limit
    internal const val MAX_YAML_TRAVERSAL_DEPTH = 64
    internal const val MAX_YAML_TRAVERSAL_NODES = 1_000_000

    internal val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    internal val SAMPLE_PREVIEW_YAML = """
        mixed-port: 7890
        allow-lan: false
        mode: rule
        log-level: info
        proxies:
          - name: "🇭🇰 香港 IEPL 01"
            type: ss
            server: hk1.example.com
            port: 8388
            cipher: aes-256-gcm
            password: "password"
          - name: "🇯🇵 日本 BGP 01"
            type: vless
            server: jp1.example.com
            port: 443
            uuid: "00000000-0000-0000-0000-000000000000"
          - name: "🇸🇬 新加坡 高速 01"
            type: trojan
            server: sg1.example.com
            port: 443
            password: "password"
          - name: "🇺🇸 美国 专线 01"
            type: hysteria2
            server: us1.example.com
            port: 443
            password: "password"
        proxy-groups:
          - name: "🚀 节点选择"
            type: select
            proxies:
              - "♻️ 自动选择"
              - "🇭🇰 香港 IEPL 01"
              - "🇯🇵 日本 BGP 01"
              - "🇸🇬 新加坡 高速 01"
              - "🇺🇸 美国 专线 01"
              - DIRECT
          - name: "♻️ 自动选择"
            type: url-test
            url: "http://www.gstatic.com/generate_204"
            interval: 300
            proxies:
              - "🇭🇰 香港 IEPL 01"
              - "🇯🇵 日本 BGP 01"
              - "🇸🇬 新加坡 高速 01"
              - "🇺🇸 美国 专线 01"
        rules:
          - DOMAIN-SUFFIX,google.com,🚀 节点选择
          - DOMAIN-KEYWORD,github,🚀 节点选择
          - GEOIP,CN,DIRECT
          - MATCH,🚀 节点选择
    """.trimIndent()

    internal fun countMapStats(map: Map<*, *>?): Triple<Int, Int, Int> {
        if (map == null) return Triple(0, 0, 0)
        val proxies = (map["proxies"] ?: map["Proxy"] ?: map["Proxies"]) as? List<*>
        val groups = (map["proxy-groups"] ?: map["Proxy Group"]) as? List<*>
        val rules = (map["rules"] ?: map["Rule"]) as? List<*>
        return Triple(proxies?.size ?: 0, groups?.size ?: 0, rules?.size ?: 0)
    }

    private class TraversalBudget(
        private val maxDepth: Int = MAX_YAML_TRAVERSAL_DEPTH,
        private val maxNodes: Int = MAX_YAML_TRAVERSAL_NODES
    ) {
        private var visitedNodes = 0
        private val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun enter(node: Any, depth: Int) {
            if (depth > maxDepth) {
                error("YAML 结构嵌套过深（超过 $maxDepth 层）")
            }
            countScalar()
            if (!ancestors.add(node)) {
                error("检测到循环引用的 YAML 结构")
            }
        }

        fun countScalar() {
            visitedNodes++
            if (visitedNodes > maxNodes) {
                error("YAML 节点展开数量超过上限（$maxNodes）")
            }
        }

        fun exit(node: Any) {
            ancestors.remove(node)
        }
    }

    private inline fun <T> withEnteredNode(
        node: Any,
        depth: Int,
        budget: TraversalBudget,
        block: () -> T
    ): T {
        budget.enter(node, depth)
        return try {
            block()
        } finally {
            budget.exit(node)
        }
    }

    internal fun anyToJsonElement(
        value: Any?,
        maxDepth: Int = MAX_YAML_TRAVERSAL_DEPTH,
        maxNodes: Int = MAX_YAML_TRAVERSAL_NODES
    ): JsonElement = anyToJsonElementInternal(value, 0, TraversalBudget(maxDepth, maxNodes))

    private fun anyToJsonElementInternal(
        value: Any?,
        depth: Int,
        budget: TraversalBudget
    ): JsonElement = when (value) {
        is Map<*, *> -> withEnteredNode(value, depth, budget) {
            buildJsonObject {
                for ((k, v) in value) {
                    if (k != null) put(k.toString(), anyToJsonElementInternal(v, depth + 1, budget))
                }
            }
        }
        is List<*> -> withEnteredNode(value, depth, budget) {
            buildJsonArray {
                for (item in value) add(anyToJsonElementInternal(item, depth + 1, budget))
            }
        }
        else -> scalarToJsonElement(value, budget)
    }

    private fun scalarToJsonElement(value: Any?, budget: TraversalBudget): JsonElement {
        budget.countScalar()
        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    internal fun parseResultMap(resultJson: String?): Map<String, Any?>? {
        if (resultJson.isNullOrBlank() || resultJson == "undefined" || resultJson == "null") return null
        val element = runCatching { scriptJson.parseToJsonElement(resultJson) }.getOrNull() ?: return null
        if (element !is JsonObject) return null
        val map = LinkedHashMap<String, Any?>()
        for ((k, v) in element) map[k] = jsonElementToPlainValue(v)
        return map
    }

    private fun jsonElementToPlainValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) {
            element.content
        } else {
            element.content.toLongOrNull()
                ?: element.content.toDoubleOrNull()
                ?: element.content.toBooleanStrictOrNull()
                ?: element.content
        }
        is JsonArray -> element.map { jsonElementToPlainValue(it) }
        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in element) map[k] = jsonElementToPlainValue(v)
            map
        }
    }

    internal fun normalizeYamlData(
        data: Any?,
        maxDepth: Int = MAX_YAML_TRAVERSAL_DEPTH,
        maxNodes: Int = MAX_YAML_TRAVERSAL_NODES
    ): Any? = normalizeYamlDataInternal(data, 0, TraversalBudget(maxDepth, maxNodes))

    private fun normalizeYamlDataInternal(
        data: Any?,
        depth: Int,
        budget: TraversalBudget
    ): Any? = when (data) {
        is Map<*, *> -> withEnteredNode(data, depth, budget) {
            val result = LinkedHashMap<String, Any?>()
            for ((key, value) in data) {
                val kStr = key?.toString() ?: ""
                var normVal = normalizeYamlDataInternal(value, depth + 1, budget)
                if (kStr == "expected-status" && normVal is Number) {
                    normVal = normVal.toLong().toString()
                }
                result[kStr] = normVal
            }
            result
        }
        is List<*> -> withEnteredNode(data, depth, budget) {
            data.map { normalizeYamlDataInternal(it, depth + 1, budget) }
        }
        else -> normalizeScalarValue(data, budget)
    }

    private fun normalizeScalarValue(data: Any?, budget: TraversalBudget): Any? {
        budget.countScalar()
        return when (data) {
            is Double -> {
                val isIntegral = data % 1.0 == 0.0
                val inLongRange = data >= Long.MIN_VALUE && data <= Long.MAX_VALUE
                if (isIntegral && inLongRange) data.toLong() else data
            }
            is Float -> if (data % 1.0f == 0.0f) data.toLong() else data
            else -> data
        }
    }

    internal fun mergeYamlMaps(baseMap: Map<*, *>?, snippetMap: Map<*, *>): LinkedHashMap<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        if (baseMap != null) {
            for ((k, v) in baseMap) {
                if (k != null) merged[k.toString()] = v
            }
        }
        for ((k, v) in snippetMap) {
            val kStr = k?.toString() ?: continue
            val baseVal = merged[kStr]
            if (baseVal is List<*> && v is List<*>) {
                merged[kStr] = (baseVal + v).distinct()
            } else {
                merged[kStr] = v
            }
        }
        return merged
    }

    internal fun mergeJsResultPreservingCoreLists(
        configMap: Any?,
        modifiedMap: Map<String, Any?>
    ): LinkedHashMap<String, Any?> {
        val finalMap = LinkedHashMap<String, Any?>()
        if (configMap is Map<*, *>) {
            for ((k, v) in configMap) {
                if (k != null) finalMap[k.toString()] = v
            }
        }
        for ((k, v) in modifiedMap) {
            finalMap[k] = v
        }
        if (configMap is Map<*, *>) {
            preserveNonEmptyList(configMap, finalMap, listOf("proxies", "Proxy", "Proxies"), "proxies")
            preserveNonEmptyList(
                configMap,
                finalMap,
                listOf("proxy-groups", "proxy-providers", "Proxy Group"),
                "proxy-groups"
            )
        }
        return finalMap
    }

    private fun preserveNonEmptyList(
        source: Map<*, *>,
        target: LinkedHashMap<String, Any?>,
        keys: List<String>,
        canonicalKey: String
    ) {
        val orig = keys.firstNotNullOfOrNull { source[it] } as? List<*> ?: return
        if (orig.isEmpty()) return
        val cur = keys.firstNotNullOfOrNull { target[it] } as? List<*>
        if (cur.isNullOrEmpty()) {
            target[canonicalKey] = orig
        }
    }

    internal suspend fun executeWithQuickJS(
        code: String,
        jsonStr: String,
        scriptName: String,
        timeoutMs: Long,
        logSink: MutableList<String>
    ): String? {
        val quickJs = QuickJs.create(Dispatchers.Default)
        return try {
            quickJs.evaluationTimeoutMillis = timeoutMs
            quickJs.memoryLimit = SCRIPT_MEMORY_LIMIT_BYTES
            quickJs.define("console") {
                function("log") { args -> pushConsoleLog(logSink, "LOG", args) }
                function("info") { args -> pushConsoleLog(logSink, "INFO", args) }
                function("warn") { args -> pushConsoleLog(logSink, "WARN", args) }
                function("error") { args -> pushConsoleLog(logSink, "ERROR", args) }
            }
            quickJs.evaluate<Unit>("var module = { exports: {} }; var exports = module.exports;")
            quickJs.evaluate<Any?>(code)

            val runnerCode = buildJsRunnerCode(jsonToJsStringLiteral(jsonStr))
            val res = quickJs.evaluate<String>(runnerCode)
            if (evaluateJsFlag(quickJs, "__promiseDropped === true ? '1' : '0'")) {
                Log.w(TAG, "Script $scriptName returned a Promise; async main() is not supported")
                logSink.add("[WARN] main() 返回了 Promise（异步脚本不支持），本次改写未生效")
                return null
            }
            if (evaluateJsFlag(quickJs, "__emptyOutput === true ? '1' : '0'") || res.isBlank()) {
                Log.w(TAG, "Script $scriptName returned empty/null output; rewrite skipped")
                logSink.add("[WARN] main() 未返回有效配置对象，本次改写未生效")
                return null
            }
            res
        } catch (_: QuickJsInterruptedException) {
            error("脚本执行超时（超过 ${timeoutMs / 1000}s）")
        } finally {
            closeQuickJsSafely(quickJs)
        }
    }

    private suspend fun evaluateJsFlag(quickJs: QuickJs, expression: String): Boolean = try {
        quickJs.evaluate<String>(expression) == "1"
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    private fun closeQuickJsSafely(quickJs: QuickJs) {
        try {
            quickJs.close()
        } catch (_: Throwable) {
            // Best-effort engine teardown.
        }
    }

    private fun buildJsRunnerCode(escapedJson: String): String = """
        var __promiseDropped = false;
        var __emptyOutput = false;
        (function() {
            var __input = JSON.parse($escapedJson);
            var inProxies = __input.proxies ? JSON.parse(JSON.stringify(__input.proxies)) :
                            (__input.Proxies ? JSON.parse(JSON.stringify(__input.Proxies)) :
                            (__input.Proxy ? JSON.parse(JSON.stringify(__input.Proxy)) : null));
            var inGroups = __input['proxy-groups'] ? JSON.parse(JSON.stringify(__input['proxy-groups'])) : null;
            var __fn = (typeof main === 'function') ? main :
                       (typeof module !== 'undefined' && typeof module.exports === 'function') ? module.exports :
                       (typeof module !== 'undefined' && module.exports && typeof module.exports.main === 'function') ? module.exports.main :
                       null;
            if (!__fn) {
                __emptyOutput = true;
                return '';
            }
            var __res = __fn(__input);
            if (typeof __res === 'undefined' || __res === null) {
                __emptyOutput = true;
                return '';
            }
            if (__res && typeof __res.then === 'function') {
                __promiseDropped = true;
                return '';
            }
            if (Array.isArray(__res)) {
                __input.proxies = __res;
                __res = __input;
            }
            var outProxies = __res.proxies || __res.Proxies || __res.Proxy;
            if (inProxies && inProxies.length > 0 && (!outProxies || !Array.isArray(outProxies) || outProxies.length === 0)) {
                __res.proxies = inProxies;
            }
            if (inGroups && inGroups.length > 0 && (!__res['proxy-groups'] || !Array.isArray(__res['proxy-groups']) || __res['proxy-groups'].length === 0)) {
                __res['proxy-groups'] = inGroups;
            }
            return JSON.stringify(__res);
        })();
    """.trimIndent()

    private fun jsonToJsStringLiteral(json: String): String {
        val sb = StringBuilder(json.length + 16)
        sb.append('"')
        for (ch in json) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch < ' ') sb.append("\\u%04x".format(java.util.Locale.ROOT, ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun consoleArgToString(a: Any?): String = when (a) {
        null -> "null"
        is String -> a
        is Map<*, *> -> a.entries.joinToString(", ", "{", "}") { "\"${it.key}\":${consoleArgToString(it.value)}" }
        is List<*> -> a.joinToString(", ", "[", "]") { consoleArgToString(it) }
        else -> a.toString()
    }

    private fun pushConsoleLog(sink: MutableList<String>, level: String, args: Array<Any?>) {
        if (sink.size >= 100) return
        sink.add("[$level] ${args.joinToString(" ") { consoleArgToString(it) }}")
    }
}
