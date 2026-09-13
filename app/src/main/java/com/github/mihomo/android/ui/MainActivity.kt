package com.github.mihomo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.github.mihomo.android.ui.theme.LocalAppLanguage
import androidx.lifecycle.lifecycleScope
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
import com.github.mihomo.android.ui.components.LoonFloatingBottomBar
import com.github.mihomo.android.ui.screens.*
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.MihomoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    PROFILE_OVERRIDE
}

/** Persists the current sub-screen across configuration changes. */
private val subScreenSaver: Saver<SubScreen?, String> = Saver(
    save = { it?.name },
    restore = { name -> runCatching { SubScreen.valueOf(name) }.getOrNull() }
)

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            MihomoVpnService.start(this)
        } else {
            Toast.makeText(this, "需要授予 VPN 权限才能建立代理", Toast.LENGTH_SHORT).show()
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
            MihomoTheme(themeMode = themeMode) {
                MainAppScreen(
                    currentThemeMode = themeMode,
                    onThemeModeChanged = { newMode ->
                        settingsManager.themeMode = newMode
                        themeMode = newMode
                    }
                )
            }
        }
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
        onThemeModeChanged: (ThemeMode) -> Unit
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
        var selectedGroupName by remember { mutableStateOf("") }
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
        var testingNodes by remember { mutableStateOf(setOf<String>()) }

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
                val savedMap = mutableMapOf<String, String>()
                proxyGroups.forEach { g ->
                    settingsManager.getSelectedNode(g)?.let { savedMap[g] = it }
                }
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
                    val groups = allGroups.filter { gName ->
                        val grp = ClashCore.queryGroup(gName)
                        grp?.hidden != true
                    }
                    if (groups.isNotEmpty()) {
                        val groupDataMap = mutableMapOf<String, ProxyGroup>()
                        for (gName in groups) {
                            val gData = ClashCore.queryGroup(gName)
                            if (gData != null) {
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
                        }
                        withContext(Dispatchers.Main) {
                            proxyGroups = groups
                            liveGroupMap = groupDataMap
                            if (selectedGroupName.isBlank() || !groups.contains(selectedGroupName)) {
                                selectedGroupName = groups.firstOrNull() ?: ""
                            }
                            if (selectedGroupName.isNotBlank()) {
                                val cur = groupDataMap[selectedGroupName]
                                if (cur != null) {
                                    currentGroupData = cur
                                }
                            }
                            val mainCur = groupDataMap[mainProxyGroupName]
                            if (mainCur != null && mainCur.now.isNotBlank()) {
                                activeNodeName = mainCur.now
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job {
            return lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (!ClashCore.isCoreLoaded) {
                        ClashCore.ensureCoreLoaded(this@MainActivity)
                    }
                    val targetGroups = if (groupName.isNotBlank()) listOf(groupName) else proxyGroups
                    for (g in targetGroups) {
                        val grp = liveGroupMap[g] ?: parsedProfile.groups[g] ?: (if (selectedGroupName == g) currentGroupData else null)
                        val normType = (grp?.type ?: "").lowercase().replace("-", "").replace("_", "").trim()
                        val isAutoGroup = normType == "urltest" || normType == "fallback" || normType == "loadbalance"

                        if (isAutoTest && !isAutoGroup) continue

                        val filterVirtual = grp?.proxies?.filter { p ->
                            !p.name.equals("DIRECT", ignoreCase = true) &&
                            !p.name.equals("REJECT", ignoreCase = true) &&
                            !p.name.equals("COMPATIBLE", ignoreCase = true)
                        } ?: emptyList()

                        val allNodeNames = filterVirtual.map { it.name }
                        if (allNodeNames.isEmpty()) continue

                        val memberSubGroups = allNodeNames.filter { parsedProfile.groups.containsKey(it) }
                        val memberProxies = allNodeNames.filter { !parsedProfile.groups.containsKey(it) }

                        withContext(Dispatchers.Main) {
                            testingNodes = if (isAutoTest) testingNodes + g else testingNodes + allNodeNames + g
                        }

                        try {
                            val curTime = System.currentTimeMillis()

                            // If it's a background auto test on an auto group, native group test is fast
                            if (isAutoTest && isAutoGroup) {
                                val groupDelays = ClashCore.healthCheckGroupNative(g)
                                withContext(Dispatchers.Main) {
                                    groupDelays.forEach { (name, delay) ->
                                        if (delay > 0 || delay == -2) {
                                            delayCache[name] = delay
                                            if (delay > 0) {
                                                nodeLastTestedTimes[name] = curTime
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Manual speed test: test each member proxy concurrently with bounded pool
                                val poolSemaphore = Semaphore(8)
                                val testJobs = memberProxies.map { nodeName ->
                                    launch(Dispatchers.IO) {
                                        poolSemaphore.withPermit {
                                            val d = ClashCore.testProxyDelayNative(nodeName, g)
                                            withContext(Dispatchers.Main) {
                                                if (d > 0 || d == -2) {
                                                    delayCache[nodeName] = d
                                                    if (d > 0) {
                                                        nodeLastTestedTimes[nodeName] = curTime
                                                    }
                                                }
                                                testingNodes = testingNodes - nodeName
                                            }
                                        }
                                    }
                                }
                                testJobs.joinAll()
                            }

                            // Member sub-groups test (nested groups)
                            for (subG in memberSubGroups) {
                                val subDelays = ClashCore.healthCheckGroupNative(subG)
                                val subGroupData = ClashCore.queryGroup(subG)
                                val subBestName = subGroupData?.now ?: ""
                                val subBestDelay = subDelays[subBestName] ?: subGroupData?.proxies?.find { it.name == subBestName }?.delay ?: -1
                                val finalSubDelay = if (subBestDelay in 1..65534) subBestDelay else if (subBestDelay >= 65535) -2 else -1
                                withContext(Dispatchers.Main) {
                                    if (finalSubDelay > 0 || finalSubDelay == -2) {
                                        delayCache[subG] = finalSubDelay
                                    }
                                    if (subGroupData != null && subGroupData.now.isNotBlank()) {
                                        val existing = liveGroupMap[subG] ?: parsedProfile.groups[subG]
                                        if (existing != null) {
                                            liveGroupMap = liveGroupMap + (subG to existing.copy(now = subGroupData.now))
                                        }
                                    }
                                    testingNodes = testingNodes - subG
                                }
                            }

                            // Update group delay and 'now'
                            val finalGroup = ClashCore.queryGroup(g)
                            withContext(Dispatchers.Main) {
                                val bestNodeName = finalGroup?.now ?: ""
                                val bestDelay = delayCache[bestNodeName] ?: finalGroup?.proxies?.find { it.name == bestNodeName }?.delay ?: -1
                                val finalGroupDelay = if (bestDelay in 1..65534) bestDelay else if (bestDelay >= 65535) -2 else -1
                                if (finalGroupDelay > 0 || finalGroupDelay == -2) {
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
                                testingNodes = if (isAutoTest) testingNodes - g else testingNodes - allNodeNames.toSet() - g
                            }
                        }
                    }
                } catch (_: Throwable) {
                    withContext(Dispatchers.Main) {
                        testingNodes = emptySet()
                    }
                }
            }
        }

        fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) {
            if (testingNodes.contains(nodeName)) return

            if (parsedProfile.groups.containsKey(nodeName)) {
                runSpeedTestForGroup(nodeName, isAutoTest = false)
                return
            }

            testingNodes = testingNodes + nodeName

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (!ClashCore.isCoreLoaded) {
                        ClashCore.ensureCoreLoaded(this@MainActivity)
                    }
                    val delay = ClashCore.testProxyDelayNative(nodeName, groupName)
                    val curTime = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        if (delay > 0 || delay == -2) {
                            delayCache[nodeName] = delay
                            if (delay > 0) {
                                nodeLastTestedTimes[nodeName] = curTime
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    withContext(Dispatchers.Main) {
                        testingNodes = testingNodes - nodeName
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

        // Trigger auto speed test whenever active profile is ready or parsed
        LaunchedEffect(parsedProfile) {
            if (parsedProfile.groups.isNotEmpty()) {
                triggerAutoSpeedTest()
            }
        }

        // Trigger auto speed test on app foreground
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    if (parsedProfile.groups.isNotEmpty()) {
                        triggerAutoSpeedTest()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Re-parse once the profile list finishes loading, otherwise the first run (when the list
        // starts empty) would parse nothing and show no nodes.
        LaunchedEffect(selectedProfileId, profiles) {
            parseActiveProfile()
        }

        // Dynamic refresh when VPN is connected
        LaunchedEffect(vpnState.status) {
            if (vpnState.status == VpnState.Status.RUNNING) {
                triggerAutoSpeedTest()
                while (isActive) {
                    refreshProxiesOnline()
                    delay(3000)
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
                            Toast.makeText(this@MainActivity, "成功导入配置: ${imported?.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "导入失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // Handle Android system back button
        BackHandler(enabled = currentSubScreen != null) {
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

        CompositionLocalProvider(LocalAppLanguage provides appLanguage) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LoonBg)
        ) {
            // Content Screen with smooth slide + fade transitions
            AnimatedContent(
                targetState = currentSubScreen,
                transitionSpec = {
                    fun getDepth(s: SubScreen?): Int = when (s) {
                        null -> 0
                        SubScreen.PROFILES, SubScreen.APP_ROUTING, SubScreen.LOGS -> 1
                        SubScreen.PROFILE_OVERRIDE -> 2
                        SubScreen.SCRIPTS -> 3
                    }
                    val initialDepth = getDepth(initialState)
                    val targetDepth = getDepth(targetState)

                    if (targetDepth > initialDepth) {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                fadeIn(animationSpec = tween(280)))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                        fadeOut(animationSpec = tween(280))
                            )
                    } else if (targetDepth < initialDepth) {
                        (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                fadeIn(animationSpec = tween(280)))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                        fadeOut(animationSpec = tween(280))
                            )
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "ScreenTransition"
            ) { subScreen ->
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
                                Toast.makeText(this@MainActivity, "已生效配置: ${profile.name}", Toast.LENGTH_SHORT).show()
                            },
                            onAddSubscription = { name, url ->
                                lifecycleScope.launch {
                                    val res = ConfigManager.downloadSubscription(this@MainActivity, url, name)
                                    if (res.isSuccess) {
                                        reloadProfiles()
                                        Toast.makeText(this@MainActivity, "订阅添加成功", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "下载失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onUpdateProfile = { profile ->
                                reloadProfiles()
                                if (selectedProfileId == profile.id) {
                                    parseActiveProfile()
                                }
                            },
                            onRefreshSubscription = { profile ->
                                if (profile.url != null) {
                                    lifecycleScope.launch {
                                        val res = ConfigManager.updateSubscription(this@MainActivity, profile.id, profile.name, profile.url)
                                        if (res.isSuccess) {
                                            reloadProfiles()
                                            Toast.makeText(this@MainActivity, "订阅更新成功", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "更新失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onDeleteProfile = { profile ->
                                ConfigManager.deleteProfile(this@MainActivity, profile.id)
                                reloadProfiles()
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
                            },
                            onTogglePackage = { pkg ->
                                val updated = if (selectedPackages.contains(pkg)) {
                                    selectedPackages - pkg
                                } else {
                                    selectedPackages + pkg
                                }
                                selectedPackages = updated
                                settingsManager.selectedPackages = updated
                            }
                        )
                    }
                    SubScreen.LOGS -> {
                        LogsScreen(
                            onBack = { currentSubScreen = null }
                        )
                    }
                    null -> {
                        when (currentTab) {
                            0 -> {
                                val rawProxies = liveGroupMap[mainProxyGroupName]?.proxies
                                    ?: (parsedProfile.groups[mainProxyGroupName]?.proxies ?: emptyList())
                                val mainGroupProxies = rawProxies.map { p ->
                                    val scopedKey = "$mainProxyGroupName:${p.name}"
                                    val d = delayCache[p.name] ?: delayCache[scopedKey]
                                    if (d != null) p.copy(delay = d) else p
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
                                    },
                                    onSelectProxy = { group, proxy -> handleSelectProxy(mainProxyGroupName, proxy) },
                                    onHealthCheck = { group -> runSpeedTestForGroup(group) },
                                    onNavigateToProfiles = { currentSubScreen = SubScreen.PROFILES },
                                    onNavigateToAppRouting = { currentSubScreen = SubScreen.APP_ROUTING },
                                    onNavigateToLogs = { currentSubScreen = SubScreen.LOGS },
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
                                val computedGroupMap = remember(parsedProfile.groups, liveGroupMap, delayCache.toMap()) {
                                    parsedProfile.groups.mapValues { (gName, baseGroup) ->
                                        val live = liveGroupMap[gName]
                                        val liveProxyMap = live?.proxies?.associateBy { it.name } ?: emptyMap()
                                        baseGroup.copy(
                                            now = live?.now?.ifBlank { baseGroup.now } ?: baseGroup.now,
                                            proxies = baseGroup.proxies.map { bp ->
                                                val p = liveProxyMap[bp.name] ?: bp
                                                val scopedKey = "$gName:${bp.name}"
                                                val cached = delayCache[bp.name] ?: delayCache[scopedKey]
                                                if (cached != null) p.copy(delay = cached) else p
                                            }
                                        )
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
                                scriptingEnabled = scriptingEnabled,
                                allowLan = allowLan,
                                fakeIpEnabled = fakeIpEnabled,
                                themeMode = currentThemeMode,
                                onTunStackChanged = { stack ->
                                    tunStack = stack
                                    settingsManager.tunStack = stack
                                },
                                onBootOnStartupChanged = { enabled ->
                                    bootOnStartup = enabled
                                    settingsManager.bootOnStartup = enabled
                                },
                                onScriptingEnabledChanged = { enabled ->
                                    scriptingEnabled = enabled
                                    settingsManager.scriptingEnabled = enabled
                                    parseActiveProfile()
                                },
                                onAllowLanChanged = { enabled ->
                                    allowLan = enabled
                                    settingsManager.allowLan = enabled
                                },
                                onFakeIpEnabledChanged = { enabled ->
                                    fakeIpEnabled = enabled
                                    settingsManager.fakeIpEnabled = enabled
                                },
                                onThemeModeChanged = onThemeModeChanged,
                                onLanguageChanged = { code -> appLanguage = code },
                                onScriptsChanged = {
                                    parseActiveProfile()
                                },
                                onNavigateToScripts = {
                                    overrideTargetProfile = null
                                    overrideTargetProfileId = null
                                    currentSubScreen = SubScreen.SCRIPTS
                                },
                                onNavigateToProfiles = { currentSubScreen = SubScreen.PROFILES },
                                onNavigateToAppRouting = { currentSubScreen = SubScreen.APP_ROUTING },
                                onNavigateToLogs = { currentSubScreen = SubScreen.LOGS }
                            )
                        }
                    }
                }
            }

            // Floating Bottom Bar (animated in/out when navigating to sub-screens)
            AnimatedVisibility(
                visible = currentSubScreen == null,
                enter = fadeIn(animationSpec = tween(240)) + slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    LoonFloatingBottomBar(
                        selectedTab = currentTab,
                        onTabSelected = { tabIndex ->
                            currentTab = tabIndex
                            if (tabIndex == 1) {
                                triggerAutoSpeedTest()
                                if (vpnState.status == VpnState.Status.RUNNING) {
                                    refreshProxiesOnline()
                                } else if (parsedProfile.groups.isEmpty()) {
                                    parseActiveProfile()
                                }
                            }
                        }
                    )
                }
            }
        }
        } // end CompositionLocalProvider
    }
}








