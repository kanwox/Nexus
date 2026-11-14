@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.kr328.clash.core.model.Proxy
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.SpeedTestState
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.AppRoutingIllustration
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.LatencySlotState
import com.github.mihomo.android.ui.components.statusFadeTransform
import com.github.mihomo.android.ui.components.CupertinoButton
import com.github.mihomo.android.ui.components.CupertinoButtonStyle
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoSingleSelectOption
import com.github.mihomo.android.ui.components.CupertinoSingleSelectSheet
import com.github.mihomo.android.ui.components.CupertinoSwitch
import com.github.mihomo.android.ui.components.DnsIllustration
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.LoonShortcutCard
import com.github.mihomo.android.ui.components.LoonTrafficCard
import com.github.mihomo.android.ui.components.NetworkShareIllustration
import com.github.mihomo.android.ui.components.NodesIllustration
import com.github.mihomo.android.ui.components.PluginsIllustration
import com.github.mihomo.android.ui.components.ProfilesIllustration
import com.github.mihomo.android.ui.components.RequestLogsIllustration
import com.github.mihomo.android.ui.components.RulesIllustration
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.LocalLoonColors
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonExtraColors
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonOrange
import com.github.mihomo.android.ui.theme.LoonOrangeLight
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.MihomoTheme
import com.github.mihomo.android.ui.theme.RedDanger
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class ShortcutDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

@Immutable
data class DashboardStatusState(
    val vpnStatus: VpnState.Status,
    val vpnError: String? = null,
    val coreVersion: String = "",
    val vpnTraffic: State<VpnState>,
    val currentMode: TunnelMode,
    val activeProfileName: String,
    val mixedPort: Int = 7890
)

@Immutable
data class DashboardProxySummary(
    val mainGroupName: String,
    val mainGroupProxies: List<Proxy>,
    val delayCache: Map<String, SpeedTestState> = emptyMap(),
    val activeNodeName: String,
    val totalNodes: Int,
    val totalRules: Int
)

class DashboardShortcutsState(
    val visibleShortcuts: Set<String>,
    val shortcutOrder: List<String>,
    val onUpdateVisibleShortcuts: (Set<String>) -> Unit,
    val onUpdateShortcutOrder: (List<String>) -> Unit
)

class DashboardActions(
    val onToggleVpn: () -> Unit,
    val onModeSelected: (TunnelMode) -> Unit,
    val onSelectProxy: (group: String, proxy: Proxy) -> Unit,
    val onHealthCheck: (group: String) -> Job?
)

class DashboardNavigation(
    val onNavigateToProfiles: () -> Unit,
    val onNavigateToAppRouting: () -> Unit,
    val onNavigateToRules: () -> Unit = {},
    val onNavigateToDns: () -> Unit = {},
    val onNavigateToRequests: () -> Unit = {},
    val onNavigateToNetworkShare: () -> Unit = {},
    val onNavigateToCore: () -> Unit = {}
)

@Composable
private fun rememberTopBgBrush(): Brush {
    val isDarkTheme = LocalLoonColors.current.isDark
    val bgColor = LoonBg
    return remember(isDarkTheme, bgColor) {
        if (isDarkTheme) {
            Brush.verticalGradient(colors = listOf(bgColor, bgColor))
        } else {
            Brush.verticalGradient(
                colors = listOf(LoonExtraColors.orangeTint, LoonExtraColors.lavender, bgColor),
                startY = 0f,
                endY = 550f
            )
        }
    }
}

private data class DashboardColumnState(
    val displayedShortcuts: List<ShortcutDef>,
    val hiddenShortcuts: List<ShortcutDef>,
    val localOrder: List<String>,
    val isEditingShortcuts: Boolean,
    val draggedId: String?,
    val isRunning: Boolean,
    val isStarting: Boolean
)

private data class DashboardColumnCallbacks(
    val onToggleVpn: () -> Unit,
    val onOpenModePicker: () -> Unit,
    val onToggleEdit: () -> Unit,
    val onDraggedIdChanged: (String?) -> Unit,
    val onOrderChanged: (List<String>) -> Unit,
    val onDeleteShortcut: (String) -> Unit,
    val onOpenAddShortcut: () -> Unit
)

