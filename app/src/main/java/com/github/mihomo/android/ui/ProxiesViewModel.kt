package com.github.mihomo.android.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ParsedProfile
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.CupertinoToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.github.mihomo.android.ui.theme.AppStrings

private const val TAG = "ProxiesViewModel"

/**
 * Owns the proxies/profiles state and the speed-test engine.
 *
 * Living in [viewModelScope] means an in-flight group speed test survives a configuration
 * change (rotation used to cancel it and wipe every measured delay), and all snapshot state
 * below is written directly from worker threads: Compose coalesces snapshot writes into one
 * invalidation pass per frame, so a 50-node sweep no longer causes 50 main-thread dispatches
 * and 50 full-map rebuilds.
 */
class ProxiesViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsManager(app)

    var profiles by mutableStateOf(emptyList<ProfileItem>())
        private set
    var selectedProfileId by mutableStateOf(settings.selectedProfileId)
        private set
    var parsedProfile by mutableStateOf(ParsedProfile())
        private set
    var proxyGroups by mutableStateOf(listOf<String>())
        private set
    var liveGroupMap by mutableStateOf<Map<String, ProxyGroup>>(emptyMap())
        private set
    var currentGroupData by mutableStateOf<ProxyGroup?>(null)
        private set
    var activeNodeName by mutableStateOf("")
        private set

    /** Blank until a group is chosen; persisted selection is not stored across processes. */
    var selectedGroupName by mutableStateOf("")

    val delayCache = mutableStateMapOf<String, Int>()

    // Whole-set replacement behind a lock: concurrent speed-test workers mutate it, and
    // Compose still coalesces the writes into one invalidation pass per frame.
    var testingNodes by mutableStateOf(setOf<String>())
        private set

    private val testingNodesLock = Any()

    private fun addTesting(key: String) {
        synchronized(testingNodesLock) { testingNodes = testingNodes + key }
    }

    private fun removeTesting(key: String) {
        synchronized(testingNodesLock) { testingNodes = testingNodes - key }
    }

    private fun mutateTestingLocked(transform: (Set<String>) -> Set<String>) {
        synchronized(testingNodesLock) { testingNodes = transform(testingNodes) }
    }

    private val lastAutoTestTimes = mutableMapOf<String, Long>()
    private var parseJob: Job? = null
    private var autoTestJob: Job? = null

    // Serializes read-modify-write cycles on the liveGroupMap instance from the concurrent
    // speed-test workers.
    private val liveMapLock = Any()

    val mainProxyGroupName: String by derivedStateOf {
        proxyGroups.firstOrNull { it.equals("节点选择", ignoreCase = true) }
            ?: proxyGroups.firstOrNull { it.equals("PROXY", ignoreCase = true) }
            ?: proxyGroups.firstOrNull { it.equals("Proxies", ignoreCase = true) }
            ?: proxyGroups.firstOrNull { it.equals("Proxy", ignoreCase = true) }
            ?: proxyGroups.firstOrNull { parsedProfile.groups[it]?.hidden != true }
            ?: proxyGroups.firstOrNull() ?: ""
    }

    private fun app() = getApplication<Application>()

    /** Lists profiles off the main thread and re-parses the active one. */
    fun reloadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            profiles = ConfigManager.getProfiles(app())
            if (selectedProfileId == null && profiles.isNotEmpty()) {
                selectedProfileId = profiles.first().id
                settings.selectedProfileId = selectedProfileId
            }
            parseActiveProfile()
        }
    }

    /** Parses the currently active profile offline to immediately show nodes & groups. */
    fun parseActiveProfile() {
        val active = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
        parseJob?.cancel()
        parseJob = viewModelScope.launch(Dispatchers.IO) {
            val savedMap = settings.getAllSelectedNodes()
            val parsed = ProfileParser.parse(active?.file, savedMap, app())
            // ProfileParser has no suspension points, so a cancel issued while switching
            // profiles would otherwise be ignored and the stale result would overwrite the
            // newer parse. Check explicitly before publishing.
            currentCoroutineContext().ensureActive()
            parsedProfile = parsed
            proxyGroups = parsed.groupNames
            if (selectedGroupName.isBlank() || !parsed.groupNames.contains(selectedGroupName)) {
                selectedGroupName = parsed.groupNames.firstOrNull() ?: ""
            }
            if (selectedGroupName.isNotBlank()) {
                val grp = parsed.groups[selectedGroupName]
                val savedNode = settings.getSelectedNode(selectedGroupName)
                val effectiveNow = if (savedNode != null && grp?.proxies?.any { it.name == savedNode } == true) {
                    savedNode
                } else {
                    grp?.now ?: ""
                }
                currentGroupData = grp?.copy(now = effectiveNow)
            }
            val mainGrp = parsed.groups[mainProxyGroupName]
            val savedMainNode = settings.getSelectedNode(mainProxyGroupName)
            val effectiveMainNow = if (savedMainNode != null && mainGrp?.proxies?.any { it.name == savedMainNode } == true) {
                savedMainNode
            } else {
                mainGrp?.now ?: parsed.activeNode
            }
            activeNodeName = effectiveMainNow
        }
    }

    /**
     * Optimistic node selection: local state updates instantly, the core is patched
     * asynchronously when it is running.
     */
    fun selectProxy(group: String, proxy: Proxy, dispatchToCore: Boolean) {
        settings.setSelectedNode(group, proxy.name)
        if (group == mainProxyGroupName) {
            activeNodeName = proxy.name
        }
        if (group == selectedGroupName) {
            currentGroupData = currentGroupData?.copy(now = proxy.name)
        }

        val existingGroup = parsedProfile.groups[group]
        if (existingGroup != null) {
            val updatedGroup = existingGroup.copy(now = proxy.name)
            val newMap = parsedProfile.groups.toMutableMap()
            newMap[group] = updatedGroup
            val updatedActiveNode = if (group == mainProxyGroupName) proxy.name else parsedProfile.activeNode
            parsedProfile = parsedProfile.copy(groups = newMap, activeNode = updatedActiveNode)
        }

        synchronized(liveMapLock) {
            val currentLive = liveGroupMap[group] ?: existingGroup
            if (currentLive != null) {
                liveGroupMap = liveGroupMap + (group to currentLive.copy(now = proxy.name))
            }
        }

        if (dispatchToCore) {
            viewModelScope.launch(Dispatchers.IO) {
                ClashCore.patchSelector(group, proxy.name)
            }
        }
    }

    fun selectGroup(gName: String, coreRunning: Boolean) {
        selectedGroupName = gName
        val savedNode = settings.getSelectedNode(gName)
        if (coreRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                currentGroupData = ClashCore.queryGroup(gName)
            }
        } else {
            val gData = parsedProfile.groups[gName]
            val effectiveNow = if (savedNode != null && gData?.proxies?.any { it.name == savedNode } == true) {
                savedNode
            } else {
                gData?.now ?: ""
            }
            currentGroupData = gData?.copy(now = effectiveNow)
        }
    }

    /** Refreshes proxy groups from the running core. Skipped while speed tests are active. */
    fun refreshProxiesOnline() {
        if (testingNodes.isNotEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allGroups = ClashCore.queryGroupNames()
                val groups = mutableListOf<String>()
                val groupDataMap = mutableMapOf<String, ProxyGroup>()
                for (gName in allGroups) {
                    val gData = ClashCore.queryGroup(gName) ?: continue
                    if (gData.hidden == true) continue
                    groups.add(gName)
                    val mergedProxies = gData.proxies.map { p ->
                        val cachedDelay = delayCache[p.name] ?: delayCache["$gName:${p.name}"]
                        if (cachedDelay != null) {
                            p.copy(delay = cachedDelay)
                        } else if (p.delay >= 65535 || p.delay <= 0) {
                            p.copy(delay = -1) // not tested -> show "--"
                        } else p
                    }
                    groupDataMap[gName] = gData.copy(proxies = mergedProxies)
                }
                if (groups.isNotEmpty()) {
                    if (proxyGroups != groups) {
                        proxyGroups = groups
                    }
                    if (liveGroupMap != groupDataMap) {
                        liveGroupMap = groupDataMap
                    }
                    if (selectedGroupName.isBlank() || !groups.contains(selectedGroupName)) {
                        selectedGroupName = groups.firstOrNull() ?: ""
                    }
                    if (selectedGroupName.isNotBlank()) {
                        currentGroupData = groupDataMap[selectedGroupName]
                    }
                    val mainCur = groupDataMap[mainProxyGroupName]
                    if (mainCur != null && mainCur.now.isNotBlank() && activeNodeName != mainCur.now) {
                        activeNodeName = mainCur.now
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun isAutoStrategyGroupType(type: String): Boolean {
        val norm = type.lowercase().replace("-", "").replace("_", "").trim()
        return norm == "urltest" || norm == "fallback" || norm == "loadbalance"
    }

    private fun updateLiveGroupNow(gName: String, now: String) {
        synchronized(liveMapLock) {
            val existing = liveGroupMap[gName] ?: parsedProfile.groups[gName]
            if (existing != null) {
                liveGroupMap = liveGroupMap + (gName to existing.copy(now = now))
            }
        }
    }

    fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job {
        val groupTestingKey = "group:$groupName"
        if (groupName.isNotBlank() && testingNodes.contains(groupTestingKey)) {
            return Job().apply { complete() }
        }

        // Immediate visual feedback when manually testing a specific group
        if (!isAutoTest && groupName.isNotBlank()) {
            val initialGrp = liveGroupMap[groupName]
                ?: parsedProfile.groups[groupName]
                ?: (if (selectedGroupName == groupName) currentGroupData else null)
            initialGrp?.proxies
                ?.filter { p ->
                    !p.name.equals("DIRECT", ignoreCase = true) &&
                        !p.name.equals("REJECT", ignoreCase = true) &&
                        !p.name.equals("COMPATIBLE", ignoreCase = true)
                }
                ?.forEach { addTesting("$groupName:${it.name}") }
            addTesting(groupTestingKey)
        }

        return viewModelScope.launch(Dispatchers.IO) {
            try {
                ClashCore.ensureCoreLoaded(app())
                val targetGroups = if (groupName.isNotBlank()) listOf(groupName) else proxyGroups
                val semaphore = Semaphore(3)
                val subGroupBatchCache = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Int>>()

                suspend fun resolveSubGroupDelay(subG: String, visited: Set<String> = emptySet()): Int = coroutineScope {
                    if (subG in visited || visited.size > 4) {
                        return@coroutineScope delayCache[subG] ?: -1
                    }
                    val deferred = subGroupBatchCache.getOrPut(subG) {
                        async(Dispatchers.IO) {
                            val subGrp = liveGroupMap[subG]
                                ?: parsedProfile.groups[subG]
                                ?: ClashCore.queryGroup(subG)
                            val nonVirtual = subGrp?.proxies?.filter { p ->
                                !p.name.equals("DIRECT", ignoreCase = true) &&
                                    !p.name.equals("REJECT", ignoreCase = true) &&
                                    !p.name.equals("COMPATIBLE", ignoreCase = true)
                            }.orEmpty()

                            val nestedSubGroups = nonVirtual.map { it.name }.filter {
                                parsedProfile.groups.containsKey(it) || liveGroupMap.containsKey(it)
                            }.toSet()
                            val leafMembers = nonVirtual.map { it.name }.filter { it !in nestedSubGroups }

                            val subDelays: Map<String, Int> = if (leafMembers.isNotEmpty()) {
                                ClashCore.healthCheckGroupNative(subG) { subNodeName, subNodeDelay ->
                                    if (subNodeName !in nestedSubGroups && subNodeDelay in 1..65534) {
                                        delayCache[subNodeName] = subNodeDelay
                                        delayCache["$subG:$subNodeName"] = subNodeDelay
                                    }
                                }
                            } else {
                                emptyMap()
                            }

                            val subGroupData = ClashCore.queryGroup(subG) ?: subGrp
                            val subBestName = subGroupData?.now ?: ""

                            // If the active choice in this sub-group is itself a nested sub-group, resolve it
                            val nestedBestDelay = if (subBestName.isNotBlank() && subBestName in nestedSubGroups) {
                                resolveSubGroupDelay(subBestName, visited + subG)
                            } else if (leafMembers.isEmpty() && nestedSubGroups.isNotEmpty()) {
                                val firstNested = nestedSubGroups.first()
                                resolveSubGroupDelay(firstNested, visited + subG)
                            } else {
                                -1
                            }

                            val rawBestDelay = when {
                                nestedBestDelay in 1..65534 -> nestedBestDelay
                                (subDelays[subBestName] ?: -1) in 1..65534 -> subDelays[subBestName]!!
                                (delayCache[subBestName] ?: -1) in 1..65534 -> delayCache[subBestName]!!
                                (subGroupData?.proxies?.find { it.name == subBestName }?.delay ?: -1) in 1..65534 ->
                                    subGroupData!!.proxies.find { it.name == subBestName }!!.delay
                                else -> -1
                            }
                            val minValidSubDelay = subDelays.values.filter { it in 1..65534 }.minOrNull()
                            val finalSubDelay = when {
                                rawBestDelay in 1..65534 -> rawBestDelay
                                minValidSubDelay != null -> minValidSubDelay
                                else -> -1
                            }

                            subDelays.forEach { (subNodeName, subNodeDelay) ->
                                if (subNodeName !in nestedSubGroups && subNodeDelay in 1..65534) {
                                    delayCache[subNodeName] = subNodeDelay
                                    delayCache["$subG:$subNodeName"] = subNodeDelay
                                }
                            }
                            if (finalSubDelay in 1..65534) {
                                delayCache[subG] = finalSubDelay
                            }
                            if (subGroupData != null && subGroupData.now.isNotBlank()) {
                                updateLiveGroupNow(subG, subGroupData.now)
                            }
                            finalSubDelay
                        }
                    }
                    deferred.await()
                }

                coroutineScope {
                    targetGroups.map { g ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val gKey = "group:$g"
                                val grp = liveGroupMap[g]
                                    ?: parsedProfile.groups[g]
                                    ?: (if (selectedGroupName == g) currentGroupData else null)
                                    ?: ClashCore.queryGroup(g)
                                val isAutoGroup = isAutoStrategyGroupType(grp?.type ?: "")

                                if (isAutoTest && !isAutoGroup) return@withPermit

                                val filterVirtual = grp?.proxies?.filter { p ->
                                    !p.name.equals("DIRECT", ignoreCase = true) &&
                                        !p.name.equals("REJECT", ignoreCase = true) &&
                                        !p.name.equals("COMPATIBLE", ignoreCase = true)
                                } ?: emptyList()

                                val allNodeNames = filterVirtual.map { it.name }
                                if (allNodeNames.isEmpty()) {
                                    removeTesting(gKey)
                                    return@withPermit
                                }

                                val memberSubGroups = allNodeNames.filter {
                                    parsedProfile.groups.containsKey(it) || liveGroupMap.containsKey(it)
                                }.toSet()
                                val leafNodeNames = allNodeNames.filter { it !in memberSubGroups }
                                val scopedKeys = allNodeNames.map { "$g:$it" }

                                if (isAutoTest) {
                                    addTesting(gKey)
                                } else {
                                    scopedKeys.forEach { addTesting(it) }
                                    addTesting(gKey)
                                }

                                try {
                                    // Run native group health check only if the group has direct leaf nodes
                                    val parentJob = async(Dispatchers.IO) {
                                        if (leafNodeNames.isNotEmpty()) {
                                            ClashCore.healthCheckGroupNative(g) { pName, delay ->
                                                val isSubGroup = memberSubGroups.contains(pName)
                                                if (delay in 1..65534) {
                                                    delayCache[pName] = delay
                                                    delayCache["$g:$pName"] = delay
                                                }
                                                if (!isSubGroup) {
                                                    removeTesting("$g:$pName")
                                                }
                                            }
                                        } else {
                                            emptyMap()
                                        }
                                    }

                                    val subJobs = memberSubGroups.map { subG ->
                                        async(Dispatchers.IO) {
                                            val finalSubDelay = resolveSubGroupDelay(subG, setOf(g))
                                            if (finalSubDelay in 1..65534) {
                                                delayCache[subG] = finalSubDelay
                                                delayCache["$g:$subG"] = finalSubDelay
                                            }
                                            removeTesting("$g:$subG")
                                        }
                                    }

                                    val groupDelays = parentJob.await()
                                    subJobs.awaitAll()

                                    groupDelays.forEach { (name, delay) ->
                                        val isSubGroup = memberSubGroups.contains(name)
                                        if (!isSubGroup && delay in 1..65534) {
                                            delayCache[name] = delay
                                            delayCache["$g:$name"] = delay
                                        } else if (isSubGroup && delay in 1..65534 && (delayCache[name] ?: -1) !in 1..65534) {
                                            delayCache[name] = delay
                                            delayCache["$g:$name"] = delay
                                        }
                                    }

                                    val finalGroup = ClashCore.queryGroup(g)
                                    val bestNodeName = finalGroup?.now ?: grp?.now ?: ""
                                    val rawBest = delayCache["$g:$bestNodeName"]
                                        ?: delayCache[bestNodeName]
                                        ?: finalGroup?.proxies?.find { it.name == bestNodeName }?.delay
                                        ?: -1
                                    val minValid = allNodeNames.mapNotNull { name ->
                                        (delayCache["$g:$name"] ?: delayCache[name] ?: groupDelays[name])?.takeIf { it in 1..65534 }
                                    }.minOrNull()
                                    val finalGroupDelay = when {
                                        rawBest in 1..65534 -> rawBest
                                        minValid != null -> minValid
                                        else -> -1
                                    }
                                    if (finalGroupDelay in 1..65534) {
                                        delayCache[g] = finalGroupDelay
                                    }

                                    if (finalGroup != null && finalGroup.now.isNotBlank()) {
                                        updateLiveGroupNow(g, finalGroup.now)
                                        if (selectedGroupName == g && currentGroupData != null) {
                                            currentGroupData = currentGroupData?.copy(now = finalGroup.now)
                                        }
                                    }
                                } finally {
                                    scopedKeys.forEach { removeTesting(it) }
                                    removeTesting(gKey)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (_: Throwable) {
                if (groupName.isNotBlank()) {
                    mutateTestingLocked { current ->
                        current.filterNot { it == groupTestingKey || it.startsWith("$groupName:") }.toSet()
                    }
                }
            }
        }
    }

    fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) {
        val scopedKey = if (!groupName.isNullOrBlank()) "$groupName:$nodeName" else nodeName
        if (testingNodes.contains(scopedKey)) return

        if (parsedProfile.groups.containsKey(nodeName) || liveGroupMap.containsKey(nodeName)) {
            addTesting(scopedKey)
            // join() instead of invokeOnCompletion: the public one-argument overload is
            // ambiguous to static analysis (the 3-arg overload is @InternalCoroutinesApi).
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    runSpeedTestForGroup(nodeName, isAutoTest = false).join()
                    val resolved = delayCache[nodeName]
                    if (resolved != null && resolved in 1..65534 && !groupName.isNullOrBlank()) {
                        delayCache["$groupName:$nodeName"] = resolved
                    }
                } finally {
                    removeTesting(scopedKey)
                }
            }
            return
        }

        val targetGroup = when {
            !groupName.isNullOrBlank() -> groupName
            else -> liveGroupMap.entries.firstOrNull { (_, g) -> g.proxies.any { it.name == nodeName } }?.key
                ?: parsedProfile.groups.entries.firstOrNull { (_, g) -> g.proxies.any { it.name == nodeName } }?.key
        }

        if (targetGroup.isNullOrBlank()) {
            Log.w(TAG, "Cannot resolve group for node $nodeName")
            return
        }

        val targetScopedKey = "$targetGroup:$nodeName"
        if (testingNodes.contains(targetScopedKey)) return
        addTesting(targetScopedKey)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ClashCore.ensureCoreLoaded(app())
                val singleDelay = ClashCore.testProxyDelayNative(nodeName, targetGroup)
                if (singleDelay in 1..65534) {
                    delayCache[nodeName] = singleDelay
                    delayCache[targetScopedKey] = singleDelay
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Speed test failed for node $nodeName: ${e.message}", e)
            } finally {
                removeTesting(targetScopedKey)
            }
        }
    }

    /**
     * Sweeps the auto-strategy groups (url-test / fallback / load-balance) with a 120s
     * per-group cooldown. Checked and set on the caller thread; callers run on main.
     */
    fun triggerAutoSpeedTest() {
        if (autoTestJob?.isActive == true) return
        autoTestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!ClashCore.isCoreLoaded) {
                    ClashCore.ensureCoreLoaded(app())
                }
                val now = System.currentTimeMillis()

                val autoGroups = parsedProfile.groups.filter { (gName, grp) ->
                    val isAutoType = isAutoStrategyGroupType(grp.type)
                    val isRootGroup = gName.equals("GLOBAL", ignoreCase = true) ||
                            gName.equals("节点选择", ignoreCase = true) ||
                            gName.equals("PROXIES", ignoreCase = true) ||
                            gName.equals("Proxy", ignoreCase = true) ||
                            gName.equals("全部节点", ignoreCase = true)
                    isAutoType && !isRootGroup
                }.keys.toList()

                val groupsToTest = autoGroups.filter { g ->
                    val lastTime = lastAutoTestTimes[g] ?: 0L
                    now - lastTime > 120_000L
                }

                for (g in groupsToTest) {
                    lastAutoTestTimes[g] = System.currentTimeMillis()
                    // Await each group so the sweep cannot overlap itself or finish early.
                    runSpeedTestForGroup(g, isAutoTest = true).join()
                    delay(200L)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Auto speed test failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Profile mutations (subscription download / update / delete / import)
    // ------------------------------------------------------------------

    private fun reloadCoreAndRefresh(coreActive: Boolean) {
        if (coreActive) {
            viewModelScope.launch(Dispatchers.IO) {
                ClashCore.reloadActiveConfig(app())
                refreshProxiesOnline()
            }
        }
    }

    fun selectProfile(profile: ProfileItem, coreActive: Boolean) {
        selectedProfileId = profile.id
        settings.selectedProfileId = profile.id
        parseActiveProfile()
        reloadCoreAndRefresh(coreActive)
        CupertinoToast.show(
            AppStrings.get("profile_activated", settings.appLanguage).replace("{name}", profile.name)
        )
    }

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            val res = ConfigManager.downloadSubscription(app(), url, name)
            if (res.isSuccess) {
                reloadProfiles()
                CupertinoToast.show(AppStrings.get("subscription_added", settings.appLanguage))
            } else {
                CupertinoToast.show(
                    AppStrings.get("download_failed", settings.appLanguage)
                        .replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                    isDestructive = true
                )
            }
        }
    }

    fun refreshSubscription(profile: ProfileItem, coreActive: Boolean) {
        val url = profile.url ?: return
        viewModelScope.launch {
            val res = ConfigManager.updateSubscription(app(), profile.id, profile.name, url)
            if (res.isSuccess) {
                reloadProfiles()
                if (selectedProfileId == profile.id) {
                    reloadCoreAndRefresh(coreActive)
                }
                CupertinoToast.show(AppStrings.get("subscription_updated", settings.appLanguage))
            } else {
                CupertinoToast.show(
                    AppStrings.get("update_failed", settings.appLanguage)
                        .replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                    isDestructive = true
                )
            }
        }
    }

    fun deleteProfile(profile: ProfileItem, coreActive: Boolean) {
        val wasSelected = selectedProfileId == profile.id
        viewModelScope.launch(Dispatchers.IO) {
            ConfigManager.deleteProfile(app(), profile.id)
            if (wasSelected) {
                selectedProfileId = null
                settings.selectedProfileId = null
            }
            profiles = ConfigManager.getProfiles(app())
            parseActiveProfile()
            if (wasSelected) {
                reloadCoreAndRefresh(coreActive)
            }
        }
    }

    fun reorderProfiles(newOrderList: List<ProfileItem>) {
        profiles = newOrderList
        viewModelScope.launch(Dispatchers.IO) {
            ConfigManager.saveProfileOrder(app(), newOrderList.map { it.id })
        }
    }

    /** Called after per-profile override edits: metadata changed, re-read and re-parse. */
    fun onProfileMetadataUpdated() {
        reloadProfiles()
    }

    /** Called when scripts changed: the parsed profile must be rebuilt. */
    fun onScriptsChanged(coreActive: Boolean) {
        parseActiveProfile()
        reloadCoreAndRefresh(coreActive)
    }
}
