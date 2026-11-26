@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.MihomoApi
import com.github.mihomo.android.data.SpeedTestState
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoSegmentedControl
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.RemoteIcon
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.LatencySlotState
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.components.statusFadeTransform
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosSelectedCardBg
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonChevronTint
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonOnAccent
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSeparator
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTertiaryBg
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.MihomoTheme
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Stable
data class ProxiesState(
    val groups: List<String>,
    val groupMap: Map<String, ProxyGroup>,
    val currentGroup: ProxyGroup?,
    val selectedGroupName: String,
    val groupIcons: Map<String, String> = emptyMap(),
    val delayCache: Map<String, SpeedTestState> = emptyMap(),
    val testingNodes: Set<String> = emptySet()
)

@Immutable
data class ProxiesDisplayConfig(
    val currentViewMode: String,
    val showGroupIcons: Boolean = true,
    val nodeColumns: Int = 1,
    val nodeSortMode: String = "DEFAULT"
)

@Immutable
data class ProxiesCallbacks(
    val onViewModeChanged: (String) -> Unit,
    val onShowGroupIconsChanged: (Boolean) -> Unit,
    val onNodeColumnsChanged: (Int) -> Unit = {},
    val onNodeSortModeChanged: (String) -> Unit = {},
    val onSelectGroup: (String) -> Unit,
    val onSelectProxy: (group: String, proxy: Proxy) -> Unit,
    val onHealthCheck: (group: String) -> Unit,
    val onTestSingleNode: (group: String, node: String) -> Unit = { _, _ -> }
)

@Stable
class ProxiesScrollState(
    val tabsListState: LazyListState,
    val listModeState: LazyListState,
    val groupRowState: LazyListState,
    val expandedGroups: SnapshotStateMap<String, Boolean>,
    val perGroupTabStates: SnapshotStateMap<String, LazyListState>
)

@Composable
fun rememberProxiesScrollState(): ProxiesScrollState {
    val tabsListState = rememberLazyListState()
    val listModeState = rememberLazyListState()
    val groupRowState = rememberLazyListState()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val perGroupTabStates = remember { mutableStateMapOf<String, LazyListState>() }
    return remember {
        ProxiesScrollState(tabsListState, listModeState, groupRowState, expandedGroups, perGroupTabStates)
    }
}

@Composable
fun AnimatedSpeedTestIcon(
    isTesting: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LoonTextPrimary
) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = isTesting,
        contentAlignment = Alignment.Center,
        transitionSpec = { statusFadeTransform(reduceMotion = reduceMotion, animateSize = false) },
        label = "speed_test_icon"
    ) { testing ->
        if (testing) {
            CupertinoActivityIndicator(modifier = modifier, color = tint)
        } else {
            // The clickable parent owns the semantics; this icon is decorative.
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
    }
}

internal data class IndexedProxy(
    val proxy: Proxy,
    val occurrenceIndex: Int
) {
    val stableKey: String
        get() = "$occurrenceIndex:${proxy.name}"
}

internal fun indexProxiesWithOccurrences(proxies: List<Proxy>): List<IndexedProxy> {
    if (proxies.isEmpty()) return emptyList()
    val seenCounts = HashMap<String, Int>(proxies.size)
    return proxies.map { proxy ->
        val occurrence = seenCounts[proxy.name] ?: 0
        seenCounts[proxy.name] = occurrence + 1
        IndexedProxy(proxy = proxy, occurrenceIndex = occurrence)
    }
}

internal fun sortIndexedProxies(
    proxies: List<Proxy>,
    sortMode: String,
    delayCache: Map<String, SpeedTestState> = emptyMap(),
    groupName: String = ""
): List<IndexedProxy> {
    val indexed = indexProxiesWithOccurrences(proxies)
    return when (sortMode) {
        "DELAY" -> indexed.sortedWith(
            compareBy<IndexedProxy> { item ->
                val state = delayCache["$groupName:${item.proxy.name}"] ?: delayCache[item.proxy.name]
                val d = state?.toLegacyDelay() ?: item.proxy.delay
                when {
                    d in 1..65534 -> 0
                    state is SpeedTestState.Timeout || state is SpeedTestState.Failed || d == -2 || d >= 65535 -> 2
                    else -> 1
                }
            }.thenBy { item ->
                val state = delayCache["$groupName:${item.proxy.name}"] ?: delayCache[item.proxy.name]
                val d = state?.toLegacyDelay() ?: item.proxy.delay
                if (d in 1..65534) d else Int.MAX_VALUE
            }
        )
        "NAME" -> indexed.sortedBy { item -> item.proxy.name.lowercase(Locale.ROOT) }
        else -> indexed
    }
}

internal fun proxyGridRowKey(
    groupName: String,
    cols: Int,
    firstItem: IndexedProxy
): String = "row_${groupName.length}_${groupName}_${cols}_${firstItem.stableKey}"

