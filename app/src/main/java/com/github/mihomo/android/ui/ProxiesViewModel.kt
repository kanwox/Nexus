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
import com.github.mihomo.android.data.ParsedProfile
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.SpeedTestState
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.components.CupertinoToast
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ProxiesViewModel"

internal fun resolveMainProxyGroupName(
    preferred: String?,
    groups: List<String>,
    parsed: ParsedProfile
): String {
    return preferred?.takeIf { it in groups }
        ?: groups.firstOrNull { it.equals("节点选择", ignoreCase = true) }
        ?: groups.firstOrNull { it.equals("PROXY", ignoreCase = true) }
        ?: groups.firstOrNull { it.equals("Proxies", ignoreCase = true) }
        ?: groups.firstOrNull { it.equals("Proxy", ignoreCase = true) }
        ?: groups.firstOrNull { parsed.groups[it]?.hidden != true }
        ?: groups.firstOrNull()
        ?: ""
}

internal fun filterActiveDelaySnapshot(
    store: Map<String, SpeedTestState>,
    scopeId: String
): Map<String, SpeedTestState> {
    if (store.isEmpty()) return emptyMap()
    val prefix = "p:$scopeId|"
    val result = HashMap<String, SpeedTestState>(store.size)
    for ((key, value) in store) {
        if (key.startsWith(prefix)) {
            result[key.removePrefix(prefix)] = value
        }
    }
    return result
}

internal fun resolveEffectiveGroupNode(group: ProxyGroup?, savedNode: String?): String {
    if (savedNode != null && group?.proxies?.any { it.name == savedNode } == true) {
        return savedNode
    }
    return group?.now ?: ""
}