@Composable
private fun DashboardMainColumn(
    status: DashboardStatusState,
    columnState: DashboardColumnState,
    lang: String,
    haptic: IosHapticHelper,
    scrollState: ScrollState,
    callbacks: DashboardColumnCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll()
            .verticalScroll(state = scrollState, enabled = columnState.draggedId == null)
            .padding(horizontal = 20.dp)
            .padding(bottom = 110.dp)
    ) {
        DashboardHeader(
            lang, status.currentMode, columnState.isRunning || columnState.isStarting,
            haptic, callbacks.onToggleVpn, callbacks.onOpenModePicker
        )
        DashboardTrafficRow(isRunning = columnState.isRunning, traffic = status.vpnTraffic, lang = lang)
        Spacer(modifier = Modifier.height(26.dp))
        DashboardShortcutsHeader(lang = lang, isEditing = columnState.isEditingShortcuts, onToggleEdit = callbacks.onToggleEdit)
        DashboardShortcutsGrid(
            columnState.displayedShortcuts, columnState.localOrder, columnState.isEditingShortcuts,
            columnState.draggedId, callbacks.onDraggedIdChanged, callbacks.onOrderChanged, callbacks.onDeleteShortcut
        )
        if (columnState.isEditingShortcuts) {
            AddShortcutFooterButton(lang, columnState.hiddenShortcuts.size, callbacks.onOpenAddShortcut)
        }
        status.vpnError?.let { errorText ->
            Spacer(modifier = Modifier.height(16.dp))
            DashboardErrorBanner(errorText)
        }
    }
}

