package com.github.mihomo.android.ui

import android.util.Log
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.CupertinoToastHost
import com.github.mihomo.android.ui.components.LoonFloatingBottomBar
import com.github.mihomo.android.ui.screens.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTypeScale
import com.github.mihomo.android.i18n.LocalAppLanguage
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
    APPEARANCE,
    CORE,
    QR_SCAN,
    VPN_TUNNEL,
    ABOUT
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
    // Only the Context API is used (contentResolver), so no Activity cast is needed —
    // casting LocalContext to ComponentActivity breaks on hosts that use a different
    // Activity type and is flagged by the ContextCastToActivity lint check.
    val activity = LocalContext.current

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

    // The profile-override screen needs a target profile. When there is none (the profile was
    // deleted, or the list has not loaded yet) it falls back to the profiles screen. This
    // redirect must not run in the composition body: assigning state while composing is an
    // illegal side effect and can trigger an endless recomposition loop.
    LaunchedEffect(currentSubScreen, overrideTargetProfileId, overrideTargetProfile, profiles) {
        if (currentSubScreen != SubScreen.PROFILE_OVERRIDE) return@LaunchedEffect
        val hasTarget = overrideTargetProfile != null ||
            profiles.firstOrNull { it.id == selectedProfileId } != null ||
            profiles.firstOrNull() != null
        if (!hasTarget) {
            currentSubScreen = SubScreen.PROFILES
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

    // Tunnel-page settings lifted as observable state: reading the settings store directly
    // inside composition is not reactive, so a flipped switch would instantly snap back.
    var killSwitchEnabled by remember { mutableStateOf(settingsManager.killSwitchEnabled) }
    var allowBypassEnabled by remember { mutableStateOf(settingsManager.allowBypassEnabled) }
    var allowPrivateSubs by remember { mutableStateOf(settingsManager.allowPrivateSubscriptionHosts) }
    var mixedPort by remember { mutableIntStateOf(settingsManager.mixedPort) }
    var tunMtu by remember { mutableIntStateOf(settingsManager.tunMtu) }

    // QR subscription import: a scanned URL pre-fills the add dialog on the profiles screen.
    var qrPrefillUrl by remember { mutableStateOf<String?>(null) }

    // Backup password flow. The backup holds the subscription address, the script code and
    // the LAN sharing password, so the export is encrypted with a user-chosen password and an
    // encrypted import asks for that password first.
    var showBackupPasswordDialog by remember { mutableStateOf(false) }
    var showBackupImportPasswordDialog by remember { mutableStateOf(false) }
    var showBackupPlainConfirmDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

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
    // currentTab is the single source of truth for the selected tab. The pager is driven from
    // it, so a configuration change restores the tab (rememberSaveable) AND the pager page.
    // The previous mirror (pagerState.currentPage -> currentTab) let the two drift apart.
    val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    // rememberPagerState only reads `initialPage` on the first composition, so the restored
    // tab has to be pushed into the pager once after a configuration change. The flag is a
    // plain `remember` so it resets on every new composition instance.
    var pagerSyncedToTab by remember { mutableStateOf(false) }
    LaunchedEffect(currentTab) {
        if (!pagerSyncedToTab) {
            pagerSyncedToTab = true
            if (pagerState.currentPage != currentTab) {
                pagerState.scrollToPage(currentTab)
            }
        } else if (pagerState.currentPage != currentTab) {
            pagerState.animateScrollToPage(
                page = currentTab,
                animationSpec = tween(durationMillis = LoonMotion.normalMs, easing = FastOutSlowInEasing)
            )
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

    // The LAN password is committed explicitly from the network-share screen, which reloads
    // the config once per save instead of once per typing pause.

    val profileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // SAF grants are scoped to the picker-launching component: read the URI with
            // the activity context (some providers deny the application context), then
            // hand the result back to the ViewModel for the list reload.
            coroutineScope.launch(Dispatchers.IO) {
                val res = ConfigManager.importProfileFromUri(activity, uri)
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        vm.reloadProfiles()
                        CupertinoToast.show(
                            AppStrings.get("imported_toast", settingsManager.appLanguage)
                                .replace("{name}", res.getOrNull()?.name.orEmpty())
                        )
                    } else {
                        CupertinoToast.show(
                            AppStrings.get("import_failed", settingsManager.appLanguage)
                                .replace("{msg}", res.exceptionOrNull()?.message.orEmpty()),
                            isDestructive = true
                        )
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

    // ---- Backup export / import with an optional password ----

    /** Writes the backup, encrypting it when [password] is not blank. */
    fun doExportBackup(uri: android.net.Uri, password: String?) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    BackupManager.exportBackup(activity, output, password)
                } ?: error(AppStrings.get("backup_write_failed", settingsManager.appLanguage))
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { CupertinoToast.show(AppStrings.get("settings_backup_done", appLanguage)) },
                    onFailure = {
                        CupertinoToast.show(
                            AppStrings.get("settings_backup_failed", appLanguage)
                                .replace("{reason}", it.message ?: AppStrings.get("unknown_error", settingsManager.appLanguage)),
                            isDestructive = true
                        )
                    }
                )
            }
        }
    }

    /** Restores the backup; [password] is null for a plain-text backup. */
    fun doImportBackup(uri: android.net.Uri, password: String?) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val inner = activity.contentResolver.openInputStream(uri)?.use { input ->
                    BackupManager.importBackup(
                        activity,
                        input,
                        AppStrings.get("backup_too_large", settingsManager.appLanguage),
                        password
                    )
                } ?: error(AppStrings.get("backup_read_failed", settingsManager.appLanguage))
                inner.getOrThrow()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { count ->
                        vm.reloadProfiles()
                        CupertinoToast.show(
                            AppStrings.get("settings_backup_restored", appLanguage)
                                .replace("{n}", count.toString())
                        )
                    },
                    onFailure = {
                        CupertinoToast.show(
                            AppStrings.get("settings_backup_failed", appLanguage)
                                .replace("{reason}", it.message ?: AppStrings.get("unknown_error", settingsManager.appLanguage)),
                            isDestructive = true
                        )
                    }
                )
            }
        }
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
            // Only the visible tab is composed. Keeping the other two alive held the whole
            // node list of the proxies tab in memory and recomposed it on every traffic tick.
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            LoonResponsiveContent(maxWidth = 680.dp) {
                when (page) {
                0 -> {
                    // The derived blocks below MUST read vm.* state properties directly:
                    // capturing the dereferenced local vals (val parsedProfile = vm.parsedProfile)
                    // freezes the first-composition values, and derivedStateOf never sees a
                    // state dependency to invalidate on — the quick node list stayed empty
                    // forever on a cold start with existing data.
                    val mainGroupProxies by remember(mainProxyGroupName) {
                        derivedStateOf {
                            vm.liveGroupMap[mainProxyGroupName]?.proxies
                                ?: (vm.parsedProfile.groups[mainProxyGroupName]?.proxies ?: emptyList())
                        }
                    }
                    val currentMainActiveNode = (liveGroupMap[mainProxyGroupName]?.now
                        ?: parsedProfile.groups[mainProxyGroupName]?.now
                        ?: vm.selectedNodes[mainProxyGroupName]
                        ?: activeNodeName).ifBlank { parsedProfile.activeNode }
                    DashboardScreen(
                        scrollState = dashboardScrollState,
                        vpnStatus = vpnStatus,
                        vpnError = vpnError,
                        coreVersion = vpnCoreVersion,
                        vpnTraffic = vpnTraffic,
                        currentMode = tunnelMode,
                        activeProfileName = profiles.firstOrNull { it.id == selectedProfileId }?.name
                            ?: AppStrings.get("default_profile_name", appLanguage),
                        mainGroupName = mainProxyGroupName,
                        mainGroupProxies = mainGroupProxies,
                        delayCache = delayCache,
                        activeNodeName = currentMainActiveNode,
                        totalNodes = parsedProfile.totalNodes,
                        totalRules = parsedProfile.totalRules,
                        visibleShortcuts = visibleShortcuts,
                        shortcutOrder = shortcutOrder,
                        onToggleVpn = { onToggleVpn() },
                        onModeSelected = { newMode ->
                            tunnelMode = newMode
                            settingsManager.tunnelMode = newMode.value
                            if (vpnStatus == VpnState.Status.RUNNING) {
                                // The REST API switches the running mode without tearing the
                                // tunnel down; fall back to a full config reload if it fails.
                                coroutineScope.launch(Dispatchers.IO) {
                                    val patched = MihomoApi.patchMode(newMode.value)
                                    if (!patched) {
                                        ClashCore.reloadActiveConfig(activity)
                                    }
                                    refreshProxiesOnline()
                                }
                            } else if (coreActive) {
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
                        onNavigateToRequests = { currentSubScreen = SubScreen.REQUESTS },
                        onNavigateToDns = { currentSubScreen = SubScreen.DNS },
                        onNavigateToNetworkShare = { currentSubScreen = SubScreen.NETWORK_SHARE },
                        onNavigateToCore = { currentSubScreen = SubScreen.CORE },
                        mixedPort = mixedPort,
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
                    // Reads vm.* properties inside the calculation so the derived state has
                    // real dependencies (see the note on page 0): with captured local vals
                    // this map froze empty at first composition and every LIST-mode group
                    // rendered blank after a cold start.
                    //
                    // The persisted selection comes from vm.selectedNodes: reading
                    // settingsManager.getSelectedNode() here would be a SharedPreferences disk
                    // read per group, on the main thread, on every speed-test refresh.
                    val computedGroupMap by remember {
                        derivedStateOf {
                            val parsed = vm.parsedProfile
                            val liveMap = vm.liveGroupMap
                            val savedNodes = vm.selectedNodes
                            val allGroupKeys = (parsed.groups.keys + liveMap.keys).distinct()
                            allGroupKeys.associateWith { gName ->
                                val baseGroup = parsed.groups[gName] ?: liveMap.getValue(gName)
                                val live = liveMap[gName]
                                val sourceProxies = if (!live?.proxies.isNullOrEmpty()) live.proxies else baseGroup.proxies
                                val liveProxyMap = live?.proxies?.associateBy { it.name } ?: emptyMap()
                                val savedNode = savedNodes[gName]
                                val resolvedNow = if (!savedNode.isNullOrBlank() && sourceProxies.any { it.name == savedNode }) {
                                    savedNode
                                } else {
                                    live?.now?.ifBlank { baseGroup.now } ?: baseGroup.now
                                }
                                baseGroup.copy(
                                    now = resolvedNow,
                                    proxies = sourceProxies.map { bp -> liveProxyMap[bp.name] ?: bp }
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
                        delayCache = delayCache,
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
                    fakeIpEnabled = fakeIpEnabled,
                    themeMode = currentThemeMode,
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
                    onNavigateToCore = { currentSubScreen = SubScreen.CORE },
                    onNavigateToVpnTunnel = { currentSubScreen = SubScreen.VPN_TUNNEL },
                    onNavigateToNetworkShare = { currentSubScreen = SubScreen.NETWORK_SHARE },
                    onNavigateToAbout = { currentSubScreen = SubScreen.ABOUT }
                )
                }
            }
        }

        var lastActiveSubScreen by remember { mutableStateOf<SubScreen?>(null) }
        LaunchedEffect(currentSubScreen) {
            if (currentSubScreen != null) {
                lastActiveSubScreen = currentSubScreen
            }
        }
        val displayedSubScreen = currentSubScreen ?: lastActiveSubScreen

        // The drag phase writes a plain float state: launching a coroutine per frame to call
        // Animatable.snapTo() cancelled the previous snap every time, so the offset could lag
        // the finger. The settle animation is the only place a coroutine is used.
        var edgeOffset by remember { mutableFloatStateOf(0f) }
        var isEdgeDragging by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val edgeZonePx = with(density) { 32.dp.toPx() }
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

        LaunchedEffect(currentSubScreen) {
            isEdgeDragging = false
        }

        // 2. Sub-screens overlay (smooth slide in / out over the main tabs with iOS edge swipe back)
        AnimatedVisibility(
            visible = currentSubScreen != null,
            // RTL mirrors the whole navigation: the sub-screen is pushed in from the LEFT and
            // the interactive back gesture starts at the RIGHT edge and drags the content
            // leftwards. Both halves agree, so the mirroring below stays as it is (UI-I18N-04).
            enter = slideInHorizontally(
                initialOffsetX = { if (isRtl) -it else it },
                animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(LoonMotion.normalMs)),
            exit = slideOutHorizontally(
                targetOffsetX = { if (isRtl) -it else it },
                animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(LoonMotion.fastMs)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
                .pointerInput(currentSubScreen, isRtl) {
                    if (currentSubScreen == null) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            // The interactive back zone lives on the *start* edge; in RTL that
                            // is the right side of the screen and the drag goes left.
                            isEdgeDragging = if (isRtl) {
                                startOffset.x >= size.width - edgeZonePx
                            } else {
                                startOffset.x <= edgeZonePx
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val delta = if (isRtl) -dragAmount else dragAmount
                            if (isEdgeDragging && (delta > 0f || edgeOffset > 0f)) {
                                change.consume()
                                edgeOffset = (edgeOffset + delta).coerceAtLeast(0f)
                            }
                        },
                        onDragEnd = {
                            if (isEdgeDragging) {
                                isEdgeDragging = false
                                val screenWidth = size.width.toFloat()
                                if (edgeOffset > screenWidth * 0.30f) {
                                    // Completing the back gesture: slide the rest of the way
                                    // out, then pop the sub-screen.
                                    coroutineScope.launch {
                                        animate(
                                            initialValue = edgeOffset,
                                            targetValue = screenWidth,
                                            animationSpec = tween(
                                                durationMillis = LoonMotion.fastMs,
                                                easing = FastOutSlowInEasing
                                            )
                                        ) { value, _ -> edgeOffset = value }
                                        handleSubScreenBack()
                                        edgeOffset = 0f
                                    }
                                } else {
                                    // Cancelling the gesture: spring back to the resting place.
                                    coroutineScope.launch {
                                        animate(
                                            initialValue = edgeOffset,
                                            targetValue = 0f,
                                            animationSpec = androidx.compose.animation.core.spring(
                                                dampingRatio = LoonMotion.edgeSettleDampingRatio,
                                                stiffness = LoonMotion.edgeSettleStiffness
                                            )
                                        ) { value, _ -> edgeOffset = value }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            if (isEdgeDragging) {
                                isEdgeDragging = false
                                coroutineScope.launch {
                                    animate(
                                        initialValue = edgeOffset,
                                        targetValue = 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = LoonMotion.edgeSettleDampingRatio,
                                            stiffness = LoonMotion.edgeSettleStiffness
                                        )
                                    ) { value, _ -> edgeOffset = value }
                                }
                            }
                        }
                    )
                }
                .graphicsLayer {
                    translationX = if (isRtl) -edgeOffset else edgeOffset
                }
        ) {
            AnimatedContent(
                targetState = displayedSubScreen,
                transitionSpec = {
                    fun getDepth(s: SubScreen?): Int = when (s) {
                        null -> 0
                        SubScreen.PROFILES, SubScreen.APP_ROUTING, SubScreen.LOGS,
                        SubScreen.RULES, SubScreen.DNS, SubScreen.DNS_SETTINGS, SubScreen.REQUESTS, SubScreen.NETWORK_SHARE,
                        SubScreen.APPEARANCE, SubScreen.CORE, SubScreen.QR_SCAN,
                        SubScreen.VPN_TUNNEL, SubScreen.ABOUT -> 1
                        SubScreen.PROFILE_OVERRIDE -> 2
                        SubScreen.SCRIPTS -> 3
                    }
                    val initialDepth = getDepth(initialState)
                    val targetDepth = getDepth(targetState)

                    if (targetDepth > initialDepth) {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)) +
                                fadeIn(animationSpec = tween(LoonMotion.normalMs)))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)) +
                                        fadeOut(animationSpec = tween(LoonMotion.normalMs))
                            ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
                    } else {
                        (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)) +
                                fadeIn(animationSpec = tween(LoonMotion.normalMs)))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)) +
                                        fadeOut(animationSpec = tween(LoonMotion.normalMs))
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
                                preFillSubscriptionUrl = qrPrefillUrl,
                                onPreFillConsumed = { qrPrefillUrl = null },
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
                                onNavigateToQrScan = { currentSubScreen = SubScreen.QR_SCAN },
                                onNavigateToOverride = { profile ->
                                    overrideTargetProfile = profile
                                    overrideTargetProfileId = profile.id
                                    currentSubScreen = SubScreen.PROFILE_OVERRIDE
                                }
                            )
                        }
                        SubScreen.PROFILE_OVERRIDE -> {
                            val currentTarget = overrideTargetProfile
                                ?: profiles.firstOrNull { it.id == selectedProfileId }
                                ?: profiles.firstOrNull()
                            if (currentTarget != null) {
                                ProfileOverrideScreen(
                                    profile = currentTarget,
                                    onBack = { currentSubScreen = SubScreen.PROFILES },
                                    onNavigateToScripts = { currentSubScreen = SubScreen.SCRIPTS },
                                    onProfileUpdated = { updated ->
                                        overrideTargetProfile = updated
                                        overrideTargetProfileId = updated.id
                                        vm.onProfileMetadataUpdated()
                                        if (updated.id == selectedProfileId && coreActive) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                ClashCore.reloadActiveConfig(activity)
                                            }
                                        }
                                    }
                                )
                            }
                            // NOTE: the "no target -> go back to PROFILES" redirect used to run
                            // here, in the composition body. Assigning state during composition is
                            // an illegal side effect and can loop; it lives in the
                            // LaunchedEffect(currentSubScreen, overrideTargetProfileId) below.
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
                                nameservers = settingsManager.dnsNameservers,
                                fallbackServers = settingsManager.dnsFallbackServers,
                                blockThirdPartyDns = settingsManager.blockThirdPartyDns,
                                onSaveDns = { nameservers, fallback, blockDns ->
                                    settingsManager.dnsNameservers = nameservers
                                    settingsManager.dnsFallbackServers = fallback
                                    settingsManager.blockThirdPartyDns = blockDns
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
                                    // Committed explicitly by the screen; one reload per save.
                                    if (coreActive) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            ClashCore.reloadActiveConfig(activity)
                                        }
                                    }
                                },
                                mixedPort = mixedPort,
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
                                onAppFontChanged = onAppFontChanged,
                                appLanguage = appLanguage,
                                onAppLanguageChanged = { code ->
                                    appLanguage = code
                                    settingsManager.appLanguage = code
                                }
                            )
                        }
                        SubScreen.CORE -> {
                            CoreScreen(
                                onBack = { currentSubScreen = null }
                            )
                        }
                        SubScreen.QR_SCAN -> {
                            QrScanScreen(
                                onBack = { currentSubScreen = null },
                                onResult = { url ->
                                    qrPrefillUrl = url
                                    CupertinoToast.show(AppStrings.get("qr_scan_result", appLanguage))
                                    currentSubScreen = SubScreen.PROFILES
                                }
                            )
                        }
                        SubScreen.VPN_TUNNEL -> {
                            VpnTunnelScreen(
                                tunStack = tunStack,
                                onTunStackChanged = { stack ->
                                    tunStack = stack
                                    settingsManager.tunStack = stack
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                tunMtu = tunMtu,
                                onTunMtuChanged = { mtu ->
                                    tunMtu = mtu
                                    settingsManager.tunMtu = mtu
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                mixedPort = mixedPort,
                                onMixedPortChanged = { port ->
                                    mixedPort = port
                                    settingsManager.mixedPort = port
                                    if (coreActive) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            ClashCore.reloadActiveConfig(activity)
                                        }
                                    }
                                },
                                ipv6Enabled = ipv6Enabled,
                                onIpv6EnabledChanged = { enabled ->
                                    ipv6Enabled = enabled
                                    settingsManager.ipv6Enabled = enabled
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                killSwitchEnabled = killSwitchEnabled,
                                onKillSwitchChanged = { enabled ->
                                    killSwitchEnabled = enabled
                                    settingsManager.killSwitchEnabled = enabled
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                allowBypassEnabled = allowBypassEnabled,
                                onAllowBypassChanged = { enabled ->
                                    allowBypassEnabled = enabled
                                    settingsManager.allowBypassEnabled = enabled
                                    if (vpnStatus == VpnState.Status.RUNNING) {
                                        MihomoVpnService.restart(activity)
                                    }
                                },
                                allowPrivateSubs = allowPrivateSubs,
                                onAllowPrivateSubsChanged = { enabled ->
                                    allowPrivateSubs = enabled
                                    settingsManager.allowPrivateSubscriptionHosts = enabled
                                },
                                bootOnStartup = bootOnStartup,
                                onBootOnStartupChanged = { enabled ->
                                    bootOnStartup = enabled
                                    settingsManager.bootOnStartup = enabled
                                },
                                onBack = { currentSubScreen = null }
                            )
                        }
                        SubScreen.ABOUT -> {
                            AboutScreen(
                                onExportBackup = { uri ->
                                    // The backup contains the subscription address, the script
                                    // code and the LAN sharing password: the user picks a
                                    // password before the file is written.
                                    pendingExportUri = uri
                                    backupPassword = ""
                                    showBackupPasswordDialog = true
                                },
                                onImportBackup = { uri ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        // Peek at the magic header to find out whether the
                                        // backup is encrypted; an encrypted file needs the
                                        // password BEFORE it is imported.
                                        val head = runCatching {
                                            activity.contentResolver.openInputStream(uri)?.use { input ->
                                                val buffer = ByteArray(8)
                                                val read = input.read(buffer)
                                                if (read <= 0) null else buffer.copyOf(read)
                                            }
                                        }.getOrNull()
                                        val encrypted = head != null && BackupManager.isEncrypted(head)
                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                            pendingImportUri = uri
                                            backupPassword = ""
                                            if (encrypted) {
                                                showBackupImportPasswordDialog = true
                                            } else {
                                                doImportBackup(uri, null)
                                            }
                                        }
                                    }
                                },
                                onBack = { currentSubScreen = null }
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
            // currentTab is the single source of truth for the selected tab (it is the
            // rememberSaveable one), so the bar must not read the pager's target page.
            LoonFloatingBottomBar(
                selectedTab = currentTab,
                onTabSelected = { tabIndex ->
                    if (tabIndex != currentTab) {
                        currentTab = tabIndex
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

    // =====================================================================
    // Backup password dialogs
    // =====================================================================

    if (showBackupPasswordDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("backup_password_title", appLanguage),
            message = AppStrings.get("backup_password_warning", appLanguage),
            confirmText = AppStrings.get("action_save", appLanguage),
            dismissText = AppStrings.get("cancel", appLanguage),
            onConfirm = {
                showBackupPasswordDialog = false
                val uri = pendingExportUri
                pendingExportUri = null
                if (uri == null) return@CupertinoAlertDialog
                val password = backupPassword
                backupPassword = ""
                if (password.isBlank()) {
                    // A blank password writes a plain-text JSON backup: ask once more.
                    showBackupPlainConfirmDialog = true
                    pendingExportUri = uri
                } else {
                    doExportBackup(uri, password)
                }
            },
            onDismiss = {
                showBackupPasswordDialog = false
                pendingExportUri = null
                backupPassword = ""
            },
            onDismissRequest = {
                showBackupPasswordDialog = false
                pendingExportUri = null
                backupPassword = ""
            }
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = AppStrings.get("backup_password_hint", appLanguage),
                color = LoonTextSecondary,
                fontSize = LoonTypeScale.footnote
            )
            Spacer(modifier = Modifier.height(8.dp))
            CupertinoTextField(
                value = backupPassword,
                onValueChange = { backupPassword = it },
                placeholder = AppStrings.get("backup_password_hint", appLanguage),
                visualTransformation = PasswordVisualTransformation()
            )
        }
    }

    if (showBackupPlainConfirmDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("backup_password_title", appLanguage),
            message = AppStrings.get("backup_password_plain_warning", appLanguage),
            confirmText = AppStrings.get("action_save", appLanguage),
            dismissText = AppStrings.get("cancel", appLanguage),
            isDestructive = true,
            onConfirm = {
                showBackupPlainConfirmDialog = false
                val uri = pendingExportUri
                pendingExportUri = null
                if (uri != null) doExportBackup(uri, null)
            },
            onDismiss = {
                showBackupPlainConfirmDialog = false
                pendingExportUri = null
            },
            onDismissRequest = {
                showBackupPlainConfirmDialog = false
                pendingExportUri = null
            }
        )
    }

    if (showBackupImportPasswordDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("backup_password_import_title", appLanguage),
            message = AppStrings.get("backup_password_import_hint", appLanguage),
            confirmText = AppStrings.get("action_ok", appLanguage),
            dismissText = AppStrings.get("cancel", appLanguage),
            onConfirm = {
                showBackupImportPasswordDialog = false
                val uri = pendingImportUri
                pendingImportUri = null
                val password = backupPassword
                backupPassword = ""
                if (uri != null) doImportBackup(uri, password)
            },
            onDismiss = {
                showBackupImportPasswordDialog = false
                pendingImportUri = null
                backupPassword = ""
            },
            onDismissRequest = {
                showBackupImportPasswordDialog = false
                pendingImportUri = null
                backupPassword = ""
            }
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoTextField(
                value = backupPassword,
                onValueChange = { backupPassword = it },
                placeholder = AppStrings.get("backup_password_import_hint", appLanguage),
                visualTransformation = PasswordVisualTransformation()
            )
        }
    }
}
