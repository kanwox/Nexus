package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.BottomSheetDefaults
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
import com.github.mihomo.android.data.RequestRecord
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private enum class RequestFilter(val label: String) {
    ALL("全部"),
    ACTIVE("活跃"),
    PROXY("代理"),
    DIRECT("直连")
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun RequestLogsScreen(
    vpnState: VpnState,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()
    val records by RequestRepository.recordsFlow.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(RequestFilter.ALL) }
    var showFilterActionSheet by remember { mutableStateOf(false) }
    var showClearActionSheet by remember { mutableStateOf(false) }
    var activeDetailRecord by remember { mutableStateOf<RequestRecord?>(null) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val detailTimeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // Filter records based on filter and fluid search
    val filteredRecords = remember(records, searchQuery, selectedFilter) {
        val query = searchQuery.trim().lowercase()
        records.filter { record ->
            // Filter match
            val filterMatch = when (selectedFilter) {
                RequestFilter.ALL -> true
                RequestFilter.ACTIVE -> record.isActive
                RequestFilter.PROXY -> !record.outbound.equals("DIRECT", ignoreCase = true) && !record.outbound.equals("REJECT", ignoreCase = true)
                RequestFilter.DIRECT -> record.outbound.equals("DIRECT", ignoreCase = true)
            }
            if (!filterMatch) return@filter false

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
                                contentDescription = "连接过滤",
                                tint = if (selectedFilter != RequestFilter.ALL) IosBlue else LoonTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            if (selectedFilter != RequestFilter.ALL) {
                                Text(
                                    text = selectedFilter.label,
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
                                text = "清空",
                                color = RedDanger,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
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
                        placeholder = "搜索域名、IP、应用或策略"
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
                                text = if (searchQuery.isNotBlank()) "未找到匹配记录" else "暂无请求记录",
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                val sectionHeader = when {
                    searchQuery.isNotBlank() -> "搜索结果 (${filteredRecords.size})"
                    selectedFilter == RequestFilter.ACTIVE -> "活跃连接 (${filteredRecords.size})"
                    selectedFilter == RequestFilter.PROXY -> "代理连接 (${filteredRecords.size})"
                    selectedFilter == RequestFilter.DIRECT -> "直连记录 (${filteredRecords.size})"
                    activeCount > 0 -> "全部记录 (${filteredRecords.size} · 活跃 $activeCount)"
                    else -> "全部记录 (${filteredRecords.size})"
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
            title = "请求详情",
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
                            text = "活跃中",
                            color = IosGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = "已关闭",
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
                    text = "目标与网络",
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
                        label = "目标域名",
                        value = rec.host,
                        showCopy = true,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(rec.host))
                            CupertinoToast.show("已复制域名")
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "目标端点",
                        value = "${rec.destinationIp}:${rec.destinationPort}",
                        showCopy = true,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString("${rec.destinationIp}:${rec.destinationPort}"))
                            CupertinoToast.show("已复制端点")
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "传输协议",
                        value = rec.network
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: 分流与策略
                Text(
                    text = "分流与策略",
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
                        label = "命中规则",
                        value = ruleDescription
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "策略节点",
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
                            label = "策略链路",
                            value = rec.chain.joinToString(" ➔ ")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 3: 来源与流量统计
                Text(
                    text = "来源与流量统计",
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
                        label = "发起应用",
                        value = appDisplay
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "流量统计",
                        value = "↑ ${formatTraffic(rec.uploadBytes)}   ↓ ${formatTraffic(rec.downloadBytes)}"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "持续时间",
                        value = if (rec.isActive) "活跃中" else formatDuration(rec.durationMs)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = "建立时间",
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
                                RequestRepository.closeConnection(rec.id)
                                activeDetailRecord = null
                                CupertinoToast.show("已断开连接", isDestructive = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "断开此连接",
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
            title = "清空请求记录",
            message = "请选择要执行的清理操作",
            actions = listOf(
                CupertinoActionSheetItem(
                    text = "清空已完成记录",
                    onClick = {
                        haptic.lightTick()
                        showClearActionSheet = false
                        RequestRepository.clearClosed()
                        CupertinoToast.show("已清理已完成记录")
                    }
                ),
                CupertinoActionSheetItem(
                    text = "断开所有连接并清空",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        showClearActionSheet = false
                        RequestRepository.clearAll()
                        CupertinoToast.show("已清空全部连接记录", isDestructive = true)
                    }
                )
            ),
            cancelText = "取消"
        )
    }

    // iOS Cupertino Action Sheet for Request Filtering
    if (showFilterActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showFilterActionSheet = false },
            title = "连接记录过滤",
            message = "当前匹配 ${filteredRecords.size} / ${records.size} 条连接",
            actions = RequestFilter.entries.map { filter ->
                val count = when (filter) {
                    RequestFilter.ALL -> records.size
                    RequestFilter.ACTIVE -> activeCount
                    RequestFilter.PROXY -> records.count { !it.outbound.equals("DIRECT", ignoreCase = true) && !it.outbound.equals("REJECT", ignoreCase = true) }
                    RequestFilter.DIRECT -> records.count { it.outbound.equals("DIRECT", ignoreCase = true) }
                }
                CupertinoActionSheetItem(
                    text = "${filter.label} ($count)" + if (filter == selectedFilter) "  ✓" else "",
                    onClick = {
                        haptic.selection()
                        selectedFilter = filter
                        showFilterActionSheet = false
                    }
                )
            },
            cancelText = "取消"
        )
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
    val (outboundColor, outboundText) = when {
        record.outbound.equals("REJECT", ignoreCase = true) -> Pair(RedDanger, "REJECT")
        record.outbound.equals("DIRECT", ignoreCase = true) -> Pair(LoonTextSecondary, "DIRECT")
        else -> Pair(IosBlue, record.outbound)
    }

    val displayHost = if (record.destinationPort != 443 && record.destinationPort != 80) {
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

                Text(
                    text = "↑ ${formatTraffic(record.uploadBytes)}  ↓ ${formatTraffic(record.downloadBytes)}",
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
                        text = "活跃",
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
                    contentDescription = "复制",
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
 * Format raw byte count into human-readable compact string.
 */
private fun formatTraffic(bytes: Long): String {
    val b = if (bytes < 0) 0L else bytes
    return when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b.toDouble() / (1024.0 * 1024.0 * 1024.0))
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b.toDouble() / (1024.0 * 1024.0))
        b >= 1024L -> String.format(Locale.US, "%.1f KB", b.toDouble() / 1024.0)
        else -> "$b B"
    }
}

/**
 * Format duration in milliseconds into human-readable string.
 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "< 1秒"
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600}小时 ${(seconds % 3600) / 60}分"
        seconds >= 60 -> "${seconds / 60}分 ${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}

@Preview(showBackground = true)
@Composable
fun RequestLogsScreenPreview() {
    RequestLogsScreen(
        vpnState = VpnState(VpnState.Status.RUNNING),
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