@Composable
fun DashboardScreen(
    status: DashboardStatusState,
    proxySummary: DashboardProxySummary,
    shortcuts: DashboardShortcutsState,
    actions: DashboardActions,
    navigation: DashboardNavigation,
    scrollState: ScrollState = rememberScrollState()
) {
    val isRunning = status.vpnStatus == VpnState.Status.RUNNING
    val isStarting = status.vpnStatus == VpnState.Status.STARTING
    var showModeBottomSheet by remember { mutableStateOf(false) }
    var showQuickNodeSelector by remember { mutableStateOf(false) }
    var isEditingShortcuts by remember { mutableStateOf(false) }
    var showAddShortcutSheet by remember { mutableStateOf(false) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var localOrder by remember(shortcuts.shortcutOrder) { mutableStateOf(shortcuts.shortcutOrder) }

    val haptic = rememberIosHaptic()
    val lang = LocalAppLanguage.current
    val allShortcutMap = rememberDashboardShortcuts(
        lang, status, proxySummary, isRunning, navigation,
        onOpenQuickNodes = { showQuickNodeSelector = true }
    )
    val displayedShortcuts = remember(localOrder, shortcuts.visibleShortcuts, allShortcutMap) {
        localOrder.mapNotNull { id -> allShortcutMap[id] }.filter { def -> shortcuts.visibleShortcuts.contains(def.id) }
    }
    val hiddenShortcuts = remember(shortcuts.shortcutOrder, shortcuts.visibleShortcuts, allShortcutMap) {
        (shortcuts.shortcutOrder + allShortcutMap.keys).distinct()
            .filter { id -> !shortcuts.visibleShortcuts.contains(id) }
            .mapNotNull { id -> allShortcutMap[id] }
    }

    Box(modifier = Modifier.fillMaxSize().background(rememberTopBgBrush()).statusBarsPadding()) {
        LoonResponsiveContent(modifier = Modifier.fillMaxSize()) {
            DashboardMainColumn(
                status = status,
                columnState = DashboardColumnState(
                    displayedShortcuts = displayedShortcuts,
                    hiddenShortcuts = hiddenShortcuts,
                    localOrder = localOrder,
                    isEditingShortcuts = isEditingShortcuts,
                    draggedId = draggedId,
                    isRunning = isRunning,
                    isStarting = isStarting
                ),
                lang = lang,
                haptic = haptic,
                scrollState = scrollState,
                callbacks = DashboardColumnCallbacks(
                    onToggleVpn = actions.onToggleVpn,
                    onOpenModePicker = { showModeBottomSheet = true },
                    onToggleEdit = { haptic.lightTick(); isEditingShortcuts = !isEditingShortcuts },
                    onDraggedIdChanged = { next -> draggedId = next },
                    onOrderChanged = { updated -> localOrder = updated; shortcuts.onUpdateShortcutOrder(updated) },
                    onDeleteShortcut = { id ->
                        if (draggedId == null) shortcuts.onUpdateVisibleShortcuts(shortcuts.visibleShortcuts - id)
                    },
                    onOpenAddShortcut = { showAddShortcutSheet = true }
                )
            )
        }
    }

    DashboardOverlays(
        lang, status.currentMode, proxySummary, haptic, showQuickNodeSelector, showAddShortcutSheet,
        showModeBottomSheet, hiddenShortcuts,
        onDismissQuickNode = { showQuickNodeSelector = false },
        onDismissAddShortcut = { showAddShortcutSheet = false },
        onDismissMode = { showModeBottomSheet = false },
        onModeSelected = actions.onModeSelected,
        onSelectProxy = actions.onSelectProxy,
        onHealthCheck = actions.onHealthCheck,
        onRestoreShortcut = { id -> shortcuts.onUpdateVisibleShortcuts(shortcuts.visibleShortcuts + id) }
    )
}

@Composable
private fun rememberDashboardShortcuts(
    lang: String,
    status: DashboardStatusState,
    proxySummary: DashboardProxySummary,
    isRunning: Boolean,
    navigation: DashboardNavigation,
    onOpenQuickNodes: () -> Unit
): Map<String, ShortcutDef> {
    val icons = rememberDashboardShortcutIcons()
    return remember(
        lang, status.activeProfileName, status.coreVersion, status.mixedPort,
        proxySummary.activeNodeName, proxySummary.totalNodes, proxySummary.totalRules, isRunning
    ) {
        buildDashboardShortcutsMap(lang, status, proxySummary, isRunning, navigation, onOpenQuickNodes, icons)
    }
}

private data class DashboardShortcutIcons(
    val nodes: @Composable () -> Unit,
    val profiles: @Composable () -> Unit,
    val requestLogs: @Composable () -> Unit,
    val dns: @Composable () -> Unit,
    val rules: @Composable () -> Unit,
    val routing: @Composable () -> Unit,
    val plugins: @Composable () -> Unit,
    val networkShare: @Composable () -> Unit
)

@Composable
private fun rememberDashboardShortcutIcons(): DashboardShortcutIcons = remember {
    DashboardShortcutIcons(
        nodes = { NodesIllustration() },
        profiles = { ProfilesIllustration() },
        requestLogs = { RequestLogsIllustration() },
        dns = { DnsIllustration() },
        rules = { RulesIllustration() },
        routing = { AppRoutingIllustration() },
        plugins = { PluginsIllustration() },
        networkShare = { NetworkShareIllustration() }
    )
}

private fun buildDashboardShortcutsMap(
    lang: String,
    status: DashboardStatusState,
    proxySummary: DashboardProxySummary,
    isRunning: Boolean,
    navigation: DashboardNavigation,
    onOpenQuickNodes: () -> Unit,
    icons: DashboardShortcutIcons
): Map<String, ShortcutDef> = mapOf(
    "nodes" to ShortcutDef(
        "nodes", AppStrings.get("shortcut_nodes", lang),
        proxySummary.activeNodeName.ifBlank { "${proxySummary.totalNodes}" }, icons.nodes, onOpenQuickNodes
    ),
    "profiles" to ShortcutDef(
        "profiles", AppStrings.get("shortcut_profiles", lang),
        status.activeProfileName, icons.profiles, navigation.onNavigateToProfiles
    ),
    "request_logs" to ShortcutDef(
        "request_logs", AppStrings.get("shortcut_requests", lang),
        AppStrings.get(if (isRunning) "subtitle_live_monitoring" else "subtitle_disabled", lang),
        icons.requestLogs, navigation.onNavigateToRequests
    ),
    "dns" to ShortcutDef(
        "dns", AppStrings.get("shortcut_dns", lang),
        AppStrings.get(if (isRunning) "subtitle_live_resolving" else "subtitle_tap_to_view", lang),
        icons.dns, navigation.onNavigateToDns
    ),
    "rules" to ShortcutDef(
        "rules", AppStrings.get("shortcut_rules", lang),
        if (proxySummary.totalRules > 0) "${proxySummary.totalRules}" else "0",
        icons.rules, navigation.onNavigateToRules
    ),
    "routing" to ShortcutDef(
        "routing", AppStrings.get("shortcut_routing", lang),
        AppStrings.get("subtitle_rules_blacklist", lang), icons.routing, navigation.onNavigateToAppRouting
    ),
    "plugins" to ShortcutDef(
        "plugins", AppStrings.get("shortcut_plugins", lang),
        if (status.coreVersion.isNotBlank()) "v${status.coreVersion.take(8)}" else "Nexus Core",
        icons.plugins, navigation.onNavigateToCore
    ),
    "network_share" to ShortcutDef(
        "network_share", AppStrings.get("shortcut_network_share", lang),
        "127.0.0.1:${status.mixedPort}", icons.networkShare, navigation.onNavigateToNetworkShare
    )
)

@Composable
private fun DashboardHeader(
    lang: String,
    currentMode: TunnelMode,
    isVpnActive: Boolean,
    haptic: IosHapticHelper,
    onToggleVpn: () -> Unit,
    onOpenModePicker: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "NEXUS", color = LoonOrange, style = LoonTextStyles.largeTitle())
            CupertinoSwitch(
                checked = isVpnActive,
                onCheckedChange = {
                    haptic.vpnToggleImpact()
                    onToggleVpn()
                }
            )
        }
        val modeShape = RoundedCornerShape(LoonRadii.badge)
        Row(
            modifier = Modifier
                .defaultMinSize(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(modeShape)
                .clickable(role = Role.Button, onClick = onOpenModePicker)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (currentMode) {
                    TunnelMode.RULE -> AppStrings.get("mode_rule", lang)
                    TunnelMode.GLOBAL -> AppStrings.get("mode_global", lang)
                    TunnelMode.DIRECT -> AppStrings.get("mode_direct", lang)
                },
                color = LoonOrangeLight,
                style = LoonTextStyles.value().copy(fontWeight = FontWeight.SemiBold)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = AppStrings.get("select_mode", lang),
                tint = LoonOrangeLight,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DashboardShortcutsHeader(
    lang: String,
    isEditing: Boolean,
    onToggleEdit: () -> Unit
) {
    val editShape = RoundedCornerShape(LoonRadii.badge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = AppStrings.get("shortcuts", lang),
            color = LoonTextPrimary,
            style = LoonTextStyles.sectionTitle()
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(editShape)
                .clickable(role = Role.Button, onClick = onToggleEdit)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isEditing) {
                    AppStrings.get("shortcuts_done", lang)
                } else {
                    AppStrings.get("shortcuts_edit", lang)
                },
                color = LoonInfoText,
                style = LoonTextStyles.actionLabelStrong()
            )
        }
    }
}

