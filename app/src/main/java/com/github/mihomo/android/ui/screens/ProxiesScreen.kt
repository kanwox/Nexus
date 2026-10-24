package com.github.mihomo.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AnimatedSpeedTestIcon(
    isTesting: Boolean,
    tint: Color = LoonTextPrimary,
    modifier: Modifier = Modifier
) {
    if (isTesting) {
        CupertinoActivityIndicator(
            modifier = modifier,
            color = IosBlue
        )
    } else {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = "测速",
            tint = tint,
            modifier = modifier
        )
    }
}

// Helper to extract emoji/flag from text if present
fun extractLeadingEmoji(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val firstCodePoint = trimmed.codePointAt(0)
    val charCount = Character.charCount(firstCodePoint)
    val isEmoji = firstCodePoint in 0x1F1E6..0x1F1FF || // Regional indicator flags
            firstCodePoint in 0x1F300..0x1FAFF ||       // Misc symbols & pictographs
            firstCodePoint in 0x2600..0x27BF ||         // Misc symbols
            firstCodePoint in 0x2300..0x23FF            // Technical symbols

    if (isEmoji) {
        if (firstCodePoint in 0x1F1E6..0x1F1FF && trimmed.length >= charCount + 2) {
            val secondCodePoint = trimmed.codePointAt(charCount)
            if (secondCodePoint in 0x1F1E6..0x1F1FF) {
                return trimmed.substring(0, charCount + Character.charCount(secondCodePoint))
            }
        }
        return trimmed.substring(0, charCount)
    }
    return null
}

private fun filterAndSortProxies(
    proxies: List<Proxy>,
    searchQuery: String,
    sortMode: String
): List<Proxy> {
    val query = searchQuery.trim().lowercase()
    val filtered = if (query.isEmpty()) {
        proxies
    } else {
        proxies.filter { proxy ->
            proxy.name.lowercase().contains(query) ||
                    proxy.type.lowercase().contains(query)
        }
    }
    return when (sortMode) {
        "DELAY" -> filtered.sortedWith(
            compareBy<Proxy> { proxy ->
                when {
                    proxy.delay in 1..65534 -> 0
                    proxy.delay == -2 || proxy.delay >= 65535 -> 2
                    else -> 1
                }
            }.thenBy { proxy ->
                if (proxy.delay in 1..65534) proxy.delay else Int.MAX_VALUE
            }
        )
        "NAME" -> filtered.sortedBy { proxy ->
            val emoji = extractLeadingEmoji(proxy.name)
            val clean = if (emoji != null) proxy.name.removePrefix(emoji).trim() else proxy.name
            clean.lowercase()
        }
        else -> filtered
    }
}

