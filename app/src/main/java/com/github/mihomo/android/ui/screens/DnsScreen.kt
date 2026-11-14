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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.data.DnsRecord
import com.github.mihomo.android.data.DnsRepository
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardCopy
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
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
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun filterDnsRecords(records: List<DnsRecord>, searchQuery: String): List<DnsRecord> {
    if (searchQuery.isBlank()) return records
    val query = searchQuery.trim()
    return records.filter { record ->
        record.domain.contains(query, ignoreCase = true) ||
            record.ip.contains(query, ignoreCase = true)
    }
}

@Composable
private fun DnsClearConfirmSheet(
    lang: String,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit
) {
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
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
    var activeDetailRecordId by remember { mutableStateOf<Long?>(null) }
    val activeDetailRecord = remember(records, activeDetailRecordId) {
        activeDetailRecordId?.let { id -> records.find { item -> item.id == id } }
    }
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) {
        DateTimeFormatter.ofPattern("HH:mm:ss", locale).withZone(ZoneId.systemDefault())
    }
    val filteredRecords = remember(records, searchQuery) { filterDnsRecords(records, searchQuery) }

    Column(modifier = Modifier.fillMaxSize().background(LoonBg).statusBarsPadding()) {
        DnsScreenNavBar(
            lang = lang,
            hasRecords = records.isNotEmpty(),
            onBack = onBack,
            onNavigateToSettings = onNavigateToSettings,
            onOpenClearSheet = { haptic.selection(); showClearActionSheet = true }
        )
        LoonResponsiveContent(modifier = Modifier.fillMaxWidth().weight(1f)) {
            DnsRecordsList(
                lang = lang,
                searchQuery = searchQuery,
                filteredRecords = filteredRecords,
                timeFormat = timeFormat,
                onSearchQueryChange = { query -> searchQuery = query },
                onSelectRecord = { id -> haptic.lightTick(); activeDetailRecordId = id }
            )
        }
    }

    activeDetailRecord?.let { rec ->
        DnsRecordDetailSheet(
            record = rec,
            lang = lang,
            timeFormat = timeFormat,
            onCopyText = { text -> copyToClipboard(text) },
            onDismiss = { activeDetailRecordId = null }
        )
    }
    if (showClearActionSheet) {
        DnsClearConfirmSheet(lang = lang, haptic = haptic, onDismiss = { showClearActionSheet = false })
    }
}

@Composable
private fun DnsScreenNavBar(
    lang: String,
    hasRecords: Boolean,
    onBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)?,
    onOpenClearSheet: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val actionShape = RoundedCornerShape(LoonRadii.control)

    CupertinoNavBar(
        title = AppStrings.get("dns_screen_title", lang),
        showBackButton = true,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onNavigateToSettings != null) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                            .clip(CircleShape)
                            .iosFocusRing(CircleShape)
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
                            modifier = Modifier.size(LoonSizes.icon)
                        )
                    }
                    Spacer(modifier = Modifier.width(LoonSpacing.xs))
                }
                if (hasRecords) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                            .clip(actionShape)
                            .iosFocusRing(actionShape)
                            .iosPressable(onClick = onOpenClearSheet)
                            .padding(horizontal = LoonSpacing.sm),
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
private fun DnsRecordsList(
    lang: String,
    searchQuery: String,
    filteredRecords: List<DnsRecord>,
    timeFormat: DateTimeFormatter,
    onSearchQueryChange: (String) -> Unit,
    onSelectRecord: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item(key = "dns_search_bar") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                CupertinoSearchBar(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = AppStrings.get("search_placeholder", lang)
                )
            }
        }

        if (filteredRecords.isEmpty()) {
            item(key = "dns_empty_state") {
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
                        Spacer(modifier = Modifier.height(LoonSpacing.md))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                AppStrings.get("dns_empty_search", lang)
                            } else {
                                AppStrings.get("dns_empty", lang)
                            },
                            color = LoonTextSecondary,
                            style = LoonTextStyles.body()
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
                val formattedTime = remember(record.timestamp, timeFormat) {
                    timeFormat.format(Instant.ofEpochMilli(record.timestamp))
                }
                DnsRecordCell(
                    record = record,
                    formattedTime = formattedTime,
                    onClick = { onSelectRecord(record.id) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DnsRecordDetailSheet(
    record: DnsRecord,
    lang: String,
    timeFormat: DateTimeFormatter,
    onCopyText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val formattedTime = remember(record.timestamp, timeFormat) {
        timeFormat.format(Instant.ofEpochMilli(record.timestamp))
    }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("dns_detail_title", lang)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LoonSpacing.xl)
                .padding(bottom = LoonSpacing.sm)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            DnsCopyableFieldCard(
                label = AppStrings.get("dns_field_domain", lang),
                value = record.domain,
                copyDesc = AppStrings.get("dns_copy_domain_desc", lang),
                isPrimaryWeight = true,
                onCopy = {
                    haptic.lightTick()
                    onCopyText(record.domain)
                    CupertinoToast.show(AppStrings.get("dns_copied_domain", lang))
                }
            )

            Spacer(modifier = Modifier.height(LoonSpacing.md))

            DnsCopyableFieldCard(
                label = AppStrings.get("dns_result_ip", lang),
                value = record.ip,
                copyDesc = AppStrings.get("dns_copy_ip_desc", lang),
                isPrimaryWeight = false,
                onCopy = {
                    haptic.lightTick()
                    onCopyText(record.ip)
                    CupertinoToast.show(AppStrings.get("dns_copied_ip", lang))
                }
            )

            if (!record.server.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = AppStrings.get("dns_server_label", lang),
                    style = LoonTextStyles.subtitleStrong(),
                    color = LoonTextSecondary
                )
                Spacer(modifier = Modifier.height(LoonSpacing.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(LoonRadii.control))
                        .background(LoonBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = record.server,
                        style = LoonTextStyles.calloutLarge(),
                        color = LoonTextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(LoonSpacing.lg))
            DnsDetailMetadataGrid(record = record, formattedTime = formattedTime, lang = lang)
        }
    }
}

@Composable
private fun DnsCopyableFieldCard(
    label: String,
    value: String,
    copyDesc: String,
    isPrimaryWeight: Boolean,
    onCopy: () -> Unit
) {
    val cardShape = RoundedCornerShape(LoonRadii.control)
    Text(
        text = label,
        style = LoonTextStyles.subtitle().copy(fontWeight = FontWeight.Medium),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(LoonSpacing.xs))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .clip(cardShape)
            .iosFocusRing(cardShape)
            .background(LoonBg)
            .iosPressable(onClick = onCopy)
            .padding(horizontal = 14.dp, vertical = LoonSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = value,
            style = LoonTextStyles.value().copy(
                fontWeight = if (isPrimaryWeight) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = LoonTextPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(LoonSpacing.sm))
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = copyDesc,
            tint = IosBlue,
            modifier = Modifier.size(LoonSizes.iconSmall)
        )
    }
}

