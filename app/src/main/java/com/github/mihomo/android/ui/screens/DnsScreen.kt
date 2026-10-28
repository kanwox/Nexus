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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.DnsRecord
import com.github.mihomo.android.data.DnsRepository
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(
    onNavigateToSettings: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val copyToClipboard = rememberClipboardCopy()
    val haptic = rememberIosHaptic()
    val records by DnsRepository.recordsFlow.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showClearActionSheet by remember { mutableStateOf(false) }
    var activeDetailRecord by remember { mutableStateOf<DnsRecord?>(null) }

    // java.time formatters are immutable and thread-safe, unlike SimpleDateFormat.
    // The locale comes from LocalConfiguration, which is observable — reading
    // Locale.getDefault() directly would not recompose when the system locale changes.
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("HH:mm:ss", locale).withZone(ZoneId.systemDefault())
    }

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
                                contentDescription = AppStrings.get("settings_dns_section", lang),
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
                                text = AppStrings.get("action_clear", lang),
                                color = LoonDangerText,
                                fontSize = LoonTypeScale.title3,
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
                        placeholder = AppStrings.get("search_placeholder", lang)
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
                                fontSize = LoonTypeScale.subhead
                            )
                        }
                    }
                }
            } else {
                val sectionHeader = if (searchQuery.isNotBlank()) {
                    AppStrings.get("search_results", lang).replace("{n}", filteredRecords.size.toString())
                } else {
                    AppStrings.get("dns_records_section", lang).replace("{n}", filteredRecords.size.toString())
                }

                cupertinoGroupedItems(
                    items = filteredRecords,
                    key = { _, record -> record.id },
                    header = sectionHeader,
                    dividerStartIndent = 16.dp
                ) { _, record ->
                    val formattedTime = remember(record.timestamp) {
                        timeFormat.format(Instant.ofEpochMilli(record.timestamp))
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
        val formattedTime = remember(rec.timestamp) { timeFormat.format(Instant.ofEpochMilli(rec.timestamp)) }

        CupertinoModalBottomSheet(
            onDismissRequest = { activeDetailRecord = null },
            title = AppStrings.get("dns_detail_title", lang)
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
                    text = AppStrings.get("dns_field_domain", lang),
                    fontSize = LoonTypeScale.footnote,
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
                            copyToClipboard(rec.domain)
                            CupertinoToast.show(AppStrings.get("dns_copied_domain", lang))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = rec.domain,
                        fontSize = LoonTypeScale.subheadLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = LoonTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = AppStrings.get("dns_copy_domain_desc", lang),
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resolved IP Card
                Text(
                    text = AppStrings.get("dns_result_ip", lang),
                    fontSize = LoonTypeScale.footnote,
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
                            copyToClipboard(rec.ip)
                            CupertinoToast.show(AppStrings.get("dns_copied_ip", lang))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = rec.ip,
                        fontSize = LoonTypeScale.subheadLarge,
                        fontWeight = FontWeight.Medium,
                        color = LoonTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = AppStrings.get("dns_copy_ip_desc", lang),
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (!rec.server.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = AppStrings.get("dns_server_label", lang),
                        fontSize = LoonTypeScale.footnote,
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
                            fontSize = LoonTypeScale.calloutLarge,
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
                        Text(text = AppStrings.get("dns_mode_label", lang), fontSize = LoonTypeScale.caption, color = LoonTextSecondary)
                        Text(
                            text = if (rec.isFakeIp) "Fake-IP" else AppStrings.get("dns_real_ip", lang),
                            fontSize = LoonTypeScale.callout,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rec.isFakeIp) IosBlue else LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = AppStrings.get("dns_type_label", lang), fontSize = LoonTypeScale.caption, color = LoonTextSecondary)
                        Text(
                            text = rec.type,
                            fontSize = LoonTypeScale.callout,
                            fontWeight = FontWeight.SemiBold,
                            color = LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = AppStrings.get("dns_latency_label", lang), fontSize = LoonTypeScale.caption, color = LoonTextSecondary)
                        Text(
                            text = if (rec.latencyMs <= 0) AppStrings.get("dns_cache_hit", lang) else "${rec.latencyMs}ms",
                            fontSize = LoonTypeScale.callout,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rec.latencyMs <= 0) LoonSuccessText else LoonTextPrimary
                        )
                    }
                    Column {
                        Text(text = AppStrings.get("dns_time_label", lang), fontSize = LoonTypeScale.caption, color = LoonTextSecondary)
                        Text(
                            text = formattedTime,
                            fontSize = LoonTypeScale.callout,
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
            title = AppStrings.get("dns_clear_confirm_title", lang),
            message = AppStrings.get("dns_clear_cache_confirm", lang),
            actions = listOf(
                CupertinoActionSheetItem(
                    text = AppStrings.get("dns_clear_all", lang),
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        DnsRepository.clear()
                        CupertinoToast.show(AppStrings.get("dns_cleared_toast", lang), isDestructive = true)
                    }
                )
            ),
            cancelText = AppStrings.get("cancel", lang)
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
    val lang = LocalAppLanguage.current
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
                fontSize = LoonTypeScale.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (record.latencyMs <= 0) {
                Text(
                    text = AppStrings.get("dns_cache_badge", lang),
                    color = LoonSuccessText,
                    fontSize = LoonTypeScale.footnote,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "${record.latencyMs}ms",
                    color = if (record.latencyMs > 100) LoonWarningText else LoonTextSecondary,
                    fontSize = LoonTypeScale.footnote,
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
                    fontSize = LoonTypeScale.callout,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (record.isFakeIp) {
                    Text(
                        text = " · Fake-IP",
                        color = IosBlue,
                        fontSize = LoonTypeScale.footnote,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formattedTime,
                color = LoonTextMuted,
                fontSize = LoonTypeScale.footnote
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
