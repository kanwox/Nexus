package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ParsedRule(
    val type: String,
    val payload: String,
    val target: String,
    val noResolve: Boolean = false,
)

data class ParsedProfile(
    val groupNames: List<String> = emptyList(),
    val groups: Map<String, ProxyGroup> = emptyMap(),
    val groupIcons: Map<String, String> = emptyMap(),
    val totalNodes: Int = 0,
    val totalRules: Int = 0,
    val activeNode: String = "",
    val rules: List<ParsedRule> = emptyList(),
)

private const val PROFILE_PARSER_TAG = "ProfileParser"
private const val MAX_REGEX_PATTERN_LENGTH = 512
private const val MAX_REGEX_INPUT_LENGTH = 4096
private const val DEFAULT_REGEX_TIMEOUT_MS = 25L
private const val MAX_REGEX_CHAR_ACCESSES = 100_000
private const val TIME_CHECK_MASK = 0xFF
private const val FALLBACK_GROUP_NAME = "节点选择"

private val PROXIES_SECTION_KEYS = listOf("proxies", "Proxy", "Proxies", "proxy")
private val PROXY_GROUPS_SECTION_KEYS = listOf(
    "proxy-groups",
    "Proxy Group",
    "Proxy-Groups",
    "Proxy-Group",
    "proxy-group",
    "Proxy Groups"
)
private val RULES_SECTION_KEYS = listOf("rules", "Rule", "Rules", "rule")
private val GROUP_PROXIES_KEYS = listOf("proxies", "Proxies", "Proxy", "proxy")
private val BUILTIN_TARGETS = setOf("DIRECT", "REJECT", "GLOBAL")

private class RegexTimeoutException(message: String) : RuntimeException(message)

