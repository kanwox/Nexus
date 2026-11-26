@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.BackupManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.screens.AboutScreen
import com.github.mihomo.android.ui.screens.AppRoutingScreen
import com.github.mihomo.android.ui.screens.AppearanceScreen
import com.github.mihomo.android.ui.screens.CoreScreen
import com.github.mihomo.android.ui.screens.DnsScreen
import com.github.mihomo.android.ui.screens.DnsSettingsScreen
import com.github.mihomo.android.ui.screens.LogsScreen
import com.github.mihomo.android.ui.screens.NetworkShareScreen
import com.github.mihomo.android.ui.screens.ProfileOverrideScreen
import com.github.mihomo.android.ui.screens.ProfilesScreen
import com.github.mihomo.android.ui.screens.QrScanScreen
import com.github.mihomo.android.ui.screens.RequestLogsScreen
import com.github.mihomo.android.ui.screens.RulesScreen
import com.github.mihomo.android.ui.screens.ScriptsScreen
import com.github.mihomo.android.ui.screens.VpnTunnelCallbacks
import com.github.mihomo.android.ui.screens.VpnTunnelScreen
import com.github.mihomo.android.ui.screens.VpnTunnelUiState
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SubScreenPredictiveBackEffect(
    enabled: Boolean,
    onPredictiveBack: suspend (Flow<BackEventCompat>) -> Unit
) {
    PredictiveBackHandler(enabled = enabled, onBack = onPredictiveBack)
}

internal data class SubScreenEnvironment(
    val state: MainAppState,
    val vm: ProxiesViewModel,
    val vpnStatus: VpnState.Status,
    val coreActive: Boolean,
    val activity: Context,
    val coroutineScope: CoroutineScope
)

internal data class SubScreenThemeCallbacks(
    val currentThemeMode: ThemeMode,
    val currentAppFont: AppFont,
    val onThemeModeChanged: (ThemeMode) -> Unit,
    val onAppFontChanged: (AppFont) -> Unit,
    val onPickProfileFile: () -> Unit
)