private data class ShortcutDragGridItemData(
    val origSlot: Int,
    val itemWidthDp: androidx.compose.ui.unit.Dp,
    val itemHeightDp: androidx.compose.ui.unit.Dp,
    val metrics: ShortcutGridMetrics,
    val isEditing: Boolean,
    val draggedId: String?,
    val dragState: ShortcutGridDragState,
    val displayedShortcuts: List<ShortcutDef>,
    val localOrder: List<String>
)

private data class ShortcutDragGridCallbacks(
    val onDraggedIdChanged: (String?) -> Unit,
    val onOrderChanged: (List<String>) -> Unit,
    val onDeleteShortcut: (String) -> Unit
)

private data class ShortcutDragTargetContext(
    val itemId: String,
    val metrics: ShortcutGridMetrics,
    val draggedId: String?,
    val dragState: ShortcutGridDragState
)

@Composable
private fun ShortcutDragGridItem(
    item: ShortcutDef,
    data: ShortcutDragGridItemData,
    callbacks: ShortcutDragGridCallbacks
) {
    val reduceMotion = LocalReduceMotion.current
    val isBeingDragged = data.draggedId == item.id
    val visualSlot = computeVisualSlot(
        data.origSlot, isBeingDragged, data.draggedId != null,
        data.dragState.initialSlotIndex, data.dragState.hoverSlotIndex
    )
    val targetX = (visualSlot % data.metrics.columns) * (data.metrics.itemWidthPx + data.metrics.spacingPx)
    val targetY = (visualSlot / data.metrics.columns) * (data.metrics.itemHeightPx + data.metrics.spacingPx)
    val posSpec = if (reduceMotion) snap<Float>() else tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
    val animX by animateFloatAsState(targetX, posSpec, label = "animX_${item.id}")
    val animY by animateFloatAsState(targetY, posSpec, label = "animY_${item.id}")
    val posX = if (isBeingDragged) data.dragState.startSlotX + data.dragState.dragOffset.x else animX
    val posY = if (isBeingDragged) data.dragState.startSlotY + data.dragState.dragOffset.y else animY
    val currentDisplayedRef = rememberUpdatedState(data.displayedShortcuts)
    val currentLocalOrderRef = rememberUpdatedState(data.localOrder)

    val baseMod = Modifier
        .size(data.itemWidthDp, data.itemHeightDp)
        .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
        .zIndex(if (isBeingDragged) 100f else 1f)
        .graphicsLayer {
            val scale = if (isBeingDragged) 1.06f else 1f
            scaleX = scale; scaleY = scale
            shadowElevation = if (isBeingDragged) 24f else 0f
        }
    val finalMod = if (data.isEditing) {
        baseMod.shortcutDragModifier(
            dragContext = ShortcutDragTargetContext(item.id, data.metrics, data.draggedId, data.dragState),
            displayedRef = currentDisplayedRef,
            localOrderRef = currentLocalOrderRef,
            onDraggedIdChanged = callbacks.onDraggedIdChanged,
            onOrderChanged = callbacks.onOrderChanged
        )
    } else {
        baseMod
    }
    LoonShortcutCard(
        modifier = finalMod, title = item.title, subtitle = item.subtitle,
        icon = item.icon, isEditing = data.isEditing,
        onDelete = { callbacks.onDeleteShortcut(item.id) }, onClick = item.onClick
    )
}

private class ShortcutGridDragState {
    var dragOffset by mutableStateOf(Offset.Zero)
    var initialSlotIndex by mutableIntStateOf(-1)
    var hoverSlotIndex by mutableIntStateOf(-1)
    var startSlotX by mutableFloatStateOf(0f)
    var startSlotY by mutableFloatStateOf(0f)

    fun reset() {
        dragOffset = Offset.Zero
        initialSlotIndex = -1
        hoverSlotIndex = -1
    }
}