@Composable
private fun DnsDetailMetadataGrid(
    record: DnsRecord,
    formattedTime: String,
    lang: String
) {
    val (latencyText, latencyColor) = when {
        record.isResolving -> AppStrings.get("dns_resolving", lang) to LoonTextSecondary
        record.isCacheHit -> AppStrings.get("dns_cache_badge", lang).ifBlank {
            AppStrings.get("dns_cache_hit", lang)
        } to LoonSuccessText
        record.latencyMs > 0 -> "${record.latencyMs}ms" to LoonTextPrimary
        else -> "--" to LoonTextSecondary
    }
    val resolvedLatencyText = if (record.isCacheHit) AppStrings.get("dns_cache_hit", lang) else latencyText

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = AppStrings.get("dns_mode_label", lang),
                style = LoonTextStyles.caption(),
                color = LoonTextSecondary
            )
            Text(
                text = if (record.isFakeIp) "Fake-IP" else AppStrings.get("dns_real_ip", lang),
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.SemiBold),
                color = if (record.isFakeIp) LoonInfoText else LoonTextPrimary
            )
        }
        Column {
            Text(
                text = AppStrings.get("dns_type_label", lang),
                style = LoonTextStyles.caption(),
                color = LoonTextSecondary
            )
            Text(
                text = record.type,
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.SemiBold),
                color = LoonTextPrimary
            )
        }
        Column {
            Text(
                text = AppStrings.get("dns_latency_label", lang),
                style = LoonTextStyles.caption(),
                color = LoonTextSecondary
            )
            Text(
                text = resolvedLatencyText,
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.SemiBold),
                color = latencyColor
            )
        }
        Column {
            Text(
                text = AppStrings.get("dns_time_label", lang),
                style = LoonTextStyles.caption(),
                color = LoonTextSecondary
            )
            Text(
                text = formattedTime,
                style = LoonTextStyles.callout(),
                color = LoonTextSecondary
            )
        }
    }
}

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
                text = record.domain,
                color = LoonTextPrimary,
                style = LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(LoonSpacing.sm))

            DnsRecordLatencyBadge(record = record, lang = lang)
        }

        Spacer(modifier = Modifier.height(LoonSpacing.xs))

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
                    style = LoonTextStyles.callout(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (record.isFakeIp) {
                    Text(
                        text = " · Fake-IP",
                        color = LoonInfoText,
                        style = LoonTextStyles.subtitle().copy(fontWeight = FontWeight.Medium)
                    )
                }
            }

            Spacer(modifier = Modifier.width(LoonSpacing.sm))

            Text(
                text = formattedTime,
                color = LoonTextMuted,
                style = LoonTextStyles.subtitle()
            )
        }
    }
}

@Composable
private fun DnsRecordLatencyBadge(record: DnsRecord, lang: String) {
    when {
        record.isResolving -> Text(
            text = AppStrings.get("dns_resolving", lang),
            color = LoonTextMuted,
            style = LoonTextStyles.subtitle()
        )
        record.isCacheHit -> Text(
            text = AppStrings.get("dns_cache_badge", lang),
            color = LoonSuccessText,
            style = LoonTextStyles.subtitle().copy(fontWeight = FontWeight.Medium)
        )
        record.latencyMs > 0 -> Text(
            text = "${record.latencyMs}ms",
            color = if (record.latencyMs > 100) LoonWarningText else LoonTextSecondary,
            style = LoonTextStyles.subtitle()
        )
        else -> Text(
            text = record.type,
            color = LoonTextSecondary,
            style = LoonTextStyles.subtitle()
        )
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