private fun formatStandardGroupType(rawType: String): String =
    when (rawType.lowercase(Locale.ROOT).replace("_", "-").trim()) {
        "selector", "select" -> "SELECT"
        "urltest", "url-test" -> "URL-TEST"
        "fallback" -> "FALLBACK"
        "loadbalance", "load-balance" -> "LOAD-BALANCE"
        "relay" -> "RELAY"
        else -> rawType.uppercase(Locale.ROOT)
    }

@Composable
private fun ProxiesActiveNodeDetailSheet(
    detailGroup: String,
    detailNode: String,
    state: ProxiesState,
    callbacks: ProxiesCallbacks,
    onDismiss: () -> Unit
) {
    val detailProxy = state.groupMap[detailGroup]?.proxies?.find { proxy -> proxy.name == detailNode }
    val detailDelayState = state.delayCache["$detailGroup:$detailNode"]
        ?: state.delayCache[detailNode]
        ?: SpeedTestState.fromNativeDelay(detailProxy?.delay ?: 0)
    val detailIsTesting = state.testingNodes.contains("$detailGroup:$detailNode") ||
        state.testingNodes.contains("group:$detailGroup")
    NodeDetailSheet(
        groupName = detailGroup,
        nodeName = detailNode,
        group = state.groupMap[detailGroup],
        delayState = detailDelayState,
        isTesting = detailIsTesting,
        onDismiss = onDismiss,
        onSelect = { proxy ->
            onDismiss()
            callbacks.onSelectProxy(detailGroup, proxy)
        },
        onTest = { callbacks.onTestSingleNode(detailGroup, detailNode) }
    )
}

@Composable
fun ProxiesScreen(
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState = rememberProxiesScrollState()
) {
    val haptic = rememberIosHaptic()
    val lang = LocalAppLanguage.current
    val reduceMotion = LocalReduceMotion.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var nodeDetailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val isListMode = display.currentViewMode == "LIST"
    val visibleGroups = remember(state.groups, state.groupMap) {
        state.groups.distinct().filter { gName -> state.groupMap[gName]?.hidden != true }
    }
    val visibleGroupNamesKey = remember(visibleGroups) { visibleGroups.joinToString("\u0001") }

    LaunchedEffect(state.selectedGroupName, visibleGroupNamesKey, isListMode) {
        if (isListMode || state.selectedGroupName.isBlank() || visibleGroups.isEmpty()) return@LaunchedEffect
        val targetIdx = visibleGroups.indexOf(state.selectedGroupName)
        if (targetIdx < 0) return@LaunchedEffect
        val scrollTarget = (targetIdx - 1).coerceIn(0, visibleGroups.lastIndex)
        if (reduceMotion) scrollState.groupRowState.scrollToItem(scrollTarget)
        else scrollState.groupRowState.animateScrollToItem(scrollTarget)
    }

    Column(modifier = Modifier.fillMaxSize().background(LoonBg).statusBarsPadding()) {
        ProxiesScreenHeader(
            lang = lang,
            isSorted = display.nodeSortMode != "DEFAULT",
            onOpenSettings = { haptic.lightTick(); showOptionsMenu = true }
        )
        val bodyModifier = Modifier.fillMaxWidth().weight(1f)
        if (!isListMode) {
            ProxiesTabsModeContent(
                visibleGroups, state, display, callbacks, scrollState, haptic, reduceMotion, lang,
                onLongPressNode = { groupName, nodeName -> nodeDetailTarget = groupName to nodeName },
                modifier = bodyModifier
            )
        } else {
            ProxiesListModeContent(
                visibleGroups, state, display, callbacks, scrollState, haptic, reduceMotion, lang,
                onLongPressNode = { groupName, nodeName -> nodeDetailTarget = groupName to nodeName },
                modifier = bodyModifier
            )
        }
    }

    if (showOptionsMenu) {
        ProxiesSettingsSheet(lang, display, callbacks) { showOptionsMenu = false }
    }
    nodeDetailTarget?.let { (detailGroup, detailNode) ->
        ProxiesActiveNodeDetailSheet(detailGroup, detailNode, state, callbacks) { nodeDetailTarget = null }
    }
}

