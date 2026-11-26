@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.InstalledApp
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.screens.ProxiesScrollState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SubScreen {
    PROFILES,
    PROFILE_OVERRIDE,
    APP_ROUTING,
    LOGS,
    SCRIPTS,
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

/** Persists the sub-screen back stack across configuration changes without throwing on unknown names. */
private val subScreenStackSaver: Saver<List<SubScreen>, String> = Saver(
    save = { stack -> stack.joinToString(",") { it.name } },
    restore = { raw ->
        if (raw.isBlank()) {
            emptyList()
        } else {
            raw.split(",").mapNotNull { name -> SubScreen.entries.firstOrNull { it.name == name } }
        }
    }
)

@Stable
internal class BackupDialogState {
    var showPasswordDialog by mutableStateOf(false)
    var showImportPasswordDialog by mutableStateOf(false)
    var showPlainConfirmDialog by mutableStateOf(false)
    var importFailures by mutableStateOf<List<String>>(emptyList())
    var password by mutableStateOf("")
    var pendingExportUri by mutableStateOf<Uri?>(null)
    var pendingImportUri by mutableStateOf<Uri?>(null)
}

@Stable
internal class MainAppState(
    val settingsManager: SettingsManager,
    initialTab: Int,
    initialSubScreenStack: List<SubScreen>,
    initialOverrideProfileId: String?,
    private val onPersistSubScreenState: (List<SubScreen>, String?) -> Unit,
    private val onPersistTab: (Int) -> Unit,
    private val onThemeModeChanged: (ThemeMode) -> Unit,
    private val onAppFontChanged: (AppFont) -> Unit
) {
    var currentTab by mutableIntStateOf(initialTab)
        private set
    val subScreenStack = mutableStateListOf<SubScreen>().apply { addAll(initialSubScreenStack) }
    val currentSubScreen: SubScreen?
        get() = subScreenStack.lastOrNull()
    val parentSubScreen: SubScreen?
        get() = if (subScreenStack.size >= 2) subScreenStack[subScreenStack.size - 2] else null
    var edgeBackBlockedByChild by mutableStateOf(false)
    var overrideTargetProfileId by mutableStateOf(initialOverrideProfileId)

    var tunnelMode by mutableStateOf(TunnelMode.fromString(settingsManager.tunnelMode))
    var appLanguage by mutableStateOf(settingsManager.appLanguage)
    var visibleShortcuts by mutableStateOf(settingsManager.visibleShortcuts)
    var shortcutOrder by mutableStateOf(settingsManager.shortcutOrder)
    var proxiesViewMode by mutableStateOf(settingsManager.proxiesViewMode)
    var showGroupIcons by mutableStateOf(settingsManager.showGroupIcons)
    var nodeColumns by mutableIntStateOf(settingsManager.nodeColumns)
    var nodeSortMode by mutableStateOf(settingsManager.nodeSortMode)
    var scriptingEnabled by mutableStateOf(settingsManager.scriptingEnabled)
    var rewriteEnabled by mutableStateOf(settingsManager.rewriteEnabled)
    var perAppMode by mutableStateOf(settingsManager.perAppProxyMode)
    var selectedPackages by mutableStateOf(settingsManager.selectedPackages)
    var installedApps by mutableStateOf(listOf<InstalledApp>())
    var isAppsLoading by mutableStateOf(false)
    var tunStack by mutableStateOf(settingsManager.tunStack)
    var bootOnStartup by mutableStateOf(settingsManager.bootOnStartup)
    var allowLan by mutableStateOf(settingsManager.allowLan)
    var ipv6Enabled by mutableStateOf(settingsManager.ipv6Enabled)
    var allowLanPassword by mutableStateOf("")
    var lanPasswordLoaded by mutableStateOf(false)
    var fakeIpEnabled by mutableStateOf(settingsManager.fakeIpEnabled)
    var dnsNameservers by mutableStateOf(settingsManager.dnsNameservers)
    var dnsFallbackServers by mutableStateOf(settingsManager.dnsFallbackServers)
    var blockThirdPartyDns by mutableStateOf(settingsManager.blockThirdPartyDns)
    var appRoutingDirty by mutableStateOf(false)
    var killSwitchEnabled by mutableStateOf(settingsManager.killSwitchEnabled)
    var allowBypassEnabled by mutableStateOf(settingsManager.allowBypassEnabled)
    var allowPrivateSubs by mutableStateOf(settingsManager.allowPrivateSubscriptionHosts)
    var mixedPort by mutableIntStateOf(settingsManager.mixedPort)
    var tunMtu by mutableIntStateOf(settingsManager.tunMtu)
    var qrPrefillUrl by mutableStateOf<String?>(null)
    val backupDialogs = BackupDialogState()

    fun selectTab(tabIndex: Int) {
        if (tabIndex != currentTab) {
            currentTab = tabIndex
            onPersistTab(tabIndex)
        }
    }

    fun openSubScreen(target: SubScreen, parent: SubScreen? = null) {
        edgeBackBlockedByChild = false
        if (parent == null) {
            subScreenStack.clear()
            subScreenStack.add(target)
        } else {
            val parentIdx = subScreenStack.lastIndexOf(parent)
            if (parentIdx >= 0) {
                while (subScreenStack.size > parentIdx + 1) {
                    subScreenStack.removeAt(subScreenStack.lastIndex)
                }
            } else {
                subScreenStack.add(parent)
            }
            if (subScreenStack.lastOrNull() != target) {
                subScreenStack.add(target)
            }
        }
        onPersistSubScreenState(subScreenStack.toList(), overrideTargetProfileId)
    }

    fun openProfileOverride(profileId: String) {
        overrideTargetProfileId = profileId
        openSubScreen(SubScreen.PROFILE_OVERRIDE, parent = SubScreen.PROFILES)
    }

    fun navigateBackSubScreen() {
        edgeBackBlockedByChild = false
        if (subScreenStack.isNotEmpty()) {
            subScreenStack.removeAt(subScreenStack.lastIndex)
        }
        onPersistSubScreenState(subScreenStack.toList(), overrideTargetProfileId)
    }

    suspend fun loadEncryptedPreferences() {
        val lanPwd = withContext(Dispatchers.IO) { settingsManager.allowLanPassword }
        if (!lanPasswordLoaded) {
            allowLanPassword = lanPwd
            lanPasswordLoaded = true
        }
    }

    fun reloadNonSensitiveSettings() {
        tunnelMode = TunnelMode.fromString(settingsManager.tunnelMode)
        appLanguage = settingsManager.appLanguage
        visibleShortcuts = settingsManager.visibleShortcuts
        shortcutOrder = settingsManager.shortcutOrder
        proxiesViewMode = settingsManager.proxiesViewMode
        showGroupIcons = settingsManager.showGroupIcons
        nodeColumns = settingsManager.nodeColumns
        nodeSortMode = settingsManager.nodeSortMode
        scriptingEnabled = settingsManager.scriptingEnabled
        rewriteEnabled = settingsManager.rewriteEnabled
        perAppMode = settingsManager.perAppProxyMode
        selectedPackages = settingsManager.selectedPackages
        tunStack = settingsManager.tunStack
        bootOnStartup = settingsManager.bootOnStartup
        allowLan = settingsManager.allowLan
        ipv6Enabled = settingsManager.ipv6Enabled
        fakeIpEnabled = settingsManager.fakeIpEnabled
        dnsNameservers = settingsManager.dnsNameservers
        dnsFallbackServers = settingsManager.dnsFallbackServers
        blockThirdPartyDns = settingsManager.blockThirdPartyDns
        killSwitchEnabled = settingsManager.killSwitchEnabled
        allowBypassEnabled = settingsManager.allowBypassEnabled
        allowPrivateSubs = settingsManager.allowPrivateSubscriptionHosts
        mixedPort = settingsManager.mixedPort
        tunMtu = settingsManager.tunMtu
        onThemeModeChanged(settingsManager.themeMode)
        onAppFontChanged(settingsManager.appFont)
    }
}

@Composable
internal fun rememberMainAppState(
    settingsManager: SettingsManager,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onAppFontChanged: (AppFont) -> Unit
): MainAppState {
    var savedTab by rememberSaveable { mutableIntStateOf(0) }
    var savedStack by rememberSaveable(stateSaver = subScreenStackSaver) {
        mutableStateOf<List<SubScreen>>(emptyList())
    }
    var savedOverrideId by rememberSaveable { mutableStateOf<String?>(null) }

    return remember(settingsManager) {
        MainAppState(
            settingsManager = settingsManager,
            initialTab = savedTab,
            initialSubScreenStack = savedStack,
            initialOverrideProfileId = savedOverrideId,
            onPersistSubScreenState = { stack, overrideId ->
                savedStack = stack
                savedOverrideId = overrideId
            },
            onPersistTab = { tab -> savedTab = tab },
            onThemeModeChanged = onThemeModeChanged,
            onAppFontChanged = onAppFontChanged
        )
    }
}

internal class MainScrollStates(
    val settingsScrollState: ScrollState,
    val dashboardScrollState: ScrollState,
    val proxiesScrollState: ProxiesScrollState
)

@Composable
internal fun rememberMainScrollStates(): MainScrollStates {
    val settingsScroll = rememberScrollState()
    val dashboardScroll = rememberScrollState()
    val tabsListState = rememberLazyListState()
    val listModeState = rememberLazyListState()
    val groupRowState = rememberLazyListState()
    val expandedGroups = rememberSaveable(
        saver = Saver<SnapshotStateMap<String, Boolean>, HashMap<String, Boolean>>(
            save = { map -> HashMap(map) },
            restore = { saved ->
                mutableStateMapOf<String, Boolean>().apply { putAll(saved) }
            }
        )
    ) { mutableStateMapOf<String, Boolean>() }
    val perGroupTabStates = remember { mutableStateMapOf<String, LazyListState>() }

    return remember {
        MainScrollStates(
            settingsScrollState = settingsScroll,
            dashboardScrollState = dashboardScroll,
            proxiesScrollState = ProxiesScrollState(
                tabsListState = tabsListState,
                listModeState = listModeState,
                groupRowState = groupRowState,
                expandedGroups = expandedGroups,
                perGroupTabStates = perGroupTabStates
            )
        )
    }
}

internal fun CoroutineScope.reloadCoreWithRollback(
    context: Context,
    appLanguage: String,
    reloadAction: suspend () -> Boolean = { ClashCore.reloadActiveConfig(context) },
    onRollback: () -> Unit,
    onSuccess: () -> Unit = {}
) {
    launch(Dispatchers.IO) {
        val ok = try {
            reloadAction()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) {
            onSuccess()
        } else {
            withContext(Dispatchers.Main) {
                onRollback()
                CupertinoToast.show(
                    AppStrings.get("core_reload_failed", appLanguage),
                    isDestructive = true
                )
            }
        }
    }
}