private fun Modifier.shortcutDragModifier(
    dragContext: ShortcutDragTargetContext,
    displayedRef: State<List<ShortcutDef>>,
    localOrderRef: State<List<String>>,
    onDraggedIdChanged: (String?) -> Unit,
    onOrderChanged: (List<String>) -> Unit
): Modifier = pointerInput(dragContext.itemId, dragContext.metrics) {
    val itemId = dragContext.itemId
    val metrics = dragContext.metrics
    val dragState = dragContext.dragState
    detectDragGesturesAfterLongPress(
        onDragStart = {
            val curIdx = displayedRef.value.indexOfFirst { entry -> entry.id == itemId }
            onDraggedIdChanged(itemId)
            dragState.initialSlotIndex = curIdx
            dragState.hoverSlotIndex = curIdx
            val c = if (curIdx != -1) curIdx % metrics.columns else 0
            val r = if (curIdx != -1) curIdx / metrics.columns else 0
            dragState.startSlotX = c * (metrics.itemWidthPx + metrics.spacingPx)
            dragState.startSlotY = r * (metrics.itemHeightPx + metrics.spacingPx)
            dragState.dragOffset = Offset.Zero
        },
        onDragEnd = {
            commitShortcutReorder(
                itemId, displayedRef.value, localOrderRef.value,
                dragState.initialSlotIndex, dragState.hoverSlotIndex, onOrderChanged
            )
            onDraggedIdChanged(null)
            dragState.reset()
        },
        onDragCancel = { onDraggedIdChanged(null); dragState.reset() },
        onDrag = { change, dragAmount ->
            change.consume()
            if (dragContext.draggedId != itemId) return@detectDragGesturesAfterLongPress
            val nextOffset = dragState.dragOffset + dragAmount
            dragState.dragOffset = nextOffset
            val targetIdx = resolveHoverSlot(
                dragState.startSlotX + nextOffset.x, dragState.startSlotY + nextOffset.y,
                displayedRef.value.size, metrics
            )
            if (targetIdx != dragState.hoverSlotIndex) dragState.hoverSlotIndex = targetIdx
        }
    )
}

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
private fun DashboardShortcutsGrid(
    displayedShortcuts: List<ShortcutDef>,
    localOrder: List<String>,
    isEditing: Boolean,
    draggedId: String?,
    onDraggedIdChanged: (String?) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
    onDeleteShortcut: (String) -> Unit
) {
    val dragState = remember { ShortcutGridDragState() }
    val shortcutIndexById = remember(displayedShortcuts) {
        displayedShortcuts.mapIndexed { index, item -> item.id to index }.toMap()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacingDp = 12.dp
        val columns = if (maxWidth >= 600.dp) 4 else 2
        val itemWidthDp = (maxWidth - spacingDp * (columns - 1)) / columns
        val density = LocalDensity.current
        // Scale shortcut height gently with system fontScale so multi-line titles never clip at 2.0x.
        val scaledMultiplier = 1f + (density.fontScale.coerceAtLeast(1f) - 1f) * 0.65f
        val itemHeightDp = 118.dp * scaledMultiplier
        val metrics = ShortcutGridMetrics(
            columns = columns,
            itemWidthPx = with(density) { itemWidthDp.toPx() },
            itemHeightPx = with(density) { itemHeightDp.toPx() },
            spacingPx = with(density) { spacingDp.toPx() }
        )
        val rowCount = (displayedShortcuts.size + columns - 1) / columns
        val rawGridHeight = if (rowCount > 0) (itemHeightDp * rowCount) + (spacingDp * (rowCount - 1)) else 0.dp
        val gridHeight = if (rawGridHeight == 0.dp && isEditing) 10.dp else rawGridHeight

        val callbacks = ShortcutDragGridCallbacks(
            onDraggedIdChanged = onDraggedIdChanged,
            onOrderChanged = onOrderChanged,
            onDeleteShortcut = onDeleteShortcut
        )
        Box(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
            displayedShortcuts.forEach { item ->
                key(item.id) {
                    ShortcutDragGridItem(
                        item = item,
                        data = ShortcutDragGridItemData(
                            origSlot = shortcutIndexById[item.id] ?: 0,
                            itemWidthDp = itemWidthDp,
                            itemHeightDp = itemHeightDp,
                            metrics = metrics,
                            isEditing = isEditing,
                            draggedId = draggedId,
                            dragState = dragState,
                            displayedShortcuts = displayedShortcuts,
                            localOrder = localOrder
                        ),
                        callbacks = callbacks
                    )
                }
            }
        }
    }
}

@Immutable
private data class ShortcutGridMetrics(
    val columns: Int,
    val itemWidthPx: Float,
    val itemHeightPx: Float,
    val spacingPx: Float
)