private fun formatStrategyTypeLabel(rawType: String): Pair<String, Color> {
    return when (rawType.lowercase()) {
        "selector", "select" -> "手动选择" to IosBlue
        "urltest", "url-test" -> "自动测速" to IosGreen
        "fallback" -> "故障转移" to IosOrange
        "loadbalance", "load-balance" -> "负载均衡" to IosPurple
        "relay" -> "链式代理" to IosTeal
        else -> rawType.uppercase() to IosBlue
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    groups: List<String>,
    groupMap: Map<String, ProxyGroup>,
    groupIcons: Map<String, String> = emptyMap(),
    currentGroup: ProxyGroup?,
    selectedGroupName: String,
    currentViewMode: String,
    showGroupIcons: Boolean = true,
    nodeColumns: Int = 1,
    nodeSortMode: String = "DEFAULT",
    onViewModeChanged: (String) -> Unit,
    onShowGroupIconsChanged: (Boolean) -> Unit,
    onNodeColumnsChanged: (Int) -> Unit = {},
    onNodeSortModeChanged: (String) -> Unit = {},
    onSelectGroup: (String) -> Unit,
    onSelectProxy: (group: String, proxy: Proxy) -> Unit,
    onHealthCheck: (group: String) -> Unit,
    testingNodes: Set<String> = emptySet(),
    onTestSingleNode: (group: String, node: String) -> Unit = { _, _ -> },
    tabsListState: LazyListState = rememberLazyListState(),
    listModeState: LazyListState = rememberLazyListState(),
    groupRowState: LazyListState = rememberLazyListState(),
    expandedGroups: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    perGroupTabStates: SnapshotStateMap<String, LazyListState> = remember { mutableStateMapOf() }
) {
    val haptic = rememberIosHaptic()
    val coroutineScope = rememberCoroutineScope()
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val activeTabState = if (selectedGroupName.isNotBlank()) {
        perGroupTabStates.getOrPut(selectedGroupName) { LazyListState() }
    } else tabsListState

    val isListMode = currentViewMode == "LIST"
    val visibleGroups = remember(groups, groupMap) {
        groups.filter { gName -> groupMap[gName]?.hidden != true }
    }

    // Automatically center/scroll the selected group tab in Tabs mode
    LaunchedEffect(selectedGroupName, visibleGroups, isListMode) {
        if (!isListMode && selectedGroupName.isNotBlank()) {
            val targetIdx = visibleGroups.indexOf(selectedGroupName)
            if (targetIdx >= 0) {
                runCatching {
                    groupRowState.animateScrollToItem(index = (targetIdx - 1).coerceAtLeast(0))
                }
            }
        }
    }

    val isAnyTesting = if (isListMode) {
        testingNodes.isNotEmpty()
    } else {
        testingNodes.contains(selectedGroupName) ||
                currentGroup?.proxies?.any {
                    testingNodes.contains("$selectedGroupName:${it.name}") || testingNodes.contains(it.name)
                } == true
    }

    val lang = LocalAppLanguage.current
    val currentGroupProxies = currentGroup?.proxies ?: emptyList()
    val displayedTabProxies = remember(currentGroupProxies, searchQuery, nodeSortMode) {
        filterAndSortProxies(currentGroupProxies, searchQuery, nodeSortMode)
    }

    // Action to locate and scroll to the currently active node
    val handleLocateActiveNode: () -> Unit = {
        haptic.lightTick()
        if (!isListMode) {
            val activeNow = currentGroup?.now ?: ""
            val targetNodeIndex = displayedTabProxies.indexOfFirst { it.name == activeNow }
            if (targetNodeIndex >= 0) {
                val targetRowIndex = if (nodeColumns <= 1) {
                    targetNodeIndex
                } else {
                    targetNodeIndex / nodeColumns.coerceIn(1, 3)
                }
                coroutineScope.launch {
                    activeTabState.animateScrollToItem(
                        index = (targetRowIndex - 1).coerceAtLeast(0)
                    )
                }
            } else {
                CupertinoToast.show("当前筛选列表中未找到选中节点")
            }
        } else {
            val targetGroup = if (selectedGroupName.isNotBlank() && visibleGroups.contains(selectedGroupName)) {
                selectedGroupName
            } else {
                visibleGroups.firstOrNull() ?: ""
            }
            if (targetGroup.isNotBlank()) {
                expandedGroups[targetGroup] = true
                val grpIdx = visibleGroups.indexOf(targetGroup)
                if (grpIdx >= 0) {
                    coroutineScope.launch {
                        listModeState.animateScrollToItem(grpIdx)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 10.dp)
        ) {
            // =================================================================
            // 1. Authentic iOS Large Title Header & Capsule Toolbar
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = AppStrings.get("proxies_title", lang),
                        color = LoonTextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.6).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    AnimatedContent(
                        targetState = isAnyTesting,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(150))
                        },
                        label = "header_subtitle_anim"
                    ) { testing ->
                        if (testing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(IosBlue)
                                )
                                Text(
                                    text = "正在并发检测节点延迟...",
                                    color = IosBlue,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            val subText = if (isListMode) {
                                "${visibleGroups.size} 个策略分组 · ${if (nodeSortMode == "DELAY") "按延迟排序" else if (nodeSortMode == "NAME") "按名称排序" else "默认排序"}"
                            } else {
                                val count = currentGroupProxies.size
                                val reachable = currentGroupProxies.count { it.delay in 1..65534 }
                                if (reachable > 0) {
                                    "${visibleGroups.size} 个策略组 · 当前 $reachable/$count 节点可用"
                                } else {
                                    "${visibleGroups.size} 个策略组 · 当前共 $count 个节点"
                                }
                            }
                            Text(
                                text = subText,
                                color = LoonTextSecondary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Right iOS Capsule Action Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search Toggle Button
                    val searchActive = showSearchBar || searchQuery.isNotBlank()
                    IosHeaderCircleButton(
                        isActive = searchActive,
                        onClick = {
                            haptic.lightTick()
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) searchQuery = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索节点",
                            tint = if (searchActive) IosBlue else LoonTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Locate Active Node Button
                    IosHeaderCircleButton(
                        isActive = false,
                        onClick = handleLocateActiveNode
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "定位当前节点",
                            tint = LoonTextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Speed Test Button
                    IosHeaderCircleButton(
                        isActive = isAnyTesting,
                        onClick = {
                            haptic.impact()
                            if (selectedGroupName.isNotBlank()) {
                                onHealthCheck(selectedGroupName)
                            } else if (visibleGroups.isNotEmpty()) {
                                onHealthCheck(visibleGroups.first())
                            }
                        }
                    ) {
                        AnimatedSpeedTestIcon(
                            isTesting = isAnyTesting,
                            tint = LoonTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // More Settings Button (•••)
                    IosHeaderCircleButton(
                        isActive = nodeSortMode != "DEFAULT",
                        onClick = {
                            haptic.lightTick()
                            showOptionsMenu = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "策略设置",
                            tint = if (nodeSortMode != "DEFAULT") IosBlue else LoonTextPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // =================================================================
            // 2. Collapsible Cupertino Search & Quick Sort Bar
            // =================================================================
            AnimatedVisibility(
                visible = showSearchBar,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CupertinoSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "搜索节点名称或协议 (如 HK, VLESS, SS)..."
                    )
                }
            }

            // =================================================================
            // 3. Strategy Settings Modal BottomSheet (Pure iOS Inset Grouped)
            // =================================================================
            if (showOptionsMenu) {
                CupertinoModalBottomSheet(
                    onDismissRequest = { showOptionsMenu = false },
                    title = "策略与排版"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CupertinoGroupedSection(title = "视图布局") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "浏览模式",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LoonTextSecondary
                                    )
                                    CupertinoSegmentedControl(
                                        items = listOf("TABS", "LIST"),
                                        selectedItem = currentViewMode,
                                        onItemSelected = { onViewModeChanged(it) },
                                        labelProvider = {
                                            if (it == "TABS") AppStrings.get("proxies_view_tabs", lang)
                                            else AppStrings.get("proxies_view_list", lang)
                                        }
                                    )
                                }

                                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "节点排版",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LoonTextSecondary
                                    )
                                    CupertinoSegmentedControl(
                                        items = listOf(1, 2, 3),
                                        selectedItem = nodeColumns,
                                        onItemSelected = { onNodeColumnsChanged(it) },
                                        labelProvider = {
                                            when (it) {
                                                1 -> "单列列表"
                                                2 -> "双列网格"
                                                else -> "三列紧凑"
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "节点排序",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LoonTextSecondary
                                    )
                                    CupertinoSegmentedControl(
                                        items = listOf("DEFAULT", "DELAY", "NAME"),
                                        selectedItem = nodeSortMode,
                                        onItemSelected = { onNodeSortModeChanged(it) },
                                        labelProvider = {
                                            when (it) {
                                                "DELAY" -> "延迟优先"
                                                "NAME" -> "名称 A-Z"
                                                else -> "默认顺序"
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        CupertinoGroupedSection(title = "显示偏好") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "显示策略组图标",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = LoonTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "加载配置中定义的远程 SVG / PNG 策略图标",
                                        fontSize = 12.sp,
                                        color = LoonTextSecondary
                                    )
                                }
                                CupertinoSwitch(
                                    checked = showGroupIcons,
                                    onCheckedChange = { onShowGroupIconsChanged(it) }
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // MODE 1: TABS VIEW (Surge / Stash Style Pill Bar + Animated Group Content)
            // =================================================================
            if (!isListMode) {
                if (visibleGroups.isNotEmpty()) {
                    // Horizontal Scrollable Group Pill Bar
                    LazyRow(
                        state = groupRowState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = visibleGroups,
                            key = { _, grpName -> grpName }
                        ) { _, groupName ->
                            val isSelected = groupName == selectedGroupName
                            val groupIconUrl = groupIcons[groupName]
                            val groupObj = groupMap[groupName]
                            val activeEmoji = remember(groupObj?.now) {
                                extractLeadingEmoji(groupObj?.now ?: "")
                            }

                            val pillBg by animateColorAsState(
                                targetValue = if (isSelected) IosBlue else LoonCard,
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                label = "pill_bg"
                            )
                            val pillTextColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White else LoonTextPrimary,
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                label = "pill_txt"
                            )
                            val pillBorderColor by animateColorAsState(
                                targetValue = if (isSelected) IosBlue else LoonCardBorder,
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                label = "pill_border"
                            )

                            Surface(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .border(
                                        width = 0.5.dp,
                                        color = pillBorderColor,
                                        shape = RoundedCornerShape(11.dp)
                                    )
                                    .iosPressable(pressedScale = 0.95f) {
                                        if (groupName != selectedGroupName) {
                                            haptic.selection()
                                            onSelectGroup(groupName)
                                        }
                                    },
                                shape = RoundedCornerShape(11.dp),
                                color = pillBg
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (showGroupIcons && !groupIconUrl.isNullOrBlank()) {
                                        RemoteIcon(
                                            url = groupIconUrl,
                                            modifier = Modifier.size(17.dp),
                                            contentDescription = groupName
                                        )
                                    } else if (activeEmoji != null) {
                                        Text(
                                            text = activeEmoji,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = groupName,
                                        color = pillTextColor,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Active Group Summary Banner (Surge / Loon status strip)
                    if (currentGroup != null) {
                        val (typeLabel, typeAccent) = formatStrategyTypeLabel(currentGroup.type)
                        val activeNodeName = currentGroup.now.ifBlank { "未选择" }
                        val activeProxyObj = remember(currentGroup.proxies, activeNodeName) {
                            currentGroup.proxies.firstOrNull { it.name == activeNodeName }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = LoonCard,
                            border = BorderStroke(0.5.dp, LoonCardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Strategy Type Pill
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(typeAccent.copy(alpha = 0.13f))
                                            .padding(horizontal = 7.dp, vertical = 2.5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = typeLabel,
                                            color = typeAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Text(
                                        text = activeNodeName,
                                        color = LoonTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (activeProxyObj != null && activeProxyObj.delay in 1..65534) {
                                        val activeDelayColor = when {
                                            activeProxyObj.delay <= 250 -> IosGreen
                                            activeProxyObj.delay <= 600 -> IosOrange
                                            else -> IosRed
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(activeDelayColor.copy(alpha = 0.12f))
                                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(activeDelayColor)
                                            )
                                            Text(
                                                text = "${activeProxyObj.delay} ms",
                                                color = activeDelayColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "${displayedTabProxies.size} 节点",
                                            color = LoonTextSecondary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Directional AnimatedContent when switching between Strategy Groups
                AnimatedContent(
                    targetState = selectedGroupName,
                    transitionSpec = {
                        val initialIdx = visibleGroups.indexOf(initialState)
                        val targetIdx = visibleGroups.indexOf(targetState)
                        val forward = targetIdx >= initialIdx
                        val slideOffset = { fullWidth: Int ->
                            if (forward) fullWidth / 5 else -fullWidth / 5
                        }
                        val slideExitOffset = { fullWidth: Int ->
                            if (forward) -fullWidth / 6 else fullWidth / 6
                        }
                        (slideInHorizontally(
                            initialOffsetX = slideOffset,
                            animationSpec = tween(230, easing = FastOutSlowInEasing)
                        ) + fadeIn(tween(200))) togetherWith (slideOutHorizontally(
                            targetOffsetX = slideExitOffset,
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(150)))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = "group_content_transition"
                ) { targetGroupName ->
                    val targetGroup = if (targetGroupName == selectedGroupName) {
                        currentGroup
                    } else {
                        groupMap[targetGroupName]
                    }
                    val rawProxies = targetGroup?.proxies ?: emptyList()
                    val proxies = remember(rawProxies, searchQuery, nodeSortMode) {
                        filterAndSortProxies(rawProxies, searchQuery, nodeSortMode)
                    }
                    val groupState = if (targetGroupName.isNotBlank()) {
                        perGroupTabStates.getOrPut(targetGroupName) { LazyListState() }
                    } else tabsListState

                    if (proxies.isEmpty()) {
                        IosEmptyProxiesPlaceholder(
                            message = when {
                                visibleGroups.isEmpty() -> "暂无节点数据\n请前往「配置」添加订阅"
                                searchQuery.isNotBlank() -> "未找到匹配「$searchQuery」的节点"
                                else -> "当前策略组无可用节点"
                            }
                        )
                    } else {
                        val currentNow = targetGroup?.now
                        val cols = nodeColumns.coerceIn(1, 3)

                        if (cols == 1) {
                            // 1-Column: Authentic iOS Inset Grouped Table (Virtualized 120 FPS)
                            LazyColumn(
                                state = groupState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .bounceOverscroll(),
                                contentPadding = PaddingValues(top = 2.dp, bottom = 115.dp)
                            ) {
                                cupertinoGroupedItems(
                                    items = proxies,
                                    key = { index, proxy -> "${targetGroupName}_1col_${index}_${proxy.name}" },
                                    dividerStartIndent = 58.dp
                                ) { _, proxy ->
                                    val isItemTesting = testingNodes.contains(proxy.name) ||
                                            testingNodes.contains("$targetGroupName:${proxy.name}")
                                    CupertinoProxyInsetRow(
                                        proxy = proxy,
                                        isSelected = proxy.name == currentNow,
                                        isTesting = isItemTesting,
                                        onClick = {
                                            haptic.selection()
                                            onSelectProxy(targetGroupName, proxy)
                                        },
                                        onTestSingleNode = {
                                            haptic.lightTick()
                                            onTestSingleNode(targetGroupName, proxy.name)
                                        }
                                    )
                                }
                            }
                        } else {
                            // 2 or 3 Columns: Surge / Loon Bento Grid Cards
                            val chunkedRows = remember(proxies, cols) {
                                proxies.chunked(cols)
                            }
                            LazyColumn(
                                state = groupState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .bounceOverscroll(),
                                contentPadding = PaddingValues(top = 2.dp, bottom = 115.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = chunkedRows,
                                    key = { rowIndex, row -> "${targetGroupName}_grid_${rowIndex}_${row.first().name}" }
                                ) { _, rowProxies ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        for (proxy in rowProxies) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                val isItemTesting = testingNodes.contains(proxy.name) ||
                                                        testingNodes.contains("$targetGroupName:${proxy.name}")
                                                LoonProxyBentoCard(
                                                    proxy = proxy,
                                                    isSelected = proxy.name == currentNow,
                                                    columns = cols,
                                                    isTesting = isItemTesting,
                                                    onClick = {
                                                        haptic.selection()
                                                        onSelectProxy(targetGroupName, proxy)
                                                    },
                                                    onTestSingleNode = {
                                                        haptic.lightTick()
                                                        onTestSingleNode(targetGroupName, proxy.name)
                                                    }
                                                )
                                            }
                                        }
                                        val remainder = cols - rowProxies.size
                                        if (remainder > 0) {
                                            repeat(remainder) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // =================================================================
            // MODE 2: ACCORDION LIST VIEW (Loon Style Inset Group Cards)
            // =================================================================
            else {
                if (visibleGroups.isEmpty()) {
                    IosEmptyProxiesPlaceholder(message = "暂无策略分组数据\n请前往「配置」添加订阅")
                } else {
                    val cols = nodeColumns.coerceIn(1, 3)
                    LazyColumn(
                        state = listModeState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .bounceOverscroll(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 115.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        visibleGroups.forEach { groupName ->
                            val group = groupMap[groupName]
                            val isExpanded = expandedGroups[groupName] ?: false
                            val rawProxies = group?.proxies ?: emptyList()
                            val proxies = filterAndSortProxies(rawProxies, searchQuery, nodeSortMode)
                            val iconUrl = groupIcons[groupName]
                            val activeProxyObj = rawProxies.firstOrNull { it.name == group?.now }

                            item(key = "grp_header_$groupName", contentType = "group_header") {
                                val isGroupTesting = testingNodes.contains(groupName) ||
                                        rawProxies.any {
                                            testingNodes.contains("$groupName:${it.name}") ||
                                                    (it.isGroup && testingNodes.contains(it.name))
                                        }
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    LoonGroupHeaderCard(
                                        groupName = groupName,
                                        groupType = group?.type ?: "select",
                                        activeNode = group?.now ?: "",
                                        activeNodeDelay = activeProxyObj?.delay ?: -1,
                                        nodeCount = rawProxies.size,
                                        iconUrl = iconUrl,
                                        showGroupIcons = showGroupIcons,
                                        isExpanded = isExpanded,
                                        isTesting = isGroupTesting,
                                        onHealthCheck = {
                                            haptic.impact()
                                            onHealthCheck(groupName)
                                        },
                                        onToggleExpand = {
                                            haptic.selection()
                                            expandedGroups[groupName] = !isExpanded
                                        }
                                    )

                                    AnimatedVisibility(
                                        visible = isExpanded && proxies.isNotEmpty(),
                                        enter = expandVertically(
                                            expandFrom = Alignment.Top,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = 0.85f
                                            )
                                        ) + fadeIn(animationSpec = tween(180)),
                                        exit = shrinkVertically(
                                            shrinkTowards = Alignment.Top,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = 0.88f
                                            )
                                        ) + fadeOut(animationSpec = tween(130)),
                                        modifier = Modifier.clipToBounds()
                                    ) {
                                        val groupNow = group?.now
                                        if (cols == 1) {
                                            // Inset Grouped Container inside Accordion
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp),
                                                shape = RoundedCornerShape(14.dp),
                                                color = LoonCard,
                                                border = BorderStroke(0.5.dp, LoonCardBorder)
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    proxies.forEachIndexed { idx, proxy ->
                                                        val isItemTesting = testingNodes.contains(proxy.name) ||
                                                                testingNodes.contains("$groupName:${proxy.name}")
                                                        CupertinoProxyInsetRow(
                                                            proxy = proxy,
                                                            isSelected = proxy.name == groupNow,
                                                            isTesting = isItemTesting,
                                                            onClick = {
                                                                haptic.selection()
                                                                onSelectProxy(groupName, proxy)
                                                            },
                                                            onTestSingleNode = {
                                                                haptic.lightTick()
                                                                onTestSingleNode(groupName, proxy.name)
                                                            }
                                                        )
                                                        if (idx < proxies.lastIndex) {
                                                            HorizontalDivider(
                                                                modifier = Modifier.padding(start = 58.dp),
                                                                color = LoonSeparator,
                                                                thickness = 0.5.dp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val chunkedRows = remember(proxies, cols) {
                                                proxies.chunked(cols)
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                for (rowProxies in chunkedRows) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        for (proxy in rowProxies) {
                                                            Box(modifier = Modifier.weight(1f)) {
                                                                val isItemTesting = testingNodes.contains(proxy.name) ||
                                                                        testingNodes.contains("$groupName:${proxy.name}")
                                                                LoonProxyBentoCard(
                                                                    proxy = proxy,
                                                                    isSelected = proxy.name == groupNow,
                                                                    columns = cols,
                                                                    isTesting = isItemTesting,
                                                                    onClick = {
                                                                        haptic.selection()
                                                                        onSelectProxy(groupName, proxy)
                                                                    },
                                                                    onTestSingleNode = {
                                                                        haptic.lightTick()
                                                                        onTestSingleNode(groupName, proxy.name)
                                                                    }
                                                                )
                                                            }
                                                        }
                                                        val remainder = cols - rowProxies.size
                                                        if (remainder > 0) {
                                                            repeat(remainder) {
                                                                Spacer(modifier = Modifier.weight(1f))
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
                    }
                }
            }
        }

        // Top smooth status-bar gradient veil
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(14.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            LoonBg,
                            LoonBg.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

// =============================================================================
// iOS Header Circle Action Button (34dp Frosted Pill Surface)
// =============================================================================
@Composable
private fun IosHeaderCircleButton(
    isActive: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) IosBlue.copy(alpha = 0.14f) else LoonCard,
        animationSpec = tween(180),
        label = "hdr_btn_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) IosBlue.copy(alpha = 0.45f) else LoonCardBorder,
        animationSpec = tween(180),
        label = "hdr_btn_border"
    )

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(0.5.dp, borderColor, CircleShape)
            .iosPressable(pressedScale = 0.90f) { onClick() },
        contentAlignment = Alignment.Center,
        content = content
    )
}

// =============================================================================
// Empty State Placeholder
// =============================================================================
@Composable
private fun IosEmptyProxiesPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(LoonTertiaryBg.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = LoonTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = message,
                color = LoonTextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =============================================================================
// Accordion Group Header Card for List Mode (Loon iOS Style)
// =============================================================================
@Composable
private fun LoonGroupHeaderCard(
    groupName: String,
    groupType: String,
    activeNode: String,
    activeNodeDelay: Int = -1,
    nodeCount: Int = 0,
    iconUrl: String?,
    showGroupIcons: Boolean,
    isExpanded: Boolean,
    isTesting: Boolean = false,
    onHealthCheck: () -> Unit,
    onToggleExpand: () -> Unit
) {
    val (typeLabel, typeAccent) = formatStrategyTypeLabel(groupType)
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "group_chevron_rot"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isExpanded) IosBlue.copy(alpha = 0.35f) else LoonCardBorder,
        animationSpec = tween(200),
        label = "group_border_color"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
            .iosPressable(pressedScale = 0.985f) { onToggleExpand() },
        color = LoonCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // iOS 40dp Squircle Icon Container
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                                LoonTertiaryBg.copy(alpha = 0.5f)
                            } else {
                                typeAccent.copy(alpha = 0.12f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                        RemoteIcon(
                            url = iconUrl,
                            modifier = Modifier.size(26.dp),
                            contentDescription = groupName
                        )
                    } else {
                        Icon(
                            imageVector = when (groupType.lowercase()) {
                                "urltest", "url-test" -> Icons.Default.Speed
                                "fallback" -> Icons.Default.SyncProblem
                                "loadbalance", "load-balance" -> Icons.Default.Balance
                                else -> Icons.Default.Hub
                            },
                            contentDescription = groupName,
                            tint = typeAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = groupName,
                            color = LoonTextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Strategy Type Micro-Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(typeAccent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.5.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = typeLabel,
                                color = typeAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "$nodeCount",
                            color = LoonTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (activeNode.isNotBlank()) activeNode else "未选择节点",
                            color = if (activeNode.isNotBlank()) LoonTextSecondary else LoonTextMuted,
                            fontSize = 12.5.sp,
                            fontWeight = if (activeNode.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (activeNodeDelay in 1..65534) {
                            val dColor = when {
                                activeNodeDelay <= 250 -> IosGreen
                                activeNodeDelay <= 600 -> IosOrange
                                else -> IosRed
                            }
                            Text(
                                text = "· ${activeNodeDelay}ms",
                                color = dColor,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedVisibility(
                    visible = isExpanded || isTesting,
                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(LoonTertiaryBg.copy(alpha = 0.65f))
                            .iosPressable(enabled = !isTesting) { onHealthCheck() },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedSpeedTestIcon(
                            isTesting = isTesting,
                            tint = IosBlue,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = LoonChevronTint,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }
        }
    }
}

// =============================================================================
// 1-Column Authentic iOS Inset Grouped Row (Stash / Loon Style)
// =============================================================================
@Composable
private fun CupertinoProxyInsetRow(
    proxy: Proxy,
    isSelected: Boolean,
    isTesting: Boolean,
    onClick: () -> Unit,
    onTestSingleNode: () -> Unit
) {
    val nodeEmoji = remember(proxy.name) { extractLeadingEmoji(proxy.name) }
    val displayName = remember(proxy.name, nodeEmoji) {
        if (nodeEmoji != null && proxy.name.startsWith(nodeEmoji)) {
            proxy.name.removePrefix(nodeEmoji).trim()
        } else {
            proxy.name
        }
    }

    val isDark = LocalLoonColors.current.isDark
    val rowBg by animateColorAsState(
        targetValue = if (isSelected) {
            if (isDark) IosBlue.copy(alpha = 0.14f) else IosBlue.copy(alpha = 0.08f)
        } else Color.Transparent,
        animationSpec = tween(180),
        label = "inset_row_bg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) IosBlue else LoonTextPrimary,
        animationSpec = tween(180),
        label = "inset_row_title"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(rowBg)
            .iosHighlightPressable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            // 34dp iOS Squircle Emoji / Protocol Badge
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isSelected) IosBlue.copy(alpha = 0.16f)
                        else LoonTertiaryBg.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (nodeEmoji != null) {
                    Text(
                        text = nodeEmoji,
                        fontSize = 17.sp
                    )
                } else {
                    Text(
                        text = proxy.type.take(2).uppercase(),
                        color = if (isSelected) IosBlue else LoonTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = displayName,
                    color = titleColor,
                    fontSize = 14.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LoonTertiaryBg.copy(alpha = 0.7f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = proxy.type.uppercase(),
                            color = LoonTextSecondary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (proxy.isGroup) {
                        Text(
                            text = "策略引用",
                            color = LoonTextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CupertinoLatencyPill(
                delay = proxy.delay,
                isTesting = isTesting,
                compact = false,
                onClick = onTestSingleNode
            )

            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(
                    animationSpec = spring(
                        dampingRatio = 0.65f,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(tween(150)),
                exit = scaleOut(tween(120)) + fadeOut(tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(IosBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选中",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

// =============================================================================
// 2 & 3 Columns Bento Grid Node Card (Surge / Loon Style with Accent Indicator)
// =============================================================================
@Composable
private fun LoonProxyBentoCard(
    proxy: Proxy,
    isSelected: Boolean,
    columns: Int = 2,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onTestSingleNode: () -> Unit = {}
) {
    val nodeEmoji = remember(proxy.name) { extractLeadingEmoji(proxy.name) }
    val displayName = remember(proxy.name, nodeEmoji) {
        if (nodeEmoji != null && proxy.name.startsWith(nodeEmoji)) {
            proxy.name.removePrefix(nodeEmoji).trim()
        } else {
            proxy.name
        }
    }

    val isDark = LocalLoonColors.current.isDark
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) {
            if (isDark) Color(0xFF12233A) else Color(0xFFEFF6FF)
        } else LoonCard,
        animationSpec = tween(180),
        label = "bento_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) IosBlue.copy(alpha = 0.65f) else LoonCardBorder,
        animationSpec = tween(180),
        label = "bento_border"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) IosBlue else LoonTextPrimary,
        animationSpec = tween(180),
        label = "bento_title"
    )
    val indicatorHeight by animateDpAsState(
        targetValue = if (isSelected) 22.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bento_indicator_h"
    )

    val isCompact3Col = columns >= 3
    val cardShape = RoundedCornerShape(if (isCompact3Col) 13.dp else 15.dp)
    val cardHeight = if (isCompact3Col) 60.dp else 66.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(cardShape)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = borderColor,
                shape = cardShape
            )
            .iosPressable(pressedScale = 0.96f) { onClick() },
        color = cardBg,
        shape = cardShape
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Left Vertical Accent Bar (Surge / Loon signature indicator)
            if (indicatorHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(3.5.dp)
                        .height(indicatorHeight)
                        .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                        .background(IosBlue)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (isCompact3Col) 9.dp else 12.dp,
                        end = if (isCompact3Col) 8.dp else 10.dp,
                        top = if (isCompact3Col) 7.dp else 9.dp,
                        bottom = if (isCompact3Col) 7.dp else 8.dp
                    ),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Emoji + Node Name + Active Dot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (nodeEmoji != null) {
                        Text(
                            text = nodeEmoji,
                            fontSize = if (isCompact3Col) 11.5.sp else 13.5.sp,
                            modifier = Modifier.padding(end = if (isCompact3Col) 3.dp else 5.dp)
                        )
                    }
                    Text(
                        text = displayName,
                        color = titleColor,
                        fontSize = if (isCompact3Col) 11.5.sp else 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bottom Row: Protocol Capsule + Latency Pill Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSelected) IosBlue.copy(alpha = 0.12f)
                                else LoonTertiaryBg.copy(alpha = 0.65f)
                            )
                            .padding(
                                horizontal = if (isCompact3Col) 4.dp else 5.dp,
                                vertical = 1.dp
                            )
                    ) {
                        Text(
                            text = proxy.type.uppercase(),
                            color = if (isSelected) IosBlue else LoonTextSecondary,
                            fontSize = if (isCompact3Col) 8.sp else 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    CupertinoLatencyPill(
                        delay = proxy.delay,
                        isTesting = isTesting,
                        compact = isCompact3Col,
                        onClick = onTestSingleNode
                    )
                }
            }
        }
    }
}

// =============================================================================
// Semantic Latency Pill Badge (Tap to Single-Test with Activity Indicator)
// =============================================================================
@Composable
private fun CupertinoLatencyPill(
    delay: Int,
    isTesting: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val isTimeout = delay == -2 || delay >= 65535
    val isValidDelay = delay in 1..65534
    val delayColor = when {
        isTimeout -> IosRed
        isValidDelay && delay <= 250 -> IosGreen
        isValidDelay && delay <= 600 -> IosOrange
        isValidDelay && delay > 600 -> IosRed
        else -> LoonTextMuted
    }
    val delayText = when {
        isTimeout -> "超时"
        isValidDelay -> if (compact) "${delay}ms" else "$delay ms"
        else -> "测速"
    }
    val pillBg = when {
        isTesting -> IosBlue.copy(alpha = 0.12f)
        isValidDelay || isTimeout -> delayColor.copy(alpha = 0.12f)
        else -> LoonTertiaryBg.copy(alpha = 0.55f)
    }

    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = if (compact) 38.dp else 52.dp,
                minHeight = if (compact) 18.dp else 23.dp
            )
            .clip(RoundedCornerShape(if (compact) 5.dp else 7.dp))
            .background(pillBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(
                horizontal = if (compact) 5.dp else 7.dp,
                vertical = if (compact) 2.dp else 3.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isTesting) {
            CupertinoActivityIndicator(
                modifier = Modifier.size(if (compact) 10.dp else 12.dp),
                color = IosBlue
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
            ) {
                if (isValidDelay && !compact) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(delayColor)
                    )
                }
                Text(
                    text = delayText,
                    color = delayColor,
                    fontSize = if (compact) 9.5.sp else 11.sp,
                    fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProxiesScreenPreview() {
    val sampleProxies = listOf(
        Proxy(name = "🇭🇰 HK - 01 (BGP)", type = "SS", delay = 42),
        Proxy(name = "🇯🇵 JP - Tokyo HighSpeed", type = "VLESS", delay = 89),
        Proxy(name = "🇸🇬 SG - Singapore Premium", type = "Trojan", delay = 74),
        Proxy(name = "🇺🇸 US - Silicon Valley", type = "Hysteria2", delay = 185)
    )
    val groupProxy = ProxyGroup(
        type = "Selector",
        proxies = sampleProxies,
        now = "🇭🇰 HK - 01 (BGP)"
    )
    val groups = listOf("节点选择", "自动选择", "故障转移")
    val groupMap = mapOf(
        "节点选择" to groupProxy,
        "自动选择" to ProxyGroup(type = "URLTest", proxies = sampleProxies, now = "🇭🇰 HK - 01 (BGP)"),
        "故障转移" to ProxyGroup(type = "Fallback", proxies = sampleProxies, now = "🇭🇰 HK - 01 (BGP)")
    )
    MihomoTheme {
        ProxiesScreen(
            groups = groups,
            groupMap = groupMap,
            currentGroup = groupProxy,
            selectedGroupName = "节点选择",
            currentViewMode = "TABS",
            onViewModeChanged = {},
            onShowGroupIconsChanged = {},
            onSelectGroup = {},
            onSelectProxy = { _, _ -> },
            onHealthCheck = {}
        )
    }
}
