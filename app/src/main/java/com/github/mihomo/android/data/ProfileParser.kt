package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

data class ParsedProfile(
    val groupNames: List<String> = emptyList(),
    val groups: Map<String, ProxyGroup> = emptyMap(),
    val groupIcons: Map<String, String> = emptyMap(),
    val totalNodes: Int = 0,
    val totalRules: Int = 0,
    val activeNode: String = ""
)

object ProfileParser {
    private const val TAG = "ProfileParser"

    private val yaml: Yaml by lazy {
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = 100 * 1024 * 1024 // 100 MB safe limit
        }
        Yaml(SafeConstructor(loaderOptions))
    }

    private data class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val savedNodesHash: Int,
        val parsed: ParsedProfile
    )
    private val parsedCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    fun clearCache() {
        parsedCache.clear()
    }

    fun parse(file: File?, savedNodes: Map<String, String> = emptyMap(), context: Context? = null): ParsedProfile {
        if (file == null || !file.exists()) {
            return ParsedProfile()
        }

        val path = file.absolutePath
        val lm = file.lastModified()
        val len = file.length()
        val savedHash = savedNodes.hashCode()
        val cached = parsedCache[path]
        if (cached != null && cached.lastModified == lm && cached.length == len && cached.savedNodesHash == savedHash) {
            return cached.parsed
        }

        var content = file.readText()
        if (context != null) {
            val settings = SettingsManager(context)
            val activeProfile = ConfigManager.getProfiles(context).find { it.file.absolutePath == file.absolutePath }
            val shouldRunScripts = settings.scriptingEnabled && (activeProfile == null || (activeProfile.scriptEnabled && activeProfile.scriptIds.isNotEmpty()))
            if (shouldRunScripts) {
                content = runCatching {
                    ConfigScriptEngine.executeScripts(
                        context = context,
                        rawYaml = content,
                        targetScriptIds = activeProfile?.scriptIds,
                        logToRepo = false
                    )
                }.getOrElse {
                    Log.w(TAG, "ConfigScriptEngine execution in ProfileParser failed: ${it.message}")
                    content
                }
            }
        }

        try {
            val data = runCatching { yaml.load<Map<String, Any?>>(content) }.getOrNull()
            if (data == null) {
                return parseFallback(content, savedNodes)
            }

            // 1. Extract proxies (support 'proxies' and 'Proxy')
            val proxyMap = mutableMapOf<String, String>() // name -> type
            val rawProxies = (data["proxies"] ?: data["Proxy"]) as? List<*>
            if (rawProxies != null) {
                for (item in rawProxies) {
                    if (item is Map<*, *>) {
                        val name = item["name"]?.toString() ?: continue
                        val type = item["type"]?.toString() ?: "Proxy"
                        proxyMap[name] = type
                    }
                }
            }

            // 2. Extract rules count (support 'rules' and 'Rule')
            val rawRules = (data["rules"] ?: data["Rule"]) as? List<*>
            val rulesCount = rawRules?.size ?: 0

            // 3. Extract proxy-groups (support 'proxy-groups' and 'Proxy Group')
            val groupNames = mutableListOf<String>()
            val groupMap = mutableMapOf<String, ProxyGroup>()
            val groupIcons = mutableMapOf<String, String>()
            val rawGroups = (data["proxy-groups"] ?: data["Proxy Group"]) as? List<*>
            val rawGroupNames = rawGroups?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }?.toSet() ?: emptySet()
            if (rawGroups != null) {
                for (item in rawGroups) {
                    if (item is Map<*, *>) {
                        val gName = item["name"]?.toString() ?: continue
                        val gType = item["type"]?.toString() ?: "select"
                        val gIcon = item["icon"]?.toString() ?: item["img-url"]?.toString()
                        val gHidden = item["hidden"] == true || item["hidden"]?.toString()?.equals("true", ignoreCase = true) == true
                        if (!gIcon.isNullOrBlank()) {
                            groupIcons[gName] = gIcon.trim()
                        }
                        val explicitProxies = (item["proxies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                        val filterPattern = item["filter"]?.toString()?.trim()
                        val excludePattern = item["exclude-filter"]?.toString()?.trim()
                        val includeAll = item["include-all"] == true ||
                                item["include-all"]?.toString()?.equals("true", ignoreCase = true) == true ||
                                item["include-all-proxies"] == true ||
                                item["include-all-proxies"]?.toString()?.equals("true", ignoreCase = true) == true

                        val memberSet = LinkedHashSet<String>()
                        memberSet.addAll(explicitProxies)

                        val filterRegex = if (!filterPattern.isNullOrBlank()) {
                            runCatching { Regex(filterPattern, RegexOption.IGNORE_CASE) }.getOrNull()
                        } else null

                        val excludeRegex = if (!excludePattern.isNullOrBlank()) {
                            runCatching { Regex(excludePattern, RegexOption.IGNORE_CASE) }.getOrNull()
                        } else null

                        if (includeAll) {
                            for (pName in proxyMap.keys) {
                                if (excludeRegex == null || !excludeRegex.containsMatchIn(pName)) {
                                    memberSet.add(pName)
                                }
                            }
                        } else if (filterRegex != null) {
                            for (pName in proxyMap.keys) {
                                if (filterRegex.containsMatchIn(pName)) {
                                    if (excludeRegex == null || !excludeRegex.containsMatchIn(pName)) {
                                        memberSet.add(pName)
                                    }
                                }
                            }
                        } else if (excludeRegex != null) {
                            memberSet.removeAll { excludeRegex.containsMatchIn(it) }
                        }

                        val memberNames = memberSet.toList()

                        val proxies = memberNames.mapNotNull { mName ->
                            val mType = proxyMap[mName] ?: if (mName in listOf("DIRECT", "REJECT", "GLOBAL")) mName else "Group"
                            val isSubGroup = rawGroupNames.contains(mName)
                            Proxy(
                                name = mName,
                                title = mName,
                                subtitle = "",
                                type = mType,
                                delay = -1,
                                isGroup = isSubGroup
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
                }
            }

            // Fallback: If no proxy-groups defined, construct default "节点选择" group with all proxies
            if (groupNames.isEmpty() && proxyMap.isNotEmpty()) {
                val proxies = proxyMap.map { (name, type) ->
                    Proxy(name = name, title = name, type = type, delay = -1)
                }
                val saved = savedNodes["节点选择"]
                val now = if (saved != null && proxies.any { it.name == saved }) saved else (proxies.firstOrNull()?.name ?: "")
                groupNames.add("节点选择")
                groupMap["节点选择"] = ProxyGroup(type = "select", proxies = proxies, now = now)
            }

            val totalNodes = proxyMap.size
            val activeNode = groupMap.values.firstOrNull { !it.hidden }?.now ?: groupMap.values.firstOrNull()?.now ?: ""
            Log.i(TAG, "Parsed profile: ${groupNames.size} groups, $totalNodes nodes, $rulesCount rules")

            val result = ParsedProfile(
                groupNames = groupNames,
                groups = groupMap,
                groupIcons = groupIcons,
                totalNodes = totalNodes,
                totalRules = rulesCount,
                activeNode = activeNode
            )
            parsedCache[path] = CacheEntry(lm, len, savedHash, result)
            return result
        } catch (e: Throwable) {
            Log.w(TAG, "SnakeYAML failed, using robust line fallback: ${e.message}")
            val fallback = parseFallback(content, savedNodes)
            parsedCache[path] = CacheEntry(lm, len, savedHash, fallback)
            return fallback
        }
    }

    private fun parseFallback(content: String, savedNodes: Map<String, String>): ParsedProfile {
        val proxyMap = mutableMapOf<String, String>()
        val groupNames = mutableListOf<String>()
        val groupMap = mutableMapOf<String, ProxyGroup>()
        val groupIcons = mutableMapOf<String, String>()
        var inProxies = false
        var inGroups = false
        var currentName: String? = null
        var currentType: String? = null
        var currentGroupName: String? = null
        var currentGroupType = "select"
        var currentGroupHidden = false
        var currentGroupFilter: String? = null
        var currentGroupIncludeAll = false
        var currentGroupProxies = mutableListOf<Proxy>()
        var rulesCount = 0

        fun finalizeGroup() {
            if (currentGroupName != null) {
                if (!currentGroupHidden) {
                    groupNames.add(currentGroupName!!)
                }
                val existingNames = currentGroupProxies.map { it.name }.toSet()
                if (currentGroupIncludeAll) {
                    for ((pName, pType) in proxyMap) {
                        if (!existingNames.contains(pName)) {
                            currentGroupProxies.add(Proxy(name = pName, title = pName, type = pType, delay = -1))
                        }
                    }
                } else if (!currentGroupFilter.isNullOrBlank()) {
                    val regex = runCatching { Regex(currentGroupFilter!!, RegexOption.IGNORE_CASE) }.getOrNull()
                    if (regex != null) {
                        for ((pName, pType) in proxyMap) {
                            if (!existingNames.contains(pName) && regex.containsMatchIn(pName)) {
                                currentGroupProxies.add(Proxy(name = pName, title = pName, type = pType, delay = -1))
                            }
                        }
                    }
                }
                val saved = savedNodes[currentGroupName!!]
                val now = if (saved != null && currentGroupProxies.any { it.name == saved }) saved else (currentGroupProxies.firstOrNull()?.name ?: "")
                groupMap[currentGroupName!!] = ProxyGroup(
                    type = currentGroupType,
                    proxies = currentGroupProxies.toList(),
                    now = now,
                    hidden = currentGroupHidden
                )
            }
        }

        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val lower = trimmed.lowercase()

            if (lower.startsWith("proxies:") || lower.startsWith("proxy:")) {
                inProxies = true
                inGroups = false
                return@forEach
            }
            if (lower.startsWith("proxy-groups:") || lower.startsWith("proxy group:")) {
                if (currentName != null) {
                    proxyMap[currentName!!] = currentType ?: "Proxy"
                    currentName = null
                }
                inProxies = false
                inGroups = true
                return@forEach
            }
            if (lower.startsWith("rules:") || lower.startsWith("rule:")) {
                finalizeGroup()
                currentGroupName = null
                inProxies = false
                inGroups = false
            }

            if (inProxies) {
                if (trimmed.startsWith("- name:") || trimmed.startsWith("- {name:")) {
                    if (currentName != null) {
                        proxyMap[currentName!!] = currentType ?: "Proxy"
                    }
                    val namePart = if (trimmed.startsWith("- name:")) {
                        trimmed.removePrefix("- name:").trim()
                    } else {
                        trimmed.substringAfter("name:").substringBefore(",").trim()
                    }
                    currentName = namePart.trim('"', '\'')
                    currentType = if (trimmed.contains("type:")) {
                        trimmed.substringAfter("type:").substringBefore(",").substringBefore("}").trim().trim('"', '\'')
                    } else null
                } else if (trimmed.startsWith("type:")) {
                    currentType = trimmed.removePrefix("type:").trim().trim('"', '\'')
                }
            } else if (inGroups) {
                if (trimmed.startsWith("- name:")) {
                    finalizeGroup()
                    currentGroupName = trimmed.removePrefix("- name:").trim().trim('"', '\'')
                    currentGroupType = "select"
                    currentGroupHidden = false
                    currentGroupFilter = null
                    currentGroupIncludeAll = false
                    currentGroupProxies = mutableListOf()
                } else if (trimmed.startsWith("type:")) {
                    currentGroupType = trimmed.removePrefix("type:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("hidden:")) {
                    currentGroupHidden = trimmed.removePrefix("hidden:").trim().trim('"', '\'').toBoolean()
                } else if (trimmed.startsWith("filter:")) {
                    currentGroupFilter = trimmed.removePrefix("filter:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("include-all:") || trimmed.startsWith("include-all-proxies:")) {
                    currentGroupIncludeAll = trimmed.substringAfter(":").trim().trim('"', '\'').toBoolean()
                } else if (trimmed.startsWith("icon:") && currentGroupName != null) {
                    groupIcons[currentGroupName!!] = trimmed.removePrefix("icon:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("img-url:") && currentGroupName != null) {
                    groupIcons[currentGroupName!!] = trimmed.removePrefix("img-url:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("- ") && !trimmed.startsWith("- name:")) {
                    val pName = trimmed.removePrefix("- ").trim().trim('"', '\'')
                    if (pName.isNotBlank() && currentGroupName != null) {
                        val pType = proxyMap[pName] ?: if (pName in listOf("DIRECT", "REJECT", "GLOBAL")) pName else "Group"
                        currentGroupProxies.add(Proxy(name = pName, title = pName, type = pType, delay = -1))
                    }
                }
            } else if (!inProxies && !inGroups && content.contains("rules:")) {
                if (trimmed.startsWith("- ")) {
                    rulesCount++
                }
            }
        }

        if (currentName != null) {
            proxyMap[currentName!!] = currentType ?: "Proxy"
        }
        finalizeGroup()

        if (groupNames.isEmpty() && proxyMap.isNotEmpty()) {
            val proxies = proxyMap.map { (name, type) ->
                Proxy(name = name, title = name, type = type, delay = -1)
            }
            val saved = savedNodes["节点选择"]
            val now = if (saved != null && proxies.any { it.name == saved }) saved else (proxies.firstOrNull()?.name ?: "")
            groupNames.add("节点选择")
            groupMap["节点选择"] = ProxyGroup(type = "select", proxies = proxies, now = now)
        }

        return ParsedProfile(
            groupNames = groupNames,
            groups = groupMap,
            groupIcons = groupIcons,
            totalNodes = proxyMap.size,
            totalRules = rulesCount,
            activeNode = groupMap.values.firstOrNull()?.now ?: ""
        )
    }
}