private fun computeVisualSlot(
    origSlotIndex: Int,
    isBeingDragged: Boolean,
    hasActiveDrag: Boolean,
    initialSlotIndex: Int,
    hoverSlotIndex: Int
): Int = when {
    !hasActiveDrag || initialSlotIndex == -1 || hoverSlotIndex == -1 -> origSlotIndex
    isBeingDragged -> hoverSlotIndex
    hoverSlotIndex > initialSlotIndex && origSlotIndex in (initialSlotIndex + 1)..hoverSlotIndex -> origSlotIndex - 1
    hoverSlotIndex < initialSlotIndex && origSlotIndex in hoverSlotIndex until initialSlotIndex -> origSlotIndex + 1
    else -> origSlotIndex
}

private fun resolveHoverSlot(
    startX: Float,
    startY: Float,
    itemCount: Int,
    metrics: ShortcutGridMetrics
): Int {
    val curCenterX = startX + metrics.itemWidthPx / 2f
    val curCenterY = startY + metrics.itemHeightPx / 2f
    val maxRow = ((itemCount - 1) / metrics.columns).coerceAtLeast(0)
    val targetCol = (curCenterX / (metrics.itemWidthPx + metrics.spacingPx)).toInt().coerceIn(0, metrics.columns - 1)
    val targetRow = (curCenterY / (metrics.itemHeightPx + metrics.spacingPx)).toInt().coerceIn(0, maxRow)
    return (targetRow * metrics.columns + targetCol).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
}

private fun commitShortcutReorder(
    itemId: String,
    displayed: List<ShortcutDef>,
    localOrder: List<String>,
    fromIdx: Int,
    toIdx: Int,
    onOrderChanged: (List<String>) -> Unit
) {
    if (fromIdx == toIdx || fromIdx !in displayed.indices || toIdx !in displayed.indices) return
    val mutable = localOrder.toMutableList()
    val fromPos = mutable.indexOf(itemId)
    val toPos = mutable.indexOf(displayed[toIdx].id)
    if (fromPos != -1 && toPos != -1) {
        mutable.removeAt(fromPos)
        mutable.add(toPos, itemId)
        onOrderChanged(mutable)
    }
}

