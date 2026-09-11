package com.github.mihomo.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import com.github.kr328.clash.core.model.Proxy
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.service.VpnState
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
    vpnState: VpnState,
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
    onHealthCheck: (group: String) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToAppRouting: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onUpdateVisibleShortcuts: (Set<String>) -> Unit,
    onUpdateShortcutOrder: (List<String>) -> Unit
) {
    val isRunning = vpnState.status == VpnState.Status.RUNNING
    val isStarting = vpnState.status == VpnState.Status.STARTING
    var showModeBottomSheet by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // State for Quick Node Selector Modal
    var showQuickNodeSelector by remember { mutableStateOf(false) }
    var quickNodeSearchQuery by remember { mutableStateOf("") }
    var isTestingQuickSheet by remember { mutableStateOf(false) }
    val quickNodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    // Automatically fully expand sheet when opened
    LaunchedEffect(showQuickNodeSelector) {
        if (showQuickNodeSelector) {
            runCatching { quickNodeSheetState.expand() }
        }
    }

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
        vpnState.upSpeed, vpnState.downSpeed, vpnState.totalUp, vpnState.totalDown,
        vpnState.coreVersion, isRunning
    ) {
        mapOf(
            "nodes" to ShortcutDef("nodes", AppStrings.get("shortcut_nodes", lang), if (activeNodeName.isNotBlank()) activeNodeName else "$totalNodes", { NodesIllustration() }) {
                showQuickNodeSelector = true
            },
            "profiles" to ShortcutDef("profiles", AppStrings.get("shortcut_profiles", lang), activeProfileName, { ProfilesIllustration() }) {
                onNavigateToProfiles()
            },
            "core_logs" to ShortcutDef("core_logs", AppStrings.get("shortcut_logs", lang), if (isRunning) "实时监控" else "点击查看", { RequestLogsIllustration() }) {
                onNavigateToLogs()
            },
            "request_logs" to ShortcutDef("request_logs", AppStrings.get("shortcut_requests", lang), if (isRunning) "实时监控" else "未开启", { RequestLogsIllustration() }) {
                activeDialogTitle = "请求记录与状态"
                activeDialogContent = if (isRunning) {
                    "Nexus 内核已连接并正常调度网络连接。\n\n" +
                            "已上传总量: ${vpnState.totalUp}\n" +
                            "已下载总量: ${vpnState.totalDown}\n" +
                            "当前上传速率: ${vpnState.upSpeed}\n" +
                            "当前下载速率: ${vpnState.downSpeed}\n" +
                            "核心运行状态: 活跃运行中"
                } else {
                    "代理服务未开启，暂无活动请求记录。请点击右上角开启连接。"
                }
            },
            "dns" to ShortcutDef("dns", AppStrings.get("shortcut_dns", lang), "Fake-IP", { DnsIllustration() }) {
                activeDialogTitle = "DNS 运行模式"
                activeDialogContent = "Nexus 内置高性能 DNS 解析器\n\n" +
                        "• 增强模式: Fake-IP 映射池\n" +
                        "• 虚拟网段: 198.18.0.1/16\n" +
                        "• 监听端口: 127.0.0.1:1053\n" +
                        "• 国内上游: 223.5.5.5, 119.29.29.29\n" +
                        "• 防污染劫持: 启用"
            },
            "rules" to ShortcutDef("rules", AppStrings.get("shortcut_rules", lang), if (totalRules > 0) "$totalRules" else "1", { RulesIllustration() }) {
                activeDialogTitle = "分流规则"
                activeDialogContent = "当前配置已加载 $totalRules 条匹配规则。\n\n" +
                        "• 分流机制: 规则引擎 (GEOIP, DOMAIN-SUFFIX, IP-CIDR, MATCH)\n" +
                        "• 当前运行模式: ${currentMode.displayName}\n" +
                        "• 规则嗅探: 开启 TLS/HTTP SNI 智能嗅探"
            },
            "routing" to ShortcutDef("routing", AppStrings.get("shortcut_routing", lang), "规则与黑白名单", { AppRoutingIllustration() }) {
                onNavigateToAppRouting()
            },
            "plugins" to ShortcutDef("plugins", AppStrings.get("shortcut_plugins", lang), if (vpnState.coreVersion.isNotBlank()) "v${vpnState.coreVersion.take(8)}" else "Nexus Core", { PluginsIllustration() }) {
                activeDialogTitle = "Nexus 内核信息"
                activeDialogContent = "内核版本: ${vpnState.coreVersion.ifBlank { "Nexus Core (Clash.Meta) v1.18.x" }}\n" +
                        "ABI 架构: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}\n" +
                        "内核通信: JNI 原生嵌入式交互\n" +
                        "系统服务: Android VpnService + TUN"
            },
            "network_share" to ShortcutDef("network_share", AppStrings.get("shortcut_network_share", lang), "127.0.0.1:7890", { NetworkShareIllustration() }) {
                activeDialogTitle = "局域网共享与代理端口"
                activeDialogContent = "混合端口 (HTTP + SOCKS5):\n" +
                        "• 本机地址: 127.0.0.1:7890\n" +
                        "• TUN 设备: tun0 (172.19.0.1)\n" +
                        "• 允许局域网设备连接可在设置中开启"
            }
        )
    }

    val displayedShortcuts = remember(localOrder, visibleShortcuts, lang) {
        localOrder.mapNotNull { allShortcutMap[it] }.filter { visibleShortcuts.contains(it.id) }
    }

    val isDarkTheme = LocalLoonColors.current.isDark
    val topBgBrush = if (isDarkTheme) {
        Brush.verticalGradient(
            colors = listOf(LoonBg, LoonBg)
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFF6EE),
                Color(0xFFF9F9FD),
                LoonBg
            ),
            startY = 0f,
            endY = 550f
        )
    }

    // Stretch overscroll disabled specifically on DashboardScreen as requested
    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
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
                    .verticalScroll(state = scrollState, enabled = draggedId == null)
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 110.dp)
            ) {
                // Header: LOON (MIHOMO) & Mode Selector + iOS Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "NEXUS",
                            color = LoonOrange,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showModeBottomSheet = true }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
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

                    // Top Right iOS Switch
                    Switch(
                        checked = isRunning || isStarting,
                        onCheckedChange = { onToggleVpn() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = LoonGreen,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE2E4EB),
                            uncheckedBorderColor = Color.Transparent,
                            checkedBorderColor = Color.Transparent
                        )
                    )
                }

                // Dual Traffic Cards Row (Upload & Download)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LoonTrafficCard(
                        modifier = Modifier.weight(1f),
                        title = AppStrings.get("traffic_up", lang),
                        speed = if (isRunning) vpnState.upSpeed else "--",
                        isUpload = true
                    )
                    LoonTrafficCard(
                        modifier = Modifier.weight(1f),
                        title = AppStrings.get("traffic_down", lang),
                        speed = if (isRunning) vpnState.downSpeed else "--",
                        isUpload = false
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // Section Header: 快捷方式
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

                    val editBtnBg = if (isDarkTheme) Color(0xFF252A37) else Color(0xFFE8EBF2)
                    val editContentColor = if (isEditingShortcuts) Color(0xFFF59E0B) else Color(0xFF2563EB)

                    Surface(
                        shape = CircleShape,
                        color = editBtnBg,
                        border = null,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { isEditingShortcuts = !isEditingShortcuts }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isEditingShortcuts) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = null,
                                tint = editContentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isEditingShortcuts) AppStrings.get("shortcuts_done", lang) else AppStrings.get("shortcuts_edit", lang),
                                color = editContentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Order according to user preference (Default: nodes, profiles, ...)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val spacingDp = 12.dp
                    val itemWidthDp = (maxWidth - spacingDp) / 2
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
                            androidx.compose.runtime.key(item.id) {
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
                val hiddenShortcuts = shortcutOrder
                    .filter { !visibleShortcuts.contains(it) }
                    .mapNotNull { allShortcutMap[it] }

                // Ensure button is always at the bottom, below any grid content
                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.5.dp, LoonBlue, RoundedCornerShape(16.dp))
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
                            tint = LoonBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hiddenShortcuts.isEmpty()) AppStrings.get("shortcuts_all_shown", lang) else "${AppStrings.get("shortcuts_add", lang)} (${hiddenShortcuts.size})",
                            color = LoonBlue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (vpnState.error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = RedDanger, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = vpnState.error, color = RedDanger, fontSize = 13.sp)
                    }
                }
            }

            // =========================================================================
            // In-Place Quick Node Selector BottomSheet (Full 92% height, smooth expansion)
            // =========================================================================
            if (showQuickNodeSelector) {
                ModalBottomSheet(
                    onDismissRequest = { showQuickNodeSelector = false },
                    sheetState = quickNodeSheetState,
                    containerColor = LoonCard,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.92f)
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = AppStrings.get("quick_node_title", lang),
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LoonTextPrimary
                                )
                                Text(
                                    text = "策略组: ${mainGroupName.ifBlank { "默认主组" }}",
                                    fontSize = 12.sp,
                                    color = LoonTextSecondary
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (mainGroupName.isNotBlank() && !isTestingQuickSheet) {
                                        isTestingQuickSheet = true
                                        coroutineScope.launch {
                                            onHealthCheck(mainGroupName)
                                            kotlinx.coroutines.delay(1800)
                                            isTestingQuickSheet = false
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                AnimatedSpeedTestIcon(
                                    isTesting = isTestingQuickSheet,
                                    tint = LoonTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Search bar
                        OutlinedTextField(
                            value = quickNodeSearchQuery,
                            onValueChange = { quickNodeSearchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            placeholder = { Text(AppStrings.get("search_nodes", lang), color = LoonTextMuted, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LoonTextSecondary, modifier = Modifier.size(17.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LoonBg,
                                unfocusedContainerColor = LoonBg,
                                focusedBorderColor = LoonBlue,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        val filteredNodes = remember(mainGroupProxies, quickNodeSearchQuery) {
                            if (quickNodeSearchQuery.isBlank()) mainGroupProxies
                            else mainGroupProxies.filter { it.name.contains(quickNodeSearchQuery, ignoreCase = true) }
                        }

                        if (filteredNodes.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (mainGroupProxies.isEmpty()) AppStrings.get("no_nodes_available", lang) else AppStrings.get("no_nodes_matched", lang),
                                    color = LoonTextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = filteredNodes,
                                    key = { index, proxy -> "${proxy.name}_$index" }
                                ) { _, proxy ->
                                    val isSelected = proxy.name == activeNodeName
                                    val nodeEmoji = extractLeadingEmoji(proxy.name)

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) LoonBlue else LoonCardBorder,
                                                shape = RoundedCornerShape(18.dp)
                                            )
                                            .clickable {
                                                onSelectProxy(mainGroupName, proxy)
                                                showQuickNodeSelector = false
                                            },
                                        color = LoonCard
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                if (nodeEmoji != null) {
                                                    Text(
                                                        text = nodeEmoji,
                                                        fontSize = 16.sp,
                                                        modifier = Modifier.padding(end = 8.dp)
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = proxy.name,
                                                        color = if (isSelected) LoonBlue else LoonTextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = proxy.type.uppercase(),
                                                        color = LoonTextMuted,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            if (isTestingQuickSheet) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(13.dp),
                                                    strokeWidth = 1.8.dp,
                                                    color = LoonBlue
                                                )
                                            } else {
                                                val delayColor = when {
                                                    proxy.delay == -2 -> RedDanger
                                                    proxy.delay in 1..250 -> LoonGreen
                                                    proxy.delay in 251..600 -> Color(0xFFF59E0B)
                                                    proxy.delay > 600 -> RedDanger
                                                    else -> LoonTextMuted
                                                }
                                                val delayText = when {
                                                    proxy.delay == -2 -> "超时"
                                                    proxy.delay > 0 -> "${proxy.delay} ms"
                                                    else -> "--"
                                                }
                                                Text(
                                                    text = delayText,
                                                    color = delayColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (proxy.delay > 0 || proxy.delay == -2) FontWeight.Bold else FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // Add Hidden Shortcut BottomSheet
            // =========================================================================
            if (showAddShortcutSheet) {
                val hiddenShortcuts = shortcutOrder
                    .filter { !visibleShortcuts.contains(it) }
                    .mapNotNull { allShortcutMap[it] }

                ModalBottomSheet(
                    onDismissRequest = { showAddShortcutSheet = false },
                    sheetState = addShortcutSheetState,
                    containerColor = LoonCard,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 32.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = AppStrings.get("shortcuts_add", lang),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = LoonTextPrimary
                            )
                            IconButton(onClick = { showAddShortcutSheet = false }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭", tint = LoonTextSecondary)
                            }
                        }

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
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(hiddenShortcuts) { _, item ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, LoonCardBorder, RoundedCornerShape(12.dp))
                                            .clickable {
                                                onUpdateVisibleShortcuts(visibleShortcuts + item.id)
                                            },
                                        color = LoonCard
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                                    item.icon()
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LoonTextPrimary)
                                                    Text(item.subtitle, fontSize = 11.sp, color = LoonTextSecondary)
                                                }
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = LoonEditBlueBg
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(AppStrings.get("shortcuts_add", lang), color = LoonBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // Mode Selector BottomSheet (Smooth 24dp rounded sheet)
            // =========================================================================
            if (showModeBottomSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showModeBottomSheet = false },
                    sheetState = modeSheetState,
                    containerColor = LoonCard,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 36.dp)
                    ) {
                        Text(
                            text = AppStrings.get("select_mode", lang),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val modes = listOf(
                            Triple(TunnelMode.RULE, AppStrings.get("mode_rule", lang), "根据分流规则匹配分流策略"),
                            Triple(TunnelMode.GLOBAL, AppStrings.get("mode_global", lang), "强制转发至目标节点"),
                            Triple(TunnelMode.DIRECT, AppStrings.get("mode_direct", lang), "所有流量直接连接，不通过代理")
                        )

                        modes.forEach { (mode, title, desc) ->
                            val isSelected = mode == currentMode
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) LoonOrange else LoonCardBorder,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable {
                                        onModeSelected(mode)
                                        showModeBottomSheet = false
                                    },
                                color = LoonCard
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) LoonOrange else LoonTextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = desc,
                                            fontSize = 12.sp,
                                            color = LoonTextSecondary
                                        )
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(LoonOrange)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Informational Dialog
            if (activeDialogTitle != null) {
                AlertDialog(
                    onDismissRequest = { activeDialogTitle = null },
                    title = {
                        Text(
                            text = activeDialogTitle ?: "",
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                    },
                    text = {
                        Text(
                            text = activeDialogContent ?: "",
                            color = LoonTextSecondary,
                            lineHeight = 20.sp,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialogTitle = null }) {
                            Text("知道了", color = LoonBlue, fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = LoonCard,
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
    }
}




}
