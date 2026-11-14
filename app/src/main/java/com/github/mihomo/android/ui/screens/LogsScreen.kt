@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.data.LogEntry
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardCopy
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LogFilter(val labelKey: String) {
    ALL("log_level_all"),
    ISSUES("log_level_issues"),
    INFO("log_level_info"),
    DEBUG("log_level_debug")
}

private fun formatLogLine(
    log: LogEntry,
    timeFormat: DateTimeFormatter,
    fullTimeFormat: DateTimeFormatter,
    fullTimestamp: Boolean = false
): String {
    val format = if (fullTimestamp) fullTimeFormat else timeFormat
    val repeat = if (log.count > 1) " (\u00D7${log.count})" else ""
    val levelLabel = log.level.uppercase(Locale.ROOT)
    return "[${format.format(Instant.ofEpochMilli(log.timestamp))}] [$levelLabel] ${log.message}$repeat"
}

@Composable
private fun logLevelStyle(level: String, fullWarningName: Boolean): Pair<Color, String> =
    when (level.lowercase(Locale.ROOT)) {
        "error" -> Pair(LoonDangerText, "ERROR")
        "warning", "warn" -> Pair(LoonWarningText, if (fullWarningName) "WARNING" else "WARN")
        "debug" -> Pair(LoonTextMuted, "DEBUG")
        else -> Pair(LoonInfoText, "INFO")
    }

@Composable
private fun rememberLogExportLauncher(
    lang: String,
    timeFormat: DateTimeFormatter,
    fullTimeFormat: DateTimeFormatter,
    getPendingLogs: () -> List<LogEntry>?,
    clearPendingLogs: () -> Unit
) = run {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val entries = getPendingLogs()
        clearPendingLogs()
        if (uri == null || entries == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val exportText = entries.joinToString("\n") { log ->
                        formatLogLine(log, timeFormat, fullTimeFormat, fullTimestamp = true)
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportText.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
                CupertinoToast.show(
                    AppStrings.get("logs_exported_count", lang).replace("{n}", entries.size.toString())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (err: Exception) {
                CupertinoToast.show(
                    AppStrings.get("logs_export_failed", lang).replace("{msg}", err.message ?: ""),
                    isDestructive = true
                )
            }
        }
    }
}

private data class LogsOverlaysState(
    val logsCount: Int,
    val filteredLogs: List<LogEntry>,
    val selectedFilter: LogFilter,
    val showActionSheet: Boolean,
    val showFilterSheet: Boolean,
    val showClearConfirm: Boolean
)

private data class LogsOverlaysCallbacks(
    val onDismissActionSheet: () -> Unit,
    val onDismissFilterSheet: () -> Unit,
    val onSelectFilter: (LogFilter) -> Unit,
    val onCopyFiltered: () -> Unit,
    val onRequestExport: () -> Unit,
    val onRequestClearConfirm: () -> Unit,
    val onConfirmClear: () -> Unit,
    val onDismissClearConfirm: () -> Unit
)

private fun copyLogsToClipboard(
    filteredLogs: List<LogEntry>,
    timeFormat: DateTimeFormatter,
    fullTimeFormat: DateTimeFormatter,
    lang: String,
    copyToClipboard: (String) -> Unit
) {
    val exportText = filteredLogs.joinToString("\n") { log ->
        formatLogLine(log, timeFormat, fullTimeFormat)
    }
    copyToClipboard(exportText)
    CupertinoToast.show(AppStrings.get("logs_copied_count", lang).replace("{n}", filteredLogs.size.toString()))
}

private fun buildLogsExportFilename(): String {
    val stamp = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    return "nexus-logs-$stamp.txt"
}

private class LogsScreenUiState {
    var searchQuery by mutableStateOf("")
    var selectedFilter by mutableStateOf(LogFilter.ALL)
    var showFilterSheet by mutableStateOf(false)
    var showActionSheet by mutableStateOf(false)
    var showClearConfirm by mutableStateOf(false)
    var activeDetailLog by mutableStateOf<LogEntry?>(null)
    var pendingExportLogs by mutableStateOf<List<LogEntry>?>(null)
}

private data class LogDetailOverlayState(
    val activeDetailLog: LogEntry?,
    val fullTimeFormat: DateTimeFormatter,
    val onCopySingle: (String) -> Unit,
    val onDismissDetail: () -> Unit
)

@Composable
private fun rememberLogTimeFormats(): Pair<DateTimeFormatter, DateTimeFormatter> {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", locale).withZone(ZoneId.systemDefault())
    }
    val fullTimeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", locale).withZone(ZoneId.systemDefault())
    }
    return timeFormat to fullTimeFormat
}