/**
 * Owns the proxies/profiles state and the speed-test engine.
 *
 * Background workers operate exclusively against synchronized/volatile backing stores and
 * publish immutable snapshots onto Compose snapshot state strictly on
 * [Dispatchers.Main.immediate], avoiding cross-thread SnapshotState mutations and races.
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

    private var _selectedGroupName by mutableStateOf("")

    /** Blank until a group is chosen; persisted selection is not stored across processes. */
    var selectedGroupName: String
        get() = _selectedGroupName
        set(value) {
            selectedGroupNameStore = value
            _selectedGroupName = value
        }

    /**
     * Persisted node selection per group. Loaded off the main thread (SharedPreferences is a
     * disk-backed store) so the composition never has to read it directly.
     */
    var selectedNodes by mutableStateOf(emptyMap<String, String>())
        private set

    // Thread-safe backing mirrors read by background workers so Compose snapshot state is
    // never read or written off the main thread.
    @Volatile
    private var profilesStore: List<ProfileItem> = emptyList()

    @Volatile
    private var selectedProfileIdStore: String? = settings.selectedProfileId

    @Volatile
    private var parsedProfileStore: ParsedProfile = ParsedProfile()

    @Volatile
    private var proxyGroupsStore: List<String> = emptyList()

    @Volatile
    private var currentGroupDataStore: ProxyGroup? = null

    @Volatile
    private var selectedGroupNameStore: String = ""

    private val profileScopeEpoch = AtomicLong(0L)
    private val parseEpoch = AtomicLong(0L)

    /**
     * Authoritative delay cache. The speed-test workers mutate it from several threads, so
     * the authoritative copy is a plain [ConcurrentHashMap] guarded by [delayCacheLock];
     * [delayCache] is the snapshot published to the UI strictly on the main thread.
     */
    private val delayCacheStore = ConcurrentHashMap<String, SpeedTestState>()
    private val delayCacheLock = Any()
    private val delayPublishVersion = AtomicLong(0L)

    /** Profile-bound scope of the current delay cache; changes invalidate the cache. */
    private var activeScopeId: String = settings.selectedProfileId ?: ""

    /** UI-facing snapshot of [delayCacheStore]; republished on the main thread. */
    val delayCache = mutableStateMapOf<String, SpeedTestState>()

    private fun recordDelayForScope(scopeId: String, key: String, delay: Int) {
        synchronized(delayCacheLock) {
            if (activeScopeId != scopeId) return
            delayCacheStore["p:$scopeId|$key"] = SpeedTestState.fromNativeDelay(delay)
        }
    }

    /** Records a completed active probe result: non-positive or >=65535 values become [SpeedTestState.Timeout]. */
    private fun recordProbeResultForScope(scopeId: String, key: String, delay: Int) {
        synchronized(delayCacheLock) {
            if (activeScopeId != scopeId) return
            delayCacheStore["p:$scopeId|$key"] = if (delay in 1..65534) {
                SpeedTestState.Success(delay)
            } else {
                SpeedTestState.Timeout
            }
        }
    }

    /** Reads a delay without touching snapshot state (safe from any thread). */
    private fun readDelayForScope(scopeId: String, key: String): Int? =
        synchronized(delayCacheLock) {
            if (activeScopeId != scopeId) return@synchronized null
            (delayCacheStore["p:$scopeId|$key"] as? SpeedTestState.Success)?.delayMs
        }

    /**
     * Drops every cached delay of a profile scope. Called on profile switch, subscription
     * content update, and core reset: stale cross-profile or cross-version results must
     * never overwrite the new data.
     */
    fun invalidateDelayCache(profileId: String?, force: Boolean = false) {
        val cleared = synchronized(delayCacheLock) {
            val newScope = profileId ?: selectedProfileIdStore ?: ""
            if (newScope == activeScopeId && !force) return@synchronized false
            activeScopeId = newScope
            delayCacheStore.clear()
            true
        }
        if (cleared) {
            schedulePublishDelayCache()
        }
    }

    private fun schedulePublishDelayCache() {
        val version = delayPublishVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            applyDelayCacheSnapshotOnMain(version)
        }
    }

    private suspend fun publishDelayCacheOnMain() {
        val version = delayPublishVersion.incrementAndGet()
        withContext(Dispatchers.Main.immediate) {
            applyDelayCacheSnapshotOnMain(version)
        }
    }

    private fun applyDelayCacheSnapshotOnMain(targetVersion: Long) {
        if (targetVersion < delayPublishVersion.get()) return
        val snapshot = synchronized(delayCacheLock) {
            filterActiveDelaySnapshot(delayCacheStore, activeScopeId)
        }
        delayCache.clear()
        if (snapshot.isNotEmpty()) {
            delayCache.putAll(snapshot)
        }
    }

    // Plain backing set under testingNodesLock; immutable Set snapshots are published to
    // Compose snapshot state strictly on Dispatchers.Main.immediate.
    private val testingNodesStore = mutableSetOf<String>()
    private val testingNodesLock = Any()
    private val testingPublishVersion = AtomicLong(0L)

    var testingNodes by mutableStateOf(setOf<String>())
        private set

    private fun schedulePublishTestingNodes() {
        val version = testingPublishVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (version < testingPublishVersion.get()) return@launch
            val snapshot = synchronized(testingNodesLock) { testingNodesStore.toSet() }
            if (testingNodes != snapshot) {
                testingNodes = snapshot
            }
        }
    }

    private fun addTesting(key: String) {
        val changed = synchronized(testingNodesLock) { testingNodesStore.add(key) }
        if (changed) schedulePublishTestingNodes()
    }

    private fun removeTesting(key: String) {
        val changed = synchronized(testingNodesLock) { testingNodesStore.remove(key) }
        if (changed) schedulePublishTestingNodes()
    }

    private fun mutateTestingLocked(transform: (Set<String>) -> Set<String>) {
        synchronized(testingNodesLock) {
            val next = transform(testingNodesStore.toSet())
            testingNodesStore.clear()
            testingNodesStore.addAll(next)
        }
        schedulePublishTestingNodes()
    }

    private val lastAutoTestTimes = ConcurrentHashMap<String, Long>()
    private var parseJob: Job? = null
    private var autoTestJob: Job? = null

    /**
     * Cancels every in-flight speed test (auto-test sweep and per-group probes whose
     * delays would land in a superseded cache scope). Called together with
     * [invalidateDelayCache] on profile switch / content update / core reset.
     */
    fun cancelSpeedTests() {
        autoTestJob?.cancel()
        autoTestJob = null
        synchronized(testingNodesLock) { testingNodesStore.clear() }
        schedulePublishTestingNodes()
    }

    // Serializes read-modify-write cycles on the immutable backing map liveGroupStore.
    private val liveMapLock = Any()
    private var liveGroupStore: Map<String, ProxyGroup> = emptyMap()
    private val liveMapPublishVersion = AtomicLong(0L)

    private fun readLiveGroup(gName: String): ProxyGroup? =
        synchronized(liveMapLock) { liveGroupStore[gName] }

    private fun clearLiveGroupMap() {
        synchronized(liveMapLock) { liveGroupStore = emptyMap() }
        val version = liveMapPublishVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (version < liveMapPublishVersion.get()) return@launch
            if (liveGroupMap.isNotEmpty()) {
                liveGroupMap = emptyMap()
            }
        }
    }

    private fun schedulePublishLiveGroupMap(expectedScopeEpoch: Long) {
        val version = liveMapPublishVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (profileScopeEpoch.get() != expectedScopeEpoch) return@launch
            if (version < liveMapPublishVersion.get()) return@launch
            val latestSnapshot = synchronized(liveMapLock) {
                if (profileScopeEpoch.get() != expectedScopeEpoch) return@launch
                liveGroupStore
            }
            if (liveGroupMap != latestSnapshot) {
                liveGroupMap = latestSnapshot
            }
            val currentSel = _selectedGroupName
            val latestSelectedGroup = if (currentSel.isNotBlank()) latestSnapshot[currentSel] else null
            val curData = currentGroupData
            if (latestSelectedGroup != null && curData != null && curData.now != latestSelectedGroup.now) {
                val updated = curData.copy(now = latestSelectedGroup.now)
                currentGroupDataStore = updated
                currentGroupData = updated
            }
        }
    }

    val mainProxyGroupName: String by derivedStateOf {
        resolveMainProxyGroupName(preferredGroupName, proxyGroups, parsedProfile)
    }

    private var _preferredGroupName by mutableStateOf<String?>(null)

    /** User-pinned default group of the active profile (see [setPreferredGroupName]). */
    val preferredGroupName: String?
        get() = _preferredGroupName

    /**
     * Pins (or clears, with null) the "home" strategy group of the active profile. The
     * pin is stored per profile scope, so the name-guessing fallback
     * ("节点选择"/"PROXY"/…) only applies to profiles the user never pinned.
     */
    fun setPreferredGroupName(groupName: String?) {
        _preferredGroupName = groupName
        val id = selectedProfileId ?: return
        settings.setPreferredGroupName(id, groupName)
    }

    private fun app() = getApplication<Application>()

    /** Lists profiles off the main thread and re-parses the active one. */
    fun reloadProfiles() {
        viewModelScope.launch {
            reloadProfilesInternal()
        }
    }

    private suspend fun reloadProfilesInternal() {
        val loadedProfiles = withContext(Dispatchers.IO) {
            ConfigManager.getProfiles(app())
        }
        val currentSelectedId = withContext(Dispatchers.Main.immediate) {
            selectedProfileId
        }
        var nextSelectedId = currentSelectedId
        var selectionInitialized = false
        if (nextSelectedId == null && loadedProfiles.isNotEmpty()) {
            nextSelectedId = loadedProfiles.first().id
            selectionInitialized = true
            withContext(Dispatchers.IO) {
                settings.selectedProfileId = nextSelectedId
            }
        }
        withContext(Dispatchers.Main.immediate) {
            profilesStore = loadedProfiles
            profiles = loadedProfiles
            if (selectionInitialized && selectedProfileId == null) {
                selectedProfileIdStore = nextSelectedId
                selectedProfileId = nextSelectedId
            }
        }
        launchParseActiveProfile().join()
    }

    private fun launchParseActiveProfile(): Job {
        val currentProfiles = profilesStore
        val currentProfileId = selectedProfileIdStore
        val active = currentProfiles.firstOrNull { it.id == currentProfileId } ?: currentProfiles.firstOrNull()
        val myParseEpoch = parseEpoch.incrementAndGet()
        val myScopeEpoch = profileScopeEpoch.get()
        parseJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.Main.immediate) {
            runParseActiveProfile(active, myParseEpoch, myScopeEpoch)
        }
        parseJob = job
        return job
    }

    /** Parses the currently active profile offline to immediately show nodes & groups. */
    fun parseActiveProfile() {
        launchParseActiveProfile()
    }

    private suspend fun runParseActiveProfile(
        active: ProfileItem?,
        myParseEpoch: Long,
        myScopeEpoch: Long
    ) {
        if (active == null) {
            clearLiveGroupMap()
        }
        invalidateDelayCache(active?.id)
        val (savedMap, pinnedGroup, parsed) = withContext(Dispatchers.IO) {
            val saved = settings.getSelectedNodesForProfile(active?.id ?: "")
            val pinned = settings.getPreferredGroupName(active?.id ?: "")
            val parsedResult = ProfileParser.parse(active?.file, saved, app())
            currentCoroutineContext().ensureActive()
            Triple(saved, pinned, parsedResult)
        }
        if (parseEpoch.get() != myParseEpoch || profileScopeEpoch.get() != myScopeEpoch) {
            return
        }
        parsedProfileStore = parsed
        parsedProfile = parsed
        selectedNodes = savedMap
        _preferredGroupName = pinnedGroup
        proxyGroupsStore = parsed.groupNames
        proxyGroups = parsed.groupNames

        val nextSelectedGroup = if (_selectedGroupName.isBlank() || !parsed.groupNames.contains(_selectedGroupName)) {
            parsed.groupNames.firstOrNull() ?: ""
        } else {
            _selectedGroupName
        }
        selectedGroupName = nextSelectedGroup

        val nextGroupData = if (nextSelectedGroup.isNotBlank()) {
            val grp = parsed.groups[nextSelectedGroup]
            val effectiveNow = resolveEffectiveGroupNode(grp, savedMap[nextSelectedGroup])
            grp?.copy(now = effectiveNow)
        } else {
            null
        }
        currentGroupDataStore = nextGroupData
        currentGroupData = nextGroupData

        val mainName = resolveMainProxyGroupName(pinnedGroup, parsed.groupNames, parsed)
        val mainGrp = parsed.groups[mainName]
        val savedMainNode = savedMap[mainName]
        activeNodeName = if (savedMainNode != null && mainGrp?.proxies?.any { it.name == savedMainNode } == true) {
            savedMainNode
        } else {
            mainGrp?.now ?: parsed.activeNode
        }
    }

    /**
     * Optimistic node selection: local state updates instantly, the core is patched
     * asynchronously when it is running. If the core rejects the patch, the selection
     * is rolled back so UI and core stay consistent.
     */
    fun selectProxy(group: String, proxy: Proxy, dispatchToCore: Boolean) {
        val prevNode = selectedNodes[group]
            ?: readLiveGroup(group)?.now
            ?: parsedProfile.groups[group]?.now
            ?: ""
        applyNodeSelectionState(group, proxy.name)

        if (dispatchToCore) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = ClashCore.patchSelector(group, proxy.name)
                if (!ok) {
                    withContext(Dispatchers.Main.immediate) {
                        if (selectedNodes[group] == proxy.name && prevNode.isNotBlank()) {
                            applyNodeSelectionState(group, prevNode)
                        }
                        CupertinoToast.show(
                            AppStrings.get("proxy_select_failed", settings.appLanguage)
                                .replace("{name}", proxy.name),
                            isDestructive = true
                        )
                    }
                }
            }
        }
    }

    private fun applyNodeSelectionState(group: String, nodeName: String) {
        settings.setSelectedNode(selectedProfileId ?: "", group, nodeName)
        selectedNodes = selectedNodes + (group to nodeName)
        if (group == mainProxyGroupName) {
            activeNodeName = nodeName
        }
        if (group == selectedGroupName) {
            val updated = currentGroupData?.copy(now = nodeName)
            currentGroupDataStore = updated
            currentGroupData = updated
        }

        val existingGroup = parsedProfile.groups[group]
        if (existingGroup != null) {
            val updatedGroup = existingGroup.copy(now = nodeName)
            val newMap = parsedProfile.groups.toMutableMap()
            newMap[group] = updatedGroup
            val updatedActiveNode = if (group == mainProxyGroupName) nodeName else parsedProfile.activeNode
            val nextParsed = parsedProfile.copy(groups = newMap, activeNode = updatedActiveNode)
            parsedProfileStore = nextParsed
            parsedProfile = nextParsed
        }

        val nextLiveMap = synchronized(liveMapLock) {
            val currentLive = liveGroupStore[group] ?: existingGroup
            if (currentLive != null) {
                val updated = liveGroupStore + (group to currentLive.copy(now = nodeName))
                liveGroupStore = updated
                updated
            } else {
                null
            }
        }
        if (nextLiveMap != null) {
            liveGroupMap = nextLiveMap
        }
    }

    fun selectGroup(gName: String, coreRunning: Boolean) {
        selectedGroupName = gName
        if (coreRunning) {
            val startScopeEpoch = profileScopeEpoch.get()
            viewModelScope.launch {
                val data = withContext(Dispatchers.IO) { ClashCore.queryGroup(gName) }
                if (profileScopeEpoch.get() == startScopeEpoch && selectedGroupName == gName) {
                    currentGroupDataStore = data
                    currentGroupData = data
                }
            }
        } else {
            val savedNode = selectedNodes[gName]
            val gData = parsedProfile.groups[gName]
            val effectiveNow = resolveEffectiveGroupNode(gData, savedNode)
            val updated = gData?.copy(now = effectiveNow)
            currentGroupDataStore = updated
            currentGroupData = updated
        }
    }

    /** Refreshes proxy groups from the running core. Skipped while speed tests are active. */
    fun refreshProxiesOnline() {
        val testingActive = synchronized(testingNodesLock) { testingNodesStore.isNotEmpty() }
        if (testingActive) return

        val startScopeEpoch = profileScopeEpoch.get()
        val startScopeId = synchronized(delayCacheLock) { activeScopeId }

        viewModelScope.launch {
            try {
                val (groups, groupDataMap) = withContext(Dispatchers.IO) {
                    val allGroups = ClashCore.queryGroupNames()
                    val groupList = mutableListOf<String>()
                    val dataMap = HashMap<String, ProxyGroup>(allGroups.size)
                    for (gName in allGroups) {
                        currentCoroutineContext().ensureActive()
                        val gData = ClashCore.queryGroup(gName) ?: continue
                        if (gData.hidden == true) continue
                        groupList.add(gName)
                        val mergedProxies = gData.proxies.map { p ->
                            val cachedDelay = readDelayForScope(startScopeId, "$gName:${p.name}")
                                ?: readDelayForScope(startScopeId, p.name)
                            if (cachedDelay != null) {
                                p.copy(delay = cachedDelay)
                            } else if (p.delay >= 65535 || p.delay <= 0) {
                                p.copy(delay = -1)
                            } else {
                                p
                            }
                        }
                        dataMap[gName] = gData.copy(proxies = mergedProxies)
                    }
                    groupList.toList() to dataMap.toMap()
                }
                val nowTesting = synchronized(testingNodesLock) { testingNodesStore.isNotEmpty() }
                if (profileScopeEpoch.get() != startScopeEpoch || nowTesting || groups.isEmpty()) {
                    return@launch
                }
                val publishedMap = synchronized(liveMapLock) {
                    if (profileScopeEpoch.get() != startScopeEpoch) return@launch
                    liveGroupStore = groupDataMap
                    groupDataMap
                }
                proxyGroupsStore = groups
                if (proxyGroups != groups) {
                    proxyGroups = groups
                }
                if (liveGroupMap != publishedMap) {
                    liveGroupMap = publishedMap
                }
                if (selectedGroupName.isBlank() || !groups.contains(selectedGroupName)) {
                    selectedGroupName = groups.firstOrNull() ?: ""
                }
                if (selectedGroupName.isNotBlank()) {
                    val grpData = publishedMap[selectedGroupName]
                    currentGroupDataStore = grpData
                    currentGroupData = grpData
                }
                val mainName = resolveMainProxyGroupName(preferredGroupName, groups, parsedProfile)
                val mainCur = publishedMap[mainName]
                if (mainCur != null && mainCur.now.isNotBlank() && activeNodeName != mainCur.now) {
                    activeNodeName = mainCur.now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun isAutoStrategyGroupType(type: String): Boolean {
        val norm = type.lowercase().replace("-", "").replace("_", "").trim()
        return norm == "urltest" || norm == "fallback" || norm == "loadbalance"
    }

    private fun updateLiveGroupNow(
        gName: String,
        now: String,
        expectedScopeEpoch: Long
    ) {
        if (profileScopeEpoch.get() != expectedScopeEpoch) return
        val updated = synchronized(liveMapLock) {
            if (profileScopeEpoch.get() != expectedScopeEpoch) return
            val existing = liveGroupStore[gName]
                ?: parsedProfileStore.groups[gName]
                ?: (if (selectedGroupNameStore == gName) currentGroupDataStore else null)
            if (existing != null) {
                liveGroupStore = liveGroupStore + (gName to existing.copy(now = now))
                true
            } else {
                false
            }
        }
        if (updated) {
            schedulePublishLiveGroupMap(expectedScopeEpoch)
        }
    }

    fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job {
        val groupTestingKey = "group:$groupName"
        if (groupName.isNotBlank() && synchronized(testingNodesLock) { testingNodesStore.contains(groupTestingKey) }) {
            return Job().apply { complete() }
        }

        val runScopeId = synchronized(delayCacheLock) { activeScopeId }
        val runScopeEpoch = profileScopeEpoch.get()

        // Immediate visual feedback when manually testing a specific group
        if (!isAutoTest && groupName.isNotBlank()) {
            val initialGrp = readLiveGroup(groupName)
                ?: parsedProfileStore.groups[groupName]
                ?: (if (selectedGroupNameStore == groupName) currentGroupDataStore else null)
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
                val targetGroups = if (groupName.isNotBlank()) listOf(groupName) else proxyGroupsStore
                val semaphore = Semaphore(3)
                val subGroupBatchCache = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Int>>()

                suspend fun resolveSubGroupDelay(subG: String, visited: Set<String> = emptySet()): Int = coroutineScope {
                    if (subG in visited || visited.size > 4) {
                        return@coroutineScope readDelayForScope(runScopeId, subG) ?: -1
                    }
                    val deferred = subGroupBatchCache.getOrPut(subG) {
                        async(Dispatchers.IO) {
                            val subGrp = readLiveGroup(subG)
                                ?: parsedProfileStore.groups[subG]
                                ?: ClashCore.queryGroup(subG)
                            val nonVirtual = subGrp?.proxies?.filter { p ->
                                !p.name.equals("DIRECT", ignoreCase = true) &&
                                    !p.name.equals("REJECT", ignoreCase = true) &&
                                    !p.name.equals("COMPATIBLE", ignoreCase = true)
                            }.orEmpty()

                            val nestedSubGroups = nonVirtual.map { it.name }.filter {
                                parsedProfileStore.groups.containsKey(it) || readLiveGroup(it) != null
                            }.toSet()
                            val leafMembers = nonVirtual.map { it.name }.filter { it !in nestedSubGroups }

                            val subDelays: Map<String, Int> = if (leafMembers.isNotEmpty()) {
                                ClashCore.healthCheckGroupNative(subG) { subNodeName, subNodeDelay ->
                                    if (subNodeName !in nestedSubGroups) {
                                        recordProbeResultForScope(runScopeId, subNodeName, subNodeDelay)
                                        recordProbeResultForScope(runScopeId, "$subG:$subNodeName", subNodeDelay)
                                    }
                                }
                            } else {
                                emptyMap()
                            }

                            val subGroupData = ClashCore.queryGroup(subG) ?: subGrp
                            val subBestName = subGroupData?.now ?: ""

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
                                (readDelayForScope(runScopeId, subBestName) ?: -1) in 1..65534 ->
                                    readDelayForScope(runScopeId, subBestName)!!
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
                                if (subNodeName !in nestedSubGroups) {
                                    recordProbeResultForScope(runScopeId, subNodeName, subNodeDelay)
                                    recordProbeResultForScope(runScopeId, "$subG:$subNodeName", subNodeDelay)
                                }
                            }
                            recordProbeResultForScope(runScopeId, subG, finalSubDelay)
                            if (subGroupData != null && subGroupData.now.isNotBlank()) {
                                updateLiveGroupNow(subG, subGroupData.now, runScopeEpoch)
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
                                val grp = readLiveGroup(g)
                                    ?: parsedProfileStore.groups[g]
                                    ?: (if (selectedGroupNameStore == g) currentGroupDataStore else null)
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
                                    parsedProfileStore.groups.containsKey(it) || readLiveGroup(it) != null
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
                                    val parentJob = async(Dispatchers.IO) {
                                        if (leafNodeNames.isNotEmpty()) {
                                            ClashCore.healthCheckGroupNative(g) { pName, delay ->
                                                val isSubGroup = memberSubGroups.contains(pName)
                                                if (!isSubGroup) {
                                                    recordProbeResultForScope(runScopeId, pName, delay)
                                                    recordProbeResultForScope(runScopeId, "$g:$pName", delay)
                                                    removeTesting("$g:$pName")
                                                } else if (delay in 1..65534) {
                                                    recordDelayForScope(runScopeId, pName, delay)
                                                    recordDelayForScope(runScopeId, "$g:$pName", delay)
                                                }
                                            }
                                        } else {
                                            emptyMap()
                                        }
                                    }

                                    val subJobs = memberSubGroups.map { subG ->
                                        async(Dispatchers.IO) {
                                            val finalSubDelay = resolveSubGroupDelay(subG, setOf(g))
                                            recordProbeResultForScope(runScopeId, subG, finalSubDelay)
                                            recordProbeResultForScope(runScopeId, "$g:$subG", finalSubDelay)
                                            removeTesting("$g:$subG")
                                        }
                                    }

                                    val groupDelays = parentJob.await()
                                    subJobs.awaitAll()

                                    groupDelays.forEach { (name, delay) ->
                                        val isSubGroup = memberSubGroups.contains(name)
                                        if (!isSubGroup) {
                                            recordProbeResultForScope(runScopeId, name, delay)
                                            recordProbeResultForScope(runScopeId, "$g:$name", delay)
                                        } else if (delay in 1..65534) {
                                            val cached = readDelayForScope(runScopeId, name) ?: -1
                                            if (cached !in 1..65534) {
                                                recordDelayForScope(runScopeId, name, delay)
                                                recordDelayForScope(runScopeId, "$g:$name", delay)
                                            }
                                        }
                                    }

                                    val finalGroup = ClashCore.queryGroup(g)
                                    val bestNodeName = finalGroup?.now ?: grp?.now ?: ""
                                    val rawBest = readDelayForScope(runScopeId, "$g:$bestNodeName")
                                        ?: readDelayForScope(runScopeId, bestNodeName)
                                        ?: finalGroup?.proxies?.find { it.name == bestNodeName }?.delay
                                        ?: -1
                                    val minValid = allNodeNames.mapNotNull { name ->
                                        (readDelayForScope(runScopeId, "$g:$name")
                                            ?: readDelayForScope(runScopeId, name)
                                            ?: groupDelays[name])?.takeIf { it in 1..65534 }
                                    }.minOrNull()
                                    val finalGroupDelay = when {
                                        rawBest in 1..65534 -> rawBest
                                        minValid != null -> minValid
                                        else -> -1
                                    }
                                    recordProbeResultForScope(runScopeId, g, finalGroupDelay)

                                    if (finalGroup != null && finalGroup.now.isNotBlank()) {
                                        updateLiveGroupNow(g, finalGroup.now, runScopeEpoch)
                                    }
                                } finally {
                                    scopedKeys.forEach { removeTesting(it) }
                                    removeTesting(gKey)
                                }
                            }
                        }
                    }.awaitAll()
                    publishDelayCacheOnMain()
                }
            } catch (e: CancellationException) {
                throw e
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
        if (synchronized(testingNodesLock) { testingNodesStore.contains(scopedKey) }) return

        val runScopeId = synchronized(delayCacheLock) { activeScopeId }

        if (parsedProfileStore.groups.containsKey(nodeName) || readLiveGroup(nodeName) != null) {
            addTesting(scopedKey)
            // join() instead of invokeOnCompletion: the public one-argument overload is
            // ambiguous to static analysis (the 3-arg overload is @InternalCoroutinesApi).
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    runSpeedTestForGroup(nodeName, isAutoTest = false).join()
                    val resolved = readDelayForScope(runScopeId, nodeName) ?: -1
                    if (!groupName.isNullOrBlank()) {
                        recordProbeResultForScope(runScopeId, "$groupName:$nodeName", resolved)
                    }
                } finally {
                    removeTesting(scopedKey)
                    publishDelayCacheOnMain()
                }
            }
            return
        }

        val targetGroup = when {
            !groupName.isNullOrBlank() -> groupName
            else -> synchronized(liveMapLock) {
                liveGroupStore.entries.firstOrNull { (_, g) -> g.proxies.any { it.name == nodeName } }?.key
            } ?: parsedProfileStore.groups.entries.firstOrNull { (_, g) -> g.proxies.any { it.name == nodeName } }?.key
        }

        if (targetGroup.isNullOrBlank()) {
            Log.w(TAG, "Cannot resolve group for node $nodeName")
            return
        }

        val targetScopedKey = "$targetGroup:$nodeName"
        if (synchronized(testingNodesLock) { testingNodesStore.contains(targetScopedKey) }) return
        addTesting(targetScopedKey)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ClashCore.ensureCoreLoaded(app())
                val singleDelay = ClashCore.testProxyDelayNative(nodeName, targetGroup)
                recordProbeResultForScope(runScopeId, nodeName, singleDelay)
                recordProbeResultForScope(runScopeId, targetScopedKey, singleDelay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Speed test failed for node $nodeName: ${e.message}", e)
            } finally {
                removeTesting(targetScopedKey)
                publishDelayCacheOnMain()
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

                val autoGroups = parsedProfileStore.groups.filter { (gName, grp) ->
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Auto speed test failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Profile mutations (subscription download / update / delete / import)
    // ------------------------------------------------------------------

    var isRefreshingAll by mutableStateOf(false)
        private set

    private fun reloadCoreAndRefresh(coreActive: Boolean) {
        if (coreActive) {
            viewModelScope.launch(Dispatchers.IO) {
                ClashCore.reloadActiveConfig(app())
                refreshProxiesOnline()
            }
        }
    }

    fun selectProfile(profile: ProfileItem, coreActive: Boolean) {
        profileScopeEpoch.incrementAndGet()
        selectedProfileIdStore = profile.id
        selectedProfileId = profile.id
        settings.selectedProfileId = profile.id
        cancelSpeedTests()
        invalidateDelayCache(profile.id)
        clearLiveGroupMap()
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
                reloadProfilesInternal()
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
                profileScopeEpoch.incrementAndGet()
                cancelSpeedTests()
                invalidateDelayCache(profile.id, force = true)
                reloadProfilesInternal()
                if (selectedProfileId == profile.id && coreActive) {
                    val reloaded = ClashCore.reloadActiveConfig(app())
                    if (!reloaded) {
                        val rolledBack = withContext(Dispatchers.IO) {
                            ConfigManager.rollbackSubscriptionUpdate(app(), profile.id)
                        }
                        if (rolledBack) {
                            reloadProfilesInternal()
                            ClashCore.reloadActiveConfig(app())
                            CupertinoToast.show(
                                AppStrings.get("subscription_rolled_back", settings.appLanguage),
                                isDestructive = true
                            )
                        } else {
                            CupertinoToast.show(
                                AppStrings.get("subscription_reload_failed", settings.appLanguage)
                                    .replace("{msg}", AppStrings.get("unknown_error", settings.appLanguage)),
                                isDestructive = true
                            )
                        }
                        return@launch
                    } else {
                        withContext(Dispatchers.IO) {
                            ConfigManager.archiveKnownGoodVersion(app(), profile.id)
                        }
                        refreshProxiesOnline()
                    }
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

    fun refreshAllSubscriptions(coreActive: Boolean) {
        if (isRefreshingAll) return
        val remoteSubs = profiles.filter { !it.url.isNullOrBlank() }
        if (remoteSubs.isEmpty()) return
        isRefreshingAll = true

        viewModelScope.launch {
            try {
                val semaphore = Semaphore(3)
                val outcomes = coroutineScope {
                    remoteSubs.map { profile ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val url = profile.url.orEmpty()
                                profile.id to ConfigManager.updateSubscription(app(), profile.id, profile.name, url).isSuccess
                            }
                        }
                    }.awaitAll()
                }
                val okCount = outcomes.count { it.second }
                val failCount = outcomes.size - okCount
                val activeUpdated = outcomes.any { (id, ok) -> ok && id == selectedProfileId }

                if (okCount > 0) {
                    profileScopeEpoch.incrementAndGet()
                    cancelSpeedTests()
                    if (activeUpdated) {
                        invalidateDelayCache(selectedProfileId, force = true)
                    }
                    reloadProfilesInternal()
                    if (activeUpdated && coreActive) {
                        val activeId = selectedProfileId ?: ""
                        val reloaded = ClashCore.reloadActiveConfig(app())
                        if (!reloaded) {
                            val rolledBack = withContext(Dispatchers.IO) {
                                ConfigManager.rollbackSubscriptionUpdate(app(), activeId)
                            }
                            if (rolledBack) {
                                reloadProfilesInternal()
                                ClashCore.reloadActiveConfig(app())
                                CupertinoToast.show(
                                    AppStrings.get("subscription_rolled_back", settings.appLanguage),
                                    isDestructive = true
                                )
                            } else {
                                CupertinoToast.show(
                                    AppStrings.get("subscription_reload_failed", settings.appLanguage)
                                        .replace("{msg}", AppStrings.get("unknown_error", settings.appLanguage)),
                                    isDestructive = true
                                )
                            }
                            return@launch
                        } else {
                            withContext(Dispatchers.IO) {
                                ConfigManager.archiveKnownGoodVersion(app(), activeId)
                            }
                            refreshProxiesOnline()
                        }
                    }
                }

                if (failCount == 0) {
                    CupertinoToast.show(AppStrings.get("subscription_updated", settings.appLanguage))
                } else {
                    CupertinoToast.show(
                        AppStrings.get("scripts_update_summary", settings.appLanguage)
                            .replace("{ok}", okCount.toString())
                            .replace("{fail}", failCount.toString()),
                        isDestructive = okCount == 0
                    )
                }
            } finally {
                isRefreshingAll = false
            }
        }
    }

    /** Clears speed-test jobs, delay caches, and live group state after a core reset. */
    fun onCoreReset() {
        profileScopeEpoch.incrementAndGet()
        cancelSpeedTests()
        invalidateDelayCache(selectedProfileId, force = true)
        clearLiveGroupMap()
        parseActiveProfile()
    }

    fun deleteProfile(profile: ProfileItem, coreActive: Boolean) {
        val wasSelected = selectedProfileId == profile.id
        viewModelScope.launch {
            val updatedProfiles = withContext(Dispatchers.IO) {
                ConfigManager.deleteProfile(app(), profile.id)
                settings.clearSelectedNodesForProfile(profile.id)
                ConfigManager.getProfiles(app())
            }
            cancelSpeedTests()
            profilesStore = updatedProfiles
            profiles = updatedProfiles
            if (wasSelected) {
                profileScopeEpoch.incrementAndGet()
                val nextId = updatedProfiles.firstOrNull()?.id
                selectedProfileIdStore = nextId
                selectedProfileId = nextId
                withContext(Dispatchers.IO) {
                    settings.selectedProfileId = nextId
                }
                invalidateDelayCache(nextId, force = true)
                clearLiveGroupMap()
            }
            launchParseActiveProfile().join()
            if (wasSelected && updatedProfiles.isNotEmpty()) {
                reloadCoreAndRefresh(coreActive)
            }
        }
    }

    fun reorderProfiles(newOrderList: List<ProfileItem>) {
        profilesStore = newOrderList
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
