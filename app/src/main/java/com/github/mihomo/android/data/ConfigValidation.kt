package com.github.mihomo.android.data

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

internal object ConfigValidation {
    private const val TAG = "ConfigValidation"
    private const val BASE64_MIN_LEN = 16
    private const val COMMENT_SCAN_LINES = 25
    private const val FALLBACK_SUFFIX_MOD = 1000
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val BASE64_BODY_REGEX = Regex("""^[A-Za-z0-9+/=_-]+$""")
    private val YAML_EXT_REGEX = Regex("""(?i)\.(yaml|yml|txt)$""")
    private val FLOW_PROTECTED_PAIR_REGEX = Regex(
        """(?i)(?:["']?(?:mode|log-level|ipv6|mixed-port|port|socks-port|redir-port|tproxy-port|""" +
            """allow-lan|bind-address|external-controller|external-controller-cors|secret|""" +
            """external-controller-unix|external-controller-pipe|external-doh|authentication|""" +
            """skip-auth-prefixes|lan-allowed-ips|lan-disallowed-ips|external-ui|external-ui-name|""" +
            """external-ui-url|tun|dns|interface-name|routing-mark|geodata-mode|geo-auto-update|""" +
            """geox-url|listeners|sub-rules|tunnels)["']?\s*:\s*(?:\{[^{}]*\}|\[[^\[\]]*\]|"[^"]*"|'[^']*'|[^,{}\n]+)\s*,?\s*)"""
    )

    private val HTML_ERROR_INDICATORS = listOf(
        "<!doctype html", "<html", "<head>", "cf-browser-verification"
    )
    private val PROTOCOL_PREFIXES = listOf(
        "vmess://", "vless://", "ss://", "ssr://", "trojan://", "hysteria://", "hysteria2://", "tuic://"
    )
    private val CLASH_STRUCTURAL_KEYS = setOf(
        "proxies", "proxy", "proxy-groups", "proxy group", "proxy-providers",
        "rule-providers", "rules", "rule", "mode", "mixed-port", "port",
        "socks-port", "redir-port", "tproxy-port", "dns", "tun", "hosts",
        "sniffer", "listeners", "sub-rules", "tunnels"
    )
    private val PROTECTED_TOP_LEVEL_KEYS = setOf(
        "mode", "log-level", "ipv6", "mixed-port", "port", "socks-port",
        "redir-port", "tproxy-port", "allow-lan", "bind-address",
        "external-controller", "external-controller-tls", "external-controller-cors", "secret",
        "external-controller-unix", "external-controller-pipe", "external-doh",
        "authentication", "skip-auth-prefixes", "lan-allowed-ips", "lan-disallowed-ips",
        "external-ui", "external-ui-name", "external-ui-url",
        "tun", "dns", "tls", "profile", "experimental", "ebpf",
        "interface-name", "routing-mark",
        "geodata-mode", "geo-auto-update", "geox-url",
        "listeners", "sub-rules", "tunnels"
    )