@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val copyToClipboard = rememberClipboardCopy()
    val haptic = rememberIosHaptic()
    val liveLogs by LogRepository.logsFlow.collectAsStateWithLifecycle()
    val ui = remember { LogsScreenUiState() }
    val (timeFormat, fullTimeFormat) = rememberLogTimeFormats()

    val exportLauncher = rememberLogExportLauncher(
        lang = lang,
        timeFormat = timeFormat,
        fullTimeFormat = fullTimeFormat,
        getPendingLogs = { ui.pendingExportLogs },
        clearPendingLogs = { ui.pendingExportLogs = null }
    )
    val filteredLogs = remember(liveLogs, ui.searchQuery, ui.selectedFilter) {
        filterLogs(liveLogs, ui.searchQuery, ui.selectedFilter)
    }
    val displayLogs = remember(filteredLogs) { filteredLogs.asReversed() }

    Column(modifier = Modifier.fillMaxSize().background(LoonBg).statusBarsPadding()) {
        CupertinoNavBar(
            title = AppStrings.get("shortcut_logs", lang),
            showBackButton = true,
            onBack = onBack,
            actions = {
                LogsNavActions(
                    lang = lang,
                    selectedFilter = ui.selectedFilter,
                    hasLogs = liveLogs.isNotEmpty(),
                    onOpenFilter = { haptic.selection(); ui.showFilterSheet = true },
                    onOpenMore = { haptic.selection(); ui.showActionSheet = true }
                )
            }
        )
        LoonResponsiveContent {
            LogsListContent(
                displayLogs = displayLogs,
                searchQuery = ui.searchQuery,
                selectedFilter = ui.selectedFilter,
                timeFormat = timeFormat,
                lang = lang,
                onSearchChange = { next -> ui.searchQuery = next },
                onSelectLog = { entry -> haptic.lightTick(); ui.activeDetailLog = entry }
            )
        }
    }

    LogsDetailAndOverlays(
        lang = lang,
        haptic = haptic,
        detailState = LogDetailOverlayState(
            activeDetailLog = ui.activeDetailLog,
            fullTimeFormat = fullTimeFormat,
            onCopySingle = { text -> copyToClipboard(text) },
            onDismissDetail = { ui.activeDetailLog = null }
        ),
        overlayState = LogsOverlaysState(
            logsCount = liveLogs.size,
            filteredLogs = filteredLogs,
            selectedFilter = ui.selectedFilter,
            showActionSheet = ui.showActionSheet,
            showFilterSheet = ui.showFilterSheet,
            showClearConfirm = ui.showClearConfirm
        ),
        overlayCallbacks = LogsOverlaysCallbacks(
            onDismissActionSheet = { ui.showActionSheet = false },
            onDismissFilterSheet = { ui.showFilterSheet = false },
            onSelectFilter = { next -> ui.selectedFilter = next; ui.showFilterSheet = false },
            onCopyFiltered = { copyLogsToClipboard(filteredLogs, timeFormat, fullTimeFormat, lang, copyToClipboard) },
            onRequestExport = { ui.pendingExportLogs = filteredLogs; exportLauncher.launch(buildLogsExportFilename()) },
            onRequestClearConfirm = { ui.showClearConfirm = true },
            onConfirmClear = {
                ui.showClearConfirm = false
                LogRepository.clear()
                CupertinoToast.show(AppStrings.get("logs_cleared_toast", lang), isDestructive = true)
            },
            onDismissClearConfirm = { ui.showClearConfirm = false }
        )
    )
}

@Composable
private fun LogsDetailAndOverlays(
    lang: String,
    haptic: IosHapticHelper,
    detailState: LogDetailOverlayState,
    overlayState: LogsOverlaysState,
    overlayCallbacks: LogsOverlaysCallbacks
) {
    detailState.activeDetailLog?.let { entry ->
        LogDetailSheet(
            entry = entry,
            fullTimeFormat = detailState.fullTimeFormat,
            lang = lang,
            onCopy = {
                haptic.lightTick()
                detailState.onCopySingle(entry.message)
                CupertinoToast.show(AppStrings.get("logs_content_copied", lang))
            },
            onDismiss = detailState.onDismissDetail
        )
    }
    LogsOverlays(
        lang = lang,
        haptic = haptic,
        state = overlayState,
        callbacks = overlayCallbacks
    )
}

private fun filterLogs(
    logs: List<LogEntry>,
    searchQuery: String,
    selectedFilter: LogFilter
): List<LogEntry> {
    val query = searchQuery.trim().lowercase(Locale.ROOT)
    return logs.filter { entry ->
        val levelMatch = when (selectedFilter) {
            LogFilter.ALL -> true
            LogFilter.ISSUES -> entry.level.equals("error", ignoreCase = true) ||
                entry.level.equals("warning", ignoreCase = true) ||
                entry.level.equals("warn", ignoreCase = true)
            LogFilter.INFO -> entry.level.equals("info", ignoreCase = true)
            LogFilter.DEBUG -> entry.level.equals("debug", ignoreCase = true)
        }
        if (!levelMatch) return@filter false
        if (query.isEmpty()) return@filter true
        entry.message.lowercase(Locale.ROOT).contains(query) ||
            entry.level.lowercase(Locale.ROOT).contains(query)
    }
}