@Composable
private fun ProxiesScreenHeader(
    lang: String,
    isSorted: Boolean,
    onOpenSettings: () -> Unit
) {
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
                .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .iosPressable(pressedScale = 0.88f, onClick = onOpenSettings),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = AppStrings.get("proxies_settings_desc", lang),
                tint = if (isSorted) IosBlue else LoonTextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxiesSettingsSheet(
    lang: String,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    onDismiss: () -> Unit
) {
    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("proxies_layout_title", lang)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSegmentBlock(
                label = AppStrings.get("proxies_view_mode", lang),
                items = listOf("TABS", "LIST"),
                selectedItem = display.currentViewMode,
                onItemSelected = callbacks.onViewModeChanged
            ) { mode ->
                if (mode == "TABS") {
                    AppStrings.get("proxies_view_tabs", lang)
                } else {
                    AppStrings.get("proxies_view_list", lang)
                }
            }
            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
            SettingsSegmentBlock(
                label = AppStrings.get("proxies_columns", lang),
                items = listOf(1, 2, 3),
                selectedItem = display.nodeColumns,
                onItemSelected = callbacks.onNodeColumnsChanged
            ) { count ->
                when (count) {
                    1 -> AppStrings.get("proxies_column_1", lang)
                    2 -> AppStrings.get("proxies_column_2", lang)
                    else -> AppStrings.get("proxies_column_3", lang)
                }
            }
            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
            SettingsSegmentBlock(
                label = AppStrings.get("proxies_sort", lang),
                items = listOf("DEFAULT", "DELAY", "NAME"),
                selectedItem = display.nodeSortMode,
                onItemSelected = callbacks.onNodeSortModeChanged
            ) { mode ->
                when (mode) {
                    "DELAY" -> AppStrings.get("proxies_sort_delay", lang)
                    "NAME" -> AppStrings.get("proxies_sort_name", lang)
                    else -> AppStrings.get("proxies_sort_default", lang)
                }
            }
            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
            CupertinoSwitchCell(
                title = AppStrings.get("proxies_show_icons", lang),
                subtitle = AppStrings.get("proxies_show_icons_desc", lang),
                checked = display.showGroupIcons,
                onCheckedChange = callbacks.onShowGroupIconsChanged
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun <T> SettingsSegmentBlock(
    label: String,
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: (T) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = LoonTextStyles.callout().copy(fontWeight = FontWeight.Medium),
            color = LoonTextSecondary
        )
        CupertinoSegmentedControl(
            items = items,
            selectedItem = selectedItem,
            onItemSelected = onItemSelected,
            labelProvider = labelProvider
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesTabsModeContent(
    visibleGroups: List<String>,
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState,
    haptic: IosHapticHelper,
    reduceMotion: Boolean,
    lang: String,
    onLongPressNode: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (visibleGroups.isNotEmpty()) {
            LazyRow(
                state = scrollState.groupRowState,
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
                    ProxiesGroupPill(
                        groupName = groupName,
                        iconUrl = state.groupIcons[groupName],
                        isSelected = groupName == state.selectedGroupName,
                        isTesting = state.testingNodes.contains("group:$groupName"),
                        showGroupIcons = display.showGroupIcons,
                        reduceMotion = reduceMotion,
                        lang = lang,
                        onSelect = {
                            if (groupName != state.selectedGroupName) {
                                haptic.selection()
                                callbacks.onSelectGroup(groupName)
                            }
                        },
                        onHealthCheck = {
                            haptic.impact()
                            callbacks.onHealthCheck(groupName)
                        }
                    )
                }
            }
        }

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            ProxiesTabsAnimatedArea(
                visibleGroups = visibleGroups,
                state = state,
                display = display,
                callbacks = callbacks,
                scrollState = scrollState,
                haptic = haptic,
                reduceMotion = reduceMotion,
                lang = lang,
                onLongPressNode = onLongPressNode
            )
        }
    }
}

@Composable
private fun GroupPillHealthCheckButton(
    isSelected: Boolean,
    isTesting: Boolean,
    lang: String,
    onHealthCheck: () -> Unit
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .clickable(
                enabled = !isTesting,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onHealthCheck
            )
            .semantics {
                contentDescription = AppStrings.get("proxies_test", lang)
                role = Role.Button
                if (isTesting) stateDescription = AppStrings.get("node_detail_testing", lang)
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedSpeedTestIcon(
            isTesting = isTesting,
            tint = if (isSelected) LoonOnAccent else IosBlue,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesGroupPill(
    groupName: String,
    iconUrl: String?,
    isSelected: Boolean,
    isTesting: Boolean,
    showGroupIcons: Boolean,
    reduceMotion: Boolean,
    lang: String,
    onSelect: () -> Unit,
    onHealthCheck: () -> Unit
) {
    val colorSpec = if (reduceMotion) snap<Color>() else tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
    val pillBg by animateColorAsState(if (isSelected) IosBlue else LoonCard, colorSpec, label = "pill_bg")
    val pillTextColor by animateColorAsState(if (isSelected) LoonOnAccent else LoonTextPrimary, colorSpec, label = "pill_txt")
    val pillBorderColor by animateColorAsState(if (isSelected) IosBlue else LoonCardBorder, colorSpec, label = "pill_border")
    val pillShape = RoundedCornerShape(LoonRadii.control)

    Surface(
        modifier = Modifier.heightIn(min = 38.dp).clip(pillShape)
            .border(width = 0.5.dp, color = pillBorderColor, shape = pillShape)
            .iosPressable(pressedScale = 0.95f, onClick = onSelect),
        shape = pillShape,
        color = pillBg
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = if (isSelected || isTesting) 4.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showGroupIcons && !iconUrl.isNullOrBlank()) {
                RemoteIcon(url = iconUrl, modifier = Modifier.size(16.dp), contentDescription = null)
            }
            Text(
                text = groupName,
                color = pillTextColor,
                style = LoonTextStyles.calloutLarge().copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1
            )
            if (isSelected || isTesting) {
                GroupPillHealthCheckButton(isSelected, isTesting, lang, onHealthCheck)
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesTabGroupPage(
    targetGroupName: String,
    visibleGroups: List<String>,
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState,
    haptic: IosHapticHelper,
    lang: String,
    onLongPressNode: (String, String) -> Unit
) {
    val targetGroup = if (targetGroupName == state.selectedGroupName) state.currentGroup else state.groupMap[targetGroupName]
    val rawProxies = targetGroup?.proxies.orEmpty()
    val proxies by remember(rawProxies, display.nodeSortMode, targetGroupName, state.delayCache) {
        androidx.compose.runtime.derivedStateOf {
            if (display.nodeSortMode == "DELAY") {
                val scopedDelays = HashMap<String, SpeedTestState>(rawProxies.size * 2)
                for (proxy in rawProxies) {
                    val scopedKey = "$targetGroupName:${proxy.name}"
                    state.delayCache[scopedKey]?.let { scopedDelays[scopedKey] = it }
                    state.delayCache[proxy.name]?.let { scopedDelays[proxy.name] = it }
                }
                sortIndexedProxies(rawProxies, display.nodeSortMode, scopedDelays, targetGroupName)
            } else {
                sortIndexedProxies(rawProxies, display.nodeSortMode, emptyMap(), targetGroupName)
            }
        }
    }
    val groupState = if (targetGroupName.isNotBlank()) {
        scrollState.perGroupTabStates.getOrPut(targetGroupName) { LazyListState() }
    } else scrollState.tabsListState

    if (proxies.isEmpty()) {
        IosEmptyProxiesPlaceholder(
            message = AppStrings.get(if (visibleGroups.isEmpty()) "proxies_empty_no_groups" else "proxies_empty_group", lang)
        )
        return
    }
    val cols = display.nodeColumns.coerceIn(1, 3)
    val chunkedRows = remember(proxies, cols) { proxies.chunked(cols) }
    LazyColumn(
        state = groupState,
        modifier = Modifier.fillMaxSize().bounceOverscroll(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 115.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items = chunkedRows, key = { _, row -> proxyGridRowKey(targetGroupName, cols, row.first()) }) { _, rowProxies ->
            ProxiesGridRow(
                rowProxies, cols, targetGroupName, targetGroup?.now, state.delayCache, state.testingNodes,
                haptic, callbacks.onSelectProxy, callbacks.onTestSingleNode, onLongPressNode
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesTabsAnimatedArea(
    visibleGroups: List<String>,
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState,
    haptic: IosHapticHelper,
    reduceMotion: Boolean,
    lang: String,
    onLongPressNode: (String, String) -> Unit
) {
    AnimatedContent(
        targetState = state.selectedGroupName,
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = visibleGroups.indexOf(targetState) >= visibleGroups.indexOf(initialState)
                val slideIn = slideInHorizontally(
                    initialOffsetX = { fullWidth -> if (forward) fullWidth / 5 else -fullWidth / 5 },
                    animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(LoonMotion.normalMs))
                val slideOut = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> if (forward) -fullWidth / 6 else fullWidth / 6 },
                    animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(LoonMotion.fastMs))
                slideIn togetherWith slideOut
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "group_content_transition"
    ) { targetGroupName ->
        ProxiesTabGroupPage(
            targetGroupName, visibleGroups, state, display, callbacks, scrollState, haptic, lang, onLongPressNode
        )
    }
}

@Suppress("LongParameterList")
private fun androidx.compose.foundation.lazy.LazyListScope.proxyGroupListSection(
    groupName: String,
    cols: Int,
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState,
    haptic: IosHapticHelper,
    reduceMotion: Boolean,
    onLongPressNode: (String, String) -> Unit
) {
    val group = state.groupMap[groupName]
    val isExpanded = scrollState.expandedGroups[groupName] ?: false
    val rawProxies = group?.proxies.orEmpty()
    val groupNow = group?.now.orEmpty()
    item(key = "grp_header_$groupName", contentType = "group_header") {
        LoonGroupHeaderCard(
            ui = GroupHeaderUi(
                groupName, group?.type ?: "select", groupNow, state.groupIcons[groupName],
                display.showGroupIcons, isExpanded, state.testingNodes.contains("group:$groupName")
            ),
            reduceMotion = reduceMotion,
            onHealthCheck = { haptic.impact(); callbacks.onHealthCheck(groupName) },
            onToggleExpand = { haptic.selection(); scrollState.expandedGroups[groupName] = !isExpanded },
            modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                fadeInSpec = null, fadeOutSpec = tween(LoonMotion.fastMs), placementSpec = groupListPlacementSpec
            )
        )
    }
    if (!isExpanded || rawProxies.isEmpty()) return
    // Only sort groups currently expanded so delay ticks don't sort collapsed groups.
    val chunkedRows = sortIndexedProxies(rawProxies, display.nodeSortMode, state.delayCache, groupName).chunked(cols)
    itemsIndexed(
        items = chunkedRows,
        key = { _, row -> proxyGridRowKey(groupName, cols, row.first()) },
        contentType = { _, _ -> "group_node_row" }
    ) { _, rowProxies ->
        val animMod = if (reduceMotion) Modifier else Modifier.animateItem(
            fadeInSpec = tween(LoonMotion.normalMs, delayMillis = 60),
            fadeOutSpec = tween(LoonMotion.fastMs),
            placementSpec = groupListPlacementSpec
        )
        ProxiesGridRow(
            rowProxies, cols, groupName, groupNow, state.delayCache, state.testingNodes, haptic,
            callbacks.onSelectProxy, callbacks.onTestSingleNode, onLongPressNode,
            modifier = animMod
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesListModeContent(
    visibleGroups: List<String>,
    state: ProxiesState,
    display: ProxiesDisplayConfig,
    callbacks: ProxiesCallbacks,
    scrollState: ProxiesScrollState,
    haptic: IosHapticHelper,
    reduceMotion: Boolean,
    lang: String,
    onLongPressNode: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    LoonResponsiveContent(modifier = modifier) {
        if (visibleGroups.isEmpty()) {
            IosEmptyProxiesPlaceholder(message = AppStrings.get("proxies_empty_list_mode", lang))
        } else {
            val cols = display.nodeColumns.coerceIn(1, 3)
            LazyColumn(
                state = scrollState.listModeState,
                modifier = Modifier.fillMaxSize().bounceOverscroll(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 115.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleGroups.forEach { groupName ->
                    proxyGroupListSection(
                        groupName, cols, state, display, callbacks, scrollState, haptic, reduceMotion, onLongPressNode
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProxiesGridRow(
    rowProxies: List<IndexedProxy>,
    cols: Int,
    groupName: String,
    currentNow: String?,
    delayCache: Map<String, SpeedTestState>,
    testingNodes: Set<String>,
    haptic: IosHapticHelper,
    onSelectProxy: (String, Proxy) -> Unit,
    onTestSingleNode: (String, String) -> Unit,
    onLongPress: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (item in rowProxies) {
            key(item.stableKey) {
                val proxy = item.proxy
                Box(modifier = Modifier.weight(1f)) {
                    val delayState = delayCache["$groupName:${proxy.name}"]
                        ?: delayCache[proxy.name]
                        ?: SpeedTestState.fromNativeDelay(proxy.delay)
                    LoonProxyItemCard(
                        proxy = proxy,
                        delayState = delayState,
                        isSelected = proxy.name == currentNow,
                        columns = cols,
                        isTesting = testingNodes.contains("$groupName:${proxy.name}"),
                        onClick = {
                            haptic.selection()
                            onSelectProxy(groupName, proxy)
                        },
                        onTestSingleNode = {
                            haptic.lightTick()
                            onTestSingleNode(groupName, proxy.name)
                        },
                        onLongPress = { onLongPress(groupName, proxy.name) }
                    )
                }
            }
        }
        val remainder = cols - rowProxies.size
        if (remainder > 0) {
            repeat(remainder) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
private fun NodeDetailSheet(
    groupName: String,
    nodeName: String,
    group: ProxyGroup?,
    delayState: SpeedTestState,
    isTesting: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Proxy) -> Unit,
    onTest: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val localProxy = group?.proxies?.find { proxy -> proxy.name == nodeName }
    var detail by remember(nodeName) { mutableStateOf<MihomoApi.ApiProxy?>(null) }

    LaunchedEffect(nodeName) {
        detail = withContext(Dispatchers.IO) { MihomoApi.proxyDetail(nodeName) }
    }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = nodeName,
        doneText = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NodeDetailInfoSection(
                groupName = groupName,
                detail = detail,
                localProxy = localProxy,
                delayState = delayState,
                isTesting = isTesting,
                lang = lang
            )
            NodeDetailHistorySection(
                history = detail?.history.orEmpty().takeLast(12),
                lang = lang
            )
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
private fun NodeDetailInfoSection(
    groupName: String,
    detail: MihomoApi.ApiProxy?,
    localProxy: Proxy?,
    delayState: SpeedTestState,
    isTesting: Boolean,
    lang: String
) {
    CupertinoGroupedSection(title = AppStrings.get("node_detail_title", lang)) {
        val rawType = detail?.type ?: localProxy?.type ?: "—"
        DetailInfoRow(AppStrings.get("node_detail_type", lang), rawType.uppercase(Locale.ROOT))
        CupertinoIndentedDivider()
        val udpSupported = detail?.udp ?: false
        DetailInfoRow(
            AppStrings.get("node_detail_udp", lang),
            AppStrings.get(if (udpSupported) "node_udp_yes" else "node_udp_no", lang),
            valueColor = if (udpSupported) LoonSuccessText else LoonTextSecondary
        )
        CupertinoIndentedDivider()
        val alive = detail?.alive ?: (delayState is SpeedTestState.Success || (localProxy?.delay ?: 0) in 1..65534)
        val isUntested = detail == null && delayState is SpeedTestState.Untested && (localProxy?.delay ?: 0) <= 0
        val (aliveText, aliveColor) = when {
            isTesting -> AppStrings.get("node_detail_testing", lang) to LoonInfoText
            isUntested -> "--" to LoonTextSecondary
            alive -> AppStrings.get("node_detail_alive_ok", lang) to LoonSuccessText
            else -> AppStrings.get("node_detail_alive_dead", lang) to LoonDangerText
        }
        DetailInfoRow(AppStrings.get("node_detail_alive", lang), aliveText, valueColor = aliveColor)
        CupertinoIndentedDivider()
        DetailInfoRow(AppStrings.get("node_detail_groups", lang), groupName)
    }
}

@Composable
private fun NodeDetailHistorySection(
    history: List<Pair<Long, Int>>,
    lang: String
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("HH:mm", locale).withZone(ZoneId.systemDefault())
    }
    CupertinoGroupedSection(title = AppStrings.get("node_detail_history", lang)) {
        if (history.isEmpty()) {
            Text(
                text = AppStrings.get("node_detail_no_history", lang),
                style = LoonTextStyles.callout(),
                color = LoonTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = history,
                    key = { index, item -> "${item.first}_$index" }
                ) { _, (timeMs, delayMs) ->
                    val (color, label) = when {
                        delayMs <= 0 -> LoonDangerText to "×"
                        delayMs <= 250 -> LoonSuccessText to "${delayMs}ms"
                        delayMs <= 600 -> LoonWarningText to "${delayMs}ms"
                        else -> LoonDangerText to "${delayMs}ms"
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            color = color,
                            style = LoonTextStyles.subtitleStrong()
                        )
                        Text(
                            text = timeFormatter.format(Instant.ofEpochMilli(timeMs)),
                            color = LoonTextSecondary,
                            style = LoonTextStyles.micro()
                        )
                    }
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
        Text(text = label, color = LoonTextPrimary, style = LoonTextStyles.body())
        Text(text = value, color = valueColor, style = LoonTextStyles.value())
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(LoonRadii.control)
    Surface(
        modifier = modifier
            .heightIn(min = LoonSizes.touchTargetMin)
            .clip(shape)
            .iosPressable(onClick = onClick),
        shape = shape,
        color = if (emphasized) IosBlue else LoonTertiaryBg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (emphasized) LoonOnAccent else LoonInfoText,
                style = LoonTextStyles.actionLabelStrong()
            )
        }
    }
}

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
            style = LoonTextStyles.value(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

private val groupListPlacementSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

@Immutable
private data class GroupHeaderUi(
    val groupName: String,
    val groupType: String,
    val activeNode: String,
    val iconUrl: String?,
    val showGroupIcons: Boolean,
    val isExpanded: Boolean,
    val isTesting: Boolean
)

@Composable
private fun LoonGroupHeaderCard(
    ui: GroupHeaderUi,
    reduceMotion: Boolean,
    onHealthCheck: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val currentOnHealthCheck by rememberUpdatedState(onHealthCheck)
    val standardType = remember(ui.groupType) { formatStandardGroupType(ui.groupType) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (ui.isExpanded) 90f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = LoonMotion.chevronDampingRatio, stiffness = LoonMotion.chevronStiffness)
        },
        label = "group_chevron_rot"
    )
    val borderColor by animateColorAsState(
        targetValue = if (ui.isExpanded) IosBlue.copy(alpha = 0.35f) else LoonCardBorder,
        animationSpec = if (reduceMotion) snap() else tween(LoonMotion.normalMs),
        label = "group_border_color"
    )
    val cardShape = RoundedCornerShape(LoonRadii.card)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.5.dp, borderColor, cardShape)
            .iosPressable(pressedScale = 1.0f, onClick = onToggleExpand),
        color = LoonCard,
        shape = cardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GroupHeaderTitleBlock(
                groupName = ui.groupName,
                activeNode = ui.activeNode,
                iconUrl = ui.iconUrl,
                showGroupIcons = ui.showGroupIcons,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .padding(end = 10.dp)
            )
            GroupHeaderRightSlot(
                isExpanded = ui.isExpanded,
                isTesting = ui.isTesting,
                standardType = standardType,
                chevronRotation = chevronRotation,
                reduceMotion = reduceMotion,
                lang = lang,
                onHealthCheck = { currentOnHealthCheck() }
            )
        }
    }
}

@Composable
private fun GroupHeaderTitleBlock(
    groupName: String,
    activeNode: String,
    iconUrl: String?,
    showGroupIcons: Boolean,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (showGroupIcons && !iconUrl.isNullOrBlank()) {
            RemoteIcon(
                url = iconUrl,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(32.dp),
                contentDescription = null
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = groupName,
                color = LoonTextPrimary,
                style = LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (activeNode.isNotBlank()) {
                Text(
                    text = activeNode,
                    color = LoonTextSecondary,
                    style = LoonTextStyles.subtitle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun GroupHeaderRightSlot(
    isExpanded: Boolean,
    isTesting: Boolean,
    standardType: String,
    chevronRotation: Float,
    reduceMotion: Boolean,
    lang: String,
    onHealthCheck: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AnimatedContent(
            targetState = isExpanded || isTesting,
            contentAlignment = Alignment.CenterEnd,
            transitionSpec = { statusFadeTransform(reduceMotion = reduceMotion) },
            label = "group_right_slot"
        ) { showSpeedTest ->
            if (showSpeedTest) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onHealthCheck
                        )
                        .semantics {
                            contentDescription = AppStrings.get("proxies_test", lang)
                            role = Role.Button
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
                Box(
                    modifier = Modifier.sizeIn(
                        minWidth = LoonSizes.touchTargetMin,
                        minHeight = LoonSizes.touchTargetMin
                    ),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = standardType,
                        color = LoonTextSecondary,
                        style = LoonTextStyles.subtitle(),
                        maxLines = 1
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) {
                AppStrings.get("collapse_desc", lang)
            } else {
                AppStrings.get("expand_desc", lang)
            },
            tint = LoonChevronTint,
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer { rotationZ = chevronRotation }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun LoonProxyItemCard(
    proxy: Proxy,
    delayState: SpeedTestState = SpeedTestState.fromNativeDelay(proxy.delay),
    isSelected: Boolean,
    columns: Int = 1,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onTestSingleNode: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val cardBg = if (isSelected) IosSelectedCardBg else LoonCard
    val borderColor = if (isSelected) IosBlue.copy(alpha = 0.65f) else LoonCardBorder
    val titleColor = if (isSelected) LoonInfoText else LoonTextPrimary
    val isSingleCol = columns == 1
    val isCompact3Col = columns >= 3
    val cardShape = remember(isCompact3Col) {
        RoundedCornerShape(if (isCompact3Col) LoonRadii.control else LoonRadii.card)
    }
    val haptic = rememberIosHaptic()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSingleCol) {
                    Modifier.defaultMinSize(minHeight = 52.dp)
                } else {
                    Modifier.heightIn(min = if (isCompact3Col) 58.dp else 62.dp)
                }
            )
            .clip(cardShape)
            .border(width = if (isSelected) 1.dp else 0.5.dp, color = borderColor, shape = cardShape)
            .iosPressable(
                pressedScale = 0.97f,
                onLongClick = {
                    haptic.impact()
                    onLongPress()
                },
                onClick = onClick
            ),
        color = cardBg,
        shape = cardShape
    ) {
        if (isSingleCol) {
            ProxySingleColumnContent(
                proxy = proxy,
                titleColor = titleColor,
                isSelected = isSelected,
                delayState = delayState,
                isTesting = isTesting,
                onTestSingleNode = onTestSingleNode
            )
        } else {
            ProxyMultiColumnContent(
                proxy = proxy,
                titleColor = titleColor,
                isSelected = isSelected,
                isCompact3Col = isCompact3Col,
                delayState = delayState,
                isTesting = isTesting,
                onTestSingleNode = onTestSingleNode
            )
        }
    }
}

@Composable
private fun ProxySingleColumnContent(
    proxy: Proxy,
    titleColor: Color,
    isSelected: Boolean,
    delayState: SpeedTestState,
    isTesting: Boolean,
    onTestSingleNode: () -> Unit
) {
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
                style = LoonTextStyles.value().copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = proxy.type.uppercase(Locale.ROOT),
                color = LoonTextSecondary,
                style = LoonTextStyles.caption(),
                maxLines = 1
            )
        }
        CupertinoLatencyText(
            delayState = delayState,
            isTesting = isTesting,
            compact = false,
            onClick = onTestSingleNode
        )
    }
}

@Composable
private fun ProxyMultiColumnContent(
    proxy: Proxy,
    titleColor: Color,
    isSelected: Boolean,
    isCompact3Col: Boolean,
    delayState: SpeedTestState,
    isTesting: Boolean,
    onTestSingleNode: () -> Unit
) {
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
            style = (if (isCompact3Col) LoonTextStyles.subtitle() else LoonTextStyles.callout()).copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            ),
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
                text = proxy.type.uppercase(Locale.ROOT),
                color = LoonTextSecondary,
                style = LoonTextStyles.micro(),
                maxLines = 1
            )
            CupertinoLatencyText(
                delayState = delayState,
                isTesting = isTesting,
                compact = isCompact3Col,
                onClick = onTestSingleNode
            )
        }
    }
}

@Composable
private fun CupertinoLatencyText(
    delayState: SpeedTestState,
    isTesting: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val reduceMotion = LocalReduceMotion.current
    val validDelay = delayState.toLegacyDelay()?.takeIf { value -> value in 1..65534 }
    val displaySlot = when {
        isTesting -> LatencySlotState.Testing
        validDelay != null -> LatencySlotState.Measured(validDelay)
        delayState is SpeedTestState.Timeout -> LatencySlotState.Timeout
        delayState is SpeedTestState.Failed -> LatencySlotState.Failed
        else -> LatencySlotState.Idle
    }

    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = if (compact) 36.dp else LoonSizes.touchTargetMin,
                minHeight = if (compact) 28.dp else 36.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = AppStrings.get("proxies_test", lang)
                role = Role.Button
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedContent(
            targetState = displaySlot,
            contentAlignment = Alignment.CenterEnd,
            transitionSpec = { statusFadeTransform(reduceMotion = reduceMotion) },
            label = "latency_text"
        ) { slot ->
            LatencySlotContent(slot = slot, compact = compact, lang = lang)
        }
    }
}

@Composable
private fun LatencySlotContent(
    slot: LatencySlotState,
    compact: Boolean,
    lang: String
) {
    val textStyle = if (compact) LoonTextStyles.micro() else LoonTextStyles.subtitleStrong()
    when (slot) {
        LatencySlotState.Testing -> CupertinoActivityIndicator(
            modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            color = IosBlue
        )
        is LatencySlotState.Measured -> Text(
            text = if (compact) {
                AppStrings.get("proxies_latency_ms_compact", lang).replace("{ms}", slot.delayMs.toString())
            } else {
                AppStrings.get("proxies_latency_ms", lang).replace("{ms}", slot.delayMs.toString())
            },
            color = when {
                slot.delayMs <= 250 -> LoonSuccessText
                slot.delayMs <= 600 -> LoonWarningText
                else -> LoonDangerText
            },
            style = textStyle,
            maxLines = 1
        )
        LatencySlotState.Timeout -> Text(
            text = AppStrings.get("speed_timeout", lang),
            color = LoonDangerText,
            style = textStyle,
            maxLines = 1
        )
        LatencySlotState.Failed -> Text(
            text = AppStrings.get("speed_failed", lang),
            color = LoonDangerText,
            style = textStyle,
            maxLines = 1
        )
        LatencySlotState.Idle -> Text(
            text = "--",
            color = LoonTextMuted,
            style = textStyle.copy(fontWeight = FontWeight.Normal),
            maxLines = 1
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProxiesScreenPreview() {
    val sampleProxies = listOf(
        Proxy(name = "HK - 01", type = "SS", delay = 42),
        Proxy(name = "JP - Tokyo", type = "VLESS", delay = 89),
        Proxy(name = "SG - Singapore", type = "Trojan", delay = 74),
        Proxy(name = "US - Silicon Valley", type = "Hysteria2", delay = 185)
    )
    val groupProxy = ProxyGroup(type = "Selector", proxies = sampleProxies, now = "HK - 01")
    MihomoTheme {
        ProxiesScreen(
            state = ProxiesState(
                groups = listOf("Proxy", "Auto", "Fallback"),
                groupMap = mapOf("Proxy" to groupProxy),
                currentGroup = groupProxy,
                selectedGroupName = "Proxy"
            ),
            display = ProxiesDisplayConfig(currentViewMode = "TABS"),
            callbacks = ProxiesCallbacks(
                onViewModeChanged = {},
                onShowGroupIconsChanged = {},
                onSelectGroup = {},
                onSelectProxy = { _, _ -> },
                onHealthCheck = {}
            )
        )
    }
}
