package com.github.mihomo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.github.mihomo.android.ui.theme.LocalAppLanguage
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.*
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.CupertinoToastHost
import com.github.mihomo.android.ui.components.LoonFloatingBottomBar
import com.github.mihomo.android.ui.screens.*
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.MihomoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class SubScreen {
    PROFILES,
    APP_ROUTING,
    LOGS,
    SCRIPTS,
    PROFILE_OVERRIDE,
    RULES,
    DNS,
    DNS_SETTINGS,
    REQUESTS,
    NETWORK_SHARE,
    APPEARANCE
}

/** Persists the current sub-screen across configuration changes. */
private val subScreenSaver: Saver<SubScreen?, String> = Saver(
    save = { it?.name },
    restore = { name -> runCatching { SubScreen.valueOf(name) }.getOrNull() }
)

@Suppress("LargeClass")
class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            MihomoVpnService.start(this)
        } else {
            CupertinoToast.show("需要授予 VPN 权限才能建立代理", isDestructive = true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional handler */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            var themeMode by remember { mutableStateOf(settingsManager.themeMode) }
            var appFont by remember { mutableStateOf(settingsManager.appFont) }
            MihomoTheme(
                themeMode = themeMode,
                appFont = appFont
            ) {
                MainAppScreen(
                    currentThemeMode = themeMode,
                    currentAppFont = appFont,
                    onThemeModeChanged = { newMode ->
                        settingsManager.themeMode = newMode
                        themeMode = newMode
                    },
                    onAppFontChanged = { newFont ->
                        settingsManager.appFont = newFont
                        appFont = newFont
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MihomoVpnService.isUiInForeground = true
    }

    override fun onStop() {
        MihomoVpnService.isUiInForeground = false
        super.onStop()
    }

    private fun handleToggleVpn() {
        val currentStatus = MihomoVpnService.vpnState.value.status
        if (currentStatus == VpnState.Status.RUNNING || currentStatus == VpnState.Status.STARTING) {
            MihomoVpnService.stop(this)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                MihomoVpnService.start(this)
            }
        }
    }

    @Composable
    private fun MainAppScreen(
        currentThemeMode: ThemeMode,
        currentAppFont: AppFont,
        onThemeModeChanged: (ThemeMode) -> Unit,
        onAppFontChanged: (AppFont) -> Unit
    ) {
        // Primary Tabs: 0: 仪表, 1: 策略, 2: 配置
        var currentTab by rememberSaveable { mutableStateOf(0) }
        var currentSubScreen by rememberSaveable(stateSaver = subScreenSaver) {
            mutableStateOf<SubScreen?>(null)
        }
        var overrideTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
        var overrideTargetProfile by remember { mutableStateOf<ProfileItem?>(null) }

        val vpnState by MihomoVpnService.vpnState.collectAsState()

        var profiles by remember { mutableStateOf(emptyList<ProfileItem>()) }
        var selectedProfileId by remember { mutableStateOf(settingsManager.selectedProfileId) }
        var tunnelMode by remember { mutableStateOf(TunnelMode.fromString(settingsManager.tunnelMode)) }
        var appLanguage by remember { mutableStateOf(settingsManager.appLanguage) }

        // Listing the profile directory touches the disk, so it is not composition work.
        LaunchedEffect(Unit) {
            profiles = withContext(Dispatchers.IO) { ConfigManager.getProfiles(this@MainActivity) }
        }

        // Re-resolve the override target after rotation, when only its id could be restored.
        LaunchedEffect(overrideTargetProfileId, profiles) {
            if (overrideTargetProfileId == null) {
                overrideTargetProfile = null
            } else if (overrideTargetProfile?.id != overrideTargetProfileId) {
                overrideTargetProfile = profiles.find { it.id == overrideTargetProfileId }
            }
        }

        var parsedProfile by remember { mutableStateOf(ParsedProfile()) }
        var proxyGroups by remember { mutableStateOf(listOf<String>()) }
        var liveGroupMap by remember { mutableStateOf<Map<String, ProxyGroup>>(emptyMap()) }
        var selectedGroupName by rememberSaveable { mutableStateOf("") }
        var currentGroupData by remember { mutableStateOf<ProxyGroup?>(null) }
        var activeNodeName by remember { mutableStateOf("") }

        val mainProxyGroupName = remember(proxyGroups, parsedProfile.groups) {
            proxyGroups.firstOrNull { it.equals("节点选择", ignoreCase = true) }
                ?: proxyGroups.firstOrNull { it.equals("PROXY", ignoreCase = true) }
                ?: proxyGroups.firstOrNull { it.equals("Proxies", ignoreCase = true) }
                ?: proxyGroups.firstOrNull { it.equals("Proxy", ignoreCase = true) }
                ?: proxyGroups.firstOrNull { parsedProfile.groups[it]?.hidden != true }
                ?: proxyGroups.firstOrNull() ?: ""
        }

        var visibleShortcuts by remember { mutableStateOf(settingsManager.visibleShortcuts) }
        var shortcutOrder by remember { mutableStateOf(settingsManager.shortcutOrder) }
        var proxiesViewMode by remember { mutableStateOf(settingsManager.proxiesViewMode) }
        var showGroupIcons by remember { mutableStateOf(settingsManager.showGroupIcons) }
        var nodeColumns by remember { mutableIntStateOf(settingsManager.nodeColumns) }
        var nodeSortMode by remember { mutableStateOf(settingsManager.nodeSortMode) }
        var scriptingEnabled by remember { mutableStateOf(settingsManager.scriptingEnabled) }
        var rewriteEnabled by remember { mutableStateOf(settingsManager.rewriteEnabled) }

        var perAppMode by remember { mutableStateOf(settingsManager.perAppProxyMode) }
        var selectedPackages by remember { mutableStateOf(settingsManager.selectedPackages) }
        var installedApps by remember { mutableStateOf(listOf<InstalledApp>()) }
        var isAppsLoading by remember { mutableStateOf(false) }

        var tunStack by remember { mutableStateOf(settingsManager.tunStack) }
        var bootOnStartup by remember { mutableStateOf(settingsManager.bootOnStartup) }
        var allowLan by remember { mutableStateOf(settingsManager.allowLan) }
        var fakeIpEnabled by remember { mutableStateOf(settingsManager.fakeIpEnabled) }
        val settingsScrollState = rememberScrollState()
        val dashboardScrollState = rememberScrollState()
        val proxiesTabsListState = rememberLazyListState()
        val proxiesListModeState = rememberLazyListState()
        val proxiesGroupRowState = rememberLazyListState()
        val proxiesExpandedGroups = rememberSaveable(
            saver = Saver<SnapshotStateMap<String, Boolean>, HashMap<String, Boolean>>(
                save = { HashMap(it) },
                restore = { saved ->
                    mutableStateMapOf<String, Boolean>().apply {
                        putAll(saved)
                    }
                }
            )
        ) { mutableStateMapOf<String, Boolean>() }
        val proxiesPerGroupTabStates = remember { mutableStateMapOf<String, LazyListState>() }
        var testingNodes by remember { mutableStateOf(setOf<String>()) }
        val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { 3 })
        val coroutineScope = rememberCoroutineScope()
        LaunchedEffect(pagerState.currentPage) {
            if (currentTab != pagerState.currentPage) {
                currentTab = pagerState.currentPage
            }
        }

        // Persistent node selection handler (Instant 0-delay optimistic update)
        fun handleSelectProxy(group: String, proxy: Proxy) {
            settingsManager.setSelectedNode(group, proxy.name)
            if (group == mainProxyGroupName) {
                activeNodeName = proxy.name
            }
            if (group == selectedGroupName) {
                currentGroupData = currentGroupData?.copy(now = proxy.name)
            }

            // 1. Update in parsedProfile map
            val existingGroup = parsedProfile.groups[group]
            if (existingGroup != null) {
                val updatedGroup = existingGroup.copy(now = proxy.name)
                val newMap = parsedProfile.groups.toMutableMap()
                newMap[group] = updatedGroup
                val updatedActiveNode = if (group == mainProxyGroupName) proxy.name else parsedProfile.activeNode
                parsedProfile = parsedProfile.copy(groups = newMap, activeNode = updatedActiveNode)
            }

            // 2. Immediately update liveGroupMap on main thread
            val currentLive = liveGroupMap[group] ?: existingGroup
            if (currentLive != null) {
                val updatedMap = liveGroupMap.toMutableMap()
                updatedMap[group] = currentLive.copy(now = proxy.name)
                liveGroupMap = updatedMap
            }

            // 3. Asynchronously dispatch to core without blocking UI
            if (vpnState.status == VpnState.Status.RUNNING) {
                lifecycleScope.launch(Dispatchers.IO) {
                    ClashCore.patchSelector(group, proxy.name)
                }
            }
        }

        var parseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        // Parse currently active profile offline to immediately show nodes & groups
        fun parseActiveProfile() {
            val active = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
            parseJob?.cancel()
            parseJob = lifecycleScope.launch(Dispatchers.IO) {
                val savedMap = settingsManager.getAllSelectedNodes()
                val parsed = ProfileParser.parse(active?.file, savedMap, this@MainActivity)
                withContext(Dispatchers.Main) {
                    parsedProfile = parsed
                    proxyGroups = parsed.groupNames
                    if (selectedGroupName.isBlank() || !parsed.groupNames.contains(selectedGroupName)) {
                        selectedGroupName = parsed.groupNames.firstOrNull() ?: ""
                    }
                    if (selectedGroupName.isNotBlank()) {
                        val grp = parsed.groups[selectedGroupName]
                        val savedNode = settingsManager.getSelectedNode(selectedGroupName)
                        val effectiveNow = if (savedNode != null && grp?.proxies?.any { it.name == savedNode } == true) {
                            savedNode
                        } else {
                            grp?.now ?: ""
                        }
                        currentGroupData = grp?.copy(now = effectiveNow)
                    }
                    val effectiveMainGroup = parsed.groupNames.firstOrNull { it.equals("节点选择", ignoreCase = true) }
                        ?: parsed.groupNames.firstOrNull { it.equals("PROXY", ignoreCase = true) }
                        ?: parsed.groupNames.firstOrNull { it.equals("Proxies", ignoreCase = true) }
                        ?: parsed.groupNames.firstOrNull { it.equals("Proxy", ignoreCase = true) }
                        ?: parsed.groupNames.firstOrNull { parsed.groups[it]?.hidden != true }
                        ?: parsed.groupNames.firstOrNull() ?: ""
                    val mainGrp = parsed.groups[effectiveMainGroup]
                    val savedMainNode = settingsManager.getSelectedNode(effectiveMainGroup)
                    val effectiveMainNow = if (savedMainNode != null && mainGrp?.proxies?.any { it.name == savedMainNode } == true) {
                        savedMainNode
                    } else {
                        mainGrp?.now ?: parsed.activeNode
                    }
                    activeNodeName = effectiveMainNow
                }
            }
        }

        // Reload profiles from storage
        fun reloadProfiles() {
            profiles = ConfigManager.getProfiles(this@MainActivity)
            if (selectedProfileId == null && profiles.isNotEmpty()) {
                selectedProfileId = profiles.first().id
                settingsManager.selectedProfileId = selectedProfileId
            }
            parseActiveProfile()
        }

        // Refresh proxy groups dynamically when core is running
        val delayCache = remember { mutableStateMapOf<String, Int>() }
        val nodeLastTestedTimes = remember { mutableMapOf<String, Long>() }

        fun refreshProxiesOnline() {
            // Skip refresh while speed tests are running to avoid clobbering results
            if (testingNodes.isNotEmpty()) return

            lifecycleScope.launch(Dispatchers.IO) {
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
                        withContext(Dispatchers.Main) {
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
                                val cur = groupDataMap[selectedGroupName]
                                if (cur != null && currentGroupData != cur) {
                                    currentGroupData = cur
                                }
                            }
                            val mainCur = groupDataMap[mainProxyGroupName]
                            if (mainCur != null && mainCur.now.isNotBlank() && activeNodeName != mainCur.now) {
                                activeNodeName = mainCur.now
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job {
            val groupTestingKey = "group:$groupName"
            if (groupName.isNotBlank() && testingNodes.contains(groupTestingKey)) {
                return Job().apply { complete() }
            }

            // Immediate visual feedback on the UI thread when manually testing a specific group
            if (!isAutoTest && groupName.isNotBlank()) {
                val initialGrp = liveGroupMap[groupName]
                    ?: parsedProfile.groups[groupName]
                    ?: (if (selectedGroupName == groupName) currentGroupData else null)
                val initialScopedKeys = initialGrp?.proxies
                    ?.filter { p ->
                        !p.name.equals("DIRECT", ignoreCase = true) &&
                            !p.name.equals("REJECT", ignoreCase = true) &&
                            !p.name.equals("COMPATIBLE", ignoreCase = true)
                    }
                    ?.map { "$groupName:${it.name}" }
                    .orEmpty()
                testingNodes = testingNodes + groupTestingKey + initialScopedKeys
            }

            return lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ClashCore.ensureCoreLoaded(this@MainActivity)
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
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                delayCache[subNodeName] = subNodeDelay
                                                delayCache["$subG:$subNodeName"] = subNodeDelay
                                                nodeLastTestedTimes[subNodeName] = System.currentTimeMillis()
                                            }
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

                                withContext(Dispatchers.Main) {
                                    val nowMs = System.currentTimeMillis()
                                    subDelays.forEach { (subNodeName, subNodeDelay) ->
                                        if (subNodeName !in nestedSubGroups && subNodeDelay in 1..65534) {
                                            delayCache[subNodeName] = subNodeDelay
                                            delayCache["$subG:$subNodeName"] = subNodeDelay
                                            nodeLastTestedTimes[subNodeName] = nowMs
                                        }
                                    }
                                    if (finalSubDelay in 1..65534) {
                                        delayCache[subG] = finalSubDelay
                                    }
                                    if (subGroupData != null && subGroupData.now.isNotBlank()) {
                                        val existing = liveGroupMap[subG] ?: parsedProfile.groups[subG]
                                        if (existing != null) {
                                            liveGroupMap = liveGroupMap + (subG to existing.copy(now = subGroupData.now))
                                        }
                                    }
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
                                    val normType = (grp?.type ?: "").lowercase().replace("-", "").replace("_", "").trim()
                                    val isAutoGroup = normType == "urltest" || normType == "fallback" || normType == "loadbalance"

                                    if (isAutoTest && !isAutoGroup) return@withPermit

                                    val filterVirtual = grp?.proxies?.filter { p ->
                                        !p.name.equals("DIRECT", ignoreCase = true) &&
                                            !p.name.equals("REJECT", ignoreCase = true) &&
                                            !p.name.equals("COMPATIBLE", ignoreCase = true)
                                    } ?: emptyList()

                                    val allNodeNames = filterVirtual.map { it.name }
                                    if (allNodeNames.isEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            testingNodes = testingNodes - gKey
                                        }
                                        return@withPermit
                                    }

                                    val memberSubGroups = allNodeNames.filter {
                                        parsedProfile.groups.containsKey(it) || liveGroupMap.containsKey(it)
                                    }.toSet()
                                    val leafNodeNames = allNodeNames.filter { it !in memberSubGroups }
                                    val scopedKeys = allNodeNames.map { "$g:$it" }

                                    withContext(Dispatchers.Main) {
                                        testingNodes = if (isAutoTest) {
                                            testingNodes + gKey
                                        } else {
                                            testingNodes + scopedKeys + gKey
                                        }
                                    }

                                    try {
                                        val curTime = System.currentTimeMillis()

                                        // Run native group health check only if the group has direct leaf nodes
                                        val parentJob = async(Dispatchers.IO) {
                                            if (leafNodeNames.isNotEmpty()) {
                                                ClashCore.healthCheckGroupNative(g) { pName, delay ->
                                                    val isSubGroup = memberSubGroups.contains(pName)
                                                    lifecycleScope.launch(Dispatchers.Main) {
                                                        if (!isSubGroup && delay in 1..65534) {
                                                            delayCache[pName] = delay
                                                            delayCache["$g:$pName"] = delay
                                                            nodeLastTestedTimes[pName] = System.currentTimeMillis()
                                                        } else if (isSubGroup && delay in 1..65534) {
                                                            delayCache[pName] = delay
                                                            delayCache["$g:$pName"] = delay
                                                        }
                                                        if (!isSubGroup) {
                                                            testingNodes = testingNodes - "$g:$pName"
                                                        }
                                                    }
                                                }
                                            } else {
                                                emptyMap()
                                            }
                                        }

                                        val subJobs = memberSubGroups.map { subG ->
                                            async(Dispatchers.IO) {
                                                val finalSubDelay = resolveSubGroupDelay(subG, setOf(g))
                                                withContext(Dispatchers.Main) {
                                                    if (finalSubDelay in 1..65534) {
                                                        delayCache[subG] = finalSubDelay
                                                        delayCache["$g:$subG"] = finalSubDelay
                                                    }
                                                    testingNodes = testingNodes - "$g:$subG"
                                                }
                                            }
                                        }

                                        val groupDelays = parentJob.await()
                                        subJobs.awaitAll()

                                        withContext(Dispatchers.Main) {
                                            groupDelays.forEach { (name, delay) ->
                                                val isSubGroup = memberSubGroups.contains(name)
                                                if (!isSubGroup && delay in 1..65534) {
                                                    delayCache[name] = delay
                                                    delayCache["$g:$name"] = delay
                                                    nodeLastTestedTimes[name] = curTime
                                                } else if (isSubGroup && delay in 1..65534 && (delayCache[name] ?: -1) !in 1..65534) {
                                                    delayCache[name] = delay
                                                    delayCache["$g:$name"] = delay
                                                }
                                            }
                                        }

                                        val finalGroup = ClashCore.queryGroup(g)
                                        withContext(Dispatchers.Main) {
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
                                                val existing = liveGroupMap[g] ?: parsedProfile.groups[g]
                                                if (existing != null) {
                                                    liveGroupMap = liveGroupMap + (g to existing.copy(now = finalGroup.now))
                                                }
                                                if (selectedGroupName == g && currentGroupData != null) {
                                                    currentGroupData = currentGroupData?.copy(now = finalGroup.now)
                                                }
                                            }
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            testingNodes = testingNodes - scopedKeys.toSet() - gKey
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } catch (_: Throwable) {
                    withContext(Dispatchers.Main) {
                        if (groupName.isNotBlank()) {
                            testingNodes = testingNodes.filterNot {
                                it == groupTestingKey || it.startsWith("$groupName:")
                            }.toSet()
                        }
                    }
                }
            }
        }

        fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) {
            val scopedKey = if (!groupName.isNullOrBlank()) "$groupName:$nodeName" else nodeName
            if (testingNodes.contains(scopedKey)) return

            if (parsedProfile.groups.containsKey(nodeName) || liveGroupMap.containsKey(nodeName)) {
                testingNodes = testingNodes + scopedKey
                val job = runSpeedTestForGroup(nodeName, isAutoTest = false)
                job.invokeOnCompletion {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val resolved = delayCache[nodeName]
                        if (resolved != null && resolved in 1..65534 && !groupName.isNullOrBlank()) {
                            delayCache["$groupName:$nodeName"] = resolved
                        }
                        testingNodes = testingNodes - scopedKey
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
                Log.w("MainActivity", "Cannot resolve group for node $nodeName")
                return
            }

            val targetScopedKey = "$targetGroup:$nodeName"
            if (testingNodes.contains(targetScopedKey)) return
            testingNodes = testingNodes + targetScopedKey

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ClashCore.ensureCoreLoaded(this@MainActivity)
                    val curTime = System.currentTimeMillis()
                    val singleDelay = ClashCore.testProxyDelayNative(nodeName, targetGroup)
                    withContext(Dispatchers.Main) {
                        if (singleDelay in 1..65534) {
                            delayCache[nodeName] = singleDelay
                            delayCache[targetScopedKey] = singleDelay
                            nodeLastTestedTimes[nodeName] = curTime
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("MainActivity", "Speed test failed for node $nodeName: ${e.message}", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        testingNodes = testingNodes - targetScopedKey
                    }
                }
            }
        }

        val lastAutoTestTimes = remember { mutableMapOf<String, Long>() }
        var autoTestJob by remember { mutableStateOf<Job?>(null) }

        fun isAutoStrategyGroupType(type: String): Boolean {
            val norm = type.lowercase().replace("-", "").replace("_", "").trim()
            return norm == "urltest" || norm == "fallback" || norm == "loadbalance"
        }

        fun triggerAutoSpeedTest() {
            // Checked and set on the main thread; every caller (LaunchedEffect, ON_RESUME) runs
            // there, so two triggers cannot both start a sweep.
            if (autoTestJob?.isActive == true) return
            autoTestJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (!ClashCore.isCoreLoaded) {
                        ClashCore.ensureCoreLoaded(this@MainActivity)
                    }
                    val now = System.currentTimeMillis()

                    // Strictly find the 3 auto-testable group types (url-test, fallback, load-balance)
                    val autoGroups = parsedProfile.groups.filter { (gName, grp) ->
                        val isAutoType = isAutoStrategyGroupType(grp.type)
                        val isRootGroup = gName.equals("GLOBAL", ignoreCase = true) ||
                                gName.equals("节点选择", ignoreCase = true) ||
                                gName.equals("PROXIES", ignoreCase = true) ||
                                gName.equals("Proxy", ignoreCase = true) ||
                                gName.equals("全部节点", ignoreCase = true)
                        isAutoType && !isRootGroup
                    }.keys.toList()

                    // Filter out groups that have already been tested within cooldown (120 seconds)
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
                    android.util.Log.w("MainActivity", "Auto speed test failed", e)
                }
            }
        }

        // Re-parse once the profile list finishes loading, otherwise the first run (when the list
        // starts empty) would parse nothing and show no nodes.
        LaunchedEffect(selectedProfileId, profiles) {
            parseActiveProfile()
        }

        val lifecycleOwner = LocalLifecycleOwner.current

        // Trigger initial auto speed test once when VPN transitions to RUNNING
        LaunchedEffect(vpnState.status) {
            if (vpnState.status == VpnState.Status.RUNNING) {
                triggerAutoSpeedTest()
                refreshProxiesOnline()
            }
        }

        // Dynamic refresh when VPN is connected and user is actively viewing Dashboard or Proxies tab
        val shouldPollProxies = vpnState.status == VpnState.Status.RUNNING &&
            currentSubScreen == null &&
            (currentTab == 0 || currentTab == 1)

        LaunchedEffect(shouldPollProxies, lifecycleOwner) {
            if (shouldPollProxies) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (isActive) {
                        delay(20_000L)
                        refreshProxiesOnline()
                    }
                }
            }
        }

        // Load apps when entering App Routing screen (delayed so the transition completes smoothly)
        LaunchedEffect(currentSubScreen) {
            if (currentSubScreen == SubScreen.APP_ROUTING && installedApps.isEmpty()) {
                isAppsLoading = true
                try {
                    delay(300L)
                    // Child of the effect, so leaving the screen cancels the load instead of leaking it.
                    installedApps = withContext(Dispatchers.IO) {
                        AppInfoManager.getInstalledApps(this@MainActivity)
                    }
                } finally {
                    isAppsLoading = false
                }
            }
        }

        val profileImportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = ConfigManager.importProfileFromUri(this@MainActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (res.isSuccess) {
                            val imported = res.getOrNull()
                            reloadProfiles()
                            CupertinoToast.show("成功导入配置: ${imported?.name}")
                        } else {
                            CupertinoToast.show("导入失败: ${res.exceptionOrNull()?.message}", isDestructive = true)
                        }
                    }
                }
            }
        }

        val handleSubScreenBack: () -> Unit = {
            when (currentSubScreen) {
                SubScreen.SCRIPTS -> {
                    if (overrideTargetProfile != null) {
                        currentSubScreen = SubScreen.PROFILE_OVERRIDE
                    } else {
                        currentSubScreen = null
                    }
                }
                SubScreen.PROFILE_OVERRIDE -> {
                    currentSubScreen = SubScreen.PROFILES
                }
                else -> {
                    currentSubScreen = null
                }
            }
        }

        // Handle Android system back button
        BackHandler(enabled = currentSubScreen != null) {
            handleSubScreenBack()
        }

        CompositionLocalProvider(LocalAppLanguage provides appLanguage) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LoonBg)
        ) {
            // 1. Primary main tabs: HorizontalPager keeps all 3 tabs persistently composed!
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 2,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        val mainGroupProxies by remember(mainProxyGroupName) {
                            derivedStateOf {
                                val rawProxies = liveGroupMap[mainProxyGroupName]?.proxies
                                    ?: (parsedProfile.groups[mainProxyGroupName]?.proxies ?: emptyList())
                                rawProxies.map { p ->
                                    val scopedKey = "$mainProxyGroupName:${p.name}"
                                    val d = delayCache[p.name] ?: delayCache[scopedKey]
                                    if (d != null) p.copy(delay = d) else p
                                }
                            }
                        }
                        val currentMainActiveNode = (liveGroupMap[mainProxyGroupName]?.now
                            ?: parsedProfile.groups[mainProxyGroupName]?.now
                            ?: settingsManager.getSelectedNode(mainProxyGroupName)
                            ?: activeNodeName).ifBlank { parsedProfile.activeNode }
                        DashboardScreen(
                            scrollState = dashboardScrollState,
                            vpnState = vpnState,
                            currentMode = tunnelMode,
                            activeProfileName = profiles.firstOrNull { it.id == selectedProfileId }?.name ?: "默认配置",
                            mainGroupName = mainProxyGroupName,
                            mainGroupProxies = mainGroupProxies,
                            activeNodeName = currentMainActiveNode,
                            totalNodes = parsedProfile.totalNodes,
                            totalRules = parsedProfile.totalRules,
                            visibleShortcuts = visibleShortcuts,
                            shortcutOrder = shortcutOrder,
                            onToggleVpn = { handleToggleVpn() },
                            onModeSelected = { newMode ->
                                tunnelMode = newMode
                                settingsManager.tunnelMode = newMode.value
                                if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        ClashCore.reloadActiveConfig(this@MainActivity)
                                        refreshProxiesOnline()
                                    }
                                }
                            },
                            onSelectProxy = { group, proxy -> handleSelectProxy(mainProxyGroupName, proxy) },
                            onHealthCheck = { group -> runSpeedTestForGroup(group) },
                            onNavigateToProfiles = { currentSubScreen = SubScreen.PROFILES },
                            onNavigateToAppRouting = { currentSubScreen = SubScreen.APP_ROUTING },
                            onNavigateToLogs = { currentSubScreen = SubScreen.LOGS },
                            onNavigateToRules = { currentSubScreen = SubScreen.RULES },
                            onNavigateToDns = { currentSubScreen = SubScreen.DNS },
                            onNavigateToRequests = { currentSubScreen = SubScreen.REQUESTS },
                            onNavigateToNetworkShare = { currentSubScreen = SubScreen.NETWORK_SHARE },
                            onUpdateVisibleShortcuts = { updated ->
                                visibleShortcuts = updated
                                settingsManager.visibleShortcuts = updated
                            },
                            onUpdateShortcutOrder = { updated ->
                                shortcutOrder = updated
                                settingsManager.shortcutOrder = updated
                            }
                        )
                    }
                    1 -> {
                        val computedGroupMap by remember {
                            derivedStateOf {
                                val allGroupKeys = (parsedProfile.groups.keys + liveGroupMap.keys).distinct()
                                allGroupKeys.associateWith { gName ->
                                    val baseGroup = parsedProfile.groups[gName] ?: liveGroupMap[gName]!!
                                    val live = liveGroupMap[gName]
                                    val sourceProxies = if (!live?.proxies.isNullOrEmpty()) live!!.proxies else baseGroup.proxies
                                    val liveProxyMap = live?.proxies?.associateBy { it.name } ?: emptyMap()
                                    val savedNode = settingsManager.getSelectedNode(gName)
                                    val resolvedNow = if (!savedNode.isNullOrBlank() && sourceProxies.any { it.name == savedNode }) {
                                        savedNode
                                    } else {
                                        live?.now?.ifBlank { baseGroup.now } ?: baseGroup.now
                                    }
                                    baseGroup.copy(
                                        now = resolvedNow,
                                        proxies = sourceProxies.map { bp ->
                                            val p = liveProxyMap[bp.name] ?: bp
                                            val scopedKey = "$gName:${bp.name}"
                                            val cached = delayCache[scopedKey] ?: delayCache[bp.name]
                                            if (cached != null) p.copy(delay = cached) else p
                                        }
                                    )
                                }
                            }
                        }
                        val effectiveCurrentGroup = computedGroupMap[selectedGroupName] ?: currentGroupData

                        ProxiesScreen(
                            groups = proxyGroups,
                            groupMap = computedGroupMap,
                            groupIcons = parsedProfile.groupIcons,
                            currentGroup = effectiveCurrentGroup,
                            selectedGroupName = selectedGroupName,
                            currentViewMode = proxiesViewMode,
                            showGroupIcons = showGroupIcons,
                            nodeColumns = nodeColumns,
                            nodeSortMode = nodeSortMode,
                            tabsListState = proxiesTabsListState,
                            listModeState = proxiesListModeState,
                            groupRowState = proxiesGroupRowState,
                            expandedGroups = proxiesExpandedGroups,
                            perGroupTabStates = proxiesPerGroupTabStates,
                            onViewModeChanged = { mode ->
                                proxiesViewMode = mode
                                settingsManager.proxiesViewMode = mode
                            },
                            onShowGroupIconsChanged = { show ->
                                showGroupIcons = show
                                settingsManager.showGroupIcons = show
                            },
                            onNodeColumnsChanged = { cols ->
                                nodeColumns = cols
                                settingsManager.nodeColumns = cols
                            },
                            onNodeSortModeChanged = { sortMode ->
                                nodeSortMode = sortMode
                                settingsManager.nodeSortMode = sortMode
                            },
                            onSelectGroup = { gName ->
                                selectedGroupName = gName
                                val savedNode = settingsManager.getSelectedNode(gName)
                                if (vpnState.status == VpnState.Status.RUNNING) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val gData = ClashCore.queryGroup(gName)
                                        withContext(Dispatchers.Main) {
                                            currentGroupData = gData
                                        }
                                    }
                                } else {
                                    val gData = parsedProfile.groups[gName]
                                    val effectiveNow = if (savedNode != null && gData?.proxies?.any { it.name == savedNode } == true) savedNode else (gData?.now ?: "")
                                    currentGroupData = gData?.copy(now = effectiveNow)
                                }
                            },
                            onSelectProxy = { group, proxy -> handleSelectProxy(group, proxy) },
                            onHealthCheck = { group -> runSpeedTestForGroup(group) },
                            testingNodes = testingNodes,
                            onTestSingleNode = { group, nodeName -> runSpeedTestForSingleNode(group, nodeName) }
                        )
                    }
                    2 -> SettingsScreen(
                        scrollState = settingsScrollState,
                        tunStack = tunStack,
                        bootOnStartup = bootOnStartup,
                        allowLan = allowLan,
                        fakeIpEnabled = fakeIpEnabled,
                        themeMode = currentThemeMode,
                        onTunStackChanged = { stack ->
                            tunStack = stack
                            settingsManager.tunStack = stack
                            if (vpnState.status == VpnState.Status.RUNNING) {
                                MihomoVpnService.restart(this@MainActivity)
                            }
                        },
                        onBootOnStartupChanged = { enabled ->
                            bootOnStartup = enabled
                            settingsManager.bootOnStartup = enabled
                        },
                        onAllowLanChanged = { enabled ->
                            allowLan = enabled
                            settingsManager.allowLan = enabled
                            if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    ClashCore.reloadActiveConfig(this@MainActivity)
                                }
                            }
                        },
                        onNavigateToScripts = {
                            overrideTargetProfile = null
                            overrideTargetProfileId = null
                            currentSubScreen = SubScreen.SCRIPTS
                        },
                        onNavigateToProfiles = { currentSubScreen = SubScreen.PROFILES },
                        onNavigateToAppRouting = { currentSubScreen = SubScreen.APP_ROUTING },
                        onNavigateToLogs = { currentSubScreen = SubScreen.LOGS },
                        onNavigateToAppearance = { currentSubScreen = SubScreen.APPEARANCE },
                        onNavigateToDnsSettings = { currentSubScreen = SubScreen.DNS_SETTINGS },
                        onNavigateToDns = { currentSubScreen = SubScreen.DNS }
                    )
                }
            }

            var lastActiveSubScreen by remember { mutableStateOf<SubScreen?>(null) }
            LaunchedEffect(currentSubScreen) {
                if (currentSubScreen != null) {
                    lastActiveSubScreen = currentSubScreen
                }
            }
            val displayedSubScreen = currentSubScreen ?: lastActiveSubScreen

            val edgeSwipeOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
            var isEdgeDragging by remember { mutableStateOf(false) }
            val density = LocalDensity.current
            val edgeZonePx = with(density) { 32.dp.toPx() }

            LaunchedEffect(currentSubScreen) {
                edgeSwipeOffsetX.snapTo(0f)
                isEdgeDragging = false
            }

            // 2. Sub-screens overlay (smooth slide in / out over the main tabs with iOS edge swipe back)
            AnimatedVisibility(
                visible = currentSubScreen != null,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f)
                    .pointerInput(currentSubScreen) {
                        if (currentSubScreen == null) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                isEdgeDragging = startOffset.x <= edgeZonePx
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (isEdgeDragging && (dragAmount > 0f || edgeSwipeOffsetX.value > 0f)) {
                                    change.consume()
                                    val next = (edgeSwipeOffsetX.value + dragAmount).coerceAtLeast(0f)
                                    coroutineScope.launch { edgeSwipeOffsetX.snapTo(next) }
                                }
                            },
                            onDragEnd = {
                                if (isEdgeDragging) {
                                    isEdgeDragging = false
                                    val screenWidth = size.width.toFloat()
                                    if (edgeSwipeOffsetX.value > screenWidth * 0.30f) {
                                        coroutineScope.launch {
                                            edgeSwipeOffsetX.animateTo(
                                                targetValue = screenWidth,
                                                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                                            )
                                            handleSubScreenBack()
                                            edgeSwipeOffsetX.snapTo(0f)
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            edgeSwipeOffsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = androidx.compose.animation.core.spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = 500f
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                if (isEdgeDragging) {
                                    isEdgeDragging = false
                                    coroutineScope.launch { edgeSwipeOffsetX.animateTo(0f) }
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        translationX = edgeSwipeOffsetX.value
                    }
            ) {
                AnimatedContent(
                    targetState = displayedSubScreen,
                    transitionSpec = {
                        fun getDepth(s: SubScreen?): Int = when (s) {
                            null -> 0
                            SubScreen.PROFILES, SubScreen.APP_ROUTING, SubScreen.LOGS,
                            SubScreen.RULES, SubScreen.DNS, SubScreen.DNS_SETTINGS, SubScreen.REQUESTS, SubScreen.NETWORK_SHARE,
                            SubScreen.APPEARANCE -> 1
                            SubScreen.PROFILE_OVERRIDE -> 2
                            SubScreen.SCRIPTS -> 3
                        }
                        val initialDepth = getDepth(initialState)
                        val targetDepth = getDepth(targetState)

                        if (targetDepth > initialDepth) {
                            (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                    fadeIn(animationSpec = tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                            fadeOut(animationSpec = tween(200))
                                ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
                        } else {
                            (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                    fadeIn(animationSpec = tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                            fadeOut(animationSpec = tween(200))
                                ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "SubScreenOverlayTransition"
                ) { subScreen ->
                    if (subScreen != null) {
                    Box(modifier = Modifier.fillMaxSize().background(LoonBg)) {
                        when (subScreen) {
                            SubScreen.PROFILES -> {
                                ProfilesScreen(
                                    profiles = profiles,
                                    selectedProfileId = selectedProfileId,
                                    onBack = { currentSubScreen = null },
                                    onSelectProfile = { profile ->
                                        selectedProfileId = profile.id
                                        settingsManager.selectedProfileId = profile.id
                                        parseActiveProfile()
                                        if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(this@MainActivity)
                                                refreshProxiesOnline()
                                            }
                                        }
                                        CupertinoToast.show("已生效配置: ${profile.name}")
                                    },
                                    onAddSubscription = { name, url ->
                                        lifecycleScope.launch {
                                            val res = ConfigManager.downloadSubscription(this@MainActivity, url, name)
                                            if (res.isSuccess) {
                                                reloadProfiles()
                                                CupertinoToast.show("订阅添加成功")
                                            } else {
                                                CupertinoToast.show("下载失败: ${res.exceptionOrNull()?.message}", isDestructive = true)
                                            }
                                        }
                                    },
                                    onUpdateProfile = { profile ->
                                        reloadProfiles()
                                        if (selectedProfileId == profile.id) {
                                            parseActiveProfile()
                                            if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    ClashCore.reloadActiveConfig(this@MainActivity)
                                                    refreshProxiesOnline()
                                                }
                                            }
                                        }
                                    },
                                    onRefreshSubscription = { profile ->
                                        if (profile.url != null) {
                                            lifecycleScope.launch {
                                                val res = ConfigManager.updateSubscription(this@MainActivity, profile.id, profile.name, profile.url)
                                                if (res.isSuccess) {
                                                    reloadProfiles()
                                                    if (selectedProfileId == profile.id && (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded)) {
                                                        withContext(Dispatchers.IO) {
                                                            ClashCore.reloadActiveConfig(this@MainActivity)
                                                            refreshProxiesOnline()
                                                        }
                                                    }
                                                    CupertinoToast.show("订阅更新成功")
                                                } else {
                                                    CupertinoToast.show("更新失败: ${res.exceptionOrNull()?.message}", isDestructive = true)
                                                }
                                            }
                                        }
                                    },
                                    onDeleteProfile = { profile ->
                                        val wasSelected = selectedProfileId == profile.id
                                        ConfigManager.deleteProfile(this@MainActivity, profile.id)
                                        if (wasSelected) {
                                            selectedProfileId = null
                                            settingsManager.selectedProfileId = null
                                        }
                                        reloadProfiles()
                                        if (wasSelected && (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded)) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(this@MainActivity)
                                                refreshProxiesOnline()
                                            }
                                        }
                                    },
                                    onImportLocalFile = {
                                        profileImportLauncher.launch("*/*")
                                    },
                                    onReorderProfiles = { newOrderList ->
                                        profiles = newOrderList
                                        ConfigManager.saveProfileOrder(this@MainActivity, newOrderList.map { it.id })
                                    },
                                    onNavigateToOverride = { profile ->
                                        overrideTargetProfile = profile
                                        overrideTargetProfileId = profile.id
                                        currentSubScreen = SubScreen.PROFILE_OVERRIDE
                                    }
                                )
                            }
                            SubScreen.PROFILE_OVERRIDE -> {
                                val currentTarget = overrideTargetProfile ?: profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
                                if (currentTarget != null) {
                                    ProfileOverrideScreen(
                                        profile = currentTarget,
                                        onBack = { currentSubScreen = SubScreen.PROFILES },
                                        onNavigateToScripts = { currentSubScreen = SubScreen.SCRIPTS },
                                        onProfileUpdated = { updated ->
                                            overrideTargetProfile = updated
                                            overrideTargetProfileId = updated.id
                                            reloadProfiles()
                                            parseActiveProfile()
                                        }
                                    )
                                } else {
                                    currentSubScreen = SubScreen.PROFILES
                                }
                            }
                            SubScreen.SCRIPTS -> {
                                ScriptsScreen(
                                    onBack = {
                                        if (overrideTargetProfile != null) {
                                            currentSubScreen = SubScreen.PROFILE_OVERRIDE
                                        } else {
                                            currentSubScreen = null
                                        }
                                    },
                                    onScriptsChanged = {
                                        parseActiveProfile()
                                        if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(this@MainActivity)
                                                refreshProxiesOnline()
                                            }
                                        }
                                    }
                                )
                            }
                            SubScreen.APP_ROUTING -> {
                                AppRoutingScreen(
                                    currentMode = perAppMode,
                                    selectedPackages = selectedPackages,
                                    apps = installedApps,
                                    isLoading = isAppsLoading,
                                    onBack = { currentSubScreen = null },
                                    onModeChanged = { mode ->
                                        perAppMode = mode
                                        settingsManager.perAppProxyMode = mode
                                        if (vpnState.status == VpnState.Status.RUNNING) {
                                            MihomoVpnService.restart(this@MainActivity)
                                        }
                                    },
                                    onTogglePackage = { pkg ->
                                        val updated = if (selectedPackages.contains(pkg)) {
                                            selectedPackages - pkg
                                        } else {
                                            selectedPackages + pkg
                                        }
                                        selectedPackages = updated
                                        settingsManager.selectedPackages = updated
                                        if (vpnState.status == VpnState.Status.RUNNING) {
                                            MihomoVpnService.restart(this@MainActivity)
                                        }
                                    },
                                    onUpdateSelectedPackages = { updated ->
                                        selectedPackages = updated
                                        settingsManager.selectedPackages = updated
                                        if (vpnState.status == VpnState.Status.RUNNING) {
                                            MihomoVpnService.restart(this@MainActivity)
                                        }
                                    }
                                )
                            }
                            SubScreen.LOGS -> {
                                LogsScreen(
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.RULES -> {
                                RulesScreen(
                                    rules = parsedProfile.rules,
                                    totalRules = parsedProfile.totalRules,
                                    tunnelMode = tunnelMode,
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.DNS -> {
                                DnsScreen(
                                    onNavigateToSettings = { currentSubScreen = SubScreen.DNS_SETTINGS },
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.DNS_SETTINGS -> {
                                DnsSettingsScreen(
                                    fakeIpEnabled = fakeIpEnabled,
                                    onFakeIpEnabledChanged = { enabled ->
                                        fakeIpEnabled = enabled
                                        settingsManager.fakeIpEnabled = enabled
                                        if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(this@MainActivity)
                                            }
                                        }
                                    },
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.REQUESTS -> {
                                RequestLogsScreen(
                                    vpnState = vpnState,
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.NETWORK_SHARE -> {
                                NetworkShareScreen(
                                    allowLan = allowLan,
                                    onAllowLanChanged = { enabled ->
                                        allowLan = enabled
                                        settingsManager.allowLan = enabled
                                        if (vpnState.status == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(this@MainActivity)
                                            }
                                        }
                                    },
                                    vpnState = vpnState,
                                    onBack = { currentSubScreen = null }
                                )
                            }
                            SubScreen.APPEARANCE -> {
                                AppearanceScreen(
                                    themeMode = currentThemeMode,
                                    onThemeModeChanged = onThemeModeChanged,
                                    onLanguageChanged = { code -> appLanguage = code },
                                    onBack = { currentSubScreen = null },
                                    appFont = currentAppFont,
                                    onAppFontChanged = onAppFontChanged
                                )
                            }
                        }
                    }
                }
            }
        }

            // 3. Floating Bottom Bar (stays stable underneath sliding sub-screens)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .wrapContentSize(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                LoonFloatingBottomBar(
                    selectedTab = pagerState.targetPage,
                    onTabSelected = { tabIndex ->
                        currentTab = tabIndex
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = tabIndex,
                                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                            )
                        }
                        if (tabIndex == 1) {
                            if (vpnState.status == VpnState.Status.RUNNING) {
                                refreshProxiesOnline()
                            } else if (parsedProfile.groups.isEmpty()) {
                                parseActiveProfile()
                            }
                        }
                    }
                )
            }

            // Global iOS Dynamic Island / Pill HUD notification host
            CupertinoToastHost()
        }
        } // end CompositionLocalProvider
    }
}