@Composable
private fun LogsNavActions(
    lang: String,
    selectedFilter: LogFilter,
    hasLogs: Boolean,
    onOpenFilter: () -> Unit,
    onOpenMore: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(RoundedCornerShape(LoonRadii.control))
                .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                .iosPressable(onClick = onOpenFilter)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = AppStrings.get("logs_filter_desc", lang),
                    tint = if (selectedFilter != LogFilter.ALL) LoonInfoText else LoonTextSecondary,
                    modifier = Modifier.size(LoonSizes.icon)
                )
                if (selectedFilter != LogFilter.ALL) {
                    Text(
                        text = AppStrings.get(selectedFilter.labelKey, lang),
                        color = LoonInfoText,
                        style = LoonTextStyles.calloutLarge(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (hasLogs) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                    .clip(RoundedCornerShape(LoonRadii.control))
                    .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                    .iosPressable(onClick = onOpenMore)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = AppStrings.get("action_more", lang),
                    tint = LoonInfoText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun LogsListContent(
    displayLogs: List<LogEntry>,
    searchQuery: String,
    selectedFilter: LogFilter,
    timeFormat: DateTimeFormatter,
    lang: String,
    onSearchChange: (String) -> Unit,
    onSelectLog: (LogEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                CupertinoSearchBar(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = AppStrings.get("logs_search_placeholder", lang)
                )
            }
        }

        if (displayLogs.isEmpty()) {
            item {
                LogsEmptyState(
                    message = if (searchQuery.isNotBlank()) {
                        AppStrings.get("logs_no_match", lang)
                    } else {
                        AppStrings.get("logs_none", lang)
                    }
                )
            }
        } else {
            val countText = displayLogs.size.toString()
            val sectionHeader = when {
                searchQuery.isNotBlank() -> AppStrings.get("search_results", lang).replace("{n}", countText)
                selectedFilter == LogFilter.ISSUES -> AppStrings.get("logs_section_issues", lang).replace("{n}", countText)
                selectedFilter == LogFilter.INFO -> AppStrings.get("logs_section_info", lang).replace("{n}", countText)
                selectedFilter == LogFilter.DEBUG -> AppStrings.get("logs_section_debug", lang).replace("{n}", countText)
                else -> AppStrings.get("logs_section_all", lang).replace("{n}", countText)
            }

            cupertinoGroupedItems(
                items = displayLogs,
                key = { _, entry -> entry.id },
                header = sectionHeader,
                dividerStartIndent = 16.dp
            ) { _, entry ->
                val formattedTime = remember(entry.timestamp, timeFormat) {
                    timeFormat.format(Instant.ofEpochMilli(entry.timestamp))
                }
                LogEntryCell(
                    entry = entry,
                    formattedTime = formattedTime,
                    onClick = { onSelectLog(entry) }
                )
            }
        }
    }
}

@Composable
private fun LogsEmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                tint = LoonTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = LoonTextSecondary,
                style = LoonTextStyles.body()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailSheet(
    entry: LogEntry,
    fullTimeFormat: DateTimeFormatter,
    lang: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val fullTimeStr = remember(entry.timestamp, fullTimeFormat) {
        fullTimeFormat.format(Instant.ofEpochMilli(entry.timestamp))
    }
    val (lvlColor, lvlText) = logLevelStyle(entry.level, fullWarningName = true)

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("logs_detail_title", lang),
        trailingAction = {
            Text(
                text = lvlText,
                color = lvlColor,
                style = LoonTextStyles.callout(),
                fontWeight = FontWeight.Bold
            )
        }
    ) {
        LogDetailSheetBody(entry = entry, fullTimeStr = fullTimeStr, lang = lang, onCopy = onCopy)
    }
}

@Composable
private fun LogDetailSheetBody(
    entry: LogEntry,
    fullTimeStr: String,
    lang: String,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = AppStrings.get("logs_time_label", lang),
            style = LoonTextStyles.subtitleStrong(),
            color = LoonTextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        LogDetailTimeBox(fullTimeStr = fullTimeStr, repeatCount = entry.count, lang = lang)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = AppStrings.get("logs_full_content", lang),
            style = LoonTextStyles.subtitleStrong(),
            color = LoonTextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        LogDetailMessageCard(message = entry.message, lang = lang, onCopy = onCopy)
    }
}

