package com.github.mihomo.android.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.github.mihomo.android.ui.components.RemoteIcon
import com.github.mihomo.android.ui.theme.*

@Composable
fun AnimatedSpeedTestIcon(
    isTesting: Boolean,
    tint: Color = LoonTextPrimary,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.Bolt,
        contentDescription = "测速",
        tint = tint,
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
    onTestSingleNode: (group: String, node: String) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showOptionsMenu by remember { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 100.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
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
                if (!isListMode && selectedGroupName.isNotBlank()) {
                    Text(
                        text = "$selectedGroupName (${currentGroup?.proxies?.size ?: 0})",
                        color = LoonTextSecondary,
                        fontSize = 12.sp
                    )
                } else if (isListMode) {
                    Text(
                        text = "${visibleGroups.size} groups",
                        color = LoonTextSecondary,
                        fontSize = 12.sp
                    )
                }
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

        // Settings Dialog (Modal with manual close, does not auto-dismiss on toggles!)
        if (showOptionsMenu) {
            Dialog(onDismissRequest = { showOptionsMenu = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = LoonCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Header with title and close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "策略与节点设置",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = LoonTextPrimary
                            )
                            IconButton(
                                onClick = { showOptionsMenu = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = LoonTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 1. 显示模式 (标签 / 列表)
                        Text(
                            text = "显示模式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LoonTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val modes = listOf("TABS" to AppStrings.get("proxies_view_tabs", lang), "LIST" to AppStrings.get("proxies_view_list", lang))
                            modes.forEach { (mode, label) ->
                                val isSel = currentViewMode == mode
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            if (isSel) LoonBlue else LoonCardBorder,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onViewModeChanged(mode) },
                                    color = if (isSel) LoonEditBlueBg else LoonCard,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) LoonBlue else LoonTextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 2. 节点排版 (单列 / 双列 / 三列)
                        Text(
                            text = "节点排版 (每行显示节点数)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LoonTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val cols = listOf(1 to "单列\n(1行1个)", 2 to "双列\n(1行2个)", 3 to "三列\n(1行3个)")
                            cols.forEach { (colCount, label) ->
                                val isSel = nodeColumns == colCount
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            if (isSel) LoonBlue else LoonCardBorder,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onNodeColumnsChanged(colCount) },
                                    color = if (isSel) LoonEditBlueBg else LoonCard,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) LoonBlue else LoonTextPrimary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 3. 策略组远程图标开关
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = LoonCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "显示策略组远程图标",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LoonTextPrimary
                                    )
                                    Text(
                                        text = "根据配置或脚本匹配远程图标集",
                                        fontSize = 11.sp,
                                        color = LoonTextMuted
                                    )
                                }
                                Switch(
                                    checked = showGroupIcons,
                                    onCheckedChange = { onShowGroupIconsChanged(it) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Manual Done Button
                        Button(
                            onClick = { showOptionsMenu = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LoonBlue)
                        ) {
                            Text(
                                text = "完成",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectGroup(groupName) }
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) LoonBlue else LoonCardBorder,
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) LoonEditBlueBg else LoonCard
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showGroupIcons && !groupIconUrl.isNullOrBlank()) {
                                    RemoteIcon(
                                        url = groupIconUrl,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .padding(end = 6.dp),
                                        contentDescription = groupName
                                    )
                                }
                                Text(
                                    text = groupName,
                                    color = if (isSelected) LoonBlue else LoonTextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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

                LazyColumn(
                    modifier = Modifier.weight(1f),
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
                                    val isItemTesting = testingNodes.contains("$selectedGroupName:${proxy.name}") || (proxy.isGroup && testingNodes.contains(proxy.name))
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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleGroups.forEach { groupName ->
                        val group = groupMap[groupName]
                        val isExpanded = expandedGroups[groupName] ?: false
                        val proxies = group?.proxies ?: emptyList()
                        val iconUrl = groupIcons[groupName]

                        // 1. Group Header Item
                        item(key = "hdr_$groupName", contentType = "group_header") {
                            val isGroupTesting = testingNodes.contains(groupName) || proxies.any { testingNodes.contains("$groupName:${it.name}") || (it.isGroup && testingNodes.contains(it.name)) }
                            LoonGroupHeaderCard(
                                groupName = groupName,
                                groupType = group?.type ?: "select",
                                activeNode = group?.now ?: "",
                                nodeCount = proxies.size,
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
                        }

                        // 2. If expanded, emit chunked proxy rows directly
                        if (isExpanded) {
                            val chunkedRows = proxies.chunked(nodeColumns.coerceIn(1, 3))
                            val groupNow = group?.now

                            items(
                                items = chunkedRows,
                                key = { row -> "prx_${groupName}_${row.first().name}" }
                            ) { rowProxies ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (proxy in rowProxies) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            val isItemTesting = testingNodes.contains("$groupName:${proxy.name}") || (proxy.isGroup && testingNodes.contains(proxy.name))
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

// -----------------------------------------------------------------------------
// Header Card for List Mode (120 FPS Flat & Fast)
// -----------------------------------------------------------------------------
@Composable
private fun LoonGroupHeaderCard(
    groupName: String,
    groupType: String,
    activeNode: String,
    nodeCount: Int,
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
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, LoonCardBorder, RoundedCornerShape(16.dp))
            .clickable { onToggleExpand() },
        color = LoonCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        RemoteIcon(
                            url = iconUrl,
                            modifier = Modifier.size(32.dp),
                            contentDescription = groupName
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = groupName,
                        color = LoonTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (activeNode.isNotBlank()) activeNode else "未选择节点",
                        color = if (activeNode.isNotBlank()) LoonTextSecondary else LoonTextMuted.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
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
                if (isTesting) {
                    AnimatedSpeedTestIcon(
                        isTesting = true,
                        tint = LoonTextPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (isExpanded) {
                    IconButton(
                        onClick = onHealthCheck,
                        modifier = Modifier.size(28.dp)
                    ) {
                        AnimatedSpeedTestIcon(
                            isTesting = false,
                            tint = LoonTextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    Text(
                        text = groupType.uppercase(),
                        color = LoonTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = LoonTextSecondary,
                    modifier = Modifier.size(20.dp)
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
        isTimeout -> RedDanger
        isValidDelay && proxy.delay <= 250 -> LoonGreen
        isValidDelay && proxy.delay <= 600 -> Color(0xFFF59E0B)
        isValidDelay && proxy.delay > 600 -> RedDanger
        else -> LoonTextMuted
    }
    val delayText = when {
        isTimeout -> "超时"
        isValidDelay -> "${proxy.delay} ms"
        else -> "--"
    }

    when (columns) {
        // ---------------------------------------------------------------------
        // 1 Column: Standard Horizontal Card (Fixed Height 58dp to prevent any jitter)
        // ---------------------------------------------------------------------
        1 -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) LoonBlue else LoonCardBorder,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { onClick() },
                color = LoonCard
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
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
                                text = displayName,
                                color = if (isSelected) LoonBlue else LoonTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
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

                    // Pure Speed Test Text / Spinner (No background capsule, centered, no ripple artifact)
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 54.dp, minHeight = 24.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { onTestSingleNode() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.6.dp,
                                color = LoonBlue
                            )
                        } else {
                            Text(
                                text = delayText,
                                color = delayColor,
                                fontSize = 12.sp,
                                fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 2 Columns: Balanced Grid Card (Fixed Height 68dp to prevent any jitter)
        // ---------------------------------------------------------------------
        2 -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) LoonBlue else LoonCardBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onClick() },
                color = LoonCard
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
                                fontSize = 14.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        Text(
                            text = displayName,
                            color = if (isSelected) LoonBlue else LoonTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
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
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 46.dp, minHeight = 20.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { onTestSingleNode() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(11.dp),
                                    strokeWidth = 1.4.dp,
                                    color = LoonBlue
                                )
                            } else {
                                Text(
                                    text = delayText,
                                    color = delayColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isValidDelay || isTimeout) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3 Columns: High-Density Mini Grid Card (Fixed Height 56dp to prevent any jitter)
        // ---------------------------------------------------------------------
        else -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) LoonBlue else LoonCardBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onClick() },
                color = LoonCard
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
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
                            color = if (isSelected) LoonBlue else LoonTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
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
                            fontSize = 8.sp,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 38.dp, minHeight = 18.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { onTestSingleNode() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(9.dp),
                                    strokeWidth = 1.2.dp,
                                    color = LoonBlue
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