    /**
     * Resolves a subscription response or imported file into a plain Clash/Mihomo YAML mapping string.
     * Base64-encoded YAML configs are decoded once upfront so validation and disk persistence operate
     * on the decoded YAML instead of raw base64 text.
     */
    fun resolveSubscriptionYaml(content: String): String? {
        val cleaned = content.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty() || looksLikeHtmlOrUriList(cleaned)) return null
        if (isValidClashYamlMapping(cleaned)) return cleaned
        return decodeBase64YamlOrNull(cleaned)
    }

    fun isValidSubscriptionPayload(content: String): Boolean = resolveSubscriptionYaml(content) != null

    private fun looksLikeHtmlOrUriList(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (HTML_ERROR_INDICATORS.any { lower.contains(it) }) return true
        return PROTOCOL_PREFIXES.any { lower.startsWith(it) }
    }

    private fun isValidClashYamlMapping(yamlText: String): Boolean {
        return try {
            val docs = YamlSupport.create().loadAll(yamlText).iterator()
            val firstDoc = if (docs.hasNext()) docs.next() else null
            if (firstDoc !is Map<*, *> || hasAdditionalDocument(docs)) return false
            firstDoc.keys.any { key ->
                key?.toString()?.trim()?.lowercase(Locale.ROOT) in CLASH_STRUCTURAL_KEYS
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeBase64YamlOrNull(cleaned: String): String? {
        val compact = WHITESPACE_REGEX.replace(cleaned, "")
        if (compact.length < BASE64_MIN_LEN || !BASE64_BODY_REGEX.matches(compact)) return null
        val bytes = tryDecodeBase64(compact) ?: return null
        val decoded = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (decoded.isEmpty() || looksLikeHtmlOrUriList(decoded)) return null
        return if (isValidClashYamlMapping(decoded)) decoded else null
    }

    private fun tryDecodeBase64(compact: String): ByteArray? {
        return try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(compact)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun hasAdditionalDocument(iterator: Iterator<Any?>): Boolean {
        while (iterator.hasNext()) {
            if (iterator.next() != null) return true
        }
        return false
    }

    /** Deep structural validation supporting both canonical and legacy Clash top-level keys. */
    fun validateConfigStructure(content: String): String? {
        val (firstDoc, hasExtraDocs) = try {
            val docs = YamlSupport.create().loadAll(content).iterator()
            val first = if (docs.hasNext()) docs.next() else null
            first to hasAdditionalDocument(docs)
        } catch (_: Exception) {
            return "配置不是有效的 YAML 映射"
        }
        if (hasExtraDocs) return "配置包含多个 YAML 文档"
        if (firstDoc !is Map<*, *>) return "配置顶层不是键值映射"

        validateProxiesSection(firstDoc)?.let { return it }
        validateGroupsSection(firstDoc)?.let { return it }
        validateRulesSection(firstDoc)?.let { return it }
        return null
    }

    private fun validateProxiesSection(root: Map<*, *>): String? {
        val seenNames = LinkedHashSet<String>()
        for (key in listOf("proxies", "Proxy", "Proxies")) {
            if (!root.containsKey(key)) continue
            val section = root[key] ?: continue
            if (section !is List<*>) return "$key 段不是列表"
            validateProxyList(section, seenNames)?.let { return it }
        }
        return null
    }

    private fun validateProxyList(list: List<*>, seenNames: MutableSet<String>): String? {
        for ((idx, entry) in list.withIndex()) {
            if (entry !is Map<*, *>) return "第 ${idx + 1} 个节点不是键值映射"
            val name = entry["name"]?.toString()
            if (name.isNullOrBlank()) return "第 ${idx + 1} 个节点缺少 name"
            if (entry["type"]?.toString().isNullOrBlank()) return "节点「$name」缺少 type"
            if (!seenNames.add(name)) return "存在重复节点名: $name"
        }
        return null
    }

    private fun validateGroupsSection(root: Map<*, *>): String? {
        val seenNames = LinkedHashSet<String>()
        for (key in listOf("proxy-groups", "Proxy Group")) {
            if (!root.containsKey(key)) continue
            val section = root[key] ?: continue
            if (section !is List<*>) return "$key 段不是列表"
            validateGroupList(section, seenNames)?.let { return it }
        }
        return null
    }

    private fun validateGroupList(list: List<*>, seenNames: MutableSet<String>): String? {
        for ((idx, entry) in list.withIndex()) {
            if (entry !is Map<*, *>) return "第 ${idx + 1} 个策略组不是键值映射"
            val name = entry["name"]?.toString()
            if (name.isNullOrBlank()) return "第 ${idx + 1} 个策略组缺少 name"
            if (!seenNames.add(name)) return "存在重复策略组名: $name"
            if (entry["type"]?.toString().isNullOrBlank()) return "策略组「$name」缺少 type"
        }
        return null
    }

    private fun validateRulesSection(root: Map<*, *>): String? {
        for (key in listOf("rules", "Rule")) {
            if (!root.containsKey(key)) continue
            val section = root[key] ?: continue
            if (section !is List<*>) return "$key 段不是列表"
            validateRuleList(section)?.let { return it }
        }
        return null
    }

    private fun validateRuleList(list: List<*>): String? {
        for ((idx, entry) in list.withIndex()) {
            if (entry !is String) return "第 ${idx + 1} 条规则不是文本: $entry"
            val trimmed = entry.trim()
            if (!trimmed.contains(',')) return "第 ${idx + 1} 条规则缺少逗号分隔符: $entry"
            val ruleType = trimmed.substringBefore(',').trim()
            if (ruleType.isBlank()) return "第 ${idx + 1} 条规则缺少规则类型: $entry"
            val isCatchAll = ruleType.equals("MATCH", ignoreCase = true) ||
                ruleType.equals("FINAL", ignoreCase = true)
            if (!isCatchAll && trimmed.count { it == ',' } < 2) {
                return "第 ${idx + 1} 条规则缺少目标出站: $entry"
            }
            if (trimmed.substringAfterLast(',').trim().isBlank()) {
                return "第 ${idx + 1} 条规则缺少目标出站: $entry"
            }
        }
        return null
    }

    fun extractProfileName(name: String?, response: Response?, body: String, url: String): String {
        val explicit = sanitizeExtractedName(name)
        if (explicit != null) return explicit

        extractNameFromUrlFragment(url)?.let { return it }
        extractNameFromUrlQuery(url)?.let { return it }
        extractNameFromProfileTitle(response)?.let { return it }
        extractNameFromContentDisposition(response)?.let { return it }
        extractNameFromYamlComments(body)?.let { return it }
        extractNameFromHost(url)?.let { return it }
        return "订阅-${System.currentTimeMillis() % FALLBACK_SUFFIX_MOD}"
    }

    private fun sanitizeExtractedName(raw: String?): String? =
        raw?.let(ConfigSanitizer::stripControlAndLineBreaks)?.trim()?.takeIf { it.isNotEmpty() }

    private fun extractNameFromUrlFragment(url: String): String? {
        val rawFrag = url.trim().toHttpUrlOrNull()?.fragment
            ?: runCatching { URI(url.trim()).rawFragment }.getOrNull()
        val decoded = rawFrag?.let { decodePercentSafely(it, plusAsSpace = true) }
        return sanitizeExtractedName(decoded)
    }

    private fun extractNameFromUrlQuery(url: String): String? {
        val rawQuery = runCatching { URI(url.trim()).rawQuery }.getOrNull()
            ?: url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() }
            ?: return null
        for (param in rawQuery.split('&')) {
            val pair = param.split('=', limit = 2)
            val keyMatch = pair.size == 2 &&
                (pair[0].equals("name", ignoreCase = true) || pair[0].equals("title", ignoreCase = true))
            if (keyMatch) {
                val decoded = decodePercentSafely(pair[1], plusAsSpace = true)
                sanitizeExtractedName(decoded)?.let { return it }
            }
        }
        return null
    }

    private fun extractNameFromProfileTitle(response: Response?): String? {
        val title = response?.header("profile-title") ?: return null
        return sanitizeExtractedName(decodePercentSafely(title, plusAsSpace = false))
    }

    private fun extractNameFromContentDisposition(response: Response?): String? {
        val contentDisp = response?.header("content-disposition") ?: return null
        val params = splitHeaderParameters(contentDisp)
        val extFilename = params.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
        if (extFilename != null) {
            val rawValue = extFilename.substringAfter('=').trim()
            val encodedPart = if (rawValue.contains("''")) rawValue.substringAfter("''") else rawValue
            val cleanEncoded = encodedPart.trim('"', '\'', ' ')
            val decoded = decodePercentSafely(cleanEncoded, plusAsSpace = false)
            val cleanName = YAML_EXT_REGEX.replace(decoded.trim(), "")
            sanitizeExtractedName(cleanName)?.let { return it }
        }
        val plainFilename = params.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        if (plainFilename != null) {
            val rawValue = plainFilename.substringAfter('=').trim('"', '\'', ' ')
            val cleanName = YAML_EXT_REGEX.replace(rawValue, "")
            sanitizeExtractedName(cleanName)?.let { return it }
        }
        return null
    }

    private fun splitHeaderParameters(header: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in header) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == ';' && !inQuotes -> {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let { parts += it }
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts
    }

    private fun decodePercentSafely(raw: String, plusAsSpace: Boolean): String {
        val prepared = if (plusAsSpace) raw else raw.replace("+", "%2B")
        return try {
            URLDecoder.decode(prepared, "UTF-8")
        } catch (_: IllegalArgumentException) {
            raw
        }
    }

    private fun extractNameFromYamlComments(body: String): String? {
        val prefixes = listOf("name:", "title:", "名称:", "订阅名称:")
        for (line in body.lineSequence().take(COMMENT_SCAN_LINES)) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#")) continue
            val afterHash = trimmed.removePrefix("#").trim()
            if (prefixes.any { afterHash.startsWith(it, ignoreCase = true) }) {
                val rawValue = afterHash.substringAfter(":").trim().trim('"', '\'')
                sanitizeExtractedName(rawValue)?.let { return it }
            }
        }
        return null
    }

    private fun extractNameFromHost(url: String): String? {
        val host = url.trim().toHttpUrlOrNull()?.host
            ?: runCatching { URI(url.trim()).host }.getOrNull()
        return sanitizeExtractedName(host?.removePrefix("www.")?.substringBefore(':'))
    }

    fun sanitizeUserConfig(
        raw: String,
        overrides: ConfigOverrides = ConfigOverrides(),
        blockThirdPartyDns: Boolean = false
    ): String {
        val yaml = YamlSupport.create()
        return when (val parsed = loadFirstYamlDocument(yaml, raw)) {
            is ParsedDocResult.Loaded -> {
                if (parsed.hasExtraDocs) {
                    Log.w(TAG, "Multi-document YAML input detected; keeping first document only")
                    LogRepository.addLog("warning", "检测到多文档 YAML 订阅，已丢弃后续文档以防配置注入")
                }
                val doc = parsed.doc
                if (doc !is Map<*, *>) {
                    Log.w(TAG, "Top-level config YAML is not a map; replacing with sanitized minimal map")
                    val minimal = applyOverridesToMap(LinkedHashMap(), overrides, blockThirdPartyDns)
                    yaml.dump(minimal)
                } else {
                    val sanitized = stripProtectedKeys(doc)
                    val merged = applyOverridesToMap(sanitized, overrides, blockThirdPartyDns)
                    yaml.dump(merged)
                }
            }
            is ParsedDocResult.ParseError -> {
                Log.w(TAG, "Structural YAML sanitization failed; rejecting malformed config (Fail-Closed)")
                LogRepository.addLog("error", "配置 YAML 结构解析失败，已拒绝加载以防受保护配置被注入")
                throw IllegalArgumentException("配置 YAML 结构解析失败，已拒绝加载")
            }
        }
    }

    private sealed class ParsedDocResult {
        data class Loaded(val doc: Any?, val hasExtraDocs: Boolean) : ParsedDocResult()
        data object ParseError : ParsedDocResult()
    }

    private fun loadFirstYamlDocument(yaml: Yaml, raw: String): ParsedDocResult {
        return try {
            val docs = yaml.loadAll(raw).iterator()
            val first = if (docs.hasNext()) docs.next() else null
            val hasExtra = runCatching { hasAdditionalDocument(docs) }.getOrDefault(true)
            ParsedDocResult.Loaded(first, hasExtra)
        } catch (t: Throwable) {
            Log.w(TAG, "Structural config sanitization failed, falling back to text filter", t)
            ParsedDocResult.ParseError
        }
    }

    private fun stripProtectedKeys(source: Map<*, *>): LinkedHashMap<String, Any?> {
        val sanitized = LinkedHashMap<String, Any?>()
        for ((key, value) in source) {
            val keyStr = key?.toString() ?: continue
            if (keyStr.trim().lowercase(Locale.ROOT) in PROTECTED_TOP_LEVEL_KEYS) continue
            sanitized[keyStr] = value
        }
        return sanitized
    }

    private fun applyOverridesToMap(
        target: LinkedHashMap<String, Any?>,
        overrides: ConfigOverrides,
        blockThirdPartyDns: Boolean
    ): LinkedHashMap<String, Any?> {
        val cleanPrependRules = ConfigSanitizer.sanitizePrependRules(overrides.prependRules)
        if (blockThirdPartyDns || cleanPrependRules.isNotEmpty()) {
            val existingRules = (target["rules"] as? List<*>) ?: emptyList<Any>()
            val dnsPrefix = if (blockThirdPartyDns) {
                listOf(ConfigManager.buildDnsRejectRule())
            } else {
                emptyList()
            }
            // Keep buildDnsRejectRule() at index 0 above user prependRules so DNS protection stays primary.
            target["rules"] = dnsPrefix + cleanPrependRules + existingRules
        }
        mergeHostsOverrides(target, overrides.hostsEntries)
        return target
    }

    private fun mergeHostsOverrides(target: LinkedHashMap<String, Any?>, entries: List<String>) {
        val cleanHosts = ConfigSanitizer.sanitizeHostsEntries(entries)
        if (cleanHosts.isEmpty()) return
        val hosts = LinkedHashMap<String, Any?>()
        for (entry in cleanHosts) {
            val domain = entry.substringBefore(':').trim()
            val hostTarget = entry.substringAfter(':', "").trim()
            if (domain.isNotBlank() && hostTarget.isNotBlank()) {
                hosts[domain] = hostTarget
            }
        }
        if (hosts.isEmpty()) return
        val existingHosts = target["hosts"] as? Map<*, *>
        if (existingHosts != null) {
            for ((key, value) in existingHosts) {
                hosts.putIfAbsent(key?.toString() ?: continue, value)
            }
        }
        target["hosts"] = hosts
    }

    /**
     * Hardened text fallback: enforces base-indent awareness (blocks uniform-indented keys while keeping
     * deeper nested node properties like `port:`), strips top-level flow `{...}` protected keys, and
     * truncates after any second `---` YAML document boundary.
     */
    internal fun sanitizeUserConfigByText(raw: String): String {
        val singleDocLines = extractFirstDocumentLines(raw)
        val baseIndent = detectBaseIndent(singleDocLines)
        val builder = StringBuilder()
        var skipBlock = false
        var flowBraceDepth = 0
        for (line in singleDocLines) {
            val indent = lineIndent(line)
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!skipBlock) builder.appendLine(line)
                continue
            }
            if (flowBraceDepth > 0) {
                flowBraceDepth = updateFlowDepth(flowBraceDepth, trimmed)
                builder.appendLine(stripFlowProtectedKeys(line))
                continue
            }
            if (indent <= baseIndent && !trimmed.startsWith("-")) {
                if (trimmed.startsWith("{")) {
                    skipBlock = false
                    flowBraceDepth = updateFlowDepth(0, trimmed)
                    builder.appendLine(stripFlowProtectedKeys(line))
                    continue
                }
                skipBlock = isProtectedTopLevelKeyLine(trimmed)
            }
            if (!skipBlock) {
                builder.appendLine(line)
            }
        }
        return builder.toString()
    }

    private fun isProtectedTopLevelKeyLine(trimmed: String): Boolean {
        if (!trimmed.contains(':')) return false
        val key = trimmed.substringBefore(':').trim().trim('"', '\'')
        return key.lowercase(Locale.ROOT) in PROTECTED_TOP_LEVEL_KEYS
    }

    private fun updateFlowDepth(currentDepth: Int, trimmed: String): Int {
        var depth = currentDepth
        for (ch in trimmed) {
            when (ch) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    private fun extractFirstDocumentLines(raw: String): List<String> {
        val result = mutableListOf<String>()
        var seenContent = false
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed == "---" || trimmed == "...") {
                if (seenContent) break else continue
            }
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                seenContent = true
            }
            result += line
        }
        return result
    }

    private fun detectBaseIndent(lines: List<String>): Int {
        var minIndent = Int.MAX_VALUE
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = lineIndent(line)
            if (indent < minIndent) minIndent = indent
            if (minIndent == 0) return 0
        }
        return if (minIndent == Int.MAX_VALUE) 0 else minIndent
    }

    private fun lineIndent(line: String): Int {
        var count = 0
        for (ch in line) {
            if (ch == ' ' || ch == '\t') count++ else break
        }
        return count
    }

    private fun stripFlowProtectedKeys(line: String): String {
        val cleaned = FLOW_PROTECTED_PAIR_REGEX.replace(line, "")
        return cleaned.replace(Regex("""\{\s*,\s*"""), "{").replace(Regex(""",\s*\}"""), "}")
    }

    fun injectDnsRejectRuleTextually(text: String): String {
        val rule = ConfigManager.buildDnsRejectRule()
        val lines = text.lines()
        val rulesIndex = findRulesLineIndex(lines)
        if (rulesIndex == -1) {
            val builder = StringBuilder(text)
            if (builder.isNotEmpty() && !builder.endsWith("\n")) builder.append('\n')
            builder.append("rules:\n")
            builder.append("  - ").append(rule).append('\n')
            return builder.toString()
        }
        val rulesIndent = " ".repeat(lineIndent(lines[rulesIndex]))
        val builder = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            builder.append(line).append('\n')
            if (i == rulesIndex) {
                builder.append(rulesIndent).append("  - ").append(rule).append('\n')
            }
        }
        val result = builder.toString()
        return if (result.endsWith("\n\n") && text.endsWith("\n")) result.dropLast(1) else result
    }

    private fun findRulesLineIndex(lines: List<String>): Int {
        val baseIndent = detectBaseIndent(lines)
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (lineIndent(line) > baseIndent) continue
            val key = trimmed.substringBefore(':').trim().trim('"', '\'')
            if (key.equals("rules", ignoreCase = true)) return i
        }
        return -1
    }
}