@Composable
private fun AddShortcutFooterButton(
    lang: String,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(14.dp))
    val shape = RoundedCornerShape(LoonRadii.sheet)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(1.dp, LoonCardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = LoonCard
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AddCircle,
                contentDescription = null,
                tint = LoonTextPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (hiddenCount == 0) {
                    AppStrings.get("shortcuts_all_shown", lang)
                } else {
                    "${AppStrings.get("shortcuts_add", lang)} ($hiddenCount)"
                },
                color = LoonTextPrimary,
                style = LoonTextStyles.value().copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun DashboardErrorBanner(errorText: String) {
    Surface(
        color = RedDanger.copy(alpha = 0.12f),
        shape = RoundedCornerShape(LoonRadii.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = RedDanger, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = errorText, color = LoonDangerText, style = LoonTextStyles.callout())
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DashboardOverlays(
    lang: String,
    currentMode: TunnelMode,
    proxySummary: DashboardProxySummary,
    haptic: IosHapticHelper,
    showQuickNodeSelector: Boolean,
    showAddShortcutSheet: Boolean,
    showModeBottomSheet: Boolean,
    hiddenShortcuts: List<ShortcutDef>,
    onDismissQuickNode: () -> Unit,
    onDismissAddShortcut: () -> Unit,
    onDismissMode: () -> Unit,
    onModeSelected: (TunnelMode) -> Unit,
    onSelectProxy: (String, Proxy) -> Unit,
    onHealthCheck: (String) -> Job?,
    onRestoreShortcut: (String) -> Unit
) {
    if (showQuickNodeSelector) {
        QuickNodeSelectorSheet(
            lang = lang,
            proxySummary = proxySummary,
            haptic = haptic,
            onDismiss = onDismissQuickNode,
            onSelectProxy = onSelectProxy,
            onHealthCheck = onHealthCheck
        )
    }

    if (showAddShortcutSheet) {
        AddHiddenShortcutSheet(
            lang = lang,
            hiddenShortcuts = hiddenShortcuts,
            haptic = haptic,
            onDismiss = onDismissAddShortcut,
            onRestoreShortcut = onRestoreShortcut
        )
    }

    if (showModeBottomSheet) {
        val modes = listOf(
            CupertinoSingleSelectOption(TunnelMode.RULE, AppStrings.get("mode_rule", lang), AppStrings.get("mode_rule_desc", lang)),
            CupertinoSingleSelectOption(TunnelMode.GLOBAL, AppStrings.get("mode_global", lang), AppStrings.get("mode_global_desc", lang)),
            CupertinoSingleSelectOption(TunnelMode.DIRECT, AppStrings.get("mode_direct", lang), AppStrings.get("mode_direct_desc", lang))
        )
        CupertinoSingleSelectSheet(
            title = AppStrings.get("select_mode", lang),
            options = modes,
            selectedValue = currentMode,
            onSelect = { mode ->
                haptic.selection()
                onModeSelected(mode)
                onDismissMode()
            },
            onDismissRequest = onDismissMode
        )
    }
}

@Composable
private fun QuickNodeSpeedTestAction(
    isTesting: Boolean,
    lang: String,
    onTriggerSpeedTest: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(LoonSizes.touchTargetMin)
            .clip(CircleShape)
            .iosPressable(onClick = onTriggerSpeedTest)
            .semantics {
                contentDescription = AppStrings.get("proxies_test", lang)
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedSpeedTestIcon(isTesting = isTesting, tint = IosBlue, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QuickNodeListContent(
    lang: String,
    proxySummary: DashboardProxySummary,
    filteredNodes: List<Proxy>,
    isTesting: Boolean,
    haptic: IosHapticHelper,
    onSelect: (Proxy) -> Unit
) {
    if (filteredNodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = AppStrings.get(
                    if (proxySummary.mainGroupProxies.isEmpty()) "quick_node_empty" else "quick_node_no_match",
                    lang
                ),
                color = LoonTextSecondary,
                style = LoonTextStyles.value()
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).bounceOverscroll(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            cupertinoGroupedItems(
                items = filteredNodes,
                key = { _, proxy -> proxy.name },
                dividerStartIndent = 16.dp
            ) { _, proxy ->
                QuickNodeSheetRow(
                    proxy = proxy,
                    mainGroupName = proxySummary.mainGroupName,
                    isSelected = proxy.name == proxySummary.activeNodeName,
                    isTesting = isTesting,
                    delayCache = proxySummary.delayCache,
                    onSelect = { haptic.selection(); onSelect(proxy) }
                )
            }
        }
    }
}

@Composable
private fun QuickNodeSelectorSheet(
    lang: String,
    proxySummary: DashboardProxySummary,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit,
    onSelectProxy: (String, Proxy) -> Unit,
    onHealthCheck: (String) -> Job?
) {
    var quickNodeSearchQuery by remember { mutableStateOf("") }
    var isTestingQuickNodes by remember { mutableStateOf(false) }
    val quickNodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val filteredNodes = remember(proxySummary.mainGroupProxies, quickNodeSearchQuery) {
        val q = quickNodeSearchQuery.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) proxySummary.mainGroupProxies
        else proxySummary.mainGroupProxies.filter { proxy -> proxy.name.lowercase(Locale.ROOT).contains(q) }
    }

    CupertinoModalBottomSheet(
        onDismissRequest = { quickNodeSearchQuery = ""; onDismiss() },
        sheetState = quickNodeSheetState,
        modifier = Modifier.fillMaxHeight(0.60f),
        title = AppStrings.get("select_node_title", lang),
        leadingAction = {
            QuickNodeSpeedTestAction(isTesting = isTestingQuickNodes, lang = lang) {
                if (proxySummary.mainGroupName.isNotBlank() && !isTestingQuickNodes) {
                    isTestingQuickNodes = true
                    coroutineScope.launch {
                        try { onHealthCheck(proxySummary.mainGroupName)?.join() } finally { isTestingQuickNodes = false }
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            CupertinoSearchBar(
                value = quickNodeSearchQuery,
                onValueChange = { query -> quickNodeSearchQuery = query },
                placeholder = AppStrings.get("quick_node_search_hint", lang)
            )
        }
        QuickNodeListContent(lang, proxySummary, filteredNodes, isTestingQuickNodes, haptic) { proxy ->
            onSelectProxy(proxySummary.mainGroupName, proxy)
            quickNodeSearchQuery = ""
            onDismiss()
        }
    }
}

@Composable
private fun QuickNodeSheetRow(
    proxy: Proxy,
    mainGroupName: String,
    isSelected: Boolean,
    isTesting: Boolean,
    delayCache: Map<String, SpeedTestState>,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = proxy.name,
                color = if (isSelected) LoonInfoText else LoonTextPrimary,
                style = LoonTextStyles.body().copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = proxy.type.uppercase(Locale.ROOT),
                color = LoonTextMuted,
                style = LoonTextStyles.subtitle()
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val reduceMotion = LocalReduceMotion.current
            val nodeDelay = (delayCache["$mainGroupName:${proxy.name}"] ?: delayCache[proxy.name])
                ?.toLegacyDelay() ?: proxy.delay
            val statusSlot = when {
                isTesting -> LatencySlotState.Testing
                nodeDelay in 1..65534 -> LatencySlotState.Measured(nodeDelay)
                else -> LatencySlotState.Idle
            }
            AnimatedContent(
                targetState = statusSlot,
                contentAlignment = Alignment.CenterEnd,
                transitionSpec = { statusFadeTransform(reduceMotion = reduceMotion) },
                label = "quick_node_status"
            ) { slot ->
                QuickNodeStatusContent(slot = slot)
            }
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = IosBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun HiddenShortcutCard(
    item: ShortcutDef,
    lang: String,
    haptic: IosHapticHelper,
    onRestore: () -> Unit
) {
    val cardShape = RoundedCornerShape(LoonRadii.card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.5.dp, LoonCardBorder, cardShape)
            .iosPressable {
                haptic.lightTick()
                onRestore()
            },
        shape = cardShape,
        color = LoonBg
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) { item.icon() }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = item.title,
                        style = LoonTextStyles.value().copy(fontWeight = FontWeight.SemiBold),
                        color = LoonTextPrimary
                    )
                    Text(text = item.subtitle, style = LoonTextStyles.subtitle(), color = LoonTextSecondary)
                }
            }
            CupertinoButton(
                text = AppStrings.get("shortcuts_add", lang),
                onClick = onRestore,
                style = CupertinoButtonStyle.Tinted,
                icon = Icons.Default.Add
            )
        }
    }
}

@Composable
private fun QuickNodeStatusContent(slot: LatencySlotState) {
    when (slot) {
        LatencySlotState.Testing -> CupertinoActivityIndicator(
            modifier = Modifier.size(14.dp),
            color = IosBlue
        )
        is LatencySlotState.Measured -> Text(
            text = "${slot.delayMs} ms",
            color = when (slot.delayMs) {
                in 1..250 -> LoonSuccessText
                in 251..600 -> LoonWarningText
                else -> LoonDangerText
            },
            style = LoonTextStyles.subtitleStrong(),
            maxLines = 1
        )
        LatencySlotState.Timeout,
        LatencySlotState.Failed,
        LatencySlotState.Idle -> Unit
    }
}

@Composable
private fun AddHiddenShortcutSheet(
    lang: String,
    hiddenShortcuts: List<ShortcutDef>,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit,
    onRestoreShortcut: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = AppStrings.get("shortcuts_add", lang)
    ) {
        if (hiddenShortcuts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = AppStrings.get("shortcuts_all_added", lang),
                    color = LoonTextSecondary,
                    style = LoonTextStyles.value()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().bounceOverscroll(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items = hiddenShortcuts, key = { _, item -> item.id }) { _, item ->
                    HiddenShortcutCard(item, lang, haptic) { onRestoreShortcut(item.id) }
                }
            }
        }
    }
}

