package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.kr328.clash.core.util.trafficString
import com.github.mihomo.android.data.RequestRecord
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoDetailRow
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoSegmentedControl
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardCopy
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

private data class RequestFilterCounts(
    val activeCount: Int,
    val proxyCount: Int,
    val directCount: Int
)

private fun buildRequestAppOptions(
    records: List<RequestRecord>,
    systemLabel: String
): List<RequestAppOption> {
    val options = ArrayList<RequestAppOption>()
    val seen = HashSet<Pair<String, String>>()
    var systemAdded = false
    for (record in records) {
        val pkg = record.packageName
        if (pkg == null) {
            if (!systemAdded) {
                systemAdded = true
                options.add(RequestAppOption(null, systemLabel))
            }
        } else if (seen.add(pkg to record.sourceApp)) {
            options.add(RequestAppOption(pkg, record.sourceApp))
        }
    }
    return options
}

private val PROXY_EXCLUDED_OUTBOUNDS = setOf("DIRECT", "REJECT")

private fun filterRequestRecords(
    records: List<RequestRecord>,
    searchQuery: String,
    selectedFilter: RequestFilter,
    selectedProtocol: RequestProtocolFilter,
    selectedApp: RequestAppOption?
): List<RequestRecord> {
    val query = searchQuery.trim().lowercase(Locale.ROOT)
    return records.filter { record ->
        val filterMatch = when (selectedFilter) {
            RequestFilter.ALL -> true
            RequestFilter.ACTIVE -> record.isActive
            RequestFilter.PROXY -> record.outbound.uppercase(Locale.ROOT) !in PROXY_EXCLUDED_OUTBOUNDS
            RequestFilter.DIRECT -> record.outbound.equals("DIRECT", ignoreCase = true)
        }
        if (!filterMatch) return@filter false

        val protocolMatch = selectedProtocol == RequestProtocolFilter.ALL ||
            record.network.equals(selectedProtocol.name, ignoreCase = true)
        if (!protocolMatch) return@filter false

        val appMatch = when {
            selectedApp == null -> true
            selectedApp.packageName == null -> record.packageName == null
            else -> record.packageName == selectedApp.packageName && record.sourceApp == selectedApp.label
        }
        if (!appMatch) return@filter false

        if (query.isEmpty()) return@filter true
        record.host.contains(query, ignoreCase = true) ||
            record.destinationIp.contains(query, ignoreCase = true) ||
            record.sourceApp.contains(query, ignoreCase = true) ||
            record.outbound.contains(query, ignoreCase = true) ||
            record.rule.contains(query, ignoreCase = true) ||
            record.rulePayload.contains(query, ignoreCase = true)
    }
}

private class RequestLogsUiState {
    var searchQuery by mutableStateOf("")
    var selectedFilter by mutableStateOf(RequestFilter.ALL)
    var selectedProtocol by mutableStateOf(RequestProtocolFilter.ALL)
    var selectedApp by mutableStateOf<RequestAppOption?>(null)
    var showFilterActionSheet by mutableStateOf(false)
    var showAppFilterSheet by mutableStateOf(false)
    var showClearActionSheet by mutableStateOf(false)
    var activeDetailRecordId by mutableStateOf<Long?>(null)
}

private data class RequestFilterUiState(
    val records: List<RequestRecord>,
    val filteredSize: Int,
    val counts: RequestFilterCounts,
    val selectedFilter: RequestFilter,
    val selectedApp: RequestAppOption?,
    val showFilterSheet: Boolean,
    val showAppFilterSheet: Boolean
)

private data class RequestFilterSheetSummary(
    val recordsSize: Int,
    val filteredSize: Int,
    val counts: RequestFilterCounts,
    val selectedFilter: RequestFilter,
    val selectedApp: RequestAppOption?
)

private data class RequestFilterSheetCallbacks(
    val onSelectFilter: (RequestFilter) -> Unit,
    val onOpenAppFilter: () -> Unit,
    val onDismissFilter: () -> Unit,
    val onSelectApp: (RequestAppOption?) -> Unit,
    val onDismissAppFilter: () -> Unit
)

