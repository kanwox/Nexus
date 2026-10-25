package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.core.util.trafficString
import com.github.mihomo.android.data.RequestRecord
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private enum class RequestFilter(val labelKey: String) {
    ALL("log_level_all"),
    ACTIVE("filter_active"),
    PROXY("filter_proxy"),
    DIRECT("filter_direct")
}

private enum class RequestProtocolFilter {
    ALL, TCP, UDP
}

/** One deduped (packageName, sourceApp) entry for the app filter sheet; null package = system. */
private data class RequestAppOption(val packageName: String?, val label: String)

private fun buildRequestAppOptions(records: List<RequestRecord>): List<RequestAppOption> {
    val options = ArrayList<RequestAppOption>()
    val seen = HashSet<Pair<String, String>>()
    var systemAdded = false
    for (record in records) {
        val pkg = record.packageName
        if (pkg == null) {
            // All records without a package name collapse into a single "system" entry.
            if (!systemAdded) {
                systemAdded = true
                options.add(RequestAppOption(null, record.sourceApp))
            }
        } else if (seen.add(pkg to record.sourceApp)) {
            options.add(RequestAppOption(pkg, record.sourceApp))
        }
    }
    return options
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun RequestLogsScreen(
    vpnStatus: VpnState.Status,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()
    val records by RequestRepository.recordsFlow.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(RequestFilter.ALL) }
    var selectedProtocol by remember { mutableStateOf(RequestProtocolFilter.ALL) }
    var selectedApp by remember { mutableStateOf<RequestAppOption?>(null) }
    var showFilterActionSheet by remember { mutableStateOf(false) }
    var showAppFilterSheet by remember { mutableStateOf(false) }
    var showClearActionSheet by remember { mutableStateOf(false) }
    var activeDetailRecord by remember { mutableStateOf<RequestRecord?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val detailTimeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // Filter records based on status, protocol, source app and fluid search
    val filteredRecords = remember(records, searchQuery, selectedFilter, selectedProtocol, selectedApp) {
        val query = searchQuery.trim().lowercase()
        val appFilter = selectedApp
        records.filter { record ->
            // Status filter
            val filterMatch = when (selectedFilter) {
                RequestFilter.ALL -> true
                RequestFilter.ACTIVE -> record.isActive
                RequestFilter.PROXY -> !record.outbound.equals("DIRECT", ignoreCase = true) && !record.outbound.equals("REJECT", ignoreCase = true)
                RequestFilter.DIRECT -> record.outbound.equals("DIRECT", ignoreCase = true)
            }
            if (!filterMatch) return@filter false

            // Protocol filter
            val protocolMatch = selectedProtocol == RequestProtocolFilter.ALL ||
                record.network.equals(selectedProtocol.name, ignoreCase = true)
            if (!protocolMatch) return@filter false

            // Source app filter
            val appMatch = when {
                appFilter == null -> true
                appFilter.packageName == null -> record.packageName == null
                else -> record.packageName == appFilter.packageName && record.sourceApp == appFilter.label
            }
            if (!appMatch) return@filter false

            // Search query match
            if (query.isEmpty()) return@filter true
            record.host.lowercase().contains(query) ||
                record.destinationIp.lowercase().contains(query) ||
                record.sourceApp.lowercase().contains(query) ||
                record.outbound.lowercase().contains(query) ||
                record.rule.lowercase().contains(query) ||
                record.rulePayload.lowercase().contains(query)
        }
    }

    val activeCount = remember(records) { records.count { it.isActive } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Navigation Bar: iOS centered title, back button, trailing filter and clear buttons
        CupertinoNavBar(
            title = AppStrings.get("requests_screen_title", lang),
            showBackButton = true,
            onBack = onBack,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val filterActive = selectedFilter != RequestFilter.ALL ||
                        selectedProtocol != RequestProtocolFilter.ALL ||
                        selectedApp != null

                    // Filter Action Button
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .iosPressable {
                                haptic.selection()
                                showFilterActionSheet = true
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = AppStrings.get("requests_filter_desc", lang),
                                tint = if (filterActive) IosBlue else LoonTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            if (selectedFilter != RequestFilter.ALL) {
                                Text(
                                    text = AppStrings.get(selectedFilter.labelKey, lang),
                                    color = IosBlue,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (records.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .iosPressable {
                                    haptic.selection()
                                    showClearActionSheet = true
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = AppStrings.get("action_clear", lang),
                                color = RedDanger,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        )

        // Main Scroll Area: Fluid search & Grouped records
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            // Fluid Search Bar (scrolls smoothly out of screen when browsing down)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    CupertinoSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = AppStrings.get("requests_search_placeholder", lang)
                    )
                }
            }

            // Protocol filter row (All / TCP / UDP), coexists with search and status filters
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    CupertinoSegmentedControl(
                        items = RequestProtocolFilter.entries.toList(),
                        selectedItem = selectedProtocol,
                        onItemSelected = { protocol -> selectedProtocol = protocol },
                        labelProvider = { protocol ->
                            if (protocol == RequestProtocolFilter.ALL) {
                                AppStrings.get("requests_protocol_all", lang)
                            } else {
                                protocol.name
                            }
                        }
                    )
                }
            }

            // List Content or Empty State
            if (filteredRecords.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = null,
                                tint = LoonTextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) AppStrings.get("dns_empty_search", lang) else AppStrings.get("requests_empty", lang),
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                val appFilter = selectedApp
                val sectionHeader = when {
                    searchQuery.isNotBlank() -> AppStrings.get("search_results", lang).replace("{n}", filteredRecords.size.toString())
                    appFilter != null -> "${appFilter.label} (${filteredRecords.size})"
                    selectedFilter == RequestFilter.ACTIVE -> AppStrings.get("requests_section_active", lang).replace("{n}", filteredRecords.size.toString())
                    selectedFilter == RequestFilter.PROXY -> AppStrings.get("requests_section_proxy", lang).replace("{n}", filteredRecords.size.toString())
                    selectedFilter == RequestFilter.DIRECT -> AppStrings.get("requests_section_direct", lang).replace("{n}", filteredRecords.size.toString())
                    activeCount > 0 -> AppStrings.get("requests_section_all_active", lang).replace("{n}", filteredRecords.size.toString()).replace("{m}", activeCount.toString())
                    else -> AppStrings.get("requests_section_all", lang).replace("{n}", filteredRecords.size.toString())
                }

                cupertinoGroupedItems(
                    items = filteredRecords,
                    header = sectionHeader,
                    key = { _, record -> record.id },
                    dividerStartIndent = 16.dp
                ) { _, record ->
                    val formattedTime = remember(record.timestamp) {
                        timeFormat.format(Date(record.timestamp))
                    }

                    RequestRecordCell(
                        record = record,
                        formattedTime = formattedTime,
                        onClick = {
                            haptic.lightTick()
                            activeDetailRecord = record
                        }
                    )
                }
            }
        }
    }

    // Detail Inspector: Pure iOS CupertinoModalBottomSheet
    if (activeDetailRecord != null) {
        val rec = activeDetailRecord!!
        val formattedDetailTime = remember(rec.timestamp) {
            detailTimeFormat.format(Date(rec.timestamp))
        }

        CupertinoModalBottomSheet(
            onDismissRequest = { activeDetailRecord = null },
            title = AppStrings.get("requests_detail_title", lang),
            trailingAction = {
                if (rec.isActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(IosGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = AppStrings.get("requests_status_active", lang),
                            color = IosGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = AppStrings.get("requests_status_closed", lang),
                        color = LoonTextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                // Card 1: 目标与网络
                Text(
                    text = AppStrings.get("requests_target_section", lang),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LoonBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    DetailRow(
                        label = AppStrings.get("requests_field_host", lang),
                        value = rec.host,
                        showCopy = true,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(rec.host))
                            CupertinoToast.show(AppStrings.get("dns_copied_domain", lang))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_endpoint", lang),
                        value = "${rec.destinationIp}:${rec.destinationPort}",
                        showCopy = true,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString("${rec.destinationIp}:${rec.destinationPort}"))
                            CupertinoToast.show(AppStrings.get("copied_endpoint_toast", lang))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_network", lang),
                        value = rec.network
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: 分流与策略
                Text(
                    text = AppStrings.get("requests_policy_section", lang),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LoonBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    val ruleDescription = if (rec.rulePayload.isNotBlank()) {
                        "${rec.rule} (${rec.rulePayload})"
                    } else {
                        rec.rule
                    }
                    DetailRow(
                        label = AppStrings.get("requests_field_rule", lang),
                        value = ruleDescription
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_node", lang),
                        value = rec.outbound,
                        valueColor = when {
                            rec.outbound.equals("REJECT", ignoreCase = true) -> RedDanger
                            rec.outbound.equals("DIRECT", ignoreCase = true) -> LoonTextSecondary
                            else -> IosBlue
                        }
                    )
                    if (rec.chain.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(
                            label = AppStrings.get("requests_field_chain", lang),
                            value = rec.chain.joinToString(" ➔ ")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 3: 来源与流量统计
                Text(
                    text = AppStrings.get("requests_source_section", lang),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LoonBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    val appDisplay = if (!rec.packageName.isNullOrBlank()) {
                        "${rec.sourceApp} (${rec.packageName})"
                    } else {
                        rec.sourceApp
                    }
                    DetailRow(
                        label = AppStrings.get("requests_field_app", lang),
                        value = appDisplay
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_traffic", lang),
                        value = "↑ ${trafficString(rec.uploadBytes)}   ↓ ${trafficString(rec.downloadBytes)}"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_duration", lang),
                        value = if (rec.isActive) AppStrings.get("requests_status_active", lang) else formatDuration(rec.durationMs, lang)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("requests_field_time", lang),
                        value = formattedDetailTime
                    )
                }

                // Close Connection Action Button (if connection is active)
                if (rec.isActive) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(RedDanger.copy(alpha = 0.12f))
                            .iosPressable {
                                haptic.impact()
                                val recordId = rec.id
                                activeDetailRecord = null
                                coroutineScope.launch {
                                    val closed = RequestRepository.closeConnection(recordId)
                                    if (closed) {
                                        CupertinoToast.show(AppStrings.get("requests_close_success", lang))
                                    } else {
                                        CupertinoToast.show(
                                            AppStrings.get("requests_close_failed", lang),
                                            isDestructive = true
                                        )
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = AppStrings.get("requests_close_action", lang),
                            color = RedDanger,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // iOS Cupertino Action Sheet for Clearing Records
    if (showClearActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showClearActionSheet = false },
            title = AppStrings.get("requests_clear_title", lang),
            message = AppStrings.get("requests_clear_message", lang),
            actions = listOf(
                CupertinoActionSheetItem(
                    text = AppStrings.get("requests_clear_closed", lang),
                    onClick = {
                        haptic.lightTick()
                        showClearActionSheet = false
                        RequestRepository.clearClosed()
                        CupertinoToast.show(AppStrings.get("requests_cleared_closed_toast", lang))
                    }
                ),
                CupertinoActionSheetItem(
                    text = AppStrings.get("requests_clear_all", lang),
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        showClearActionSheet = false
                        RequestRepository.clearAll()
                        CupertinoToast.show(AppStrings.get("requests_cleared_all_toast", lang), isDestructive = true)
                    }
                )
            ),
            cancelText = AppStrings.get("cancel", lang)
        )
    }

    // iOS Cupertino Action Sheet for Request Filtering
    if (showFilterActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showFilterActionSheet = false },
            title = AppStrings.get("requests_filter_title", lang),
            message = AppStrings.get("requests_filter_message", lang).replace("{a}", filteredRecords.size.toString()).replace("{b}", records.size.toString()),
            actions = RequestFilter.entries.map { filter ->
                val count = when (filter) {
                    RequestFilter.ALL -> records.size
                    RequestFilter.ACTIVE -> activeCount
                    RequestFilter.PROXY -> records.count { !it.outbound.equals("DIRECT", ignoreCase = true) && !it.outbound.equals("REJECT", ignoreCase = true) }
                    RequestFilter.DIRECT -> records.count { it.outbound.equals("DIRECT", ignoreCase = true) }
                }
                CupertinoActionSheetItem(
                    text = AppStrings.get(filter.labelKey, lang) + " ($count)" + if (filter == selectedFilter) "  ✓" else "",
                    onClick = {
                        haptic.selection()
                        selectedFilter = filter
                        showFilterActionSheet = false
                    }
                )
            } + listOf(
                CupertinoActionSheetItem(
                    text = AppStrings.get("requests_filter_app", lang) + (selectedApp?.let { " · ${it.label}" } ?: ""),
                    onClick = {
                        haptic.selection()
                        showFilterActionSheet = false
                        showAppFilterSheet = true
                    }
                )
            ),
            cancelText = AppStrings.get("cancel", lang)
        )
    }

    // App filter sheet: deduped (packageName, sourceApp) entries seen in the current records
    if (showAppFilterSheet) {
        val appOptions = remember(records) { buildRequestAppOptions(records) }
        CupertinoModalBottomSheet(
            onDismissRequest = { showAppFilterSheet = false },
            title = AppStrings.get("requests_filter_app", lang),
            doneText = null
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .bounceOverscroll(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
            ) {
                item {
                    AppFilterOptionRow(
                        label = AppStrings.get("requests_filter_all_apps", lang),
                        subtitle = null,
                        isSelected = selectedApp == null,
                        onClick = {
                            haptic.selection()
                            selectedApp = null
                            showAppFilterSheet = false
                        }
                    )
                    CupertinoIndentedDivider(startIndent = 16.dp)
                }
                items(
                    count = appOptions.size,
                    key = { index -> "${appOptions[index].packageName}|${appOptions[index].label}" }
                ) { index ->
                    val option = appOptions[index]
                    AppFilterOptionRow(
                        label = option.label,
                        subtitle = option.packageName,
                        isSelected = option == selectedApp,
                        onClick = {
                            haptic.selection()
                            selectedApp = option
                            showAppFilterSheet = false
                        }
                    )
                    if (index < appOptions.lastIndex) {
                        CupertinoIndentedDivider(startIndent = 16.dp)
                    }
                }
            }
        }
    }
}

/**
 * Pure, high signal-to-noise ratio Request Record Row.
 * Preserves full width for domains, linear typography with middots, zero rectangular tag noise.
 */
@Composable
private fun RequestRecordCell(
    record: RequestRecord,
    formattedTime: String,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val (outboundColor, outboundText) = when {
        record.outbound.equals("REJECT", ignoreCase = true) -> Pair(RedDanger, "REJECT")
        record.outbound.equals("DIRECT", ignoreCase = true) -> Pair(LoonTextSecondary, "DIRECT")
        else -> Pair(IosBlue, record.outbound)
    }

    val displayHost = if ((record.destinationPort != 443) && (record.destinationPort != 80)) {
        "${record.host}:${record.destinationPort}"
    } else {
        record.host
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        // Line 1: Target Endpoint + Outbound Node / Rule policy name
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayHost,
                color = LoonTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = outboundText,
                color = outboundColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Line 2: Source App · Traffic · Protocol + Active Green Dot or Completion Time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = record.sourceApp,
                    color = LoonTextSecondary,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = " · ",
                    color = LoonTextMuted,
                    fontSize = 12.5.sp
                )

                val midText = if (record.uploadBytes > 0L || record.downloadBytes > 0L) {
                    "↑ ${trafficString(record.uploadBytes)}  ↓ ${trafficString(record.downloadBytes)}"
                } else if (record.rulePayload.isNotBlank()) {
                    "${record.rule}(${record.rulePayload})"
                } else {
                    record.rule
                }

                Text(
                    text = midText,
                    color = LoonTextSecondary,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = " · ",
                    color = LoonTextMuted,
                    fontSize = 12.5.sp
                )

                Text(
                    text = record.network,
                    color = LoonTextMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (record.isActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(IosGreen)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = AppStrings.get("filter_active", lang),
                        color = IosGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = formattedTime,
                    color = LoonTextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Clean detail row for the iOS Inspector sheet.
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = LoonTextPrimary,
    showCopy: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    val lang = LocalAppLanguage.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = LoonTextSecondary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f, fill = false)
        ) {
            Text(
                text = value,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (showCopy && onCopy != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = AppStrings.get("copy_desc", lang),
                    tint = IosBlue,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable(onClick = onCopy)
                )
            }
        }
    }
}

/**
 * One selectable row inside the app filter sheet (iOS grouped-list row with check mark).
 */
@Composable
private fun AppFilterOptionRow(
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isSelected) IosBlue else LoonTextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = LoonTextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
}

/**
 * Format duration in milliseconds into human-readable string.
 */
private fun formatDuration(ms: Long, lang: String): String {
    if (ms <= 0) return AppStrings.get("duration_lt_second", lang)
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> AppStrings.get("duration_hm", lang).replace("{h}", (seconds / 3600).toString()).replace("{m}", ((seconds % 3600) / 60).toString())
        seconds >= 60 -> AppStrings.get("duration_ms", lang).replace("{m}", (seconds / 60).toString()).replace("{s}", (seconds % 60).toString())
        else -> AppStrings.get("duration_s", lang).replace("{s}", seconds.toString())
    }
}

@Preview(showBackground = true)
@Composable
fun RequestLogsScreenPreview() {
    RequestLogsScreen(
        vpnStatus = VpnState.Status.RUNNING,
        onBack = {}
    )
}

@Preview(showBackground = true)
@Composable
fun RequestRecordCellPreview() {
    val sampleRecord = RequestRecord(
        id = 1L,
        host = "www.youtube.com",
        destinationIp = "198.18.0.12",
        destinationPort = 443,
        network = "TCP",
        sourceApp = "YouTube",
        packageName = "com.google.android.youtube",
        rule = "Match",
        rulePayload = "Google",
        outbound = "🇭🇰 香港 01",
        chain = listOf("🔰 节点选择", "🇭🇰 香港 01"),
        uploadBytes = 127_400L,
        downloadBytes = 8_840_000L,
        isActive = true,
        timestamp = System.currentTimeMillis() - 5_000L
    )
    RequestRecordCell(
        record = sampleRecord,
        formattedTime = "12:00:00",
        onClick = {}
    )
}