@Composable
private fun LogDetailTimeBox(fullTimeStr: String, repeatCount: Int, lang: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LoonBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = fullTimeStr,
            style = LoonTextStyles.calloutLarge(),
            fontWeight = FontWeight.Medium,
            color = LoonTextPrimary
        )
        if (repeatCount > 1) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = AppStrings.get("logs_repeated", lang).replace("{n}", repeatCount.toString()),
                style = LoonTextStyles.subtitle(),
                color = LoonTextSecondary
            )
        }
    }
}

@Composable
private fun LogDetailMessageCard(message: String, lang: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .clip(RoundedCornerShape(12.dp))
            .background(LoonBg)
            .iosFocusRing(RoundedCornerShape(12.dp))
            .iosPressable(onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = AppStrings.get("logs_tap_to_copy", lang),
                style = LoonTextStyles.subtitle(),
                color = LoonInfoText
            )
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = LoonInfoText,
                modifier = Modifier.size(LoonSizes.iconSmall)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = LoonTextStyles.calloutLarge(), color = LoonTextPrimary)
    }
}

@Composable
private fun LogsOverlays(
    lang: String,
    haptic: IosHapticHelper,
    state: LogsOverlaysState,
    callbacks: LogsOverlaysCallbacks
) {
    if (state.showActionSheet) {
        LogsActionSheet(
            lang = lang,
            logsCount = state.logsCount,
            haptic = haptic,
            onDismiss = callbacks.onDismissActionSheet,
            onCopyFiltered = callbacks.onCopyFiltered,
            onRequestExport = callbacks.onRequestExport,
            onRequestClearConfirm = callbacks.onRequestClearConfirm
        )
    }
    if (state.showFilterSheet) {
        LogsFilterSheet(
            lang = lang,
            logsCount = state.logsCount,
            filteredCount = state.filteredLogs.size,
            selectedFilter = state.selectedFilter,
            haptic = haptic,
            onDismiss = callbacks.onDismissFilterSheet,
            onSelectFilter = callbacks.onSelectFilter
        )
    }
    if (state.showClearConfirm) {
        CupertinoAlertDialog(
            title = AppStrings.get("logs_clear_confirm_title", lang),
            message = AppStrings.get("logs_clear_confirm_message", lang),
            confirmText = AppStrings.get("confirm", lang),
            dismissText = AppStrings.get("cancel", lang),
            isDestructive = true,
            onConfirm = callbacks.onConfirmClear,
            onDismissRequest = callbacks.onDismissClearConfirm
        )
    }
}

@Composable
private fun LogsActionSheet(
    lang: String,
    logsCount: Int,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit,
    onCopyFiltered: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestClearConfirm: () -> Unit
) {
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("logs_actions_title", lang),
        message = AppStrings.get("logs_count_message", lang).replace("{n}", logsCount.toString()),
        actions = listOf(
            CupertinoActionSheetItem(
                text = AppStrings.get("logs_copy", lang),
                onClick = { haptic.lightTick(); onDismiss(); onCopyFiltered() }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("logs_export", lang),
                onClick = { haptic.lightTick(); onDismiss(); onRequestExport() }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("logs_clear", lang),
                isDestructive = true,
                onClick = { haptic.impact(); onDismiss(); onRequestClearConfirm() }
            )
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun LogsFilterSheet(
    lang: String,
    logsCount: Int,
    filteredCount: Int,
    selectedFilter: LogFilter,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit,
    onSelectFilter: (LogFilter) -> Unit
) {
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("logs_filter_title", lang),
        message = AppStrings.get("logs_filter_message", lang)
            .replace("{a}", filteredCount.toString())
            .replace("{b}", logsCount.toString()),
        actions = LogFilter.entries.map { filter ->
            CupertinoActionSheetItem(
                text = if (filter == selectedFilter) {
                    AppStrings.get("filter_selected_mark", lang)
                        .replace("{label}", AppStrings.get(filter.labelKey, lang))
                } else {
                    AppStrings.get(filter.labelKey, lang)
                },
                onClick = { haptic.selection(); onSelectFilter(filter) }
            )
        },
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun LogEntryCell(
    entry: LogEntry,
    formattedTime: String,
    onClick: () -> Unit
) {
    val (lvlColor, lvlText) = logLevelStyle(entry.level, fullWarningName = false)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formattedTime,
                color = LoonTextMuted,
                style = LoonTextStyles.caption()
            )
            Text(
                text = if (entry.count > 1) "$lvlText \u00D7${entry.count}" else lvlText,
                color = lvlColor,
                style = LoonTextStyles.caption(),
                fontWeight = if (lvlColor == LoonDangerText) FontWeight.Bold else FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.message,
            color = LoonTextPrimary,
            style = LoonTextStyles.calloutLarge(),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LogsScreenPreview() {
    LogsScreen(onBack = {})
}