private class BoundedTimeoutCharSequence(
    private val inner: CharSequence,
    private val deadlineNs: Long,
    private val maxCharAccesses: Int,
    private val counter: IntArray = intArrayOf(0)
) : CharSequence {
    override val length: Int
        get() = inner.length

    override fun get(index: Int): Char {
        val count = ++counter[0]
        if (count > maxCharAccesses) {
            throw RegexTimeoutException("Regex exceeded step budget ($maxCharAccesses)")
        }
        if ((count and TIME_CHECK_MASK) == 0 && System.nanoTime() > deadlineNs) {
            throw RegexTimeoutException("Regex exceeded time budget")
        }
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BoundedTimeoutCharSequence(
            inner.subSequence(startIndex, endIndex),
            deadlineNs,
            maxCharAccesses,
            counter
        )

    override fun toString(): String = inner.toString()
}

private enum class FallbackSection { NONE, PROXIES, GROUPS, RULES }

private data class FallbackParserState(
    val proxyMap: MutableMap<String, String> = mutableMapOf(),
    val groupNames: MutableList<String> = mutableListOf(),
    val groupMap: MutableMap<String, ProxyGroup> = mutableMapOf(),
    val groupIcons: MutableMap<String, String> = mutableMapOf(),
    val fallbackRules: MutableList<ParsedRule> = mutableListOf(),
    var section: FallbackSection = FallbackSection.NONE,
    var currentProxyName: String? = null,
    var currentProxyType: String? = null,
    var currentGroupName: String? = null,
    var currentGroupType: String = "select",
    var currentGroupHidden: Boolean = false,
    var currentGroupFilter: String? = null,
    var currentGroupExcludeFilter: String? = null,
    var currentGroupIncludeAll: Boolean = false,
    var currentGroupProxies: MutableList<Proxy> = mutableListOf(),
    var rulesCount: Int = 0
)

object ProfileParser {

    private data class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val savedNodesHash: Int,
        val scriptKey: String,
        val prependRulesHash: Int,
        val parsed: ParsedProfile
    ) {
        fun isValid(
            lm: Long,
            len: Long,
            hash: Int,
            sKey: String,
            prependHash: Int
        ): Boolean {
            val fileUnchanged = lastModified == lm && length == len
            val optionsMatch = savedNodesHash == hash && scriptKey == sKey
            return fileUnchanged && optionsMatch && prependRulesHash == prependHash
        }
    }

    private val parsedCache = ConcurrentHashMap<String, CacheEntry>()

    fun clearCache() {
        parsedCache.clear()
    }

    /**
     * Parses a profile file into the node/group/rule model the UI renders.
     *
     * `suspend` because the config-rewrite scripts run inside this function and the JS
     * engine blocks its calling thread for up to the script timeout.
     */
    suspend fun parse(
        file: File?,
        savedNodes: Map<String, String> = emptyMap(),
        context: Context? = null
    ): ParsedProfile = parseWithOverrides(file, savedNodes, context, emptyList())

    internal suspend fun parseWithOverrides(
        file: File?,
        savedNodes: Map<String, String> = emptyMap(),
        context: Context? = null,
        overridePrependRules: List<String> = emptyList()
    ): ParsedProfile {
        if (file == null || !file.exists()) {
            return ParsedProfile()
        }

        val path = file.absolutePath
        val lm = file.lastModified()
        val len = file.length()
        val savedHash = savedNodes.hashCode()
        val activeProfile = resolveActiveProfile(file, context)
        val resolvedScriptIds = resolveScriptIds(context, activeProfile)
        val scriptKey = resolvedScriptIds.sorted().joinToString(",")
        val prependRules = resolvePrependRules(activeProfile, overridePrependRules)
        val prependHash = prependRules.hashCode()

        val cached = parsedCache[path]
        if (cached?.isValid(lm, len, savedHash, scriptKey, prependHash) == true) {
            return cached.parsed
        }

        val rawText = file.readText()
        val (content, scriptRewriteFailed) = applyProfileScripts(context, rawText, resolvedScriptIds)
        val parsed = parseYamlOrFallback(content, savedNodes, prependRules)
        if (!scriptRewriteFailed) {
            parsedCache[path] = CacheEntry(lm, len, savedHash, scriptKey, prependHash, parsed)
        }
        return parsed
    }

    internal fun compileSafeRegex(pattern: String?): Regex? {
        val trimmed = pattern?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_REGEX_PATTERN_LENGTH) {
            Log.w(
                PROFILE_PARSER_TAG,
                "Ignoring proxy filter regex exceeding $MAX_REGEX_PATTERN_LENGTH chars (length=${trimmed.length})"
            )
            return null
        }
        return try {
            Regex(trimmed, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Log.w(PROFILE_PARSER_TAG, "Ignoring invalid proxy filter regex", e)
            null
        }
    }

    internal fun safeRegexContains(
        regex: Regex,
        input: String,
        timeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS,
        maxCharAccesses: Int = MAX_REGEX_CHAR_ACCESSES
    ): Boolean {
        val boundedInput = if (input.length > MAX_REGEX_INPUT_LENGTH) {
            input.substring(0, MAX_REGEX_INPUT_LENGTH)
        } else {
            input
        }
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        val guarded = BoundedTimeoutCharSequence(boundedInput, deadlineNs, maxCharAccesses)
        return try {
            regex.containsMatchIn(guarded)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: RegexTimeoutException) {
            Log.w(PROFILE_PARSER_TAG, "Aborted slow proxy filter regex", e)
            false
        } catch (e: StackOverflowError) {
            Log.w(PROFILE_PARSER_TAG, "Aborted backtracking overflow in proxy filter regex", e)
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveActiveProfile(file: File, context: Context?): ProfileItem? {
        if (context == null) return null
        return ConfigManager.getProfiles(context).find {
            it.file.name == file.name ||
                it.file.absolutePath == file.absolutePath ||
                it.id == file.nameWithoutExtension
        }
    }

    private fun resolveScriptIds(context: Context?, activeProfile: ProfileItem?): List<String> {
        val allowScripts = context != null && SettingsManager(context).scriptingEnabled
        return if (allowScripts && activeProfile?.scriptEnabled == true) {
            activeProfile.scriptIds
        } else {
            emptyList()
        }
    }

    private fun resolvePrependRules(
        activeProfile: ProfileItem?,
        overridePrependRules: List<String>
    ): List<String> {
        val raw = if (activeProfile != null && activeProfile.prependRules.isNotEmpty()) {
            activeProfile.prependRules
        } else {
            overridePrependRules
        }
        return ConfigSanitizer.sanitizePrependRules(raw)
    }

    private suspend fun applyProfileScripts(
        context: Context?,
        rawContent: String,
        resolvedScriptIds: List<String>
    ): Pair<String, Boolean> {
        if (context == null || resolvedScriptIds.isEmpty()) {
            return rawContent to false
        }
        return try {
            val scriptResult = ConfigScriptEngine.executeScripts(
                context = context,
                rawYaml = rawContent,
                targetScriptIds = resolvedScriptIds,
                logToRepo = false
            )
            if (scriptResult.hasFailures) {
                Log.w(
                    PROFILE_PARSER_TAG,
                    "Config scripts partially failed in ProfileParser " +
                        "(${scriptResult.failed.size}/${scriptResult.total}); not caching the result"
                )
            }
            scriptResult.yaml to scriptResult.hasFailures
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(PROFILE_PARSER_TAG, "ConfigScriptEngine execution in ProfileParser failed", e)
            rawContent to true
        }
    }
}

private fun parseYamlOrFallback(
    content: String,
    savedNodes: Map<String, String>,
    prependRules: List<String>
): ParsedProfile {
    return try {
        val yaml = YamlSupport.create()
        val data = yaml.load<Any?>(content) as? Map<*, *>
        if (data == null) {
            parseFallback(content, savedNodes, prependRules)
        } else {
            buildParsedProfileFromMap(data, savedNodes, prependRules)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Log.w(PROFILE_PARSER_TAG, "SnakeYAML failed, using robust line fallback", e)
        parseFallback(content, savedNodes, prependRules)
    } catch (e: StackOverflowError) {
        Log.w(PROFILE_PARSER_TAG, "SnakeYAML overflowed, using robust line fallback", e)
        parseFallback(content, savedNodes, prependRules)
    }
}

private fun buildParsedProfileFromMap(
    data: Map<*, *>,
    savedNodes: Map<String, String>,
    prependRules: List<String>
): ParsedProfile {
    val proxyMap = extractProxyMap(data)
    val rawRules = data.getSection(RULES_SECTION_KEYS) as? List<*>
    val prependParsedRules = extractParsedRules(prependRules)
    val profileParsedRules = extractParsedRules(rawRules)
    val parsedRules = prependParsedRules + profileParsedRules
    val rawRuleCount = if (profileParsedRules.isNotEmpty()) profileParsedRules.size else (rawRules?.size ?: 0)
    val rulesCount = prependParsedRules.size + rawRuleCount

    val groupNames = mutableListOf<String>()
    val groupMap = mutableMapOf<String, ProxyGroup>()
    val groupIcons = mutableMapOf<String, String>()
    val rawGroups = data.getSection(PROXY_GROUPS_SECTION_KEYS) as? List<*>
    val rawGroupNames = rawGroups
        ?.mapNotNull { (it as? Map<*, *>)?.getField("name")?.toString() }
        ?.toSet()
        ?: emptySet()

    if (rawGroups != null) {
        for (item in rawGroups) {
            if (item is Map<*, *>) {
                addGroupFromMap(
                    item = item,
                    proxyMap = proxyMap,
                    rawGroupNames = rawGroupNames,
                    savedNodes = savedNodes,
                    groupNames = groupNames,
                    groupMap = groupMap,
                    groupIcons = groupIcons
                )
            }
        }
    }

    ensureDefaultFallbackGroup(groupNames, groupMap, proxyMap, savedNodes)

    val totalNodes = proxyMap.size
    val activeNode = groupMap.values.firstOrNull { !it.hidden }?.now
        ?: groupMap.values.firstOrNull()?.now
        ?: ""
    Log.i(PROFILE_PARSER_TAG, "Parsed profile: ${groupNames.size} groups, $totalNodes nodes, $rulesCount rules")

    return ParsedProfile(
        groupNames = groupNames,
        groups = groupMap,
        groupIcons = groupIcons,
        totalNodes = totalNodes,
        totalRules = rulesCount,
        activeNode = activeNode,
        rules = parsedRules
    )
}

private fun addGroupFromMap(
    item: Map<*, *>,
    proxyMap: Map<String, String>,
    rawGroupNames: Set<String>,
    savedNodes: Map<String, String>,
    groupNames: MutableList<String>,
    groupMap: MutableMap<String, ProxyGroup>,
    groupIcons: MutableMap<String, String>
) {
    val gName = item.getField("name")?.toString() ?: return
    val gType = item.getField("type")?.toString() ?: "select"
    val gIcon = (item.getField("icon") ?: item.getField("img-url"))?.toString()
    val gHidden = isYamlTrue(item.getField("hidden"))
    if (!gIcon.isNullOrBlank()) {
        groupIcons[gName] = gIcon.trim()
    }
    val explicitRaw = item.getSection(GROUP_PROXIES_KEYS) as? List<*>
    val explicitProxies = explicitRaw?.mapNotNull { it?.toString() } ?: emptyList()
    val filterPattern = item.getField("filter")?.toString()?.trim()
    val excludePattern = item.getField("exclude-filter")?.toString()?.trim()
    val includeAll = isYamlTrue(item.getField("include-all")) ||
        isYamlTrue(item.getField("include-all-proxies"))

    val memberNames = resolveGroupMembers(
        explicitProxies = explicitProxies,
        proxyNames = proxyMap.keys,
        filterPattern = filterPattern,
        excludePattern = excludePattern,
        includeAll = includeAll
    )
    val proxies = memberNames.map { mName ->
        val mType = proxyMap[mName] ?: if (mName in BUILTIN_TARGETS) mName else "Group"
        Proxy(
            name = mName,
            title = mName,
            subtitle = "",
            type = mType,
            delay = -1,
            isGroup = rawGroupNames.contains(mName)
        )
    }

    val saved = savedNodes[gName]
    val now = if (saved != null && proxies.any { it.name == saved }) {
        saved
    } else {
        proxies.firstOrNull()?.name ?: ""
    }
    if (!gHidden) {
        groupNames.add(gName)
    }
    groupMap[gName] = ProxyGroup(type = gType, proxies = proxies, now = now, hidden = gHidden)
}

private fun isYamlTrue(value: Any?): Boolean =
    value == true || value?.toString()?.equals("true", ignoreCase = true) == true

private fun ensureDefaultFallbackGroup(
    groupNames: MutableList<String>,
    groupMap: MutableMap<String, ProxyGroup>,
    proxyMap: Map<String, String>,
    savedNodes: Map<String, String>
) {
    if (groupNames.isNotEmpty() || proxyMap.isEmpty()) return
    val proxies = proxyMap.map { (name, type) ->
        Proxy(name = name, title = name, type = type, delay = -1)
    }
    val saved = savedNodes[FALLBACK_GROUP_NAME]
    val now = if (saved != null && proxies.any { it.name == saved }) {
        saved
    } else {
        proxies.firstOrNull()?.name ?: ""
    }
    groupNames.add(FALLBACK_GROUP_NAME)
    groupMap[FALLBACK_GROUP_NAME] = ProxyGroup(type = "select", proxies = proxies, now = now)
}

private fun Map<*, *>.getSection(candidates: List<String>): Any? {
    for (key in candidates) {
        if (containsKey(key)) return this[key]
    }
    for ((k, v) in entries) {
        val kStr = k?.toString() ?: continue
        if (candidates.any { it.equals(kStr, ignoreCase = true) }) {
            return v
        }
    }
    return null
}

private fun Map<*, *>.getField(targetKey: String): Any? {
    if (containsKey(targetKey)) return this[targetKey]
    for ((k, v) in entries) {
        val kStr = k?.toString() ?: continue
        if (kStr.equals(targetKey, ignoreCase = true)) {
            return v
        }
    }
    return null
}

private fun extractProxyMap(data: Map<*, *>): Map<String, String> {
    val proxyMap = mutableMapOf<String, String>()
    val rawProxies = data.getSection(PROXIES_SECTION_KEYS) as? List<*> ?: return proxyMap
    for (item in rawProxies) {
        if (item is Map<*, *>) {
            val name = item.getField("name")?.toString() ?: continue
            val type = item.getField("type")?.toString() ?: "Proxy"
            proxyMap[name] = type
        }
    }
    return proxyMap
}

private fun extractParsedRules(rawRules: List<*>?): List<ParsedRule> {
    val parsedRules = mutableListOf<ParsedRule>()
    if (rawRules == null) return parsedRules
    for (item in rawRules) {
        val parsed = parseRuleLine(item?.toString()) ?: continue
        parsedRules.add(parsed)
    }
    return parsedRules
}

private fun parseRuleLine(raw: String?): ParsedRule? {
    val ruleStr = raw?.trim() ?: return null
    if (ruleStr.isBlank() || ruleStr.startsWith("#")) return null
    val parts = splitTopLevelRuleParts(ruleStr)
    if (parts.isEmpty() || parts[0].isEmpty()) return null
    val rType = parts[0]
    val rPayload = if (parts.size >= 3) parts[1] else ""
    val rTarget = when {
        parts.size >= 3 -> parts[2]
        parts.size == 2 -> parts[1]
        else -> ""
    }
    val rNoResolve = parts.any { it.equals("no-resolve", ignoreCase = true) }
    return ParsedRule(rType, rPayload, rTarget, rNoResolve)
}

private fun splitTopLevelRuleParts(ruleStr: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var parenDepth = 0
    for (ch in ruleStr) {
        when (ch) {
            '(' -> {
                parenDepth++
                current.append(ch)
            }
            ')' -> {
                if (parenDepth > 0) parenDepth--
                current.append(ch)
            }
            ',' -> if (parenDepth == 0) {
                parts.add(current.toString().trim())
                current.setLength(0)
            } else {
                current.append(ch)
            }
            else -> current.append(ch)
        }
    }
    parts.add(current.toString().trim())
    return parts
}

private fun resolveGroupMembers(
    explicitProxies: List<String>,
    proxyNames: Set<String>,
    filterPattern: String?,
    excludePattern: String?,
    includeAll: Boolean
): List<String> {
    val memberSet = LinkedHashSet<String>(explicitProxies)
    val filterRegex = ProfileParser.compileSafeRegex(filterPattern)
    val excludeRegex = ProfileParser.compileSafeRegex(excludePattern)

    if (includeAll) {
        for (pName in proxyNames) {
            if (excludeRegex == null || !ProfileParser.safeRegexContains(excludeRegex, pName)) {
                memberSet.add(pName)
            }
        }
    } else if (filterRegex != null) {
        for (pName in proxyNames) {
            val matchesFilter = ProfileParser.safeRegexContains(filterRegex, pName)
            val excluded = excludeRegex != null && ProfileParser.safeRegexContains(excludeRegex, pName)
            if (matchesFilter && !excluded) {
                memberSet.add(pName)
            }
        }
    } else if (excludeRegex != null) {
        memberSet.removeAll { ProfileParser.safeRegexContains(excludeRegex, it) }
    }
    return memberSet.toList()
}

private fun parseFallback(
    content: String,
    savedNodes: Map<String, String>,
    prependRules: List<String>
): ParsedProfile {
    val state = FallbackParserState()
    val prependParsedRules = extractParsedRules(prependRules)
    state.fallbackRules.addAll(prependParsedRules)
    state.rulesCount += prependParsedRules.size

    content.lineSequence().forEach { rawLine ->
        processFallbackLine(rawLine, state, savedNodes)
    }

    flushPendingProxy(state)
    finalizeFallbackGroup(state, savedNodes)
    ensureDefaultFallbackGroup(state.groupNames, state.groupMap, state.proxyMap, savedNodes)

    return ParsedProfile(
        groupNames = state.groupNames,
        groups = state.groupMap,
        groupIcons = state.groupIcons,
        totalNodes = state.proxyMap.size,
        totalRules = state.rulesCount,
        activeNode = state.groupMap.values.firstOrNull { !it.hidden }?.now
            ?: state.groupMap.values.firstOrNull()?.now
            ?: "",
        rules = state.fallbackRules
    )
}

private fun processFallbackLine(
    rawLine: String,
    state: FallbackParserState,
    savedNodes: Map<String, String>
) {
    val trimmed = rawLine.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return
    if (handleFallbackSectionHeader(rawLine, trimmed, state, savedNodes)) return

    when (state.section) {
        FallbackSection.PROXIES -> parseFallbackProxyLine(trimmed, state)
        FallbackSection.GROUPS -> parseFallbackGroupLine(trimmed, state, savedNodes)
        FallbackSection.RULES -> parseFallbackRuleLine(trimmed, state)
        FallbackSection.NONE -> Unit
    }
}

private fun handleFallbackSectionHeader(
    rawLine: String,
    trimmed: String,
    state: FallbackParserState,
    savedNodes: Map<String, String>
): Boolean {
    val isTopLevel = !rawLine.first().isWhitespace() && !trimmed.startsWith("-") && trimmed.contains(":")
    if (!isTopLevel) return false
    val key = trimmed.substringBefore(":").trim().trim('"', '\'').lowercase(Locale.ROOT)
    val newSection = when (key) {
        "proxies", "proxy" -> FallbackSection.PROXIES
        "proxy-groups", "proxy group", "proxy-group", "proxy groups" -> FallbackSection.GROUPS
        "rules", "rule" -> FallbackSection.RULES
        else -> FallbackSection.NONE
    }
    if (state.section == FallbackSection.PROXIES && newSection != FallbackSection.PROXIES) {
        flushPendingProxy(state)
    }
    if (state.section == FallbackSection.GROUPS && newSection != FallbackSection.GROUPS) {
        finalizeFallbackGroup(state, savedNodes)
        state.currentGroupName = null
    }
    state.section = newSection
    return true
}

private fun parseFallbackProxyLine(trimmed: String, state: FallbackParserState) {
    val lower = trimmed.lowercase(Locale.ROOT)
    val isInlineMap = trimmed.startsWith("- {") && lower.contains("name:")
    val isListEntry = lower.startsWith("- name:") || isInlineMap
    if (isListEntry) {
        flushPendingProxy(state)
        val proxyName = if (isInlineMap) {
            extractInlineField(trimmed, "name")
        } else {
            trimmed.substringAfter(":").trim().trim('"', '\'')
        }
        state.currentProxyName = proxyName
        state.currentProxyType = extractInlineField(trimmed, "type")
    } else if (lower.startsWith("type:")) {
        state.currentProxyType = trimmed.substringAfter(":").trim().trim('"', '\'')
    }
}

private fun extractInlineField(line: String, field: String): String? {
    val idx = line.indexOf("$field:", ignoreCase = true)
    if (idx < 0) return null
    return line.substring(idx + field.length + 1)
        .substringBefore(",")
        .substringBefore("}")
        .trim()
        .trim('"', '\'')
}

private fun parseFallbackGroupLine(
    trimmed: String,
    state: FallbackParserState,
    savedNodes: Map<String, String>
) {
    val lower = trimmed.lowercase(Locale.ROOT)
    when {
        lower.startsWith("- name:") -> {
            finalizeFallbackGroup(state, savedNodes)
            state.currentGroupName = trimmed.substringAfter(":").trim().trim('"', '\'')
            state.currentGroupType = "select"
            state.currentGroupHidden = false
            state.currentGroupFilter = null
            state.currentGroupExcludeFilter = null
            state.currentGroupIncludeAll = false
            state.currentGroupProxies = mutableListOf()
        }
        lower.startsWith("type:") ->
            state.currentGroupType = trimmed.substringAfter(":").trim().trim('"', '\'')
        lower.startsWith("hidden:") ->
            state.currentGroupHidden = trimmed.substringAfter(":").trim().trim('"', '\'').toBoolean()
        lower.startsWith("filter:") ->
            state.currentGroupFilter = trimmed.substringAfter(":").trim().trim('"', '\'')
        lower.startsWith("exclude-filter:") ->
            state.currentGroupExcludeFilter = trimmed.substringAfter(":").trim().trim('"', '\'')
        lower.startsWith("include-all:") || lower.startsWith("include-all-proxies:") ->
            state.currentGroupIncludeAll = trimmed.substringAfter(":").trim().trim('"', '\'').toBoolean()
        lower.startsWith("icon:") || lower.startsWith("img-url:") -> {
            val gName = state.currentGroupName
            if (gName != null) {
                state.groupIcons[gName] = trimmed.substringAfter(":").trim().trim('"', '\'')
            }
        }
        trimmed.startsWith("- ") -> addFallbackGroupMember(trimmed, state)
    }
}

private fun addFallbackGroupMember(trimmed: String, state: FallbackParserState) {
    val gName = state.currentGroupName ?: return
    if (gName.isEmpty()) return
    val pName = trimmed.removePrefix("- ").trim().trim('"', '\'')
    if (pName.isNotBlank()) {
        val pType = state.proxyMap[pName] ?: if (pName in BUILTIN_TARGETS) pName else "Group"
        state.currentGroupProxies.add(Proxy(name = pName, title = pName, type = pType, delay = -1))
    }
}

private fun parseFallbackRuleLine(trimmed: String, state: FallbackParserState) {
    if (!trimmed.startsWith("- ")) return
    val ruleContent = trimmed.removePrefix("- ").trim().trim('"', '\'')
    val parsed = parseRuleLine(ruleContent)
    if (parsed != null) {
        state.fallbackRules.add(parsed)
    }
    state.rulesCount++
}

private fun flushPendingProxy(state: FallbackParserState) {
    val cName = state.currentProxyName ?: return
    state.proxyMap[cName] = state.currentProxyType ?: "Proxy"
    state.currentProxyName = null
    state.currentProxyType = null
}

private fun finalizeFallbackGroup(state: FallbackParserState, savedNodes: Map<String, String>) {
    val gName = state.currentGroupName ?: return
    if (!state.currentGroupHidden) {
        state.groupNames.add(gName)
    }
    val resolvedMembers = resolveGroupMembers(
        explicitProxies = state.currentGroupProxies.map { it.name },
        proxyNames = state.proxyMap.keys,
        filterPattern = state.currentGroupFilter,
        excludePattern = state.currentGroupExcludeFilter,
        includeAll = state.currentGroupIncludeAll
    )
    val explicitByName = state.currentGroupProxies.associateBy { it.name }
    val finalProxies = resolvedMembers.map { name ->
        explicitByName[name] ?: Proxy(
            name = name,
            title = name,
            type = state.proxyMap[name] ?: "Proxy",
            delay = -1
        )
    }
    val saved = savedNodes[gName]
    val now = if (saved != null && finalProxies.any { it.name == saved }) {
        saved
    } else {
        finalProxies.firstOrNull()?.name ?: ""
    }
    state.groupMap[gName] = ProxyGroup(
        type = state.currentGroupType,
        proxies = finalProxies,
        now = now,
        hidden = state.currentGroupHidden
    )
}
