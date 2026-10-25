package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import com.github.mihomo.android.data.DnsRecord
import com.github.mihomo.android.data.DnsRepository
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(
    onNavigateToSettings: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()
    val records by DnsRepository.recordsFlow.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showClearActionSheet by remember { mutableStateOf(false) }
    var activeDetailRecord by remember { mutableStateOf<DnsRecord?>(null) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val filteredRecords = remember(records, searchQuery) {
        if (searchQuery.isBlank()) {
            records
        } else {
            val query = searchQuery.trim()
            records.filter {
                it.domain.contains(query, ignoreCase = true) || it.ip.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // iOS Navigation Bar: Balanced Left Back, Absolute Centered Single-Line Title, Single Plain Text Action
        CupertinoNavBar(
            title = AppStrings.get("dns_screen_title", lang),
            showBackButton = true,
            onBack = onBack,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onNavigateToSettings != null) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .iosPressable {
                                    haptic.lightTick()
                                    onNavigateToSettings()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "DNS 设置",
                                tint = IosBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
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
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "清空",
                                color = RedDanger,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        )

        // Main Scroll Area: Fluid search bar inside scroll container + Grouped records
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            // Fluid iOS Search Bar (Scrolls naturally with content, freeing full screen when browsing)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    CupertinoSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "搜索"
                    )
                }
            }

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
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = LoonTextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    AppStrings.get("dns_empty_search", lang)
                                } else {
                                    AppStrings.get("dns_empty", lang)
                                },
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                val sectionHeader = if (searchQuery.isNotBlank()) {
                    "搜索结果 (${filteredRecords.size})"
                } else {
                    "解析记录 (${filteredRecords.size})"
                }

                cupertinoGroupedItems(
                    items = filteredRecords,
                    header = sectionHeader,
                    dividerStartIndent = 16.dp
                ) { _, record ->
                    val formattedTime = remember(record.timestamp) {
                        timeFormat.format(Date(record.timestamp))
                    }

                    DnsRecordCell(
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
        val formattedTime = remember(rec.timestamp) { timeFormat.format(Date(rec.timestamp)) }

        CupertinoModalBottomSheet(
            onDismissRequest = { activeDetailRecord = null },
            title = "解析详情"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                // Domain Card
                Text(
                    text = "域名",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LoonBg)
                        .iosPressable {
                            haptic.lightTick()
                            clipboardManager.setText(AnnotatedString(rec.domain))
                            CupertinoToast.show(AppStrings.get("dns_copied_domain", lang))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = rec.domain,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LoonTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制域名",
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resolved IP Card
                Text(
                    text = "解析结果 (IP)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LoonBg)
                        .iosPressable {
                            haptic.lightTick()
                            clipboardManager.setText(AnnotatedString(rec.ip))
                            CupertinoToast.show(AppStrings.get("dns_copied_ip", lang))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = rec.ip,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = LoonTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制 IP",
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (!rec.server.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "解析服务器",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = LoonTextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LoonBg)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rec.server,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = LoonTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Parameters Grid (Mode, Type, Latency, Time)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "模式", fontSize = 11.sp, color = LoonTextSecondary)
                        Text(
                            text = if (rec.isFakeIp) "Fake-IP" else "真实 IP",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rec.isFakeIp) IosBlue else LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = "类型", fontSize = 11.sp, color = LoonTextSecondary)
                        Text(
                            text = rec.type,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = "耗时", fontSize = 11.sp, color = LoonTextSecondary)
                        Text(
                            text = if (rec.latencyMs <= 0) "缓存命中" else "${rec.latencyMs}ms",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rec.latencyMs <= 0) IosGreen else LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = "时间", fontSize = 11.sp, color = LoonTextSecondary)
                        Text(
                            text = formattedTime,
                            fontSize = 13.sp,
                            color = LoonTextSecondary
                        )
                    }
                }
            }
        }
    }

    // Flush Cache Confirmation: Authentic iOS Action Sheet Presentation
    if (showClearActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showClearActionSheet = false },
            title = "清空 DNS 记录",
            message = "确定要清空当前的本地解析缓存吗？此操作无法撤销。",
            actions = listOf(
                CupertinoActionSheetItem(
                    text = "清空所有记录",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        DnsRepository.clear()
                        CupertinoToast.show("已清空 DNS 记录", isDestructive = true)
                    }
                )
            ),
            cancelText = "取消"
        )
    }
}

/**
 * Pure, high signal-to-noise ratio DNS Record Row.
 * Preserves full width for domains, linear metadata typography with zero box noise.
 */
@Composable
private fun DnsRecordCell(
    record: DnsRecord,
    formattedTime: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        // Line 1: Domain + Latency / Cache Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = record.domain,
                color = LoonTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (record.latencyMs <= 0) {
                Text(
                    text = "缓存",
                    color = IosGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "${record.latencyMs}ms",
                    color = if (record.latencyMs > 100) IosOrange else LoonTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Line 2: IP + Pure Inline Fake-IP Text + Timestamp
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
                    text = record.ip,
                    color = LoonTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (record.isFakeIp) {
                    Text(
                        text = " · Fake-IP",
                        color = IosBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formattedTime,
                color = LoonTextMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DnsScreenPreview() {
    DnsScreen(
        onNavigateToSettings = {},
        onBack = {}
    )
}
