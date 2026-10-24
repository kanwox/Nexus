package com.github.mihomo.android.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.github.mihomo.android.ui.theme.LocalAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
@Composable
fun MainAppScreen(
    settingsManager: SettingsManager,
    onToggleVpn: () -> Unit,
    currentThemeMode: ThemeMode,
    currentAppFont: AppFont,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onAppFontChanged: (AppFont) -> Unit
) {
    // The composable is only hosted by MainActivity; data-layer calls need its Context.
    val activity = LocalContext.current as ComponentActivity

    // Profiles / proxies / speed-test state lives in the ViewModel so an in-flight group
    // speed test survives rotation and its measured delays are not wiped.
    val vm: ProxiesViewModel = viewModel()
    val profiles = vm.profiles
    val selectedProfileId = vm.selectedProfileId
    val parsedProfile = vm.parsedProfile
    val proxyGroups = vm.proxyGroups
    val liveGroupMap = vm.liveGroupMap
    val selectedGroupName = vm.selectedGroupName
    val currentGroupData = vm.currentGroupData
    val activeNodeName = vm.activeNodeName
    val delayCache = vm.delayCache
    val testingNodes = vm.testingNodes
    val mainProxyGroupName = vm.mainProxyGroupName

    // Primary Tabs: 0: 仪表, 1: 策略, 2: 配置
    var currentTab by rememberSaveable { mutableStateOf(0) }
    var currentSubScreen by rememberSaveable(stateSaver = subScreenSaver) {
        mutableStateOf<SubScreen?>(null)
    }
    var overrideTargetProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var overrideTargetProfile by remember { mutableStateOf<ProfileItem?>(null) }

    // Field-level derived reads: vpnState updates once per second (traffic strings), and
    // reading the whole object here would recompose this entire screen (and the three
    // persistent pager tabs) on every tick. Status/error/version change rarely; the traffic
    // values are only read inside DashboardTrafficRow via vpnTraffic.
    val vpnTraffic = MihomoVpnService.vpnState.collectAsStateWithLifecycle()
    val vpnStatus by remember { derivedStateOf { vpnTraffic.value.status } }
    val vpnError by remember { derivedStateOf { vpnTraffic.value.error } }
    val vpnCoreVersion by remember { derivedStateOf { vpnTraffic.value.coreVersion } }
    val coreActive = vpnStatus == VpnState.Status.RUNNING || ClashCore.isCoreLoaded

    var tunnelMode by remember { mutableStateOf(TunnelMode.fromString(settingsManager.tunnelMode)) }
    var appLanguage by remember { mutableStateOf(settingsManager.appLanguage) }

    // Initial profile listing (disk IO) is owned by the ViewModel.
    LaunchedEffect(Unit) {
        vm.reloadProfiles()
    }

    // Re-resolve the override target after rotation, when only its id could be restored.
    LaunchedEffect(overrideTargetProfileId, profiles) {
        if (overrideTargetProfileId == null) {
            overrideTargetProfile = null
        } else if (overrideTargetProfile?.id != overrideTargetProfileId) {
            overrideTargetProfile = profiles.find { it.id == overrideTargetProfileId }
        }
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
    var ipv6Enabled by remember { mutableStateOf(settingsManager.ipv6Enabled) }
    var allowLanPassword by remember { mutableStateOf(settingsManager.allowLanPassword) }
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
    // Group tab scroll states are keyed by group name; a different profile's groups would
    // otherwise accumulate in this map forever as the user switches subscriptions.
    LaunchedEffect(selectedProfileId) {
        proxiesPerGroupTabStates.clear()
    }
    val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) {
        if (currentTab != pagerState.currentPage) {
            currentTab = pagerState.currentPage
        }
    }

    // ---- Delegates to ProxiesViewModel (state + speed-test engine live there) ----

    fun handleSelectProxy(group: String, proxy: Proxy) {
        vm.selectProxy(group, proxy, dispatchToCore = vpnStatus == VpnState.Status.RUNNING)
    }

    fun parseActiveProfile() = vm.parseActiveProfile()

    fun refreshProxiesOnline() = vm.refreshProxiesOnline()

    fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean = false): Job =
        vm.runSpeedTestForGroup(groupName, isAutoTest)

    fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) =
        vm.runSpeedTestForSingleNode(groupName, nodeName)

    fun triggerAutoSpeedTest() = vm.triggerAutoSpeedTest()

    val lifecycleOwner = LocalLifecycleOwner.current

    // Trigger initial auto speed test once when VPN transitions to RUNNING
    LaunchedEffect(vpnStatus) {
        if (vpnStatus == VpnState.Status.RUNNING) {
            triggerAutoSpeedTest()
            refreshProxiesOnline()
        }
    }

    // Dynamic refresh when VPN is connected and user is actively viewing Dashboard or Proxies tab
    val shouldPollProxies = vpnStatus == VpnState.Status.RUNNING &&
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
                    AppInfoManager.getInstalledApps(activity)
                }
            } finally {
                isAppsLoading = false
            }
        }
    }

    // Debounced config reload while typing a LAN access password: one reload per pause
    // instead of one per keystroke. The first fire is the restored initial value.
    var allowLanPasswordInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(allowLanPassword) {
        if (!allowLanPasswordInitialized) {
            allowLanPasswordInitialized = true
            return@LaunchedEffect
        }
        if (vpnStatus == VpnState.Status.RUNNING || ClashCore.isCoreLoaded) {
            delay(800L)
            withContext(Dispatchers.IO) {
                ClashCore.reloadActiveConfig(activity)
            }
        }
    }

    val profileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.importProfile(uri)
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
                        vpnStatus = vpnStatus,
                        vpnError = vpnError,
                        coreVersion = vpnCoreVersion,
                        vpnTraffic = vpnTraffic,
                        currentMode = tunnelMode,
                        activeProfileName = profiles.firstOrNull { it.id == selectedProfileId }?.name ?: "默认配置",
                        mainGroupName = mainProxyGroupName,
                        mainGroupProxies = mainGroupProxies,
                        activeNodeName = currentMainActiveNode,
                        totalNodes = parsedProfile.totalNodes,
                        totalRules = parsedProfile.totalRules,
                        visibleShortcuts = visibleShortcuts,
                        shortcutOrder = shortcutOrder,
                        onToggleVpn = { onToggleVpn() },
                        onModeSelected = { newMode ->
                            tunnelMode = newMode
                            settingsManager.tunnelMode = newMode.value
                            if (coreActive) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    ClashCore.reloadActiveConfig(activity)
                                    refreshProxiesOnline()
                                }
                            }
                        },
                        onSelectProxy = { group, proxy -> handleSelectProxy(mainProxyGroupName, proxy) },
                        onHealthCheck = { group -> runSpeedTestForGroup(group) },
                        onNavigateToProfiles = { currentSubScreen = SubScreen.PROFILES },
                        onNavigateToAppRouting = { currentSubScreen = SubScreen.APP_ROUTING },
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
                            vm.selectGroup(gName, vpnStatus == VpnState.Status.RUNNING)
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
                    ipv6Enabled = ipv6Enabled,
                    themeMode = currentThemeMode,
                    onTunStackChanged = { stack ->
                        tunStack = stack
                        settingsManager.tunStack = stack
                        if (vpnStatus == VpnState.Status.RUNNING) {
                            MihomoVpnService.restart(activity)
                        }
                    },
                    onBootOnStartupChanged = { enabled ->
                        bootOnStartup = enabled
                        settingsManager.bootOnStartup = enabled
                    },
                    onAllowLanChanged = { enabled ->
                        allowLan = enabled
                        settingsManager.allowLan = enabled
                        if (coreActive) {
                            coroutineScope.launch(Dispatchers.IO) {
                                ClashCore.reloadActiveConfig(activity)
                            }
                        }
                    },
                    onIpv6EnabledChanged = { enabled ->
                        ipv6Enabled = enabled
                        settingsManager.ipv6Enabled = enabled
                        // Both the injected header and the TUN routes change, so a full
                        // tunnel restart is required for the switch to take effect.
                        if (vpnStatus == VpnState.Status.RUNNING) {
                            MihomoVpnService.restart(activity)
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
                                    vm.selectProfile(profile, coreActive)
                                },
                                onAddSubscription = { name, url ->
                                    vm.addSubscription(name, url)
                                },
                                onUpdateProfile = { profile ->
                                    vm.reloadProfiles()
                                    if (selectedProfileId == profile.id) {
                                        parseActiveProfile()
                                        if (coreActive) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(activity)
                                                refreshProxiesOnline()
                                            }
                                        }
                                    }
                                },
                                onRefreshSubscription = { profile ->
                                    vm.refreshSubscription(profile, coreActive)
                                },
                                onDeleteProfile = { profile ->
                                    vm.deleteProfile(profile, coreActive)
                                },
                                onImportLocalFile = {
                                    profileImportLauncher.launch("*/*")
                                },
                                onReorderProfiles = { newOrderList ->
                                    vm.reorderProfiles(newOrderList)
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
                                        vm.onProfileMetadataUpdated()
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
                                    vm.onScriptsChanged(coreActive)
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
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
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
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                onUpdateSelectedPackages = { updated ->
                                    selectedPackages = updated
                                    settingsManager.selectedPackages = updated
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
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
                                    if (coreActive) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            ClashCore.reloadActiveConfig(activity)
                                        }
                                    }
                                },
                                onBack = { currentSubScreen = null }
                            )
                        }
                        SubScreen.REQUESTS -> {
                            RequestLogsScreen(
                                vpnStatus = vpnStatus,
                                onBack = { currentSubScreen = null }
                            )
                        }
                        SubScreen.NETWORK_SHARE -> {
                            NetworkShareScreen(
                                allowLan = allowLan,
                                onAllowLanChanged = { enabled ->
                                    allowLan = enabled
                                    settingsManager.allowLan = enabled
                                    if (coreActive) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            ClashCore.reloadActiveConfig(activity)
                                        }
                                    }
                                },
                                allowLanPassword = allowLanPassword,
                                onAllowLanPasswordChanged = { pwd ->
                                    allowLanPassword = pwd
                                    settingsManager.allowLanPassword = pwd
                                },
                                vpnStatus = vpnStatus,
                                onBack = { currentSubScreen = null }
                            )
                        }
                        SubScreen.APPEARANCE -> {
                            AppearanceScreen(
                                themeMode = currentThemeMode,
                                onThemeModeChanged = onThemeModeChanged,
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
            // Derived read: targetPage changes every frame while a tab animation runs, so
            // reading it directly here would recompose this bar on every frame.
            val targetTab by remember { derivedStateOf { pagerState.targetPage } }
            LoonFloatingBottomBar(
                selectedTab = targetTab,
                onTabSelected = { tabIndex ->
                    currentTab = tabIndex
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(
                            page = tabIndex,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                        )
                    }
                    if (tabIndex == 1) {
                        if (vpnStatus == VpnState.Status.RUNNING) {
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