@Composable
internal fun MainSubScreenOverlay(
    env: SubScreenEnvironment,
    theme: SubScreenThemeCallbacks
) {
    val state = env.state
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val edgeZonePx = with(density) { 32.dp.toPx() }
    val backSweepWidthPx = with(density) { 200.dp.toPx() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val visibleState = remember { MutableTransitionState(state.currentSubScreen != null) }
    visibleState.targetState = state.currentSubScreen != null

    var lastActiveSubScreen by remember { mutableStateOf<SubScreen?>(null) }
    val displayedSubScreen = state.currentSubScreen ?: lastActiveSubScreen
    val bodyTransition = updateTransition(
        targetState = displayedSubScreen,
        label = "SubScreenOverlayTransition"
    )
    val controller = rememberSubScreenEdgeBackController(
        env = env,
        visibleState = visibleState,
        fallbackSweepWidthPx = backSweepWidthPx,
        reduceMotion = reduceMotion
    )
    controller.isBodyTransitionSettled = { targetParent ->
        bodyTransition.currentState == targetParent && !bodyTransition.isRunning
    }

    LaunchedEffect(state.currentSubScreen) {
        controller.syncOnScreenChange(state.currentSubScreen) { active ->
            lastActiveSubScreen = active
        }
    }

    LaunchedEffect(state.currentSubScreen, visibleState.currentState, visibleState.targetState) {
        controller.resetIfOverlaySettledHidden()
    }

    SubScreenPredictiveBackEffect(
        enabled = state.currentSubScreen != null && !state.edgeBackBlockedByChild,
        onPredictiveBack = controller::handlePredictiveBack
    )

    val enterAnim = if (reduceMotion) EnterTransition.None else {
        slideInHorizontally(
            initialOffsetX = { width -> if (isRtl) -width else width },
            animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
        )
    }
    val exitAnim = if (reduceMotion || controller.skipVisibilityExit) ExitTransition.None else {
        slideOutHorizontally(
            targetOffsetX = { width -> if (isRtl) -width else width },
            animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
        )
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = enterAnim,
        exit = exitAnim,
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .onSizeChanged { size -> controller.containerWidthPx = size.width.toFloat() }
            .subScreenEdgeBackGesture(
                currentSubScreen = state.currentSubScreen,
                enabled = !state.edgeBackBlockedByChild,
                isRtl = isRtl,
                edgeZonePx = edgeZonePx,
                controller = controller
            )
            .graphicsLayer {
                val offset = controller.edgeOffset.value
                translationX = if (isRtl) -offset else offset
                alpha = controller.overlayAlpha.value
            }
    ) {
        SubScreenAnimatedBody(
            bodyTransition = bodyTransition,
            isRtl = isRtl,
            skipTransition = reduceMotion || controller.skipNestedTransition,
            env = env,
            theme = theme
        )
    }
}

private fun Modifier.subScreenEdgeBackGesture(
    currentSubScreen: SubScreen?,
    enabled: Boolean,
    isRtl: Boolean,
    edgeZonePx: Float,
    controller: SubScreenEdgeBackController
): Modifier = pointerInput(currentSubScreen, enabled, isRtl, controller.reduceMotion) {
    if (currentSubScreen == null || !enabled) return@pointerInput

    detectHorizontalDragGestures(
        onDragStart = { startOffset ->
            controller.onDragStarted(startOffset.x, size.width.toFloat(), edgeZonePx, isRtl)
        },
        onHorizontalDrag = { change, dragAmount ->
            if (controller.onDragDelta(dragAmount, isRtl)) {
                change.consume()
            }
        },
        onDragEnd = { controller.onDragEnded(size.width.toFloat()) },
        onDragCancel = { controller.onDragCanceled() }
    )
}

@Composable
private fun SubScreenAnimatedBody(
    bodyTransition: Transition<SubScreen?>,
    isRtl: Boolean,
    skipTransition: Boolean,
    env: SubScreenEnvironment,
    theme: SubScreenThemeCallbacks
) {
    val rtlSign = if (isRtl) -1 else 1
    bodyTransition.AnimatedContent(
        transitionSpec = {
            if (skipTransition) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fun depthOf(screen: SubScreen?): Int = when (screen) {
                    null -> 0
                    SubScreen.QR_SCAN, SubScreen.PROFILE_OVERRIDE -> 2
                    SubScreen.SCRIPTS ->
                        if (initialState == SubScreen.PROFILE_OVERRIDE || targetState == SubScreen.PROFILE_OVERRIDE) 3 else 2
                    SubScreen.DNS_SETTINGS ->
                        if (initialState == SubScreen.DNS || targetState == SubScreen.DNS) 2 else 1
                    SubScreen.PROFILES, SubScreen.APP_ROUTING, SubScreen.LOGS, SubScreen.RULES,
                    SubScreen.DNS, SubScreen.REQUESTS, SubScreen.NETWORK_SHARE,
                    SubScreen.APPEARANCE, SubScreen.CORE, SubScreen.VPN_TUNNEL, SubScreen.ABOUT -> 1
                }
                if (depthOf(targetState) > depthOf(initialState)) {
                    slideInHorizontally(
                        initialOffsetX = { width -> rtlSign * width },
                        animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
                    ).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { width -> -rtlSign * width / 3 },
                            animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
                        )
                    ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
                } else {
                    slideInHorizontally(
                        initialOffsetX = { width -> -rtlSign * width / 3 },
                        animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
                    ).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { width -> rtlSign * width },
                            animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
                        )
                    ).using(SizeTransform(clip = false) { _, _ -> tween(0) }).apply { targetContentZIndex = -1f }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { subScreen ->
        if (subScreen != null) {
            Box(modifier = Modifier.fillMaxSize().background(LoonBg)) {
                SubScreenDestinationContent(subScreen = subScreen, env = env, theme = theme)
            }
        }
    }
}

@Composable
private fun SubScreenDestinationContent(
    subScreen: SubScreen,
    env: SubScreenEnvironment,
    theme: SubScreenThemeCallbacks
) {
    when (subScreen) {
        SubScreen.PROFILES, SubScreen.PROFILE_OVERRIDE, SubScreen.SCRIPTS, SubScreen.QR_SCAN ->
            ProfilesAndScriptsDestinations(
                subScreen = subScreen,
                state = env.state,
                vm = env.vm,
                coreActive = env.coreActive,
                activity = env.activity,
                coroutineScope = env.coroutineScope,
                onPickProfileFile = theme.onPickProfileFile
            )
        SubScreen.APP_ROUTING, SubScreen.DNS_SETTINGS, SubScreen.NETWORK_SHARE, SubScreen.VPN_TUNNEL ->
            NetworkAndTunnelDestinations(
                subScreen = subScreen,
                state = env.state,
                vpnStatus = env.vpnStatus,
                coreActive = env.coreActive,
                activity = env.activity,
                coroutineScope = env.coroutineScope
            )
        SubScreen.LOGS, SubScreen.RULES, SubScreen.DNS, SubScreen.REQUESTS,
        SubScreen.APPEARANCE, SubScreen.CORE, SubScreen.ABOUT ->
            DiagnosticsAndGeneralDestinations(
                subScreen = subScreen,
                env = env,
                theme = theme
            )
    }
}

@Composable
private fun ProfilesAndScriptsDestinations(
    subScreen: SubScreen,
    state: MainAppState,
    vm: ProxiesViewModel,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope,
    onPickProfileFile: () -> Unit
) {
    when (subScreen) {
        SubScreen.PROFILES -> ProfilesScreen(
            profiles = vm.profiles,
            selectedProfileId = vm.selectedProfileId,
            onBack = state::navigateBackSubScreen,
            preFillSubscriptionUrl = state.qrPrefillUrl,
            onPreFillConsumed = { state.qrPrefillUrl = null },
            onSelectProfile = { profile -> vm.selectProfile(profile, coreActive) },
            onAddSubscription = { name, url -> vm.addSubscription(name, url) },
            onUpdateProfile = { profile ->
                vm.reloadProfiles()
                if (vm.selectedProfileId == profile.id) {
                    vm.parseActiveProfile()
                    if (coreActive) {
                        coroutineScope.launch(Dispatchers.IO) {
                            ClashCore.reloadActiveConfig(activity)
                            vm.refreshProxiesOnline()
                        }
                    }
                }
            },
            onRefreshSubscription = { profile -> vm.refreshSubscription(profile, coreActive) },
            isUpdatingAll = vm.isRefreshingAll,
            onRefreshAllSubscriptions = { vm.refreshAllSubscriptions(coreActive) },
            onDeleteProfile = { profile -> vm.deleteProfile(profile, coreActive) },
            onImportLocalFile = onPickProfileFile,
            onReorderProfiles = { newOrderList -> vm.reorderProfiles(newOrderList) },
            onNavigateToQrScan = { state.openSubScreen(SubScreen.QR_SCAN, parent = SubScreen.PROFILES) },
            onNavigateToOverride = { profile -> state.openProfileOverride(profile.id) }
        )
        SubScreen.PROFILE_OVERRIDE -> {
            val targetProfile = vm.profiles.find { item -> item.id == state.overrideTargetProfileId }
                ?: vm.profiles.firstOrNull { item -> item.id == vm.selectedProfileId }
                ?: vm.profiles.firstOrNull()
            if (targetProfile != null) {
                ProfileOverrideScreen(
                    profile = targetProfile,
                    onBack = state::navigateBackSubScreen,
                    onNavigateToScripts = { state.openSubScreen(SubScreen.SCRIPTS, parent = SubScreen.PROFILE_OVERRIDE) },
                    onProfileUpdated = { vm.onProfileMetadataUpdated() }
                )
            }
        }
        SubScreen.SCRIPTS -> ScriptsScreen(
            onBack = state::navigateBackSubScreen,
            onScriptsChanged = { vm.onScriptsChanged(coreActive) },
            onEditorVisibilityChanged = { visible -> state.edgeBackBlockedByChild = visible }
        )
        SubScreen.QR_SCAN -> QrScanScreen(
            onBack = state::navigateBackSubScreen,
            onResult = { url ->
                state.qrPrefillUrl = url
                CupertinoToast.show(AppStrings.get("qr_scan_result", state.appLanguage))
                state.openSubScreen(SubScreen.PROFILES, parent = null)
            }
        )
        else -> Unit
    }
}

@Composable
private fun NetworkAndTunnelDestinations(
    subScreen: SubScreen,
    state: MainAppState,
    vpnStatus: VpnState.Status,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    when (subScreen) {
        SubScreen.APP_ROUTING -> AppRoutingScreen(
            currentMode = state.perAppMode,
            selectedPackages = state.selectedPackages,
            apps = state.installedApps,
            isLoading = state.isAppsLoading,
            onBack = state::navigateBackSubScreen,
            onModeChanged = { mode ->
                state.perAppMode = mode
                state.settingsManager.perAppProxyMode = mode
                state.appRoutingDirty = true
            },
            onTogglePackage = { pkg ->
                val updated = if (state.selectedPackages.contains(pkg)) state.selectedPackages - pkg
                else state.selectedPackages + pkg
                state.selectedPackages = updated
                state.settingsManager.selectedPackages = updated
                state.appRoutingDirty = true
            },
            onUpdateSelectedPackages = { updated ->
                state.selectedPackages = updated
                state.settingsManager.selectedPackages = updated
                state.appRoutingDirty = true
            }
        )
        SubScreen.DNS_SETTINGS -> DnsSettingsDestination(state, coreActive, activity, coroutineScope)
        SubScreen.NETWORK_SHARE -> NetworkShareDestination(state, vpnStatus, coreActive, activity, coroutineScope)
        SubScreen.VPN_TUNNEL -> VpnTunnelDestination(state, vpnStatus, coreActive, activity, coroutineScope)
        else -> Unit
    }
}

@Composable
private fun DnsSettingsDestination(
    state: MainAppState,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    DnsSettingsScreen(
        fakeIpEnabled = state.fakeIpEnabled,
        onFakeIpEnabledChanged = { enabled ->
            val prev = state.fakeIpEnabled
            state.fakeIpEnabled = enabled
            state.settingsManager.fakeIpEnabled = enabled
            if (coreActive) {
                coroutineScope.reloadCoreWithRollback(
                    context = activity,
                    appLanguage = state.appLanguage,
                    onRollback = {
                        state.fakeIpEnabled = prev
                        state.settingsManager.fakeIpEnabled = prev
                    }
                )
            }
        },
        nameservers = state.dnsNameservers,
        fallbackServers = state.dnsFallbackServers,
        blockThirdPartyDns = state.blockThirdPartyDns,
        onSaveDns = { nameservers, fallback, blockDns ->
            val prevNs = state.dnsNameservers
            val prevFb = state.dnsFallbackServers
            val prevBlock = state.blockThirdPartyDns
            state.dnsNameservers = nameservers
            state.dnsFallbackServers = fallback
            state.blockThirdPartyDns = blockDns
            state.settingsManager.dnsNameservers = nameservers
            state.settingsManager.dnsFallbackServers = fallback
            state.settingsManager.blockThirdPartyDns = blockDns
            if (coreActive) {
                coroutineScope.reloadCoreWithRollback(
                    context = activity,
                    appLanguage = state.appLanguage,
                    onRollback = {
                        state.dnsNameservers = prevNs
                        state.dnsFallbackServers = prevFb
                        state.blockThirdPartyDns = prevBlock
                        state.settingsManager.dnsNameservers = prevNs
                        state.settingsManager.dnsFallbackServers = prevFb
                        state.settingsManager.blockThirdPartyDns = prevBlock
                    }
                )
            }
        },
        onBack = state::navigateBackSubScreen
    )
}

@Composable
private fun NetworkShareDestination(
    state: MainAppState,
    vpnStatus: VpnState.Status,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    NetworkShareScreen(
        allowLan = state.allowLan,
        onAllowLanChanged = { enabled ->
            val prev = state.allowLan
            state.allowLan = enabled
            state.settingsManager.allowLan = enabled
            if (coreActive) {
                coroutineScope.reloadCoreWithRollback(
                    context = activity,
                    appLanguage = state.appLanguage,
                    onRollback = {
                        state.allowLan = prev
                        state.settingsManager.allowLan = prev
                    }
                )
            }
        },
        allowLanPassword = state.allowLanPassword,
        onAllowLanPasswordChanged = { pwd ->
            val prev = state.allowLanPassword
            state.allowLanPassword = pwd
            state.lanPasswordLoaded = true
            coroutineScope.launch(Dispatchers.IO) {
                state.settingsManager.allowLanPassword = pwd
                if (coreActive) {
                    val ok = runCatching { ClashCore.reloadActiveConfig(activity) }.getOrDefault(false)
                    if (!ok) {
                        state.settingsManager.allowLanPassword = prev
                        withContext(Dispatchers.Main) {
                            state.allowLanPassword = prev
                            CupertinoToast.show(
                                AppStrings.get("core_reload_failed", state.appLanguage),
                                isDestructive = true
                            )
                        }
                    }
                }
            }
        },
        mixedPort = state.mixedPort,
        vpnStatus = vpnStatus,
        onBack = state::navigateBackSubScreen
    )
}

@Composable
private fun VpnTunnelDestination(
    state: MainAppState,
    vpnStatus: VpnState.Status,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    fun restartVpnIfRunning() {
        if (vpnStatus == VpnState.Status.RUNNING) {
            MihomoVpnService.restart(activity)
        }
    }

    VpnTunnelScreen(
        state = VpnTunnelUiState(
            tunStack = state.tunStack,
            tunMtu = state.tunMtu,
            mixedPort = state.mixedPort,
            ipv6Enabled = state.ipv6Enabled,
            killSwitchEnabled = state.killSwitchEnabled,
            allowBypassEnabled = state.allowBypassEnabled,
            allowPrivateSubs = state.allowPrivateSubs,
            bootOnStartup = state.bootOnStartup
        ),
        callbacks = VpnTunnelCallbacks(
            onTunStackChanged = { stack ->
                state.tunStack = stack
                state.settingsManager.tunStack = stack
                restartVpnIfRunning()
            },
            onTunMtuChanged = { mtu ->
                state.tunMtu = mtu
                state.settingsManager.tunMtu = mtu
                restartVpnIfRunning()
            },
            onMixedPortChanged = { port ->
                val prevPort = state.mixedPort
                state.mixedPort = port
                state.settingsManager.mixedPort = port
                if (coreActive) {
                    coroutineScope.reloadCoreWithRollback(
                        context = activity,
                        appLanguage = state.appLanguage,
                        onRollback = {
                            state.mixedPort = prevPort
                            state.settingsManager.mixedPort = prevPort
                        }
                    )
                }
            },
            onIpv6EnabledChanged = { enabled ->
                state.ipv6Enabled = enabled
                state.settingsManager.ipv6Enabled = enabled
                restartVpnIfRunning()
            },
            onKillSwitchChanged = { enabled ->
                state.killSwitchEnabled = enabled
                state.settingsManager.killSwitchEnabled = enabled
                restartVpnIfRunning()
            },
            onAllowBypassChanged = { enabled ->
                state.allowBypassEnabled = enabled
                state.settingsManager.allowBypassEnabled = enabled
                restartVpnIfRunning()
            },
            onAllowPrivateSubsChanged = { enabled ->
                state.allowPrivateSubs = enabled
                state.settingsManager.allowPrivateSubscriptionHosts = enabled
            },
            onBootOnStartupChanged = { enabled ->
                state.bootOnStartup = enabled
                state.settingsManager.bootOnStartup = enabled
            }
        ),
        onBack = state::navigateBackSubScreen
    )
}

private fun handleBackupImportUri(
    uri: android.net.Uri,
    coroutineScope: CoroutineScope,
    activity: Context,
    coreActive: Boolean,
    state: MainAppState,
    vm: ProxiesViewModel
) {
    coroutineScope.launch(Dispatchers.IO) {
        val head = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(32)
                val read = input.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val encrypted = head != null && BackupManager.isEncrypted(head)
        val capturedLang = state.appLanguage
        withContext(Dispatchers.Main) {
            state.backupDialogs.pendingImportUri = uri
            state.backupDialogs.password = ""
            if (encrypted) {
                state.backupDialogs.showImportPasswordDialog = true
            } else {
                importBackupAsync(
                    scope = coroutineScope,
                    context = activity,
                    request = BackupImportRequest(
                        uri = uri,
                        password = null,
                        lang = capturedLang,
                        coreActive = coreActive
                    ),
                    state = state,
                    vm = vm
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsAndGeneralDestinations(
    subScreen: SubScreen,
    env: SubScreenEnvironment,
    theme: SubScreenThemeCallbacks
) {
    val state = env.state
    val vm = env.vm
    when (subScreen) {
        SubScreen.LOGS -> LogsScreen(onBack = state::navigateBackSubScreen)
        SubScreen.RULES -> RulesScreen(
            rules = vm.parsedProfile.rules,
            totalRules = vm.parsedProfile.totalRules,
            tunnelMode = state.tunnelMode,
            onBack = state::navigateBackSubScreen
        )
        SubScreen.DNS -> DnsScreen(
            onNavigateToSettings = { state.openSubScreen(SubScreen.DNS_SETTINGS, parent = SubScreen.DNS) },
            onBack = state::navigateBackSubScreen
        )
        SubScreen.REQUESTS -> RequestLogsScreen(vpnStatus = env.vpnStatus, onBack = state::navigateBackSubScreen)
        SubScreen.APPEARANCE -> AppearanceScreen(
            themeMode = theme.currentThemeMode,
            onThemeModeChanged = theme.onThemeModeChanged,
            onBack = state::navigateBackSubScreen,
            appFont = theme.currentAppFont,
            onAppFontChanged = theme.onAppFontChanged,
            appLanguage = state.appLanguage,
            onAppLanguageChanged = { code ->
                state.appLanguage = code
                state.settingsManager.appLanguage = code
            }
        )
        SubScreen.CORE -> CoreScreen(onBack = state::navigateBackSubScreen)
        SubScreen.ABOUT -> AboutScreen(
            onExportBackup = { uri ->
                state.backupDialogs.pendingExportUri = uri
                state.backupDialogs.password = ""
                state.backupDialogs.showPasswordDialog = true
            },
            onImportBackup = { uri ->
                handleBackupImportUri(uri, env.coroutineScope, env.activity, env.coreActive, state, vm)
            },
            onBack = state::navigateBackSubScreen,
            onCoreReset = { vm.onCoreReset() }
        )
        else -> Unit
    }
}
