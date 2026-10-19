package com.github.mihomo.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@Composable
fun AnimatedSpeedTestIcon(
    isTesting: Boolean,
    tint: Color = LoonTextPrimary,
    modifier: Modifier = Modifier
) {
    val alpha by if (isTesting) {
        val infiniteTransition = rememberInfiniteTransition(label = "speed_test_anim")
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "speed_test_alpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Icon(
        imageVector = Icons.Default.Bolt,
        contentDescription = "测速",
        tint = tint.copy(alpha = alpha),
        modifier = modifier
    )
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
    onViewModeChanged: (String) -> Unit,
    onShowGroupIconsChanged: (Boolean) -> Unit,
    onNodeColumnsChanged: (Int) -> Unit = {},
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
    var showOptionsMenu by remember { mutableStateOf(false) }
    val activeTabState = if (selectedGroupName.isNotBlank()) {
        perGroupTabStates.getOrPut(selectedGroupName) { LazyListState() }
    } else tabsListState

    val isListMode = currentViewMode == "LIST"
    val visibleGroups = remember(groups, groupMap) {
        groups.filter { gName -> groupMap[gName]?.hidden != true }
    }
    val isAnyTesting = if (isListMode) {
        testingNodes.isNotEmpty()
    } else {
        testingNodes.contains(selectedGroupName) || currentGroup?.proxies?.any { testingNodes.contains("$selectedGroupName:${it.name}") || testingNodes.contains(it.name) } == true
    }

    val lang = LocalAppLanguage.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp)
        ) {
            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = AppStrings.get("proxies_title", lang),
                    color = LoonTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Health check button (Pure Lightning Bolt with Animation, no text, no circle background)
                IconButton(
                    onClick = {
                        if (selectedGroupName.isNotBlank()) {
                            onHealthCheck(selectedGroupName)
                        } else if (visibleGroups.isNotEmpty()) {
                            onHealthCheck(visibleGroups.first())
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    AnimatedSpeedTestIcon(
                        isTesting = isAnyTesting,
                        tint = LoonTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 3-Dots Settings Dialog Trigger (•••)
                IconButton(
                    onClick = { showOptionsMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多设置",
                        tint = LoonTextPrimary
                    )
                }
            }
        }

        // Settings BottomSheet (Smooth, modern, non-cluttered bottom sheet)
        if (showOptionsMenu) {
            ModalBottomSheet(
                onDismissRequest = { showOptionsMenu = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = LoonCard,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(LoonTextMuted.copy(alpha = 0.3f))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "策略设置",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoonTextPrimary
                    )

                    // 1. 显示模式 (标签 / 列表)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "显示模式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = LoonTextSecondary
                        )
                        CupertinoSegmentedControl(
                            items = listOf("TABS", "LIST"),
                            selectedItem = currentViewMode,
                            onItemSelected = { onViewModeChanged(it) },
                            labelProvider = {
                                if (it == "TABS") AppStrings.get("proxies_view_tabs", lang) else AppStrings.get("proxies_view_list", lang)
                            }
                        )
                    }

                    // 2. 节点排版 (单列 / 双列 / 三列)
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
                            labelProvider = { "${it}列" }
                        )
                    }

                    // 3. 策略组远程图标开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "显示策略组远程图标",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = LoonTextPrimary
                        )
                        CupertinoSwitch(
                            checked = showGroupIcons,
                            onCheckedChange = { onShowGroupIconsChanged(it) }
                        )
                    }
                }
            }
        }

        // =====================================================================
        // MODE 1: TABS VIEW (High-Performance, Butter Smooth)
        // =====================================================================
        if (!isListMode) {
            // Group Selector Tabs (Scrollable)
            if (visibleGroups.isNotEmpty()) {
                LazyRow(
                    state = groupRowState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleGroups.size, key = { visibleGroups[it] }) { index ->
                        val groupName = visibleGroups[index]
                        val isSelected = groupName == selectedGroupName
                        val groupIconUrl = groupIcons[groupName]

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 0.5.dp,
                                    color = if (isSelected) IosBlue else LoonCardBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .iosPressable { onSelectGroup(groupName) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) IosBlue else LoonCard
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showGroupIcons && !groupIconUrl.isNullOrBlank()) {
                                    RemoteIcon(
                                        url = groupIconUrl,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(end = 6.dp),
                                        contentDescription = groupName
                                    )
                                }
                                Text(
                                    text = groupName,
                                    color = if (isSelected) Color.White else LoonTextPrimary,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            val proxies = currentGroup?.proxies ?: emptyList()

            if (proxies.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (visibleGroups.isEmpty()) "暂无节点数据\n请在[配置]中添加订阅" else "当前策略组无可用节点",
                        color = LoonTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                val chunkedRows = remember(proxies, nodeColumns) {
                    proxies.chunked(nodeColumns.coerceIn(1, 3))
                }
                val currentNow = currentGroup?.now

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = activeTabState,
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = chunkedRows,
                            key = { row -> row.first().name }
                        ) { rowProxies ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (proxy in rowProxies) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        val isItemTesting = testingNodes.contains(proxy.name) || testingNodes.contains("$selectedGroupName:${proxy.name}")
                                        LoonProxyItemCard(
                                            proxy = proxy,
                                            isSelected = proxy.name == currentNow,
                                            columns = nodeColumns,
                                            isTesting = isItemTesting,
                                            onClick = { onSelectProxy(selectedGroupName, proxy) },
                                            onTestSingleNode = { onTestSingleNode(selectedGroupName, proxy.name) }
                                        )
                                    }
                                }
                                // Fill remaining slots in last row if not full
                                val remainder = nodeColumns - rowProxies.size
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
        // =====================================================================
        // MODE 2: FLATTENED 120 FPS LIST VIEW (Zero Frame Drops, Default Collapsed)
        // =====================================================================
        else {
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无策略分组数据",
                        color = LoonTextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listModeState,
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visibleGroups.forEach { groupName ->
                            val group = groupMap[groupName]
                            val isExpanded = expandedGroups[groupName] ?: false
                            val proxies = group?.proxies ?: emptyList()
                            val iconUrl = groupIcons[groupName]

                            item(key = "grp_item_$groupName", contentType = "group_container") {
                                val isGroupTesting = testingNodes.contains(groupName) || proxies.any { testingNodes.contains("$groupName:${it.name}") || (it.isGroup && testingNodes.contains(it.name)) }
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    LoonGroupHeaderCard(
                                        groupName = groupName,
                                        groupType = group?.type ?: "select",
                                        activeNode = group?.now ?: "",
                                        iconUrl = iconUrl,
                                        showGroupIcons = showGroupIcons,
                                        isExpanded = isExpanded,
                                        isTesting = isGroupTesting,
                                        onHealthCheck = {
                                            onHealthCheck(groupName)
                                        },
                                        onToggleExpand = {
                                            expandedGroups[groupName] = !isExpanded
                                        }
                                    )

                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = expandVertically(
                                            expandFrom = Alignment.Top,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy
                                            )
                                        ) + fadeIn(animationSpec = tween(150, delayMillis = 30)),
                                        exit = shrinkVertically(
                                            shrinkTowards = Alignment.Top,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy
                                            )
                                        ) + fadeOut(animationSpec = tween(120)),
                                        modifier = Modifier.clipToBounds()
                                    ) {
                                        val chunkedRows = remember(proxies, nodeColumns) {
                                            proxies.chunked(nodeColumns.coerceIn(1, 3))
                                        }
                                        val groupNow = group?.now

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            for (rowProxies in chunkedRows) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    for (proxy in rowProxies) {
                                                        Box(modifier = Modifier.weight(1f)) {
                                                            val isItemTesting = testingNodes.contains(proxy.name) || testingNodes.contains("$groupName:${proxy.name}")
                                                            LoonProxyItemCard(
                                                                proxy = proxy,
                                                                isSelected = proxy.name == groupNow,
                                                                columns = nodeColumns,
                                                                isTesting = isItemTesting,
                                                                onClick = { onSelectProxy(groupName, proxy) },
                                                                onTestSingleNode = { onTestSingleNode(groupName, proxy.name) }
                                                            )
                                                        }
                                                    }
                                                    val remainder = nodeColumns - rowProxies.size
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

        // Top smooth gradient fade (positioned at the very top of the screen, above content)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(18.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            LoonBg,
                            LoonBg.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

// -----------------------------------------------------------------------------
// Header Card for List Mode (120 FPS Flat & Fast)
// -----------------------------------------------------------------------------
@Composable
private fun LoonGroupHeaderCard(
    groupName: String,
    groupType: String,
    activeNode: String,
    iconUrl: String?,
    showGroupIcons: Boolean,
    isExpanded: Boolean,
    isTesting: Boolean = false,
    onHealthCheck: () -> Unit,
    onToggleExpand: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, LoonCardBorder, RoundedCornerShape(20.dp))
            .clickable { onToggleExpand() },
        color = LoonCard,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        RemoteIcon(
                            url = iconUrl,
                            modifier = Modifier.size(30.dp),
                            contentDescription = groupName
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 1.dp)
                ) {
                    Text(
                        text = groupName,
                        color = LoonTextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (activeNode.isNotBlank()) activeNode else "未选择节点",
                        color = if (activeNode.isNotBlank()) LoonTextSecondary else LoonTextMuted.copy(alpha = 0.6f),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (activeNode.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = groupType.uppercase(),
                    color = LoonTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 6.dp)
                )

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(animationSpec = tween(150)) + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut(animationSpec = tween(120)) + shrinkHorizontally(shrinkTowards = Alignment.End)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (!isTesting) onHealthCheck() },
                            enabled = !isTesting,
                            modifier = Modifier.size(30.dp)
                        ) {
                            AnimatedSpeedTestIcon(
                                isTesting = isTesting,
                                tint = LoonTextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }

                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "arrow_rot"
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = LoonTextSecondary,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Multi-Column Responsive Proxy Node Card (Supports 1, 2, or 3 columns)
// -----------------------------------------------------------------------------
@Composable
private fun LoonProxyItemCard(
    proxy: Proxy,
    isSelected: Boolean,
    columns: Int = 1,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onTestSingleNode: () -> Unit = {}
) {
    val nodeEmoji = extractLeadingEmoji(proxy.name)
    val displayName = if (nodeEmoji != null && proxy.name.startsWith(nodeEmoji)) {
        proxy.name.removePrefix(nodeEmoji).trim()
    } else {
        proxy.name
    }

    val isTimeout = proxy.delay == -2 || proxy.delay >= 65535
    val isValidDelay = proxy.delay in 1..65534
    val delayColor = when {
        isTimeout -> IosRed
        isValidDelay && proxy.delay <= 250 -> IosGreen
        isValidDelay && proxy.delay <= 600 -> IosOrange
        isValidDelay && proxy.delay > 600 -> IosRed
        else -> LoonTextMuted
    }
    val delayText = when {
        isTimeout -> "超时"
        isValidDelay -> "${proxy.delay} ms"
        else -> "--"
    }

    val cardBg = if (isSelected) {
        if (LocalLoonColors.current.isDark) Color(0xFF132238) else Color(0xFFEDF4FF)
    } else LoonCard

    when (columns) {
        // ---------------------------------------------------------------------
        // 1 Column: Standard Horizontal Card (Fixed Height 54dp)
        // ---------------------------------------------------------------------
        1 -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) IosBlue else LoonCardBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .iosPressable { onClick() },
                color = cardBg
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (nodeEmoji != null) {
                            Text(
                                text = nodeEmoji,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(end = 9.dp)
                            )
                        }

                        Column {
                            Text(
                                text = displayName,
                                color = if (isSelected) IosBlue else LoonTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = proxy.type.uppercase(),
                                color = LoonTextMuted,
                                fontSize = 9.5.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 50.dp, minHeight = 24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTestSingleNode() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = IosBlue
                                )
                            } else {
                                Text(
                                    text = delayText,
                                    color = delayColor,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }

                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 2 Columns: Balanced Grid Card (Fixed Height 62dp)
        // ---------------------------------------------------------------------
        2 -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) IosBlue else LoonCardBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .iosPressable { onClick() },
                color = cardBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (nodeEmoji != null) {
                            Text(
                                text = nodeEmoji,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        Text(
                            text = displayName,
                            color = if (isSelected) IosBlue else LoonTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = proxy.type.uppercase(),
                            color = LoonTextMuted,
                            fontSize = 8.5.sp,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTestSingleNode() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.3.dp,
                                    color = IosBlue
                                )
                            } else {
                                Text(
                                    text = delayText,
                                    color = delayColor,
                                    fontSize = 10.sp,
                                    fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3 Columns: High-Density Mini Grid Card (Fixed Height 56dp)
        // ---------------------------------------------------------------------
        else -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) IosBlue else LoonCardBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .iosPressable { onClick() },
                color = cardBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (nodeEmoji != null) {
                            Text(
                                text = nodeEmoji,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(end = 2.dp)
                            )
                        }
                        Text(
                            text = displayName,
                            color = if (isSelected) IosBlue else LoonTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = proxy.type.uppercase(),
                            color = LoonTextMuted,
                            fontSize = 8.sp,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 36.dp, minHeight = 18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTestSingleNode() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(9.dp),
                                    strokeWidth = 1.2.dp,
                                    color = IosBlue
                                )
                            } else {
                                Text(
                                    text = delayText,
                                    color = delayColor,
                                    fontSize = 9.sp,
                                    fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProxiesScreenPreview() {
    val sampleProxies = listOf(
        Proxy(name = "HK - 01 (BGP)", type = "SS", delay = 52),
        Proxy(name = "JP - Tokyo HighSpeed", type = "Vmess", delay = 89),
        Proxy(name = "SG - Singapore Premium", type = "Trojan", delay = 74),
        Proxy(name = "US - Silicon Valley", type = "Shadowsocks", delay = 185)
    )
    val groupProxy = ProxyGroup(
        type = "Selector",
        proxies = sampleProxies,
        now = "HK - 01 (BGP)"
    )
    val groups = listOf("PROXY", "AUTO", "FALLBACK")
    val groupMap = mapOf(
        "PROXY" to groupProxy,
        "AUTO" to ProxyGroup(type = "URLTest", proxies = sampleProxies, now = "HK - 01 (BGP)"),
        "FALLBACK" to ProxyGroup(type = "Fallback", proxies = sampleProxies, now = "HK - 01 (BGP)")
    )
    MihomoTheme {
        ProxiesScreen(
            groups = groups,
            groupMap = groupMap,
            currentGroup = groupProxy,
            selectedGroupName = "PROXY",
            currentViewMode = "TABS",
            onViewModeChanged = {},
            onShowGroupIconsChanged = {},
            onSelectGroup = {},
            onSelectProxy = { _, _ -> },
            onHealthCheck = {}
        )
    }
}