@Composable
private fun DashboardTrafficRow(
    isRunning: Boolean,
    traffic: State<VpnState>,
    lang: String
) {
    val upSpeed = traffic.value.upSpeed
    val downSpeed = traffic.value.downSpeed
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LoonTrafficCard(
            modifier = Modifier.weight(1f),
            title = AppStrings.get("traffic_up", lang),
            speed = if (isRunning) upSpeed else "--",
            isUpload = true
        )
        LoonTrafficCard(
            modifier = Modifier.weight(1f),
            title = AppStrings.get("traffic_down", lang),
            speed = if (isRunning) downSpeed else "--",
            isUpload = false
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun DashboardScreenPreview() {
    val sampleProxies = listOf(
        Proxy(name = "Hong Kong 01", type = "SS", delay = 68),
        Proxy(name = "Tokyo 02", type = "VMess", delay = 112),
        Proxy(name = "Singapore 03", type = "Trojan", delay = 85)
    )
    val previewTraffic = remember {
        mutableStateOf(
            VpnState(
                status = VpnState.Status.RUNNING,
                upSpeed = "128 KB/s",
                downSpeed = "2.4 MB/s",
                totalUp = "1.2 GB",
                totalDown = "18.5 GB"
            )
        )
    }
    MihomoTheme {
        DashboardScreen(
            status = DashboardStatusState(
                vpnStatus = VpnState.Status.RUNNING,
                coreVersion = "v1.18.0-test",
                vpnTraffic = previewTraffic,
                currentMode = TunnelMode.RULE,
                activeProfileName = "Premium Auto Sub"
            ),
            proxySummary = DashboardProxySummary(
                mainGroupName = "PROXY",
                mainGroupProxies = sampleProxies,
                activeNodeName = "Hong Kong 01",
                totalNodes = 48,
                totalRules = 3520
            ),
            shortcuts = DashboardShortcutsState(
                visibleShortcuts = setOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "network_share"),
                shortcutOrder = listOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "network_share"),
                onUpdateVisibleShortcuts = {},
                onUpdateShortcutOrder = {}
            ),
            actions = DashboardActions(
                onToggleVpn = {},
                onModeSelected = {},
                onSelectProxy = { _, _ -> },
                onHealthCheck = { null }
            ),
            navigation = DashboardNavigation(
                onNavigateToProfiles = {},
                onNavigateToAppRouting = {}
            )
        )
    }
}
