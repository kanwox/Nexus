@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.MihomoApi
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*

@Composable
fun AnimatedSpeedTestIcon(
    isTesting: Boolean,
    tint: Color = LoonTextPrimary,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    if (isTesting) {
        CupertinoActivityIndicator(
            modifier = modifier,
            color = IosBlue
        )
    } else {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = AppStrings.get("proxies_test", lang),
            tint = tint,
            modifier = modifier
        )
    }
}

private fun sortProxies(
    proxies: List<Proxy>,
    sortMode: String
): List<Proxy> {
    return when (sortMode) {
        "DELAY" -> proxies.sortedWith(
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
        "NAME" -> proxies.sortedBy { it.name.lowercase() }
        else -> proxies
    }
}

private fun formatStandardGroupType(rawType: String): String {
    return when (rawType.lowercase().replace("_", "-").trim()) {
        "selector", "select" -> "SELECT"
        "urltest", "url-test" -> "URL-TEST"
        "fallback" -> "FALLBACK"
        "loadbalance", "load-balance" -> "LOAD-BALANCE"
        "relay" -> "RELAY"
        else -> rawType.uppercase()
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
    var showOptionsMenu by remember { mutableStateOf(false) }
    // Long-pressed node for the detail sheet: (groupName, nodeName), null = closed.
    var nodeDetailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val isListMode = currentViewMode == "LIST"
    val visibleGroups = remember(groups, groupMap) {
        groups.filter { gName -> groupMap[gName]?.hidden != true }
    }
    // Stable membership signature: groupMap gets a new instance on every delay refresh, and
    // keying the scroll effect on the list identity would re-run the scroll animation (and
    // yank the user back to center) on every speed-test result.
    val visibleGroupNamesKey = remember(visibleGroups) { visibleGroups.joinToString("\u0001") }

    // Automatically center/scroll the selected group tab in Tabs mode
    LaunchedEffect(selectedGroupName, visibleGroupNamesKey, isListMode) {
        if (!isListMode && selectedGroupName.isNotBlank()) {
            val targetIdx = visibleGroups.indexOf(selectedGroupName)
            if (targetIdx >= 0) {
                runCatching {
                    groupRowState.animateScrollToItem(index = (targetIdx - 1).coerceAtLeast(0))
                }
            }
        }
    }

    val lang = LocalAppLanguage.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // =================================================================
        // 1. iOS Large Title Header & Clean Plain Icon Action
        //    (Unified position & typography with SettingsScreen)
        // =================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AppStrings.get("proxies_title", lang),
                color = LoonTextPrimary,
                style = LoonTextStyles.largeTitleBold()
            )

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .iosPressable(pressedScale = 0.88f) {
                        haptic.lightTick()
                        showOptionsMenu = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = AppStrings.get("proxies_settings_desc", lang),
                    tint = if (nodeSortMode != "DEFAULT") IosBlue else LoonTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // =================================================================
        // 2. Strategy Settings Modal BottomSheet (Pure iOS Inset Grouped)
        // =================================================================
        if (showOptionsMenu) {
            CupertinoModalBottomSheet(
                onDismissRequest = { showOptionsMenu = false },
                title = AppStrings.get("proxies_layout_title", lang)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CupertinoGroupedSection(title = AppStrings.get("proxies_layout_section", lang)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = AppStrings.get("proxies_view_mode", lang),
                                    fontSize = LoonTypeScale.callout,
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
                                    text = AppStrings.get("proxies_columns", lang),
                                    fontSize = LoonTypeScale.callout,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextSecondary
                                )
                                CupertinoSegmentedControl(
                                    items = listOf(1, 2, 3),
                                    selectedItem = nodeColumns,
                                    onItemSelected = { onNodeColumnsChanged(it) },
                                    labelProvider = {
                                        when (it) {
                                            1 -> AppStrings.get("proxies_column_1", lang)
                                            2 -> AppStrings.get("proxies_column_2", lang)
                                            else -> AppStrings.get("proxies_column_3", lang)
                                        }
                                    }
                                )
                            }

                            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = AppStrings.get("proxies_sort", lang),
                                    fontSize = LoonTypeScale.callout,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextSecondary
                                )
                                CupertinoSegmentedControl(
                                    items = listOf("DEFAULT", "DELAY", "NAME"),
                                    selectedItem = nodeSortMode,
                                    onItemSelected = { onNodeSortModeChanged(it) },
                                    labelProvider = {
                                        when (it) {
                                            "DELAY" -> AppStrings.get("proxies_sort_delay", lang)
                                            "NAME" -> AppStrings.get("proxies_sort_name", lang)
                                            else -> AppStrings.get("proxies_sort_default", lang)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    CupertinoGroupedSection(title = AppStrings.get("proxies_display_section", lang)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = AppStrings.get("proxies_show_icons", lang),
                                    fontSize = LoonTypeScale.body,
                                    fontWeight = FontWeight.Normal,
                                    color = LoonTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = AppStrings.get("proxies_show_icons_desc", lang),
                                    fontSize = LoonTypeScale.footnote,
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
        // MODE 1: TABS VIEW (Pill Bar + Animated Group Content)
        // =================================================================
        if (!isListMode) {
            if (visibleGroups.isNotEmpty()) {
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
                        val isPillTesting = testingNodes.contains("group:$groupName")
                        val groupIconUrl = groupIcons[groupName]

                        val pillBg by animateColorAsState(
                            targetValue = if (isSelected) IosBlue else LoonCard,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "pill_bg"
                        )
                        val pillTextColor by animateColorAsState(
                            targetValue = if (isSelected) Color.White else LoonTextPrimary,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "pill_txt"
                        )
                        val pillBorderColor by animateColorAsState(
                            targetValue = if (isSelected) IosBlue else LoonCardBorder,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "pill_border"
                        )

                        Surface(
                            modifier = Modifier
                                .height(35.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = 0.5.dp,
                                    color = pillBorderColor,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .iosPressable(pressedScale = 0.95f) {
                                    if (groupName != selectedGroupName) {
                                        haptic.selection()
                                        onSelectGroup(groupName)
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
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
                                        modifier = Modifier.size(16.dp),
                                        contentDescription = groupName
                                    )
                                }
                                Text(
                                    text = groupName,
                                    color = pillTextColor,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = LoonTypeScale.calloutLarge,
                                    maxLines = 1
                                )
                                if (isSelected || isPillTesting) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable(
                                                enabled = !isPillTesting,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                haptic.impact()
                                                onHealthCheck(groupName)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AnimatedSpeedTestIcon(
                                            isTesting = isPillTesting,
                                            tint = if (isSelected) Color.White else IosBlue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
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
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(180))) togetherWith (slideOutHorizontally(
                        targetOffsetX = slideExitOffset,
                        animationSpec = tween(190, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(140)))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
                label = "group_content_transition"
            ) { targetGroupName ->
                val targetGroup = if (targetGroupName == selectedGroupName) {
                    currentGroup
                } else {
                    groupMap[targetGroupName]
                }
                val rawProxies = targetGroup?.proxies ?: emptyList()
                val proxies = remember(rawProxies, nodeSortMode) {
                    sortProxies(rawProxies, nodeSortMode)
                }
                val groupState = if (targetGroupName.isNotBlank()) {
                    perGroupTabStates.getOrPut(targetGroupName) { LazyListState() }
                } else tabsListState

                if (proxies.isEmpty()) {
                    IosEmptyProxiesPlaceholder(
                        message = if (visibleGroups.isEmpty()) {
                            AppStrings.get("proxies_empty_no_groups", lang)
                        } else {
                            AppStrings.get("proxies_empty_group", lang)
                        }
                    )
                } else {
                    val currentNow = targetGroup?.now
                    val cols = nodeColumns.coerceIn(1, 3)
                    val chunkedRows = remember(proxies, cols) {
                        proxies.chunked(cols)
                    }
                    LazyColumn(
                        state = groupState,
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 2.dp,
                            bottom = 115.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = chunkedRows,
                            // Row identity must not depend on rowIndex: under DELAY sorting the
                            // order shifts as results arrive, and index-based keys would
                            // discard and recompose every node card.
                            key = { _, row -> "${targetGroupName}_grid_${cols}_${row.first().name}" }
                        ) { _, rowProxies ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (proxy in rowProxies) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        val isItemTesting = testingNodes.contains("$targetGroupName:${proxy.name}")
                                        LoonProxyItemCard(
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
                                            },
                                            onLongPress = {
                                                nodeDetailTarget = targetGroupName to proxy.name
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
        // =================================================================
        // MODE 2: ACCORDION LIST VIEW (Slim Strategy Group Cards)
        // =================================================================
        else {
            if (visibleGroups.isEmpty()) {
                IosEmptyProxiesPlaceholder(message = AppStrings.get("proxies_empty_list_mode", lang))
            } else {
                val cols = nodeColumns.coerceIn(1, 3)
                LazyColumn(
                    state = listModeState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .bounceOverscroll(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 2.dp,
                        bottom = 115.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleGroups.forEach { groupName ->
                        val group = groupMap[groupName]
                        val isExpanded = expandedGroups[groupName] ?: false
                        val rawProxies = group?.proxies ?: emptyList()
                        val iconUrl = groupIcons[groupName]

                        item(key = "grp_header_$groupName", contentType = "group_header") {
                            val proxies = sortProxies(rawProxies, nodeSortMode)
                            val isGroupTesting = testingNodes.contains("group:$groupName")
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
                                    val chunkedRows = remember(proxies, cols) {
                                        proxies.chunked(cols)
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        for (rowProxies in chunkedRows) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                for (proxy in rowProxies) {
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        val isItemTesting = testingNodes.contains("$groupName:${proxy.name}")
                                                        LoonProxyItemCard(
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
                                                            },
                                                            onLongPress = {
                                                                nodeDetailTarget = groupName to proxy.name
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

    // =================================================================
    // Node Detail Sheet (long-press): protocol info + latency history
    // =================================================================
    nodeDetailTarget?.let { (detailGroup, detailNode) ->
        NodeDetailSheet(
            groupName = detailGroup,
            nodeName = detailNode,
            group = groupMap[detailGroup],
            onDismiss = { nodeDetailTarget = null },
            onSelect = { proxy ->
                nodeDetailTarget = null
                onSelectProxy(detailGroup, proxy)
            },
            onTest = {
                onTestSingleNode(detailGroup, detailNode)
            }
        )
    }
}

// =============================================================================
// Node Detail Sheet: live protocol/availability/latency history from the core's
// external-controller API, falling back to the locally parsed profile data.
// =============================================================================
@Composable
private fun NodeDetailSheet(
    groupName: String,
    nodeName: String,
    group: ProxyGroup?,
    onDismiss: () -> Unit,
    onSelect: (Proxy) -> Unit,
    onTest: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val localProxy = group?.proxies?.find { it.name == nodeName }
    var detail by remember(nodeName) { mutableStateOf<MihomoApi.ApiProxy?>(null) }

    LaunchedEffect(nodeName) {
        detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MihomoApi.proxyDetail(nodeName)
        }
    }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = nodeName,
        doneText = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CupertinoGroupedSection(title = AppStrings.get("node_detail_title", lang)) {
                DetailInfoRow(AppStrings.get("node_detail_type", lang), (detail?.type ?: localProxy?.type ?: "—").uppercase())
                CupertinoIndentedDivider()
                val udpSupported = detail?.udp ?: false
                DetailInfoRow(
                    AppStrings.get("node_detail_udp", lang),
                    AppStrings.get(if (udpSupported) "node_udp_yes" else "node_udp_no", lang),
                    valueColor = if (udpSupported) LoonSuccessText else LoonTextSecondary
                )
                CupertinoIndentedDivider()
                val alive = detail?.alive ?: ((localProxy?.delay ?: 0) > 0)
                DetailInfoRow(
                    AppStrings.get("node_detail_alive", lang),
                    AppStrings.get(if (alive) "node_detail_alive_ok" else "node_detail_alive_dead", lang),
                    valueColor = if (alive) LoonSuccessText else LoonDangerText
                )
                CupertinoIndentedDivider()
                DetailInfoRow(AppStrings.get("node_detail_groups", lang), groupName)
            }

            CupertinoGroupedSection(title = AppStrings.get("node_detail_history", lang)) {
                val history = detail?.history.orEmpty().takeLast(12)
                if (history.isEmpty()) {
                    Text(
                        text = AppStrings.get("node_detail_no_history", lang),
                        fontSize = LoonTypeScale.callout,
                        color = LoonTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history.size) { index ->
                            val (timeMs, delay) = history[index]
                            val (color, label) = when {
                                delay <= 0 -> LoonDangerText to "×"
                                delay <= 250 -> LoonSuccessText to "${delay}ms"
                                delay <= 600 -> LoonWarningText to "${delay}ms"
                                else -> LoonDangerText to "${delay}ms"
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = label, color = color, fontSize = LoonTypeScale.footnote, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                        .format(java.util.Date(timeMs)),
                                    color = LoonTextSecondary,
                                    fontSize = LoonTypeScale.micro
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DetailActionButton(
                    label = AppStrings.get("proxies_test", lang),
                    emphasized = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onTest()
                        onDismiss()
                    }
                )
                if (group != null && localProxy != null) {
                    DetailActionButton(
                        label = AppStrings.get("node_select", lang),
                        emphasized = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(localProxy) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String, valueColor: Color = LoonTextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = LoonTextPrimary, fontSize = LoonTypeScale.body)
        Text(text = value, color = valueColor, fontSize = LoonTypeScale.subhead)
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (emphasized) IosBlue else LoonTertiaryBg,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (emphasized) Color.White else IosBlue,
                fontSize = LoonTypeScale.title3,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
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
        Text(
            text = message,
            color = LoonTextSecondary,
            fontSize = LoonTypeScale.subhead,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

// =============================================================================
// Slim Strategy Group Header Card for List Mode
// Collapsed right side: Standard English Group Type (SELECT / URL-TEST / ...)
// Expanded right side: Type disappears and that exact position becomes Speed Test
// =============================================================================
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
    val lang = LocalAppLanguage.current
    val currentOnHealthCheck by rememberUpdatedState(onHealthCheck)
    val standardType = remember(groupType) { formatStandardGroupType(groupType) }
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
        animationSpec = tween(180),
        label = "group_border_color"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(12.dp))
            .iosPressable(pressedScale = 0.985f) { onToggleExpand() },
        color = LoonCard,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                    RemoteIcon(
                        url = iconUrl,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(32.dp),
                        contentDescription = groupName
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = groupName,
                        color = LoonTextPrimary,
                        fontSize = LoonTypeScale.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (activeNode.isNotBlank()) {
                        Text(
                            text = activeNode,
                            color = LoonTextSecondary,
                            fontSize = LoonTypeScale.footnote,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Collapsed -> Standard English type name; Expanded -> Speed Test button in the exact same position
                AnimatedContent(
                    targetState = isExpanded || isTesting,
                    transitionSpec = {
                        fadeIn(tween(150)) togetherWith fadeOut(tween(120))
                    },
                    label = "group_right_slot"
                ) { showSpeedTest ->
                    if (showSpeedTest) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clickable(
                                    enabled = !isTesting,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    currentOnHealthCheck()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedSpeedTestIcon(
                                isTesting = isTesting,
                                tint = IosBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Text(
                            text = standardType,
                            color = LoonTextSecondary,
                            fontSize = LoonTypeScale.captionLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) AppStrings.get("collapse_desc", lang) else AppStrings.get("expand_desc", lang),
                    tint = LoonChevronTint,
                    modifier = Modifier
                        .size(17.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }
        }
    }
}

// =============================================================================
// Unified Node Card (1, 2, and 3 Columns)
// Strictly adheres to UI_GUIDELINES.md:
// - No left vertical blue bar when selected
// - No fake icon boxes or emoji/letter extraction
// - No colored background blocks behind protocol names or latency numbers
// =============================================================================
@Composable
private fun LoonProxyItemCard(
    proxy: Proxy,
    isSelected: Boolean,
    columns: Int = 1,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onTestSingleNode: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val isDark = LocalLoonColors.current.isDark
    val cardBg = if (isSelected) {
        IosSelectedCardBg
    } else {
        LoonCard
    }
    val borderColor = if (isSelected) IosBlue.copy(alpha = 0.65f) else LoonCardBorder
    val titleColor = if (isSelected) LoonInfoText else LoonTextPrimary

    val isSingleCol = columns == 1
    val isCompact3Col = columns >= 3
    val cardShape = remember(isCompact3Col) { RoundedCornerShape(if (isCompact3Col) 11.dp else 13.dp) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSingleCol) Modifier.defaultMinSize(minHeight = 52.dp)
                else Modifier.height(if (isCompact3Col) 58.dp else 62.dp)
            )
            .clip(cardShape)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = borderColor,
                shape = cardShape
            )
            // Long press opens the node detail sheet; the regular tap still goes to the
            // card's own pressable handler.
            .pointerInput(proxy.name) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .iosPressable(pressedScale = 0.97f) { onClick() },
        color = cardBg,
        shape = cardShape
    ) {
        if (isSingleCol) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = proxy.name,
                        color = titleColor,
                        fontSize = LoonTypeScale.subheadLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = proxy.type.uppercase(),
                        color = LoonTextSecondary,
                        fontSize = LoonTypeScale.caption,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                }

                CupertinoLatencyText(
                    delay = proxy.delay,
                    isTesting = isTesting,
                    compact = false,
                    onClick = onTestSingleNode
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (isCompact3Col) 9.dp else 12.dp,
                        vertical = if (isCompact3Col) 7.dp else 8.dp
                    ),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = proxy.name,
                    color = titleColor,
                    fontSize = if (isCompact3Col) 11.5.sp else 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = proxy.type.uppercase(),
                        color = LoonTextSecondary,
                        fontSize = if (isCompact3Col) 9.5.sp else 10.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )

                    CupertinoLatencyText(
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
// Plain Semantic Latency Text (Tap to Single-Test, No Background Chip)
// =============================================================================
@Composable
private fun CupertinoLatencyText(
    delay: Int,
    isTesting: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val isValidDelay = delay in 1..65534
    val delayColor = when {
        isValidDelay && delay <= 250 -> LoonSuccessText
        isValidDelay && delay <= 600 -> LoonWarningText
        else -> LoonDangerText
    }

    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = if (compact) 34.dp else 44.dp,
                minHeight = if (compact) 18.dp else 24.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.CenterEnd
    ) {
        if (isTesting) {
            CupertinoActivityIndicator(
                modifier = Modifier.size(if (compact) 11.dp else 13.dp),
                color = IosBlue
            )
        } else if (isValidDelay) {
            Text(
                text = if (compact) "${delay}ms" else "$delay ms",
                color = delayColor,
                fontSize = if (compact) 10.sp else 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
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