private data class RequestLogsListData(
    val selectedFilter: RequestFilter,
    val selectedProtocol: RequestProtocolFilter,
    val selectedApp: RequestAppOption?,
    val filteredRecords: List<RequestRecord>,
    val activeCount: Int,
    val timeFormat: DateTimeFormatter
)

private data class RequestLogFormatters(
    val timeFormat: DateTimeFormatter,
    val detailTimeFormat: DateTimeFormatter
)

@Composable
private fun rememberRequestLogFormatters(): RequestLogFormatters {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("HH:mm:ss", locale).withZone(ZoneId.systemDefault())
    }
    val detailTimeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", locale).withZone(ZoneId.systemDefault())
    }
    return remember(timeFormat, detailTimeFormat) {
        RequestLogFormatters(timeFormat = timeFormat, detailTimeFormat = detailTimeFormat)
    }
}

private fun computeRequestFilterCounts(records: List<RequestRecord>): RequestFilterCounts {
    var active = 0
    var proxy = 0
    var direct = 0
    for (record in records) {
        if (record.isActive) active++
        when {
            record.outbound.equals("DIRECT", ignoreCase = true) -> direct++
            !record.outbound.equals("REJECT", ignoreCase = true) -> proxy++
        }
    }
    return RequestFilterCounts(activeCount = active, proxyCount = proxy, directCount = direct)
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun RequestLogsScreen(
    vpnStatus: VpnState.Status,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val copyToClipboard = rememberClipboardCopy()
    val haptic = rememberIosHaptic()
    val records by RequestRepository.recordsFlow.collectAsStateWithLifecycle()
    val ui = remember { RequestLogsUiState() }
    val formatters = rememberRequestLogFormatters()

    val filteredRecords = remember(records, ui.searchQuery, ui.selectedFilter, ui.selectedProtocol, ui.selectedApp) {
        filterRequestRecords(records, ui.searchQuery, ui.selectedFilter, ui.selectedProtocol, ui.selectedApp)
    }
    val filterCounts = remember(records) { computeRequestFilterCounts(records) }
    val activeDetailRecord = remember(records, ui.activeDetailRecordId) {
        ui.activeDetailRecordId?.let { id -> records.firstOrNull { item -> item.id == id } }
    }

    Column(modifier = Modifier.fillMaxSize().background(LoonBg).statusBarsPadding()) {
        RequestLogsNavBar(
            lang = lang,
            selectedFilter = ui.selectedFilter,
            filterActive = ui.selectedFilter != RequestFilter.ALL ||
                ui.selectedProtocol != RequestProtocolFilter.ALL ||
                ui.selectedApp != null,
            hasRecords = records.isNotEmpty(),
            onBack = onBack,
            onOpenFilter = { haptic.selection(); ui.showFilterActionSheet = true },
            onOpenClear = { haptic.selection(); ui.showClearActionSheet = true }
        )

        LoonResponsiveContent(modifier = Modifier.fillMaxWidth().weight(1f)) {
            RequestLogsListContent(
                lang = lang,
                searchQuery = ui.searchQuery,
                listData = RequestLogsListData(
                    selectedFilter = ui.selectedFilter,
                    selectedProtocol = ui.selectedProtocol,
                    selectedApp = ui.selectedApp,
                    filteredRecords = filteredRecords,
                    activeCount = filterCounts.activeCount,
                    timeFormat = formatters.timeFormat
                ),
                onSearchQueryChange = { query -> ui.searchQuery = query },
                onSelectProtocol = { protocol -> ui.selectedProtocol = protocol },
                onSelectRecord = { id -> haptic.lightTick(); ui.activeDetailRecordId = id }
            )
        }
    }

    RequestLogsDetailAndClearSheets(
        lang = lang,
        activeDetailRecord = activeDetailRecord,
        detailTimeFormat = formatters.detailTimeFormat,
        showClearActionSheet = ui.showClearActionSheet,
        onCopy = { text -> copyToClipboard(text) },
        onDismissDetail = { ui.activeDetailRecordId = null },
        onDismissClear = { ui.showClearActionSheet = false }
    )
    RequestLogsFilterSheetsHost(lang, ui, haptic, records, filteredRecords.size, filterCounts)
}

@Composable
private fun RequestLogsFilterSheetsHost(
    lang: String,
    ui: RequestLogsUiState,
    haptic: IosHapticHelper,
    records: List<RequestRecord>,
    filteredSize: Int,
    filterCounts: RequestFilterCounts
) {
    RequestLogsFilterSheets(
        lang = lang,
        state = RequestFilterUiState(
            records = records,
            filteredSize = filteredSize,
            counts = filterCounts,
            selectedFilter = ui.selectedFilter,
            selectedApp = ui.selectedApp,
            showFilterSheet = ui.showFilterActionSheet,
            showAppFilterSheet = ui.showAppFilterSheet
        ),
        callbacks = RequestFilterSheetCallbacks(
            onSelectFilter = { filter -> haptic.selection(); ui.selectedFilter = filter; ui.showFilterActionSheet = false },
            onOpenAppFilter = { haptic.selection(); ui.showFilterActionSheet = false; ui.showAppFilterSheet = true },
            onDismissFilter = { ui.showFilterActionSheet = false },
            onSelectApp = { option -> haptic.selection(); ui.selectedApp = option; ui.showAppFilterSheet = false },
            onDismissAppFilter = { ui.showAppFilterSheet = false }
        )
    )
}

@Composable
private fun RequestLogsDetailAndClearSheets(
    lang: String,
    activeDetailRecord: RequestRecord?,
    detailTimeFormat: DateTimeFormatter,
    showClearActionSheet: Boolean,
    onCopy: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onDismissClear: () -> Unit
) {
    activeDetailRecord?.let { rec ->
        RequestDetailSheet(
            record = rec,
            lang = lang,
            detailTimeFormat = detailTimeFormat,
            onCopy = onCopy,
            onDismiss = onDismissDetail
        )
    }
    if (showClearActionSheet) {
        RequestClearActionSheet(lang = lang, onDismiss = onDismissClear)
    }
}

@Composable
private fun RequestLogsFilterSheets(
    lang: String,
    state: RequestFilterUiState,
    callbacks: RequestFilterSheetCallbacks
) {
    if (state.showFilterSheet) {
        RequestFilterActionSheet(
            lang = lang,
            summary = RequestFilterSheetSummary(
                recordsSize = state.records.size,
                filteredSize = state.filteredSize,
                counts = state.counts,
                selectedFilter = state.selectedFilter,
                selectedApp = state.selectedApp
            ),
            onSelectFilter = callbacks.onSelectFilter,
            onOpenAppFilter = callbacks.onOpenAppFilter,
            onDismiss = callbacks.onDismissFilter
        )
    }
    if (state.showAppFilterSheet) {
        RequestAppFilterSheet(
            lang = lang,
            records = state.records,
            selectedApp = state.selectedApp,
            onSelectApp = callbacks.onSelectApp,
            onDismiss = callbacks.onDismissAppFilter
        )
    }
}

@Composable
private fun RequestLogsNavBar(
    lang: String,
    selectedFilter: RequestFilter,
    filterActive: Boolean,
    hasRecords: Boolean,
    onBack: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenClear: () -> Unit
) {
    val actionShape = RoundedCornerShape(LoonRadii.control)
    CupertinoNavBar(
        title = AppStrings.get("requests_screen_title", lang),
        showBackButton = true,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                        .clip(actionShape)
                        .iosFocusRing(actionShape)
                        .iosPressable(onClick = onOpenFilter)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LoonSpacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = AppStrings.get("requests_filter_desc", lang),
                            tint = if (filterActive) IosBlue else LoonTextSecondary,
                            modifier = Modifier.size(LoonSizes.icon)
                        )
                        if (selectedFilter != RequestFilter.ALL) {
                            Text(
                                text = AppStrings.get(selectedFilter.labelKey, lang),
                                color = LoonInfoText,
                                style = LoonTextStyles.calloutLarge().copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }

                if (hasRecords) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                            .clip(actionShape)
                            .iosFocusRing(actionShape)
                            .iosPressable(onClick = onOpenClear)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = AppStrings.get("action_clear", lang),
                            color = LoonDangerText,
                            style = LoonTextStyles.button()
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun RequestLogsListContent(
    lang: String,
    searchQuery: String,
    listData: RequestLogsListData,
    onSearchQueryChange: (String) -> Unit,
    onSelectProtocol: (RequestProtocolFilter) -> Unit,
    onSelectRecord: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        requestLogsControlItems(
            lang = lang,
            searchQuery = searchQuery,
            selectedProtocol = listData.selectedProtocol,
            onSearchQueryChange = onSearchQueryChange,
            onSelectProtocol = onSelectProtocol
        )

        if (listData.filteredRecords.isEmpty()) {
            item(key = "requests_empty") {
                RequestLogsEmptyState(lang = lang, searchQuery = searchQuery)
            }
        } else {
            val sectionHeader = resolveRequestSectionHeader(
                lang = lang,
                searchQuery = searchQuery,
                selectedFilter = listData.selectedFilter,
                selectedApp = listData.selectedApp,
                filteredCount = listData.filteredRecords.size,
                activeCount = listData.activeCount
            )
            cupertinoGroupedItems(
                items = listData.filteredRecords,
                header = sectionHeader,
                key = { _, record -> record.id },
                dividerStartIndent = 16.dp
            ) { _, record ->
                val formattedTime = remember(record.timestamp, listData.timeFormat) {
                    listData.timeFormat.format(Instant.ofEpochMilli(record.timestamp))
                }
                RequestRecordCell(
                    record = record,
                    formattedTime = formattedTime,
                    onClick = { onSelectRecord(record.id) }
                )
            }
        }
    }
}

private fun LazyListScope.requestLogsControlItems(
    lang: String,
    searchQuery: String,
    selectedProtocol: RequestProtocolFilter,
    onSearchQueryChange: (String) -> Unit,
    onSelectProtocol: (RequestProtocolFilter) -> Unit
) {
    item(key = "requests_search_bar") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            CupertinoSearchBar(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = AppStrings.get("requests_search_placeholder", lang)
            )
        }
    }

    item(key = "requests_protocol_segment") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
        ) {
            CupertinoSegmentedControl(
                items = RequestProtocolFilter.entries.toList(),
                selectedItem = selectedProtocol,
                onItemSelected = onSelectProtocol,
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
}

@Composable
private fun RequestLogsEmptyState(
    lang: String,
    searchQuery: String
) {
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
            Spacer(modifier = Modifier.height(LoonSpacing.md))
            Text(
                text = if (searchQuery.isNotBlank()) AppStrings.get("dns_empty_search", lang)
                else AppStrings.get("requests_empty", lang),
                color = LoonTextSecondary,
                style = LoonTextStyles.body()
            )
        }
    }
}

