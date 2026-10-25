package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.ParsedRule
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

private enum class RuleFilter(val labelKey: String) {
    ALL("log_level_all"),
    PROXY("filter_proxy"),
    DIRECT("filter_direct"),
    REJECT("filter_reject")
}

private data class IndexedRule(
    val index: Int,
    val rule: ParsedRule
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UnusedParameter", "UNUSED_PARAMETER")
@Composable
fun RulesScreen(
    rules: List<ParsedRule>,
    totalRules: Int,
    tunnelMode: TunnelMode,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(RuleFilter.ALL) }
    var showFilterActionSheet by remember { mutableStateOf(false) }
    var activeDetailRule by remember { mutableStateOf<IndexedRule?>(null) }

    // Index original rules so rule priority order is accurately maintained
    val indexedRules = remember(rules) {
        rules.mapIndexed { idx, rule -> IndexedRule(idx, rule) }
    }

    // Filter rules by segment and search query
    val filteredRules = remember(indexedRules, searchQuery, selectedFilter) {
        val query = searchQuery.trim().lowercase()
        indexedRules.filter { indexed ->
            val rule = indexed.rule

            // Filter match (traffic direction)
            val filterMatch = when (selectedFilter) {
                RuleFilter.ALL -> true
                RuleFilter.PROXY -> !rule.target.equals("DIRECT", ignoreCase = true) && !rule.target.equals("REJECT", ignoreCase = true)
                RuleFilter.DIRECT -> rule.target.equals("DIRECT", ignoreCase = true)
                RuleFilter.REJECT -> rule.target.equals("REJECT", ignoreCase = true)
            }
            if (!filterMatch) return@filter false

            // Search query match
            if (query.isEmpty()) return@filter true
            rule.payload.lowercase().contains(query) ||
                rule.target.lowercase().contains(query) ||
                rule.type.lowercase().contains(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Navigation Bar: Centered title, back button, iOS style
        CupertinoNavBar(
            title = AppStrings.get("rules_screen_title", lang),
            showBackButton = true,
            onBack = onBack,
            actions = {
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
                            contentDescription = AppStrings.get("rules_filter_desc", lang),
                            tint = if (selectedFilter != RuleFilter.ALL) IosBlue else LoonTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        if (selectedFilter != RuleFilter.ALL) {
                            Text(
                                text = AppStrings.get(selectedFilter.labelKey, lang),
                                color = IosBlue,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        )

        // Main Scroll Area: Fluid search & Grouped rules
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            // Mode Awareness Notice (Only shown if mode is not RULE)
            if (tunnelMode != TunnelMode.RULE) {
                item {
                    val noticeText = if (tunnelMode == TunnelMode.GLOBAL) {
                        AppStrings.get("rules_notice_global", lang)
                    } else {
                        AppStrings.get("rules_notice_direct", lang)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(IosOrange.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = IosOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = noticeText,
                            color = IosOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

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
                        placeholder = AppStrings.get("rules_search_placeholder", lang)
                    )
                }
            }

            // List Content or Empty State
            if (filteredRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Rule,
                                contentDescription = null,
                                tint = LoonTextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) AppStrings.get("rules_no_match", lang) else AppStrings.get("rules_empty", lang),
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                val sectionHeader = when {
                    searchQuery.isNotBlank() -> AppStrings.get("search_results", lang).replace("{n}", filteredRules.size.toString())
                    selectedFilter == RuleFilter.PROXY -> AppStrings.get("rules_section_proxy", lang).replace("{n}", filteredRules.size.toString())
                    selectedFilter == RuleFilter.DIRECT -> AppStrings.get("rules_section_direct", lang).replace("{n}", filteredRules.size.toString())
                    selectedFilter == RuleFilter.REJECT -> AppStrings.get("rules_section_reject", lang).replace("{n}", filteredRules.size.toString())
                    totalRules > filteredRules.size -> AppStrings.get("rules_section_all_total", lang).replace("{n}", filteredRules.size.toString()).replace("{m}", totalRules.toString())
                    else -> AppStrings.get("rules_section_all", lang).replace("{n}", filteredRules.size.toString())
                }

                cupertinoGroupedItems(
                    items = filteredRules,
                    header = sectionHeader,
                    key = { _, indexedRule -> indexedRule.index },
                    dividerStartIndent = 16.dp
                ) { _, indexedRule ->
                    RuleCell(
                        indexedRule = indexedRule,
                        onClick = {
                            haptic.lightTick()
                            activeDetailRule = indexedRule
                        }
                    )
                }
            }
        }
    }

    // Detail Inspector: Pure iOS CupertinoModalBottomSheet
    if (activeDetailRule != null) {
        val indexed = activeDetailRule!!
        val rule = indexed.rule

        val rawRuleText = remember(rule) {
            buildString {
                append(rule.type)
                if (rule.payload.isNotBlank()) {
                    append(",")
                    append(rule.payload)
                }
                append(",")
                append(rule.target)
                if (rule.noResolve) {
                    append(",no-resolve")
                }
            }
        }

        CupertinoModalBottomSheet(
            onDismissRequest = { activeDetailRule = null },
            title = AppStrings.get("rules_detail_title", lang),
            trailingAction = {
                Text(
                    text = AppStrings.get("rules_priority", lang).replace("{n}", (indexed.index + 1).toString()),
                    color = IosBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
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

                // Card 1: 完整语法 (Raw Rule Syntax)
                Text(
                    text = AppStrings.get("rules_syntax_label", lang),
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
                        .iosPressable {
                            haptic.lightTick()
                            clipboardManager.setText(AnnotatedString(rawRuleText))
                            CupertinoToast.show(AppStrings.get("rules_syntax_copied", lang))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = rawRuleText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LoonTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = AppStrings.get("rules_copy_desc", lang),
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: 规则解析属性
                Text(
                    text = AppStrings.get("rules_attributes", lang),
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
                        label = AppStrings.get("rules_field_type", lang),
                        value = "${rule.type} (${getRuleTypeDescription(rule.type, lang)})"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("rules_field_payload", lang),
                        value = rule.payload.ifBlank { AppStrings.get("rules_match_all", lang) },
                        showCopy = rule.payload.isNotBlank(),
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(rule.payload))
                            CupertinoToast.show(AppStrings.get("rules_payload_copied", lang))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("rules_field_target", lang),
                        value = rule.target,
                        valueColor = when {
                            rule.target.equals("REJECT", ignoreCase = true) -> RedDanger
                            rule.target.equals("DIRECT", ignoreCase = true) -> LoonTextSecondary
                            else -> IosBlue
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("rules_field_dns", lang),
                        value = if (rule.noResolve) AppStrings.get("rules_no_resolve", lang) else AppStrings.get("rules_standard_resolve", lang)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(
                        label = AppStrings.get("rules_field_order", lang),
                        value = AppStrings.get("rules_order_value", lang).replace("{n}", (indexed.index + 1).toString())
                    )
                }
            }
        }
    }

    // iOS Cupertino Action Sheet for Rule Filtering
    if (showFilterActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showFilterActionSheet = false },
            title = AppStrings.get("rules_filter_title", lang),
            message = AppStrings.get("rules_filter_message", lang).replace("{a}", filteredRules.size.toString()).replace("{b}", indexedRules.size.toString()),
            actions = RuleFilter.entries.map { filter ->
                CupertinoActionSheetItem(
                    text = if (filter == selectedFilter) AppStrings.get(filter.labelKey, lang) + "  ✓" else AppStrings.get(filter.labelKey, lang),
                    onClick = {
                        haptic.selection()
                        selectedFilter = filter
                        showFilterActionSheet = false
                    }
                )
            },
            cancelText = AppStrings.get("cancel", lang)
        )
    }
}

/**
 * Pure, high signal-to-noise ratio Rule Row Cell.
 * Preserves full width for domain / pattern expressions, linear metadata typography with middots.
 */
@Composable
private fun RuleCell(
    indexedRule: IndexedRule,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val rule = indexedRule.rule

    val (targetColor, targetText) = when {
        rule.target.equals("REJECT", ignoreCase = true) -> Pair(RedDanger, "REJECT")
        rule.target.equals("DIRECT", ignoreCase = true) -> Pair(LoonTextSecondary, "DIRECT")
        else -> Pair(IosBlue, rule.target)
    }

    val displayPayload = when {
        rule.type.equals("MATCH", ignoreCase = true) -> AppStrings.get("rules_match_all_badge", lang)
        rule.payload.isBlank() -> rule.type
        else -> rule.payload
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        // Line 1: Pattern / Domain Expression + Target Outbound Policy
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayPayload,
                color = LoonTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = targetText,
                color = targetColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Line 2: #Index · Rule Type · no-resolve
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#${indexedRule.index + 1}",
                color = LoonTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )

            Text(
                text = " · ",
                color = LoonTextMuted,
                fontSize = 12.sp
            )

            Text(
                text = rule.type,
                color = LoonTextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (rule.noResolve) {
                Text(
                    text = " · ",
                    color = LoonTextMuted,
                    fontSize = 12.sp
                )
                Text(
                    text = "no-resolve",
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
            if (showCopy && (onCopy != null)) {
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

private fun getRuleTypeDescription(type: String, lang: String): String {
    return when (type.uppercase()) {
        "DOMAIN-SUFFIX" -> AppStrings.get("rule_type_domain_suffix", lang)
        "DOMAIN-KEYWORD" -> AppStrings.get("rule_type_domain_keyword", lang)
        "DOMAIN" -> AppStrings.get("rule_type_domain", lang)
        "IP-CIDR", "IP-CIDR6" -> AppStrings.get("rule_type_ip_cidr", lang)
        "GEOIP" -> AppStrings.get("rule_type_geoip", lang)
        "RULE-SET" -> AppStrings.get("rule_type_rule_set", lang)
        "PROCESS-NAME" -> AppStrings.get("rule_type_process", lang)
        "PORT" -> AppStrings.get("rule_type_port", lang)
        "MATCH" -> AppStrings.get("rule_type_match", lang)
        else -> AppStrings.get("rule_type_custom", lang)
    }
}

@Preview(showBackground = true)
@Composable
fun RulesScreenPreview() {
    val sampleRules = listOf(
        ParsedRule("DOMAIN-SUFFIX", "google.com", "🔰 节点选择"),
        ParsedRule("DOMAIN-SUFFIX", "github.com", "🇭🇰 香港 01"),
        ParsedRule("DOMAIN-KEYWORD", "youtube", "🔰 节点选择"),
        ParsedRule("RULE-SET", "advertising", "REJECT"),
        ParsedRule("GEOIP", "CN", "DIRECT"),
        ParsedRule("IP-CIDR", "192.168.0.0/16", "DIRECT", noResolve = true),
        ParsedRule("MATCH", "", "🐟 漏网之鱼")
    )
    RulesScreen(
        rules = sampleRules,
        totalRules = sampleRules.size,
        tunnelMode = TunnelMode.RULE,
        onBack = {}
    )
}
