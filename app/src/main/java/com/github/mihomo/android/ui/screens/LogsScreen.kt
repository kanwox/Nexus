package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.github.mihomo.android.data.LogEntry
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private enum class LogFilter(val label: String) {
    ALL("全部"),
    ISSUES("异常"),
    INFO("信息"),
    DEBUG("调试")
}

/** One log line for copy/export, including the collapse count when a message repeated. */
private fun formatLogLine(
    log: LogEntry,
    timeFormat: SimpleDateFormat,
    fullTimeFormat: SimpleDateFormat,
    fullTimestamp: Boolean = false
): String {
    val format = if (fullTimestamp) fullTimeFormat else timeFormat
    val repeat = if (log.count > 1) " (×${log.count})" else ""
    return "[${format.format(Date(log.timestamp))}] [${log.level.uppercase()}] ${log.message}$repeat"
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UnusedParameter", "UNUSED_PARAMETER")
@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()
    val logs by LogRepository.logsFlow.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var activeDetailLog by remember { mutableStateOf<LogEntry?>(null) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val fullTimeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

    // Export to file via SAF: the user picks the destination, so no storage permission
    // or FileProvider is needed.
    val context = LocalContext.current
    var pendingExportLogs by remember { mutableStateOf<List<LogEntry>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val entries = pendingExportLogs
        pendingExportLogs = null
        if (uri != null && entries != null) {
            val exportText = entries.joinToString("\n") { log ->
                formatLogLine(log, timeFormat, fullTimeFormat, fullTimestamp = true)
            }
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(exportText.toByteArray(Charsets.UTF_8))
                }
                CupertinoToast.show("已导出 ${entries.size} 条日志")
            }.onFailure {
                CupertinoToast.show("导出失败: ${it.message}", isDestructive = true)
            }
        }
    }

    // Filter logs based on filter and search query
    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        val query = searchQuery.trim().lowercase()
        logs.filter { entry ->
            // Level filter
            val levelMatch = when (selectedFilter) {
                LogFilter.ALL -> true
                LogFilter.ISSUES -> entry.level.equals("error", ignoreCase = true) || entry.level.equals("warning", ignoreCase = true)
                LogFilter.INFO -> entry.level.equals("info", ignoreCase = true)
                LogFilter.DEBUG -> entry.level.equals("debug", ignoreCase = true)
            }
            if (!levelMatch) return@filter false

            // Search query match
            if (query.isEmpty()) return@filter true
            entry.message.lowercase().contains(query) || entry.level.lowercase().contains(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Navigation Bar: iOS centered title, back button, trailing filter and action buttons
        CupertinoNavBar(
            title = AppStrings.get("shortcut_logs", lang),
            showBackButton = true,
            onBack = onBack,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Level Filter Button
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .iosPressable {
                                haptic.selection()
                                showFilterSheet = true
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
                                contentDescription = "日志过滤",
                                tint = if (selectedFilter != LogFilter.ALL) IosBlue else LoonTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            if (selectedFilter != LogFilter.ALL) {
                                Text(
                                    text = selectedFilter.label,
                                    color = IosBlue,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (logs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .iosPressable {
                                    haptic.selection()
                                    showActionSheet = true
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "操作",
                                color = IosBlue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        )

        // Main Scroll Area: Fluid search & Grouped logs
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
                        placeholder = "搜索日志内容、级别或报错"
                    )
                }
            }

            // List Content or Empty State
            if (filteredLogs.isEmpty()) {
                item {
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
                                text = if (searchQuery.isNotBlank()) "未找到匹配日志" else "暂无核心日志",
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                val sectionHeader = when {
                    searchQuery.isNotBlank() -> "搜索结果 (${filteredLogs.size})"
                    selectedFilter == LogFilter.ISSUES -> "异常日志 (${filteredLogs.size})"
                    selectedFilter == LogFilter.INFO -> "信息日志 (${filteredLogs.size})"
                    selectedFilter == LogFilter.DEBUG -> "调试日志 (${filteredLogs.size})"
                    else -> "运行日志 (${filteredLogs.size})"
                }
                val displayLogs = filteredLogs.asReversed()

                cupertinoGroupedItems(
                    items = displayLogs,
                    header = sectionHeader,
                    dividerStartIndent = 16.dp
                ) { _, entry ->
                    val formattedTime = remember(entry.timestamp) {
                        timeFormat.format(Date(entry.timestamp))
                    }

                    LogEntryCell(
                        entry = entry,
                        formattedTime = formattedTime,
                        onClick = {
                            haptic.lightTick()
                            activeDetailLog = entry
                        }
                    )
                }
            }
        }
    }

    // Detail Inspector: Pure iOS CupertinoModalBottomSheet
    if (activeDetailLog != null) {
        val entry = activeDetailLog!!
        val fullTimeStr = remember(entry.timestamp) {
            fullTimeFormat.format(Date(entry.timestamp))
        }

        val (lvlColor, lvlText) = when (entry.level.lowercase()) {
            "error" -> Pair(RedDanger, "ERROR")
            "warning", "warn" -> Pair(IosOrange, "WARNING")
            "debug" -> Pair(LoonTextMuted, "DEBUG")
            else -> Pair(IosBlue, "INFO")
        }

        CupertinoModalBottomSheet(
            onDismissRequest = { activeDetailLog = null },
            title = "日志详情",
            trailingAction = {
                Text(
                    text = lvlText,
                    color = lvlColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                // Card 1: 发生时间
                Text(
                    text = "发生时间",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = LoonTextPrimary
                    )
                    if (entry.count > 1) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "重复 ×${entry.count} 次",
                            fontSize = 12.sp,
                            color = LoonTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: 完整内容 (带一键复制)
                Text(
                    text = "完整内容",
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
                        .iosPressable {
                            haptic.lightTick()
                            clipboardManager.setText(AnnotatedString(entry.message))
                            CupertinoToast.show("已复制日志内容")
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "轻点复制全文",
                            fontSize = 12.sp,
                            color = IosBlue
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = IosBlue,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = entry.message,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = LoonTextPrimary
                    )
                }
            }
        }
    }

    // iOS Cupertino Action Sheet for Log Operations
    if (showActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showActionSheet = false },
            title = "核心日志操作",
            message = "当前已产生 ${logs.size} 条日志记录",
            actions = listOf(
                CupertinoActionSheetItem(
                    text = "复制日志",
                    onClick = {
                        haptic.lightTick()
                        showActionSheet = false
                        val exportText = filteredLogs.joinToString("\n") { log ->
                            formatLogLine(log, timeFormat, fullTimeFormat)
                        }
                        clipboardManager.setText(AnnotatedString(exportText))
                        CupertinoToast.show("已复制 ${filteredLogs.size} 条日志")
                    }
                ),
                CupertinoActionSheetItem(
                    text = "导出日志",
                    onClick = {
                        haptic.lightTick()
                        showActionSheet = false
                        pendingExportLogs = filteredLogs
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("nexus-logs-$stamp.txt")
                    }
                ),
                CupertinoActionSheetItem(
                    text = "清空日志",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        showActionSheet = false
                        LogRepository.clear()
                        CupertinoToast.show("已清空核心日志", isDestructive = true)
                    }
                )
            ),
            cancelText = "取消"
        )
    }

    // iOS Cupertino Action Sheet for Log Level Filtering
    if (showFilterSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showFilterSheet = false },
            title = "日志级别过滤",
            message = "当前匹配 ${filteredLogs.size} / ${logs.size} 条日志记录",
            actions = LogFilter.entries.map { filter ->
                CupertinoActionSheetItem(
                    text = if (filter == selectedFilter) "${filter.label}  ✓" else filter.label,
                    onClick = {
                        haptic.selection()
                        selectedFilter = filter
                        showFilterSheet = false
                    }
                )
            },
            cancelText = "取消"
        )
    }
}

/**
 * Pure, high signal-to-noise ratio Log Row Cell.
 * Preserves clean typography, distinct level indicator, zero monospace black-box terminal noise.
 */
@Composable
private fun LogEntryCell(
    entry: LogEntry,
    formattedTime: String,
    onClick: () -> Unit
) {
    val (lvlColor, lvlText) = when (entry.level.lowercase()) {
        "error" -> Pair(RedDanger, "ERROR")
        "warning", "warn" -> Pair(IosOrange, "WARN")
        "debug" -> Pair(LoonTextMuted, "DEBUG")
        else -> Pair(IosBlue, "INFO")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        // Line 1: Timestamp + Level Indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formattedTime,
                color = LoonTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )

            Text(
                text = if (entry.count > 1) "$lvlText ×${entry.count}" else lvlText,
                color = lvlColor,
                fontSize = 12.sp,
                fontWeight = if (lvlColor == RedDanger) FontWeight.Bold else FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Line 2: Message Content (up to 4 lines, truncated gracefully with ellipsis)
        Text(
            text = entry.message,
            color = LoonTextPrimary,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LogsScreenPreview() {
    LogsScreen(
        onBack = {}
    )
}