private fun resolveRequestSectionHeader(
    lang: String,
    searchQuery: String,
    selectedFilter: RequestFilter,
    selectedApp: RequestAppOption?,
    filteredCount: Int,
    activeCount: Int
): String = when {
    searchQuery.isNotBlank() -> AppStrings.get("search_results", lang).replace("{n}", filteredCount.toString())
    selectedApp != null -> "${selectedApp.label} ($filteredCount)"
    selectedFilter == RequestFilter.ACTIVE -> AppStrings.get("requests_section_active", lang).replace("{n}", filteredCount.toString())
    selectedFilter == RequestFilter.PROXY -> AppStrings.get("requests_section_proxy", lang).replace("{n}", filteredCount.toString())
    selectedFilter == RequestFilter.DIRECT -> AppStrings.get("requests_section_direct", lang).replace("{n}", filteredCount.toString())
    activeCount > 0 -> AppStrings.get("requests_section_all_active", lang)
        .replace("{n}", filteredCount.toString())
        .replace("{m}", activeCount.toString())
    else -> AppStrings.get("requests_section_all", lang).replace("{n}", filteredCount.toString())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestDetailSheet(
    record: RequestRecord,
    lang: String,
    detailTimeFormat: DateTimeFormatter,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val scope = rememberCoroutineScope()
    var isClosing by remember(record.id) { mutableStateOf(false) }
    val formattedDetailTime = remember(record.timestamp, detailTimeFormat) {
        detailTimeFormat.format(Instant.ofEpochMilli(record.timestamp))
    }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("requests_detail_title", lang),
        trailingAction = { RequestDetailTrailingStatus(isActive = record.isActive, lang = lang) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LoonSpacing.xl)
                .padding(bottom = LoonSpacing.sm)
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            RequestDetailTargetCard(record = record, lang = lang, onCopy = onCopy)
            Spacer(modifier = Modifier.height(14.dp))
            RequestDetailPolicyCard(record = record, lang = lang)
            Spacer(modifier = Modifier.height(14.dp))
            RequestDetailSourceCard(
                record = record,
                formattedDetailTime = formattedDetailTime,
                lang = lang
            )

            if (record.isActive) {
                Spacer(modifier = Modifier.height(18.dp))
                val closeShape = RoundedCornerShape(LoonRadii.control)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LoonSizes.touchTargetMin)
                        .clip(closeShape)
                        .iosFocusRing(closeShape)
                        .background(LoonDangerText.copy(alpha = 0.12f))
                        .iosPressable(enabled = !isClosing) {
                            haptic.impact()
                            isClosing = true
                            scope.launch {
                                try {
                                    val closed = RequestRepository.closeConnection(record.id)
                                    isClosing = false
                                    if (closed) {
                                        onDismiss()
                                        CupertinoToast.show(AppStrings.get("requests_close_success", lang))
                                    } else {
                                        CupertinoToast.show(
                                            AppStrings.get("requests_close_failed", lang),
                                            isDestructive = true
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    isClosing = false
                                    throw e
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.get("requests_close_action", lang),
                        color = LoonDangerText,
                        style = LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestDetailTrailingStatus(isActive: Boolean, lang: String) {
    if (isActive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(IosGreen)
            )
            Spacer(modifier = Modifier.width(LoonSpacing.xs))
            Text(
                text = AppStrings.get("requests_status_active", lang),
                color = LoonSuccessText,
                style = LoonTextStyles.subtitleStrong()
            )
        }
    } else {
        Text(
            text = AppStrings.get("requests_status_closed", lang),
            color = LoonTextMuted,
            style = LoonTextStyles.subtitle()
        )
    }
}

@Composable
private fun RequestDetailTargetCard(
    record: RequestRecord,
    lang: String,
    onCopy: (String) -> Unit
) {
    val formattedEndpoint = formatHostPort(record.destinationIp, record.destinationPort)
    Text(
        text = AppStrings.get("requests_target_section", lang),
        style = LoonTextStyles.subtitleStrong(),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LoonRadii.control))
            .background(LoonBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(LoonSpacing.sm)
    ) {
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_host", lang),
            value = record.host,
            showCopy = true,
            onCopy = {
                onCopy(record.host)
                CupertinoToast.show(AppStrings.get("dns_copied_domain", lang))
            }
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_endpoint", lang),
            value = formattedEndpoint,
            showCopy = true,
            onCopy = {
                onCopy(formattedEndpoint)
                CupertinoToast.show(AppStrings.get("copied_endpoint_toast", lang))
            }
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_network", lang),
            value = record.network
        )
    }
}

@Composable
private fun RequestDetailPolicyCard(record: RequestRecord, lang: String) {
    val ruleDescription = if (record.rulePayload.isNotBlank()) {
        "${record.rule} (${record.rulePayload})"
    } else {
        record.rule
    }
    Text(
        text = AppStrings.get("requests_policy_section", lang),
        style = LoonTextStyles.subtitleStrong(),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LoonRadii.control))
            .background(LoonBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(LoonSpacing.sm)
    ) {
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_rule", lang),
            value = ruleDescription
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_node", lang),
            value = record.outbound,
            valueColor = when {
                record.outbound.equals("REJECT", ignoreCase = true) -> LoonDangerText
                record.outbound.equals("DIRECT", ignoreCase = true) -> LoonTextSecondary
                else -> LoonInfoText
            }
        )
        if (record.chain.size > 1) {
            CupertinoDetailRow(
                label = AppStrings.get("requests_field_chain", lang),
                value = record.chain.joinToString(" ➔ ")
            )
        }
    }
}

@Composable
private fun RequestDetailSourceCard(
    record: RequestRecord,
    formattedDetailTime: String,
    lang: String
) {
    val systemLabel = AppStrings.get("system_app", lang)
    val appDisplay = if (!record.packageName.isNullOrBlank()) {
        AppStrings.get("requests_app_with_package", lang)
            .replace("{app}", record.sourceApp.ifBlank { record.packageName })
            .replace("{package}", record.packageName)
    } else {
        record.sourceApp.ifBlank { systemLabel }
    }

    Text(
        text = AppStrings.get("requests_source_section", lang),
        style = LoonTextStyles.subtitleStrong(),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LoonRadii.control))
            .background(LoonBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(LoonSpacing.sm)
    ) {
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_app", lang),
            value = appDisplay
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_traffic", lang),
            value = AppStrings.get("traffic_up_down", lang)
                .replace("{up}", trafficString(record.uploadBytes))
                .replace("{down}", trafficString(record.downloadBytes))
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_duration", lang),
            value = if (record.isActive) AppStrings.get("requests_status_active", lang)
            else formatDuration(record.durationMs, lang)
        )
        CupertinoDetailRow(
            label = AppStrings.get("requests_field_time", lang),
            value = formattedDetailTime
        )
    }
}

