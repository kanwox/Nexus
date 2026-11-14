@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.AppInfoManager
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.MihomoApi
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.CupertinoToastHost
import com.github.mihomo.android.ui.components.LoonFloatingBottomBar
import com.github.mihomo.android.ui.screens.DashboardActions
import com.github.mihomo.android.ui.screens.DashboardNavigation
import com.github.mihomo.android.ui.screens.DashboardProxySummary
import com.github.mihomo.android.ui.screens.DashboardScreen
import com.github.mihomo.android.ui.screens.DashboardShortcutsState
import com.github.mihomo.android.ui.screens.DashboardStatusState
import com.github.mihomo.android.ui.screens.ProxiesCallbacks
import com.github.mihomo.android.ui.screens.ProxiesDisplayConfig
import com.github.mihomo.android.ui.screens.ProxiesScreen
import com.github.mihomo.android.ui.screens.ProxiesState
import com.github.mihomo.android.ui.screens.SettingsScreen
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun rememberProfileImportLauncher(
    activity: Context,
    vm: ProxiesViewModel,
    getLanguage: () -> String,
    coroutineScope: CoroutineScope
) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val lang = getLanguage()
    coroutineScope.launch(Dispatchers.IO) {
        val res = ConfigManager.importProfileFromUri(activity, uri)
        withContext(Dispatchers.Main) {
            if (res.isSuccess) {
                vm.reloadProfiles()
                CupertinoToast.show(
                    AppStrings.get("imported_toast", lang)
                        .replace("{name}", res.getOrNull()?.name.orEmpty())
                )
            } else {
                CupertinoToast.show(
                    AppStrings.get("import_failed", lang)
                        .replace("{msg}", res.exceptionOrNull()?.message.orEmpty()),
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
fun MainAppScreen(
    settingsManager: SettingsManager,
    onToggleVpn: () -> Unit,
    currentThemeMode: ThemeMode,
    currentAppFont: AppFont,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onAppFontChanged: (AppFont) -> Unit
) {
    val activity = LocalContext.current
    val vm: ProxiesViewModel = viewModel()
    val state = rememberMainAppState(settingsManager, onThemeModeChanged, onAppFontChanged)
    val scrollStates = rememberMainScrollStates()
    val coroutineScope = rememberCoroutineScope()

    val vpnTraffic = MihomoVpnService.vpnState.collectAsStateWithLifecycle()
    val vpnStatus by remember { derivedStateOf { vpnTraffic.value.status } }
    val coreActive = vpnStatus == VpnState.Status.RUNNING || ClashCore.isCoreLoaded
    val pagerState = rememberPagerState(initialPage = state.currentTab, pageCount = { 3 })

    MainAppEffects(activity, vm, state, scrollStates, pagerState, vpnStatus)
    val profileImportLauncher = rememberProfileImportLauncher(
        activity = activity,
        vm = vm,
        getLanguage = { state.appLanguage },
        coroutineScope = coroutineScope
    )

    val subScreenEnv = SubScreenEnvironment(
        state = state,
        vm = vm,
        vpnStatus = vpnStatus,
        coreActive = coreActive,
        activity = activity,
        coroutineScope = coroutineScope
    )
    val subScreenTheme = SubScreenThemeCallbacks(
        currentThemeMode = currentThemeMode,
        currentAppFont = currentAppFont,
        onThemeModeChanged = onThemeModeChanged,
        onAppFontChanged = onAppFontChanged,
        onPickProfileFile = { profileImportLauncher.launch("*/*") }
    )

    CompositionLocalProvider(LocalAppLanguage provides state.appLanguage) {
        Box(modifier = Modifier.fillMaxSize().background(LoonBg)) {
            MainTabsPager(
                pagerState = pagerState,
                env = subScreenEnv,
                scrollStates = scrollStates,
                vpnTraffic = vpnTraffic,
                currentThemeMode = currentThemeMode,
                onToggleVpn = onToggleVpn
            )
            MainSubScreenOverlay(env = subScreenEnv, theme = subScreenTheme)
            MainBottomBarContainer(state.currentTab, vpnStatus, vm, state::selectTab)
            CupertinoToastHost()
        }
        MainAppBackupDialogs(state, vm, coreActive, activity, coroutineScope)
    }
}

@Composable
private fun MainAppEffects(
    activity: Context,
    vm: ProxiesViewModel,
    state: MainAppState,
    scrollStates: MainScrollStates,
    pagerState: PagerState,
    vpnStatus: VpnState.Status
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(Unit) {
        vm.reloadProfiles()
        state.loadEncryptedPreferences()
        UpdateChecker.checkIfDue(activity)
    }

    LaunchedEffect(vm.selectedProfileId) {
        scrollStates.proxiesScrollState.perGroupTabStates.clear()
    }

    var pagerSyncedToTab by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentTab) {
        if (!pagerSyncedToTab) {
            pagerSyncedToTab = true
            if (pagerState.currentPage != state.currentTab) {
                pagerState.scrollToPage(state.currentTab)
            }
        } else if (pagerState.currentPage != state.currentTab) {
            if (reduceMotion) {
                pagerState.scrollToPage(state.currentTab)
            } else {
                pagerState.animateScrollToPage(
                    page = state.currentTab,
                    animationSpec = tween(durationMillis = LoonMotion.normalMs, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    LaunchedEffect(vpnStatus) {
        if (vpnStatus == VpnState.Status.RUNNING) {
            vm.triggerAutoSpeedTest()
            vm.refreshProxiesOnline()
        }
    }

    val shouldPollProxies = vpnStatus == VpnState.Status.RUNNING &&
        state.currentSubScreen == null &&
        (state.currentTab == 0 || state.currentTab == 1)

    LaunchedEffect(shouldPollProxies, lifecycleOwner) {
        if (shouldPollProxies) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(20_000L)
                    vm.refreshProxiesOnline()
                }
            }
        }
    }

    LaunchedEffect(state.currentSubScreen) {
        if (state.currentSubScreen != SubScreen.APP_ROUTING && state.appRoutingDirty) {
            state.appRoutingDirty = false
            if (vpnStatus == VpnState.Status.RUNNING) {
                MihomoVpnService.restart(activity)
            }
        }
        if (state.currentSubScreen == SubScreen.APP_ROUTING && state.installedApps.isEmpty()) {
            state.isAppsLoading = true
            try {
                delay(300L)
                state.installedApps = withContext(Dispatchers.IO) {
                    AppInfoManager.getInstalledApps(activity)
                }
            } finally {
                state.isAppsLoading = false
            }
        }
    }
}

@Composable
private fun MainTabsPager(
    pagerState: PagerState,
    env: SubScreenEnvironment,
    scrollStates: MainScrollStates,
    vpnTraffic: State<VpnState>,
    currentThemeMode: ThemeMode,
    onToggleVpn: () -> Unit
) {
    val state = env.state
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = false,
        beyondViewportPageCount = 0,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        LoonResponsiveContent(maxWidth = 680.dp) {
            when (page) {
                0 -> DashboardTabContent(
                    env = env,
                    scrollStates = scrollStates,
                    vpnTraffic = vpnTraffic,
                    onToggleVpn = onToggleVpn
                )
                1 -> ProxiesTabContent(
                    state = state,
                    scrollStates = scrollStates,
                    vm = env.vm,
                    vpnStatus = env.vpnStatus
                )
                2 -> SettingsScreen(
                    scrollState = scrollStates.settingsScrollState,
                    tunStack = state.tunStack,
                    fakeIpEnabled = state.fakeIpEnabled,
                    themeMode = currentThemeMode,
                    onNavigateToScripts = { state.openSubScreen(SubScreen.SCRIPTS) },
                    onNavigateToProfiles = { state.openSubScreen(SubScreen.PROFILES) },
                    onNavigateToAppRouting = { state.openSubScreen(SubScreen.APP_ROUTING) },
                    onNavigateToLogs = { state.openSubScreen(SubScreen.LOGS) },
                    onNavigateToAppearance = { state.openSubScreen(SubScreen.APPEARANCE) },
                    onNavigateToDnsSettings = { state.openSubScreen(SubScreen.DNS_SETTINGS) },
                    onNavigateToCore = { state.openSubScreen(SubScreen.CORE) },
                    onNavigateToVpnTunnel = { state.openSubScreen(SubScreen.VPN_TUNNEL) },
                    onNavigateToNetworkShare = { state.openSubScreen(SubScreen.NETWORK_SHARE) },
                    onNavigateToAbout = { state.openSubScreen(SubScreen.ABOUT) }
                )
            }
        }
    }
}

private fun updateTunnelModeWithRollback(
    newMode: TunnelMode,
    state: MainAppState,
    vm: ProxiesViewModel,
    vpnStatus: VpnState.Status,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    val prevMode = state.tunnelMode
    state.tunnelMode = newMode
    state.settingsManager.tunnelMode = newMode.value
    if (vpnStatus != VpnState.Status.RUNNING && !coreActive) return
    coroutineScope.reloadCoreWithRollback(
        context = activity,
        appLanguage = state.appLanguage,
        reloadAction = {
            if (vpnStatus == VpnState.Status.RUNNING) {
                MihomoApi.patchMode(newMode.value) || ClashCore.reloadActiveConfig(activity)
            } else {
                ClashCore.reloadActiveConfig(activity)
            }
        },
        onRollback = {
            state.tunnelMode = prevMode
            state.settingsManager.tunnelMode = prevMode.value
        },
        onSuccess = { vm.refreshProxiesOnline() }
    )
}

@Composable
private fun DashboardTabContent(
    env: SubScreenEnvironment,
    scrollStates: MainScrollStates,
    vpnTraffic: State<VpnState>,
    onToggleVpn: () -> Unit
) {
    val state = env.state
    val vm = env.vm
    val vpnStatus = env.vpnStatus
    val coreActive = env.coreActive
    val activity = env.activity
    val coroutineScope = env.coroutineScope
    val vpnError by remember { derivedStateOf { vpnTraffic.value.error } }
    val vpnCoreVersion by remember { derivedStateOf { vpnTraffic.value.coreVersion } }
    val mainGroupName = vm.mainProxyGroupName
    val mainGroupProxies by remember(mainGroupName) {
        derivedStateOf {
            vm.liveGroupMap[mainGroupName]?.proxies
                ?: (vm.parsedProfile.groups[mainGroupName]?.proxies ?: emptyList())
        }
    }
    val currentMainActiveNode = (vm.liveGroupMap[mainGroupName]?.now
        ?: vm.parsedProfile.groups[mainGroupName]?.now
        ?: vm.selectedNodes[mainGroupName]
        ?: vm.activeNodeName).ifBlank { vm.parsedProfile.activeNode }
    val activeProfileName = vm.profiles.firstOrNull { profile -> profile.id == vm.selectedProfileId }?.name
        ?: AppStrings.get("default_profile_name", state.appLanguage)

    DashboardScreen(
        status = DashboardStatusState(
            vpnStatus, vpnError, vpnCoreVersion, vpnTraffic, state.tunnelMode, activeProfileName, state.mixedPort
        ),
        proxySummary = DashboardProxySummary(
            mainGroupName, mainGroupProxies, vm.delayCache, currentMainActiveNode,
            vm.parsedProfile.totalNodes, vm.parsedProfile.totalRules
        ),
        shortcuts = DashboardShortcutsState(
            visibleShortcuts = state.visibleShortcuts,
            shortcutOrder = state.shortcutOrder,
            onUpdateVisibleShortcuts = { updated ->
                state.visibleShortcuts = updated
                state.settingsManager.visibleShortcuts = updated
            },
            onUpdateShortcutOrder = { updated ->
                state.shortcutOrder = updated
                state.settingsManager.shortcutOrder = updated
            }
        ),
        actions = DashboardActions(
            onToggleVpn = onToggleVpn,
            onModeSelected = { newMode ->
                updateTunnelModeWithRollback(newMode, state, vm, vpnStatus, coreActive, activity, coroutineScope)
            },
            onSelectProxy = { _, proxy ->
                vm.selectProxy(mainGroupName, proxy, dispatchToCore = vpnStatus == VpnState.Status.RUNNING)
            },
            onHealthCheck = { group -> vm.runSpeedTestForGroup(group) }
        ),
        navigation = DashboardNavigation(
            onNavigateToProfiles = { state.openSubScreen(SubScreen.PROFILES) },
            onNavigateToAppRouting = { state.openSubScreen(SubScreen.APP_ROUTING) },
            onNavigateToRules = { state.openSubScreen(SubScreen.RULES) },
            onNavigateToRequests = { state.openSubScreen(SubScreen.REQUESTS) },
            onNavigateToDns = { state.openSubScreen(SubScreen.DNS) },
            onNavigateToNetworkShare = { state.openSubScreen(SubScreen.NETWORK_SHARE) },
            onNavigateToCore = { state.openSubScreen(SubScreen.CORE) }
        ),
        scrollState = scrollStates.dashboardScrollState
    )
}

@Composable
private fun ProxiesTabContent(
    state: MainAppState,
    scrollStates: MainScrollStates,
    vm: ProxiesViewModel,
    vpnStatus: VpnState.Status
) {
    val computedGroupMap by remember {
        derivedStateOf {
            val parsed = vm.parsedProfile
            val liveMap = vm.liveGroupMap
            val savedNodes = vm.selectedNodes
            val allGroupKeys = (parsed.groups.keys + liveMap.keys).distinct()
            allGroupKeys.mapNotNull { gName ->
                val baseGroup = parsed.groups[gName] ?: liveMap[gName] ?: return@mapNotNull null
                val live = liveMap[gName]
                val sourceProxies = if (!live?.proxies.isNullOrEmpty()) live.proxies else baseGroup.proxies
                val liveProxyMap = live?.proxies?.associateBy { item -> item.name } ?: emptyMap()
                val savedNode = savedNodes[gName]
                val resolvedNow = if (!savedNode.isNullOrBlank() && sourceProxies.any { p -> p.name == savedNode }) {
                    savedNode
                } else {
                    live?.now?.ifBlank { baseGroup.now } ?: baseGroup.now
                }
                gName to baseGroup.copy(
                    now = resolvedNow,
                    proxies = sourceProxies.map { bp -> liveProxyMap[bp.name] ?: bp }
                )
            }.toMap()
        }
    }
    val effectiveCurrentGroup = computedGroupMap[vm.selectedGroupName] ?: vm.currentGroupData

    ProxiesScreen(
        state = ProxiesState(
            groups = vm.proxyGroups,
            groupMap = computedGroupMap,
            currentGroup = effectiveCurrentGroup,
            selectedGroupName = vm.selectedGroupName,
            groupIcons = vm.parsedProfile.groupIcons,
            delayCache = vm.delayCache,
            testingNodes = vm.testingNodes
        ),
        display = ProxiesDisplayConfig(
            currentViewMode = state.proxiesViewMode,
            showGroupIcons = state.showGroupIcons,
            nodeColumns = state.nodeColumns,
            nodeSortMode = state.nodeSortMode
        ),
        callbacks = ProxiesCallbacks(
            onViewModeChanged = { mode ->
                state.proxiesViewMode = mode
                state.settingsManager.proxiesViewMode = mode
            },
            onShowGroupIconsChanged = { show ->
                state.showGroupIcons = show
                state.settingsManager.showGroupIcons = show
            },
            onNodeColumnsChanged = { cols ->
                state.nodeColumns = cols
                state.settingsManager.nodeColumns = cols
            },
            onNodeSortModeChanged = { sortMode ->
                state.nodeSortMode = sortMode
                state.settingsManager.nodeSortMode = sortMode
            },
            onSelectGroup = { gName -> vm.selectGroup(gName, vpnStatus == VpnState.Status.RUNNING) },
            onSelectProxy = { group, proxy -> vm.selectProxy(group, proxy, vpnStatus == VpnState.Status.RUNNING) },
            onHealthCheck = { group -> vm.runSpeedTestForGroup(group) },
            onTestSingleNode = { group, nodeName -> vm.runSpeedTestForSingleNode(group, nodeName) }
        ),
        scrollState = scrollStates.proxiesScrollState
    )
}

@Composable
private fun BoxScope.MainBottomBarContainer(
    currentTab: Int,
    vpnStatus: VpnState.Status,
    vm: ProxiesViewModel,
    onSelectTab: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .wrapContentSize(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        LoonFloatingBottomBar(
            selectedTab = currentTab,
            onTabSelected = { tabIndex ->
                onSelectTab(tabIndex)
                if (tabIndex == 1) {
                    if (vpnStatus == VpnState.Status.RUNNING) {
                        vm.refreshProxiesOnline()
                    } else if (vm.parsedProfile.groups.isEmpty()) {
                        vm.parseActiveProfile()
                    }
                }
            }
        )
    }
}
