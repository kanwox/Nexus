@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ProxiesViewModel"

internal fun resolveMainProxyGroupName(
    preferred: String?,
    groups: List<String>,
    parsed: ParsedProfile
): String {
    return preferred?.takeIf { groupName -> groupName in groups }
        ?: groups.firstOrNull { groupName -> groupName.equals("节点选择", ignoreCase = true) }
        ?: groups.firstOrNull { groupName -> groupName.equals("PROXY", ignoreCase = true) }
        ?: groups.firstOrNull { groupName -> groupName.equals("Proxies", ignoreCase = true) }
        ?: groups.firstOrNull { groupName -> groupName.equals("Proxy", ignoreCase = true) }
        ?: groups.firstOrNull { groupName -> parsed.groups[groupName]?.hidden != true }
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
    if (savedNode != null && group?.proxies?.any { proxy -> proxy.name == savedNode } == true) {
        return savedNode
    }
    return group?.now ?: ""
}

/**
 * Owns the proxies/profiles state and coordinates the speed-test engine.
 *
 * Background workers operate against synchronized/volatile backing stores and publish immutable
 * snapshots onto Compose snapshot state strictly on [Dispatchers.Main.immediate].
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
    var selectedNodes by mutableStateOf(emptyMap<String, String>())
        private set
    var testingNodes by mutableStateOf(setOf<String>())
        private set
    var isRefreshingAll by mutableStateOf(false)
        private set

    @Volatile private var profilesStore: List<ProfileItem> = emptyList()
    @Volatile private var selectedProfileIdStore: String? = settings.selectedProfileId
    @Volatile private var parsedProfileStore: ParsedProfile = ParsedProfile()
    @Volatile private var proxyGroupsStore: List<String> = emptyList()
    @Volatile private var currentGroupDataStore: ProxyGroup? = null
    @Volatile private var selectedGroupNameStore: String = ""

    private var _selectedGroupName by mutableStateOf("")

    /** Blank until a group is chosen; persisted selection is not stored across processes. */
    var selectedGroupName: String
        get() = _selectedGroupName
        set(value) {
            selectedGroupNameStore = value
            _selectedGroupName = value
        }

    private var _preferredGroupName by mutableStateOf<String?>(null)

    /** User-pinned default group of the active profile (see [setPreferredGroupName]). */
    val preferredGroupName: String?
        get() = _preferredGroupName

    val mainProxyGroupName: String by derivedStateOf {
        resolveMainProxyGroupName(preferredGroupName, proxyGroups, parsedProfile)
    }

    private val profileScopeEpoch = AtomicLong(0L)
    private val parseEpoch = AtomicLong(0L)
    private var parseJob: Job? = null

    private val cacheStore = ProxiesRuntimeCacheStore(
        scope = viewModelScope,
        initialScopeId = settings.selectedProfileId ?: "",
        currentScopeEpoch = { profileScopeEpoch.get() },
        fallbackParsedGroup = { groupName -> parsedProfileStore.groups[groupName] },
        fallbackCurrentGroup = { groupName -> if (selectedGroupNameStore == groupName) currentGroupDataStore else null },
        onTestingChanged = { snapshot -> if (testingNodes != snapshot) testingNodes = snapshot },
        onLiveMapChanged = { snapshot -> publishLiveMapState(snapshot) }
    )

    /** UI-facing snapshot of [ProxiesRuntimeCacheStore]; republished on the main thread. */
    val delayCache: SnapshotStateMap<String, SpeedTestState>
        get() = cacheStore.delayCache

    private val speedTestEngine = ProxiesSpeedTestEngine(
        scope = viewModelScope,
        host = ProxiesSpeedTestHostAdapter(
            appProvider = ::app,
            cacheStore = cacheStore,
            scopeEpochProvider = { profileScopeEpoch.get() },
            proxyGroupsProvider = { proxyGroupsStore },
            parsedProfileProvider = { parsedProfileStore },
            fallbackGroupProvider = { groupName ->
                if (selectedGroupNameStore == groupName) currentGroupDataStore else null
            }
        )
    )

    fun invalidateDelayCache(profileId: String?, force: Boolean = false) =
        cacheStore.invalidateDelayCache(profileId ?: selectedProfileIdStore ?: "", force)

    fun cancelSpeedTests() {
        speedTestEngine.cancelAll()
        cacheStore.clearTestingNodes()
    }

    fun setPreferredGroupName(groupName: String?) {
        _preferredGroupName = groupName
        val id = selectedProfileId ?: return
        settings.setPreferredGroupName(id, groupName)
    }

    fun reloadProfiles() {
        viewModelScope.launch { reloadProfilesInternal() }
    }

    fun parseActiveProfile() {
        launchParseActiveProfile()
    }

    fun selectProxy(group: String, proxy: Proxy, dispatchToCore: Boolean) {
        val prevNode = selectedNodes[group]
            ?: cacheStore.readLiveGroupSnapshot(group)?.now
            ?: parsedProfile.groups[group]?.now
            ?: ""
        applyNodeSelectionState(group, proxy.name)
        if (!dispatchToCore) return
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
            val gData = parsedProfile.groups[gName]
            val updated = gData?.copy(now = resolveEffectiveGroupNode(gData, selectedNodes[gName]))
            currentGroupDataStore = updated
            currentGroupData = updated
        }
    }

    fun refreshProxiesOnline() {
        if (cacheStore.hasActiveTests()) return
        val startScopeEpoch = profileScopeEpoch.get()
        val startScopeId = cacheStore.currentScopeId()
        viewModelScope.launch {
            try {
                val (groups, groupDataMap) = queryOnlineGroups(startScopeId)
                if (profileScopeEpoch.get() != startScopeEpoch || cacheStore.hasActiveTests() || groups.isEmpty()) {
                    return@launch
                }
                applyOnlineGroupsOnMain(groups, groupDataMap, startScopeEpoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Online proxy refresh failed", e)
            }
        }
    }

    fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job =
        speedTestEngine.runSpeedTestForGroup(groupName, isAutoTest)

    fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) =
        speedTestEngine.runSpeedTestForSingleNode(groupName, nodeName)

    fun triggerAutoSpeedTest() = speedTestEngine.triggerAutoSpeedTest()

    fun selectProfile(profile: ProfileItem, coreActive: Boolean) {
        profileScopeEpoch.incrementAndGet()
        selectedProfileIdStore = profile.id
        selectedProfileId = profile.id
        settings.selectedProfileId = profile.id
        cancelSpeedTests()
        invalidateDelayCache(profile.id)
        cacheStore.clearLiveGroupMap()
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
            if (!res.isSuccess) {
                CupertinoToast.show(
                    AppStrings.get("update_failed", settings.appLanguage)
                        .replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                    isDestructive = true
                )
                return@launch
            }
            profileScopeEpoch.incrementAndGet()
            cancelSpeedTests()
            invalidateDelayCache(profile.id, force = true)
            reloadProfilesInternal()
            val canToastUpdated = if (selectedProfileId == profile.id && coreActive) {
                reloadActiveProfileWithRollback(profile.id)
            } else {
                true
            }
            if (canToastUpdated) {
                CupertinoToast.show(AppStrings.get("subscription_updated", settings.appLanguage))
            }
        }
    }

    fun refreshAllSubscriptions(coreActive: Boolean) {
        if (isRefreshingAll) return
        val remoteSubs = profiles.filter { profile -> !profile.url.isNullOrBlank() }
        if (remoteSubs.isEmpty()) return
        isRefreshingAll = true

        viewModelScope.launch {
            try {
                val outcomes = updateSubscriptionsConcurrently(remoteSubs)
                val okCount = outcomes.count { (_, succeeded) -> succeeded }
                val failCount = outcomes.size - okCount
                val activeUpdated = outcomes.any { (id, ok) -> ok && id == selectedProfileId }
                if (okCount > 0) {
                    profileScopeEpoch.incrementAndGet()
                    cancelSpeedTests()
                    if (activeUpdated) invalidateDelayCache(selectedProfileId, force = true)
                    reloadProfilesInternal()
                    if (activeUpdated && coreActive && !reloadActiveProfileWithRollback(selectedProfileId ?: "")) {
                        return@launch
                    }
                }
                showBatchUpdateSummary(okCount, failCount)
            } finally {
                isRefreshingAll = false
            }
        }
    }

    fun onCoreReset() {
        profileScopeEpoch.incrementAndGet()
        cancelSpeedTests()
        invalidateDelayCache(selectedProfileId, force = true)
        cacheStore.clearLiveGroupMap()
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
                resetSelectionAfterDelete(updatedProfiles.firstOrNull()?.id)
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
            ConfigManager.saveProfileOrder(app(), newOrderList.map { profile -> profile.id })
        }
    }

    fun onProfileMetadataUpdated() = reloadProfiles()

    fun onScriptsChanged(coreActive: Boolean) {
        parseActiveProfile()
        reloadCoreAndRefresh(coreActive)
    }

    private fun app(): Application = getApplication()

    private fun publishLiveMapState(snapshot: Map<String, ProxyGroup>) {
        if (liveGroupMap != snapshot) liveGroupMap = snapshot
        val currentSel = _selectedGroupName
        val latestSelectedGroup = if (currentSel.isNotBlank()) snapshot[currentSel] else null
        val curData = currentGroupData
        if (latestSelectedGroup != null && curData != null && curData.now != latestSelectedGroup.now) {
            val updated = curData.copy(now = latestSelectedGroup.now)
            currentGroupDataStore = updated
            currentGroupData = updated
        }
    }

    private suspend fun reloadProfilesInternal() {
        val loadedProfiles = withContext(Dispatchers.IO) { ConfigManager.getProfiles(app()) }
        val currentSelectedId = withContext(Dispatchers.Main.immediate) { selectedProfileId }
        var nextSelectedId = currentSelectedId
        var selectionInitialized = false
        if (nextSelectedId == null && loadedProfiles.isNotEmpty()) {
            nextSelectedId = loadedProfiles.first().id
            selectionInitialized = true
            withContext(Dispatchers.IO) { settings.selectedProfileId = nextSelectedId }
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
        val active = currentProfiles.firstOrNull { profile -> profile.id == currentProfileId }
            ?: currentProfiles.firstOrNull()
        val myParseEpoch = parseEpoch.incrementAndGet()
        val myScopeEpoch = profileScopeEpoch.get()
        parseJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.Main.immediate) {
            runParseActiveProfile(active, myParseEpoch, myScopeEpoch)
        }
        parseJob = job
        return job
    }

    private suspend fun runParseActiveProfile(
        active: ProfileItem?,
        myParseEpoch: Long,
        myScopeEpoch: Long
    ) {
        if (active == null) cacheStore.clearLiveGroupMap()
        invalidateDelayCache(active?.id)
        val (savedMap, pinnedGroup, parsed) = withContext(Dispatchers.IO) {
            val saved = settings.getSelectedNodesForProfile(active?.id ?: "")
            val pinned = settings.getPreferredGroupName(active?.id ?: "")
            val parsedResult = ProfileParser.parse(active?.file, saved, app())
            currentCoroutineContext().ensureActive()
            Triple(saved, pinned, parsedResult)
        }
        if (parseEpoch.get() != myParseEpoch || profileScopeEpoch.get() != myScopeEpoch) return
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
            grp?.copy(now = resolveEffectiveGroupNode(grp, savedMap[nextSelectedGroup]))
        } else {
            null
        }
        currentGroupDataStore = nextGroupData
        currentGroupData = nextGroupData

        val mainName = resolveMainProxyGroupName(pinnedGroup, parsed.groupNames, parsed)
        val mainGrp = parsed.groups[mainName]
        val savedMainNode = savedMap[mainName]
        activeNodeName = if (savedMainNode != null && mainGrp?.proxies?.any { proxy -> proxy.name == savedMainNode } == true) {
            savedMainNode
        } else {
            mainGrp?.now ?: parsed.activeNode
        }
    }

    private fun applyNodeSelectionState(group: String, nodeName: String) {
        settings.setSelectedNode(selectedProfileId ?: "", group, nodeName)
        selectedNodes = selectedNodes + (group to nodeName)
        if (group == mainProxyGroupName) activeNodeName = nodeName
        if (group == selectedGroupName) {
            val updated = currentGroupData?.copy(now = nodeName)
            currentGroupDataStore = updated
            currentGroupData = updated
        }
        val existingGroup = parsedProfile.groups[group]
        if (existingGroup != null) {
            val newMap = parsedProfile.groups.toMutableMap()
            newMap[group] = existingGroup.copy(now = nodeName)
            val updatedActive = if (group == mainProxyGroupName) nodeName else parsedProfile.activeNode
            val nextParsed = parsedProfile.copy(groups = newMap, activeNode = updatedActive)
            parsedProfileStore = nextParsed
            parsedProfile = nextParsed
        }
        cacheStore.applyLiveNodeSelection(group, nodeName, existingGroup)
    }

    private suspend fun queryOnlineGroups(startScopeId: String): Pair<List<String>, Map<String, ProxyGroup>> =
        withContext(Dispatchers.IO) {
            val allGroups = ClashCore.queryGroupNames()
            val groupList = mutableListOf<String>()
            val dataMap = HashMap<String, ProxyGroup>(allGroups.size)
            for (gName in allGroups) {
                currentCoroutineContext().ensureActive()
                val gData = ClashCore.queryGroup(gName) ?: continue
                if (gData.hidden == true) continue
                groupList.add(gName)
                val mergedProxies = gData.proxies.map { proxy ->
                    val cachedDelay = cacheStore.readDelayInScope(startScopeId, "$gName:${proxy.name}")
                        ?: cacheStore.readDelayInScope(startScopeId, proxy.name)
                    when {
                        cachedDelay != null -> proxy.copy(delay = cachedDelay)
                        proxy.delay >= 65535 || proxy.delay <= 0 -> proxy.copy(delay = -1)
                        else -> proxy
                    }
                }
                dataMap[gName] = gData.copy(proxies = mergedProxies)
            }
            groupList.toList() to dataMap.toMap()
        }

    private fun applyOnlineGroupsOnMain(
        groups: List<String>,
        groupDataMap: Map<String, ProxyGroup>,
        startScopeEpoch: Long
    ) {
        val publishedMap = cacheStore.replaceLiveGroupMapOnMain(groupDataMap, startScopeEpoch) ?: return
        proxyGroupsStore = groups
        if (proxyGroups != groups) proxyGroups = groups
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
    }

    private fun reloadCoreAndRefresh(coreActive: Boolean) {
        if (!coreActive) return
        viewModelScope.launch(Dispatchers.IO) {
            ClashCore.reloadActiveConfig(app())
            refreshProxiesOnline()
        }
    }

    private suspend fun updateSubscriptionsConcurrently(remoteSubs: List<ProfileItem>): List<Pair<String, Boolean>> {
        val semaphore = Semaphore(3)
        return coroutineScope {
            remoteSubs.map { profile ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val url = profile.url.orEmpty()
                        profile.id to ConfigManager.updateSubscription(app(), profile.id, profile.name, url).isSuccess
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun reloadActiveProfileWithRollback(profileId: String): Boolean {
        val reloaded = ClashCore.reloadActiveConfig(app())
        if (reloaded) {
            withContext(Dispatchers.IO) { ConfigManager.archiveKnownGoodVersion(app(), profileId) }
            refreshProxiesOnline()
            return true
        }
        val rolledBack = withContext(Dispatchers.IO) {
            ConfigManager.rollbackSubscriptionUpdate(app(), profileId)
        }
        if (rolledBack) {
            reloadProfilesInternal()
            ClashCore.reloadActiveConfig(app())
            CupertinoToast.show(AppStrings.get("subscription_rolled_back", settings.appLanguage), isDestructive = true)
        } else {
            CupertinoToast.show(
                AppStrings.get("subscription_reload_failed", settings.appLanguage)
                    .replace("{msg}", AppStrings.get("unknown_error", settings.appLanguage)),
                isDestructive = true
            )
        }
        return false
    }

    private fun showBatchUpdateSummary(okCount: Int, failCount: Int) {
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
    }

    private suspend fun resetSelectionAfterDelete(nextId: String?) {
        profileScopeEpoch.incrementAndGet()
        selectedProfileIdStore = nextId
        selectedProfileId = nextId
        withContext(Dispatchers.IO) { settings.selectedProfileId = nextId }
        invalidateDelayCache(nextId, force = true)
        cacheStore.clearLiveGroupMap()
    }
}