@Composable
private fun RequestClearActionSheet(
    lang: String,
    onDismiss: () -> Unit
) {
    val haptic = rememberIosHaptic()
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("requests_clear_title", lang),
        message = AppStrings.get("requests_clear_message", lang),
        actions = listOf(
            CupertinoActionSheetItem(
                text = AppStrings.get("requests_clear_closed", lang),
                onClick = {
                    haptic.lightTick()
                    onDismiss()
                    RequestRepository.clearClosed()
                    CupertinoToast.show(AppStrings.get("requests_cleared_closed_toast", lang))
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("requests_clear_all", lang),
                isDestructive = true,
                onClick = {
                    haptic.impact()
                    onDismiss()
                    RequestRepository.clearAll()
                    CupertinoToast.show(AppStrings.get("requests_cleared_all_toast", lang), isDestructive = true)
                }
            )
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun RequestFilterActionSheet(
    lang: String,
    summary: RequestFilterSheetSummary,
    onSelectFilter: (RequestFilter) -> Unit,
    onOpenAppFilter: () -> Unit,
    onDismiss: () -> Unit
) {
    val filterActions = RequestFilter.entries.map { filter ->
        val count = when (filter) {
            RequestFilter.ALL -> summary.recordsSize
            RequestFilter.ACTIVE -> summary.counts.activeCount
            RequestFilter.PROXY -> summary.counts.proxyCount
            RequestFilter.DIRECT -> summary.counts.directCount
        }
        val counted = AppStrings.get("filter_count", lang)
            .replace("{label}", AppStrings.get(filter.labelKey, lang))
            .replace("{n}", count.toString())
        CupertinoActionSheetItem(
            text = if (filter == summary.selectedFilter) {
                AppStrings.get("filter_selected_mark", lang).replace("{label}", counted)
            } else {
                counted
            },
            onClick = { onSelectFilter(filter) }
        )
    }

    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("requests_filter_title", lang),
        message = AppStrings.get("requests_filter_message", lang)
            .replace("{a}", summary.filteredSize.toString())
            .replace("{b}", summary.recordsSize.toString()),
        actions = filterActions + CupertinoActionSheetItem(
            text = AppStrings.get("requests_filter_app", lang) +
                (summary.selectedApp?.let { app -> " · ${app.label}" } ?: ""),
            onClick = onOpenAppFilter
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestAppFilterSheet(
    lang: String,
    records: List<RequestRecord>,
    selectedApp: RequestAppOption?,
    onSelectApp: (RequestAppOption?) -> Unit,
    onDismiss: () -> Unit
) {
    val appOptions = remember(records, lang) {
        buildRequestAppOptions(records, AppStrings.get("system_app", lang))
    }
    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
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
            item(key = "all_apps") {
                AppFilterOptionRow(
                    label = AppStrings.get("requests_filter_all_apps", lang),
                    subtitle = null,
                    isSelected = selectedApp == null,
                    onClick = { onSelectApp(null) }
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
                    onClick = { onSelectApp(option) }
                )
                if (index < appOptions.lastIndex) {
                    CupertinoIndentedDivider(startIndent = 16.dp)
                }
            }
        }
    }
}

internal fun formatHostPort(host: String, port: Int): String {
    val cleanHost = host.trim()
    if (cleanHost.isEmpty()) return ""
    val bracketed = if (cleanHost.contains(':') && !cleanHost.startsWith("[")) "[$cleanHost]" else cleanHost
    return if (port > 0) "$bracketed:$port" else bracketed
}

private fun resolveRequestRecordMidText(record: RequestRecord, lang: String): String = when {
    record.uploadBytes > 0L || record.downloadBytes > 0L -> AppStrings.get("traffic_up_down", lang)
        .replace("{up}", trafficString(record.uploadBytes))
        .replace("{down}", trafficString(record.downloadBytes))
    record.rulePayload.isNotBlank() -> "${record.rule}(${record.rulePayload})"
    else -> record.rule
}

@Composable
private fun RequestRecordCell(
    record: RequestRecord,
    formattedTime: String,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val (outboundColor, outboundText) = when {
        record.outbound.equals("REJECT", ignoreCase = true) -> Pair(LoonDangerText, "REJECT")
        record.outbound.equals("DIRECT", ignoreCase = true) -> Pair(LoonTextSecondary, "DIRECT")
        else -> Pair(LoonInfoText, record.outbound)
    }
    val displayHost = if (record.destinationPort != 443 && record.destinationPort != 80) {
        formatHostPort(record.host, record.destinationPort)
    } else {
        record.host
    }
    val midText = resolveRequestRecordMidText(record = record, lang = lang)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .iosPressable(onClick = onClick)
            .padding(horizontal = LoonSpacing.lg, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayHost,
                color = LoonTextPrimary,
                style = LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(LoonSpacing.sm))
            Text(
                text = outboundText,
                color = outboundColor,
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(LoonSpacing.xs))

        RequestRecordBottomRow(
            record = record,
            midText = midText,
            formattedTime = formattedTime,
            lang = lang
        )
    }
}

@Composable
private fun RequestRecordBottomRow(
    record: RequestRecord,
    midText: String,
    formattedTime: String,
    lang: String
) {
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
                text = record.sourceApp.ifBlank { AppStrings.get("system_app", lang) },
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = " · ", color = LoonTextMuted, style = LoonTextStyles.subtitle())
            Text(
                text = midText,
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = " · ", color = LoonTextMuted, style = LoonTextStyles.subtitle())
            Text(text = record.network, color = LoonTextMuted, style = LoonTextStyles.subtitle())
        }

        Spacer(modifier = Modifier.width(LoonSpacing.sm))

        if (record.isActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(IosGreen)
                )
                Spacer(modifier = Modifier.width(LoonSpacing.xs))
                Text(
                    text = AppStrings.get("filter_active", lang),
                    color = LoonSuccessText,
                    style = LoonTextStyles.subtitle().copy(fontWeight = FontWeight.Medium)
                )
            }
        } else {
            Text(text = formattedTime, color = LoonTextMuted, style = LoonTextStyles.subtitle())
        }
    }
}

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
            .heightIn(min = LoonSizes.touchTargetMin)
            .semantics { selected = isSelected }
            .iosPressable(onClick = onClick)
            .padding(horizontal = LoonSpacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isSelected) LoonInfoText else LoonTextPrimary,
                style = if (isSelected) LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold)
                else LoonTextStyles.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(LoonSpacing.xxs))
                Text(
                    text = subtitle,
                    color = LoonTextMuted,
                    style = LoonTextStyles.subtitle(),
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

private fun formatDuration(ms: Long, lang: String): String {
    if (ms <= 0) return AppStrings.get("duration_lt_second", lang)
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> AppStrings.get("duration_hm", lang)
            .replace("{h}", (seconds / 3600).toString())
            .replace("{m}", ((seconds % 3600) / 60).toString())
        seconds >= 60 -> AppStrings.get("duration_ms", lang)
            .replace("{m}", (seconds / 60).toString())
            .replace("{s}", (seconds % 60).toString())
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
