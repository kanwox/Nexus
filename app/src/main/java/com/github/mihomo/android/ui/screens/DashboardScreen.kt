package com.github.mihomo.android.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import com.github.kr328.clash.core.model.Proxy
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.service.VpnState
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlin.math.roundToInt

data class ShortcutDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    vpnStatus: VpnState.Status,
    vpnError: String? = null,
    coreVersion: String = "",
    // Only the traffic cards read this state, so the once-per-second speed update
    // recomposes just that row instead of the entire dashboard.
    vpnTraffic: State<VpnState>,
    currentMode: TunnelMode,
    activeProfileName: String,
    mainGroupName: String,
    mainGroupProxies: List<Proxy>,
    activeNodeName: String,
    totalNodes: Int,
    totalRules: Int,
    visibleShortcuts: Set<String>,
    shortcutOrder: List<String>,
    onToggleVpn: () -> Unit,
    onModeSelected: (TunnelMode) -> Unit,
    onSelectProxy: (group: String, proxy: Proxy) -> Unit,
    onHealthCheck: (group: String) -> kotlinx.coroutines.Job?,
    onNavigateToProfiles: () -> Unit,
    onNavigateToAppRouting: () -> Unit,
    onNavigateToRules: () -> Unit = {},
    onNavigateToDns: () -> Unit = {},
    onNavigateToRequests: () -> Unit = {},
    onNavigateToNetworkShare: () -> Unit = {},
    onUpdateVisibleShortcuts: (Set<String>) -> Unit,
    onUpdateShortcutOrder: (List<String>) -> Unit
) {
    val isRunning = vpnStatus == VpnState.Status.RUNNING
    val isStarting = vpnStatus == VpnState.Status.STARTING
    var showModeBottomSheet by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // State for Quick Node Selector Modal
    var showQuickNodeSelector by remember { mutableStateOf(false) }
    var quickNodeSearchQuery by remember { mutableStateOf("") }
    var isTestingQuickNodes by remember { mutableStateOf(false) }
    val quickNodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()

    // Visual In-Place Shortcuts Edit State
    var isEditingShortcuts by remember { mutableStateOf(false) }
    var showAddShortcutSheet by remember { mutableStateOf(false) }
    val addShortcutSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetState by remember { mutableStateOf(Offset.Zero) }
    var initialSlotIndex by remember { mutableIntStateOf(-1) }
    var hoverSlotIndex by remember { mutableIntStateOf(-1) }
    var dragStartSlotX by remember { mutableFloatStateOf(0f) }
    var dragStartSlotY by remember { mutableFloatStateOf(0f) }
    var localOrder by remember(shortcutOrder) { mutableStateOf(shortcutOrder) }

    // State for general Informational Dialog
    var activeDialogTitle by remember { mutableStateOf<String?>(null) }
    var activeDialogContent by remember { mutableStateOf<String?>(null) }

    val lang = LocalAppLanguage.current

    // All shortcut item definitions available across entire DashboardScreen (cached to eliminate recomposition lag)
    val allShortcutMap = remember(
        lang, activeProfileName, activeNodeName, totalNodes, totalRules,
        coreVersion, isRunning
    ) {
        mapOf(
            "nodes" to ShortcutDef("nodes", AppStrings.get("shortcut_nodes", lang), activeNodeName.ifBlank { "$totalNodes" }, { NodesIllustration() }) {
                showQuickNodeSelector = true
            },
            "profiles" to ShortcutDef("profiles", AppStrings.get("shortcut_profiles", lang), activeProfileName, { ProfilesIllustration() }) {
                onNavigateToProfiles()
            },
            "request_logs" to ShortcutDef("request_logs", AppStrings.get("shortcut_requests", lang), if (isRunning) AppStrings.get("subtitle_live_monitoring", lang) else AppStrings.get("subtitle_disabled", lang), { RequestLogsIllustration() }) {
                onNavigateToRequests()
            },
            "dns" to ShortcutDef("dns", AppStrings.get("shortcut_dns", lang), if (isRunning) AppStrings.get("subtitle_live_resolving", lang) else AppStrings.get("subtitle_tap_to_view", lang), { DnsIllustration() }) {
                onNavigateToDns()
            },
            "rules" to ShortcutDef("rules", AppStrings.get("shortcut_rules", lang), if (totalRules > 0) "$totalRules" else "1", { RulesIllustration() }) {
                onNavigateToRules()
            },
            "routing" to ShortcutDef("routing", AppStrings.get("shortcut_routing", lang), AppStrings.get("subtitle_rules_blacklist", lang), { AppRoutingIllustration() }) {
                onNavigateToAppRouting()
            },
            "plugins" to ShortcutDef("plugins", AppStrings.get("shortcut_plugins", lang), if (coreVersion.isNotBlank()) "v${coreVersion.take(8)}" else "Nexus Core", { PluginsIllustration() }) {
                activeDialogTitle = AppStrings.get("shortcut_plugins", lang)
                activeDialogContent = AppStrings.get("core_info_version", lang).replace("{v}", coreVersion.ifBlank { "v1.18.x" }) + "\n" +
                        AppStrings.get("core_info_arch", lang).replace("{v}", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            },
            "network_share" to ShortcutDef("network_share", AppStrings.get("shortcut_network_share", lang), "127.0.0.1:7890", { NetworkShareIllustration() }) {
                onNavigateToNetworkShare()
            }
        )
    }

    val displayedShortcuts = remember(localOrder, visibleShortcuts, allShortcutMap, lang) {
        localOrder.mapNotNull { allShortcutMap[it] }.filter { visibleShortcuts.contains(it.id) }
    }

    val isDarkTheme = LocalLoonColors.current.isDark
    val bgColor = LoonBg
    val topBgBrush = remember(isDarkTheme, bgColor) {
        if (isDarkTheme) {
            Brush.verticalGradient(
                colors = listOf(bgColor, bgColor)
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFF6EE),
                    Color(0xFFF9F9FD),
                    bgColor
                ),
                startY = 0f,
                endY = 550f
            )
        }
    }

    CompositionLocalProvider {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LoonBg)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(topBgBrush)
                    .statusBarsPadding()
                    .bounceOverscroll()
                    .verticalScroll(state = scrollState, enabled = draggedId == null)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 110.dp)
            ) {
                // Header: NEXUS (LOON style) & Mode Selector + iOS Switch
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NEXUS",
                            color = LoonOrange,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = if (LocalAppFont.current == com.github.mihomo.android.data.AppFont.IOS) (-0.45).sp else 0.sp
                        )

                        // Top Right iOS Cupertino Switch (aligned directly with NEXUS title)
                        CupertinoSwitch(
                            checked = isRunning || isStarting,
                            onCheckedChange = {
                                haptic.vpnToggleImpact()
                                onToggleVpn()
                            }
                        )
                    }

                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showModeBottomSheet = true }
                                .padding(vertical = 3.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (currentMode) {
                                    TunnelMode.RULE -> AppStrings.get("mode_rule", lang)
                                    TunnelMode.GLOBAL -> AppStrings.get("mode_global", lang)
                                    TunnelMode.DIRECT -> AppStrings.get("mode_direct", lang)
                                },
                                color = LoonOrangeLight,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = LoonOrangeLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Dual Traffic Cards Row (Upload & Download - isolated to prevent whole dashboard recomposition)
                DashboardTrafficRow(
                    isRunning = isRunning,
                    traffic = vpnTraffic,
                    lang = lang
                )

                Spacer(modifier = Modifier.height(26.dp))

                // Section Header: 快捷方式 + 纯文字“编辑 / 完成”按钮 (无铅笔图标)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AppStrings.get("shortcuts", lang),
                        color = LoonTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val haptic = rememberIosHaptic()
                    Text(
                        text = if (isEditingShortcuts) AppStrings.get("shortcuts_done", lang) else AppStrings.get("shortcuts_edit", lang),
                        color = IosBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                haptic.lightTick()
                                isEditingShortcuts = !isEditingShortcuts
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Order according to user preference (Default: nodes, profiles, ...)
                @Suppress("UnusedBoxWithConstraintsScope")
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val spacingDp = 12.dp
                    val itemWidthDp = (this.maxWidth - spacingDp) / 2
                    val itemHeightDp = 118.dp

                    val density = LocalDensity.current
                    val itemWidthPx = with(density) { itemWidthDp.toPx() }
                    val itemHeightPx = with(density) { itemHeightDp.toPx() }
                    val spacingPx = with(density) { spacingDp.toPx() }

                    val rowCount = (displayedShortcuts.size + 1) / 2
                    val rawGridHeight = if (rowCount > 0) (itemHeightDp * rowCount) + (spacingDp * (rowCount - 1)) else 0.dp
                    val gridHeight = if (rawGridHeight == 0.dp && isEditingShortcuts) 10.dp else rawGridHeight

                    // Keep current displayedShortcuts available in gesture callbacks
                    val currentDisplayedRef = rememberUpdatedState(displayedShortcuts)
                    val currentLocalOrderRef = rememberUpdatedState(localOrder)

                    val gridBoxModifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeight)

                    Box(modifier = gridBoxModifier) {
                        displayedShortcuts.forEach { item ->
                            key(item.id) {
                                val origSlotIndex = displayedShortcuts.indexOfFirst { it.id == item.id }
                                val isBeingDragged = draggedId == item.id

                                // Compute visual slot index
                                val visualSlotIndex: Int = if (draggedId == null || initialSlotIndex == -1 || hoverSlotIndex == -1) {
                                    origSlotIndex
                                } else if (isBeingDragged) {
                                    hoverSlotIndex
                                } else {
                                    if (hoverSlotIndex > initialSlotIndex) {
                                        if (origSlotIndex in (initialSlotIndex + 1)..hoverSlotIndex) {
                                            origSlotIndex - 1
                                        } else {
                                            origSlotIndex
                                        }
                                    } else if (hoverSlotIndex < initialSlotIndex) {
                                        if (origSlotIndex in hoverSlotIndex until initialSlotIndex) {
                                            origSlotIndex + 1
                                        } else {
                                            origSlotIndex
                                        }
                                    } else {
                                        origSlotIndex
                                    }
                                }

                                val col = visualSlotIndex % 2
                                val row = visualSlotIndex / 2
                                val targetSlotX = col * (itemWidthPx + spacingPx)
                                val targetSlotY = row * (itemHeightPx + spacingPx)

                                val animatedX by animateFloatAsState(
                                    targetValue = targetSlotX,
                                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    label = "animX_${item.id}"
                                )
                                val animatedY by animateFloatAsState(
                                    targetValue = targetSlotY,
                                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    label = "animY_${item.id}"
                                )

                                val currentPosX: Float
                                val currentPosY: Float
                                if (isBeingDragged) {
                                    currentPosX = dragStartSlotX + dragOffsetState.x
                                    currentPosY = dragStartSlotY + dragOffsetState.y
                                } else {
                                    currentPosX = animatedX
                                    currentPosY = animatedY
                                }

                                var itemModifier = Modifier
                                    .size(itemWidthDp, itemHeightDp)
                                    .offset {
                                        IntOffset(currentPosX.roundToInt(), currentPosY.roundToInt())
                                    }
                                    .zIndex(if (isBeingDragged) 100f else 1f)
                                    .graphicsLayer {
                                        val scale = if (isBeingDragged) 1.06f else 1f
                                        scaleX = scale
                                        scaleY = scale
                                        shadowElevation = if (isBeingDragged) 24f else 0f
                                    }

                                if (isEditingShortcuts) {
                                    itemModifier = itemModifier.pointerInput(item.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                val list = currentDisplayedRef.value
                                                val curIdx = list.indexOfFirst { it.id == item.id }
                                                draggedId = item.id
                                                initialSlotIndex = curIdx
                                                hoverSlotIndex = curIdx
                                                val startCol = if (curIdx != -1) curIdx % 2 else 0
                                                val startRow = if (curIdx != -1) curIdx / 2 else 0
                                                dragStartSlotX = startCol * (itemWidthPx + spacingPx)
                                                dragStartSlotY = startRow * (itemHeightPx + spacingPx)
                                                dragOffsetState = Offset.Zero
                                            },
                                            onDragEnd = {
                                                val list = currentDisplayedRef.value
                                                val fromIdx = initialSlotIndex
                                                val toIdx = hoverSlotIndex
                                                val canSwap = fromIdx != toIdx && fromIdx in list.indices && toIdx in list.indices
                                                if (canSwap) {
                                                    val mutable = currentLocalOrderRef.value.toMutableList()
                                                    val fromPos = mutable.indexOf(item.id)
                                                    val targetItem = list[toIdx]
                                                    val toPos = mutable.indexOf(targetItem.id)
                                                    if (fromPos != -1 && toPos != -1) {
                                                        mutable.removeAt(fromPos)
                                                        mutable.add(toPos, item.id)
                                                        localOrder = mutable
                                                        onUpdateShortcutOrder(mutable)
                                                    }
                                                }
                                                draggedId = null
                                                dragOffsetState = Offset.Zero
                                                initialSlotIndex = -1
                                                hoverSlotIndex = -1
                                            },
                                            onDragCancel = {
                                                draggedId = null
                                                dragOffsetState = Offset.Zero
                                                initialSlotIndex = -1
                                                hoverSlotIndex = -1
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                if (draggedId != item.id) return@detectDragGesturesAfterLongPress

                                                val nextOffset = dragOffsetState + dragAmount
                                                dragOffsetState = nextOffset

                                                val list = currentDisplayedRef.value
                                                val curCenterX = dragStartSlotX + nextOffset.x + itemWidthPx / 2f
                                                val curCenterY = dragStartSlotY + nextOffset.y + itemHeightPx / 2f

                                                val targetCol = (curCenterX / (itemWidthPx + spacingPx)).toInt().coerceIn(0, 1)
                                                val targetRow = (curCenterY / (itemHeightPx + spacingPx)).toInt().coerceIn(0, (list.size - 1) / 2)
                                                val targetIndex = (targetRow * 2 + targetCol).coerceIn(0, list.size - 1)

                                                if (targetIndex != hoverSlotIndex) {
                                                    hoverSlotIndex = targetIndex
                                                }
                                            }
                                        )
                                    }
                                }

                            LoonShortcutCard(
                                modifier = itemModifier,
                                title = item.title,
                                subtitle = item.subtitle,
                                icon = item.icon,
                                isEditing = isEditingShortcuts,
                                onDelete = {
                                    if (draggedId == null) { // Prevent delete during drag
                                        onUpdateVisibleShortcuts(visibleShortcuts - item.id)
                                    }
                                },
                                onClick = item.onClick
                            )
                        }
                    }
                }
            } // Close BoxWithConstraints so add button is placed linearly below the grid

            // Visual Add Shortcut button at bottom when in editing mode
            if (isEditingShortcuts) {
                val allIds = (shortcutOrder + allShortcutMap.keys).distinct()
                val hiddenShortcuts = allIds
                    .filter { !visibleShortcuts.contains(it) }
                    .mapNotNull { allShortcutMap[it] }

                // Ensure button is always at the bottom, below any grid content
                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, LoonCardBorder, RoundedCornerShape(16.dp))
                        .clickable { showAddShortcutSheet = true },
                    color = LoonCard
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
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
                            text = if (hiddenShortcuts.isEmpty()) AppStrings.get("shortcuts_all_shown", lang) else "${AppStrings.get("shortcuts_add", lang)} (${hiddenShortcuts.size})",
                            color = LoonTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (vpnError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = RedDanger.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = RedDanger, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = vpnError, color = RedDanger, fontSize = 13.sp)
                    }
                }
            }
            } // Close scrollable content Column

            // =========================================================================
            // Quick Node Selector ModalBottomSheet (Authentic iOS Presentation)
            // =========================================================================
            if (showQuickNodeSelector) {
                CupertinoModalBottomSheet(
                    onDismissRequest = {
                        showQuickNodeSelector = false
                        quickNodeSearchQuery = ""
                    },
                    sheetState = quickNodeSheetState,
                    modifier = Modifier.fillMaxHeight(0.60f),
                    title = AppStrings.get("select_node_title", lang),
                    leadingAction = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .iosPressable {
                                    if (mainGroupName.isNotBlank() && !isTestingQuickNodes) {
                                        isTestingQuickNodes = true
                                        coroutineScope.launch {
                                            // Tie the spinner to the real speed test instead
                                            // of guessing its duration with a fixed delay.
                                            val job = onHealthCheck(mainGroupName)
                                            job?.join()
                                            isTestingQuickNodes = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedSpeedTestIcon(
                                isTesting = isTestingQuickNodes,
                                tint = IosBlue,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                ) {
                    // Search Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        CupertinoSearchBar(
                            value = quickNodeSearchQuery,
                            onValueChange = { quickNodeSearchQuery = it },
                            placeholder = AppStrings.get("quick_node_search_hint", lang)
                        )
                    }

                    val filteredNodes = remember(mainGroupProxies, quickNodeSearchQuery) {
                        val q = quickNodeSearchQuery.trim().lowercase()
                        if (q.isBlank()) mainGroupProxies
                        else mainGroupProxies.filter { it.name.lowercase().contains(q) }
                    }

                    if (filteredNodes.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (mainGroupProxies.isEmpty()) AppStrings.get("quick_node_empty", lang) else AppStrings.get("quick_node_no_match", lang),
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .bounceOverscroll(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                        ) {
                            cupertinoGroupedItems(
                                items = filteredNodes,
                                // Identity is the node name alone: mixing the index in meant
                                // every keystroke in the search box shifted all indices and
                                // rebuilt the entire visible list.
                                key = { _, proxy -> proxy.name },
                                dividerStartIndent = 16.dp
                            ) { _, proxy ->
                                val isSelected = proxy.name == activeNodeName

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .iosPressable {
                                            haptic.selection()
                                            onSelectProxy(mainGroupName, proxy)
                                            showQuickNodeSelector = false
                                            quickNodeSearchQuery = ""
                                        }
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
                                            color = if (isSelected) IosBlue else LoonTextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = proxy.type.uppercase(),
                                            color = LoonTextMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isTestingQuickNodes) {
                                            CupertinoActivityIndicator(
                                                modifier = Modifier.size(13.dp),
                                                color = IosBlue
                                            )
                                        } else if (proxy.delay in 1..65534) {
                                            val delayColor = when (proxy.delay) {
                                                in 1..250 -> IosGreen
                                                in 251..600 -> IosOrange
                                                else -> RedDanger
                                            }
                                            Text(
                                                text = "${proxy.delay} ms",
                                                color = delayColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
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
                        }
                    }
                }
            }

            // =========================================================================
            // Add Hidden Shortcut ModalBottomSheet
            // =========================================================================
            if (showAddShortcutSheet) {
                val allIds = (shortcutOrder + allShortcutMap.keys).distinct()
                val hiddenShortcuts = allIds
                    .filter { !visibleShortcuts.contains(it) }
                    .mapNotNull { allShortcutMap[it] }

                CupertinoModalBottomSheet(
                    onDismissRequest = { showAddShortcutSheet = false },
                    sheetState = addShortcutSheetState,
                    title = AppStrings.get("shortcuts_add", lang)
                ) {
                    if (hiddenShortcuts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = AppStrings.get("shortcuts_all_added", lang),
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceOverscroll(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(hiddenShortcuts) { _, item ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(0.5.dp, LoonCardBorder, RoundedCornerShape(12.dp))
                                        .iosPressable {
                                            haptic.lightTick()
                                            onUpdateVisibleShortcuts(visibleShortcuts + item.id)
                                        },
                                    color = LoonBg
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                                item.icon()
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = LoonTextPrimary)
                                                Text(item.subtitle, fontSize = 11.5.sp, color = LoonTextSecondary)
                                            }
                                        }

                                        CupertinoButton(
                                            text = AppStrings.get("shortcuts_add", lang),
                                            onClick = {
                                                onUpdateVisibleShortcuts(visibleShortcuts + item.id)
                                            },
                                            style = CupertinoButtonStyle.Tinted,
                                            icon = Icons.Default.Add
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // Mode Selector ModalBottomSheet (Pure iOS Inset Grouped presentation)
            // =========================================================================
            if (showModeBottomSheet) {
                CupertinoModalBottomSheet(
                    onDismissRequest = { showModeBottomSheet = false },
                    sheetState = modeSheetState,
                    title = AppStrings.get("select_mode", lang)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        CupertinoGroupedSection {
                            val modes = listOf(
                                Triple(TunnelMode.RULE, AppStrings.get("mode_rule", lang), AppStrings.get("mode_rule_desc", lang)),
                                Triple(TunnelMode.GLOBAL, AppStrings.get("mode_global", lang), AppStrings.get("mode_global_desc", lang)),
                                Triple(TunnelMode.DIRECT, AppStrings.get("mode_direct", lang), AppStrings.get("mode_direct_desc", lang))
                            )

                            modes.forEachIndexed { index, (mode, title, desc) ->
                                val isSelected = mode == currentMode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .iosPressable {
                                            haptic.selection()
                                            onModeSelected(mode)
                                            showModeBottomSheet = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = LoonTextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = desc,
                                            fontSize = 12.sp,
                                            color = LoonTextSecondary
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = IosBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (index < modes.lastIndex) {
                                    CupertinoIndentedDivider(startIndent = 16.dp)
                                }
                            }
                        }
                    }
                }
            }

            // Informational Dialog
            if (activeDialogTitle != null) {
                CupertinoAlertDialog(
                    title = activeDialogTitle ?: "",
                    message = activeDialogContent ?: "",
                    confirmText = AppStrings.get("dialog_ok", lang),
                    dismissText = null,
                    onConfirm = { activeDialogTitle = null },
                    onDismissRequest = { activeDialogTitle = null }
                )
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
    // Scoped read: only this row recomposes on the once-per-second speed update.
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
    MihomoTheme {
        DashboardScreen(
            vpnStatus = VpnState.Status.RUNNING,
            vpnError = null,
            coreVersion = "v1.18.0-test",
            vpnTraffic = androidx.compose.runtime.mutableStateOf(
                VpnState(
                    status = VpnState.Status.RUNNING,
                    upSpeed = "128 KB/s",
                    downSpeed = "2.4 MB/s",
                    totalUp = "1.2 GB",
                    totalDown = "18.5 GB"
                )
            ),
            currentMode = TunnelMode.RULE,
            activeProfileName = "Premium Auto Sub",
            mainGroupName = "PROXY",
            mainGroupProxies = sampleProxies,
            activeNodeName = "Hong Kong 01",
            totalNodes = 48,
            totalRules = 3520,
            visibleShortcuts = setOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "network_share"),
            shortcutOrder = listOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "network_share"),
            onToggleVpn = {},
            onModeSelected = {},
            onSelectProxy = { _, _ -> },
            onHealthCheck = { null },
            onNavigateToProfiles = {},
            onNavigateToAppRouting = {},
            onNavigateToRules = {},
            onNavigateToDns = {},
            onNavigateToRequests = {},
            onNavigateToNetworkShare = {},
            onUpdateVisibleShortcuts = {},
            onUpdateShortcutOrder = {}
        )
    }
}

