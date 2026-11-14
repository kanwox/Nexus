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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.ParsedRule
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoDetailRow
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardCopy
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.delay
import java.util.Locale

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

private data class RulesListData(
    val tunnelMode: TunnelMode,
    val selectedFilter: RuleFilter,
    val totalRules: Int,
    val filteredRules: List<IndexedRule>
)

private data class RulesSheetsState(
    val rules: List<ParsedRule>,
    val totalCount: Int,
    val filteredCount: Int,
    val selectedFilter: RuleFilter,
    val activeDetailRule: IndexedRule?,
    val showFilterSheet: Boolean,
    val showRuleTester: Boolean,
    val testerInput: String
)

private data class RulesSheetsCallbacks(
    val onCopy: (String) -> Unit,
    val onSelectFilter: (RuleFilter) -> Unit,
    val onDismissDetail: () -> Unit,
    val onDismissFilter: () -> Unit,
    val onTesterInputChange: (String) -> Unit,
    val onDismissTester: () -> Unit
)

private fun filterIndexedRules(
    indexedRules: List<IndexedRule>,
    searchQuery: String,
    selectedFilter: RuleFilter
): List<IndexedRule> {
    val query = searchQuery.trim().lowercase(Locale.ROOT)
    return indexedRules.filter { indexed ->
        val rule = indexed.rule
        val filterMatch = when (selectedFilter) {
            RuleFilter.ALL -> true
            RuleFilter.PROXY -> !rule.target.equals("DIRECT", ignoreCase = true) &&
                !rule.target.equals("REJECT", ignoreCase = true)
            RuleFilter.DIRECT -> rule.target.equals("DIRECT", ignoreCase = true)
            RuleFilter.REJECT -> rule.target.equals("REJECT", ignoreCase = true)
        }
        if (!filterMatch) return@filter false
        if (query.isEmpty()) return@filter true
        rule.payload.lowercase(Locale.ROOT).contains(query) ||
            rule.target.lowercase(Locale.ROOT).contains(query) ||
            rule.type.lowercase(Locale.ROOT).contains(query)
    }
}

@Composable
fun RulesScreen(
    rules: List<ParsedRule>,
    totalRules: Int,
    tunnelMode: TunnelMode,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val copyToClipboard = rememberClipboardCopy()
    val haptic = rememberIosHaptic()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(RuleFilter.ALL) }
    var showFilterActionSheet by remember { mutableStateOf(false) }
    var activeDetailRule by remember { mutableStateOf<IndexedRule?>(null) }
    var showRuleTester by remember { mutableStateOf(false) }
    var testerInput by remember { mutableStateOf("") }

    val indexedRules = remember(rules) { rules.mapIndexed { idx, rule -> IndexedRule(idx, rule) } }
    val filteredRules = remember(indexedRules, searchQuery, selectedFilter) {
        filterIndexedRules(indexedRules, searchQuery, selectedFilter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        RulesNavBar(
            lang = lang,
            selectedFilter = selectedFilter,
            onBack = onBack,
            onOpenFilter = { haptic.selection(); showFilterActionSheet = true },
            onOpenTester = { haptic.selection(); showRuleTester = true }
        )

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            RulesListContent(
                lang = lang,
                searchQuery = searchQuery,
                data = RulesListData(
                    tunnelMode = tunnelMode,
                    selectedFilter = selectedFilter,
                    totalRules = totalRules,
                    filteredRules = filteredRules
                ),
                onSearchQueryChange = { query -> searchQuery = query },
                onSelectRule = { indexed -> haptic.lightTick(); activeDetailRule = indexed }
            )
        }
    }

    RulesSheetsHost(
        lang = lang,
        state = RulesSheetsState(
            rules = rules,
            totalCount = indexedRules.size,
            filteredCount = filteredRules.size,
            selectedFilter = selectedFilter,
            activeDetailRule = activeDetailRule,
            showFilterSheet = showFilterActionSheet,
            showRuleTester = showRuleTester,
            testerInput = testerInput
        ),
        callbacks = RulesSheetsCallbacks(
            onCopy = { text -> copyToClipboard(text) },
            onSelectFilter = { filter -> haptic.selection(); selectedFilter = filter; showFilterActionSheet = false },
            onDismissDetail = { activeDetailRule = null },
            onDismissFilter = { showFilterActionSheet = false },
            onTesterInputChange = { input -> testerInput = input },
            onDismissTester = { showRuleTester = false }
        )
    )
}

@Composable
private fun RulesSheetsHost(
    lang: String,
    state: RulesSheetsState,
    callbacks: RulesSheetsCallbacks
) {
    state.activeDetailRule?.let { indexed ->
        RuleDetailSheet(
            indexed = indexed,
            lang = lang,
            onCopy = callbacks.onCopy,
            onDismiss = callbacks.onDismissDetail
        )
    }
    if (state.showFilterSheet) {
        RuleFilterActionSheet(
            lang = lang,
            selectedFilter = state.selectedFilter,
            filteredCount = state.filteredCount,
            totalCount = state.totalCount,
            onSelectFilter = callbacks.onSelectFilter,
            onDismiss = callbacks.onDismissFilter
        )
    }
    if (state.showRuleTester) {
        RuleTesterSheet(
            lang = lang,
            rules = state.rules,
            testerInput = state.testerInput,
            onTesterInputChange = callbacks.onTesterInputChange,
            onDismiss = callbacks.onDismissTester
        )
    }
}

@Composable
private fun RulesNavBar(
    lang: String,
    selectedFilter: RuleFilter,
    onBack: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenTester: () -> Unit
) {
    val actionShape = RoundedCornerShape(LoonRadii.control)
    CupertinoNavBar(
        title = AppStrings.get("rules_screen_title", lang),
        showBackButton = true,
        onBack = onBack,
        actions = {
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
                        contentDescription = AppStrings.get("rules_filter_desc", lang),
                        tint = if (selectedFilter != RuleFilter.ALL) IosBlue else LoonTextSecondary,
                        modifier = Modifier.size(LoonSizes.icon)
                    )
                    if (selectedFilter != RuleFilter.ALL) {
                        Text(
                            text = AppStrings.get(selectedFilter.labelKey, lang),
                            color = LoonInfoText,
                            style = LoonTextStyles.calloutLarge().copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                    .clip(actionShape)
                    .iosFocusRing(actionShape)
                    .iosPressable(onClick = onOpenTester)
                    .padding(horizontal = LoonSpacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = AppStrings.get("rules_test", lang),
                    color = LoonInfoText,
                    style = LoonTextStyles.button()
                )
            }
        }
    )
}

@Composable
private fun RulesListContent(
    lang: String,
    searchQuery: String,
    data: RulesListData,
    onSearchQueryChange: (String) -> Unit,
    onSelectRule: (IndexedRule) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        if (data.tunnelMode != TunnelMode.RULE) {
            item(key = "rules_mode_notice") {
                RulesModeNoticeBanner(tunnelMode = data.tunnelMode, lang = lang)
            }
        }

        item(key = "rules_search_bar") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                CupertinoSearchBar(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = AppStrings.get("rules_search_placeholder", lang)
                )
            }
        }

        if (data.filteredRules.isEmpty()) {
            item(key = "rules_empty") {
                RulesEmptyPlaceholder(searchQuery = searchQuery, lang = lang)
            }
        } else {
            val sectionHeader = resolveRulesSectionHeader(
                lang = lang,
                searchQuery = searchQuery,
                selectedFilter = data.selectedFilter,
                filteredCount = data.filteredRules.size,
                totalRules = data.totalRules
            )
            cupertinoGroupedItems(
                items = data.filteredRules,
                header = sectionHeader,
                key = { _, indexedRule -> indexedRule.index },
                dividerStartIndent = 16.dp
            ) { _, indexedRule ->
                RuleCell(
                    indexedRule = indexedRule,
                    onClick = { onSelectRule(indexedRule) }
                )
            }
        }
    }
}

@Composable
private fun RulesModeNoticeBanner(tunnelMode: TunnelMode, lang: String) {
    val noticeText = if (tunnelMode == TunnelMode.GLOBAL) {
        AppStrings.get("rules_notice_global", lang)
    } else {
        AppStrings.get("rules_notice_direct", lang)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(LoonRadii.control))
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
            color = LoonWarningText,
            style = LoonTextStyles.callout().copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun RulesEmptyPlaceholder(searchQuery: String, lang: String) {
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
            Spacer(modifier = Modifier.height(LoonSpacing.md))
            Text(
                text = if (searchQuery.isNotBlank()) AppStrings.get("rules_no_match", lang)
                else AppStrings.get("rules_empty", lang),
                color = LoonTextSecondary,
                style = LoonTextStyles.body()
            )
        }
    }
}

private fun resolveRulesSectionHeader(
    lang: String,
    searchQuery: String,
    selectedFilter: RuleFilter,
    filteredCount: Int,
    totalRules: Int
): String = when {
    searchQuery.isNotBlank() -> AppStrings.get("search_results", lang).replace("{n}", filteredCount.toString())
    selectedFilter == RuleFilter.PROXY -> AppStrings.get("rules_section_proxy", lang).replace("{n}", filteredCount.toString())
    selectedFilter == RuleFilter.DIRECT -> AppStrings.get("rules_section_direct", lang).replace("{n}", filteredCount.toString())
    selectedFilter == RuleFilter.REJECT -> AppStrings.get("rules_section_reject", lang).replace("{n}", filteredCount.toString())
    totalRules > filteredCount -> AppStrings.get("rules_section_all_total", lang)
        .replace("{n}", filteredCount.toString())
        .replace("{m}", totalRules.toString())
    else -> AppStrings.get("rules_section_all", lang).replace("{n}", filteredCount.toString())
}

private fun formatRawRuleText(rule: ParsedRule): String = buildString {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDetailSheet(
    indexed: IndexedRule,
    lang: String,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val rule = indexed.rule
    val rawRuleText = remember(rule) { formatRawRuleText(rule) }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("rules_detail_title", lang),
        trailingAction = {
            Text(
                text = AppStrings.get("rules_priority", lang).replace("{n}", (indexed.index + 1).toString()),
                color = LoonInfoText,
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.SemiBold)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LoonSpacing.xl)
                .padding(bottom = LoonSpacing.sm)
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            RuleSyntaxCopyCard(rawRuleText = rawRuleText, lang = lang, onCopy = onCopy)
            Spacer(modifier = Modifier.height(14.dp))
            RuleAttributesCard(indexed = indexed, lang = lang, onCopy = onCopy)
        }
    }
}

@Composable
private fun RuleSyntaxCopyCard(
    rawRuleText: String,
    lang: String,
    onCopy: (String) -> Unit
) {
    val haptic = rememberIosHaptic()
    val cardShape = RoundedCornerShape(LoonRadii.control)

    Text(
        text = AppStrings.get("rules_syntax_label", lang),
        style = LoonTextStyles.subtitleStrong(),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .clip(cardShape)
            .iosFocusRing(cardShape)
            .background(LoonBg)
            .iosPressable {
                haptic.lightTick()
                onCopy(rawRuleText)
                CupertinoToast.show(AppStrings.get("rules_syntax_copied", lang))
            }
            .padding(horizontal = 14.dp, vertical = LoonSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = rawRuleText,
            style = LoonTextStyles.value().copy(fontWeight = FontWeight.SemiBold),
            color = LoonTextPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(LoonSpacing.sm))
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = AppStrings.get("rules_copy_desc", lang),
            tint = IosBlue,
            modifier = Modifier.size(LoonSizes.iconSmall)
        )
    }
}

@Composable
private fun RuleAttributesCard(
    indexed: IndexedRule,
    lang: String,
    onCopy: (String) -> Unit
) {
    val rule = indexed.rule
    val cardShape = RoundedCornerShape(LoonRadii.control)

    Text(
        text = AppStrings.get("rules_attributes", lang),
        style = LoonTextStyles.subtitleStrong(),
        color = LoonTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LoonBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(LoonSpacing.sm)
    ) {
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_type", lang),
            value = "${rule.type} (${getRuleTypeDescription(rule.type, lang)})"
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_payload", lang),
            value = rule.payload.ifBlank { AppStrings.get("rules_match_all", lang) },
            showCopy = rule.payload.isNotBlank(),
            onCopy = {
                onCopy(rule.payload)
                CupertinoToast.show(AppStrings.get("rules_payload_copied", lang))
            }
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_target", lang),
            value = rule.target,
            valueColor = when {
                rule.target.equals("REJECT", ignoreCase = true) -> LoonDangerText
                rule.target.equals("DIRECT", ignoreCase = true) -> LoonTextSecondary
                else -> LoonInfoText
            }
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_dns", lang),
            value = if (rule.noResolve) AppStrings.get("rules_no_resolve", lang)
            else AppStrings.get("rules_standard_resolve", lang)
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_order", lang),
            value = AppStrings.get("rules_order_value", lang).replace("{n}", (indexed.index + 1).toString())
        )
    }
}

@Composable
private fun RuleFilterActionSheet(
    lang: String,
    selectedFilter: RuleFilter,
    filteredCount: Int,
    totalCount: Int,
    onSelectFilter: (RuleFilter) -> Unit,
    onDismiss: () -> Unit
) {
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("rules_filter_title", lang),
        message = AppStrings.get("rules_filter_message", lang)
            .replace("{a}", filteredCount.toString())
            .replace("{b}", totalCount.toString()),
        actions = RuleFilter.entries.map { filter ->
            CupertinoActionSheetItem(
                text = if (filter == selectedFilter) {
                    AppStrings.get("filter_selected_mark", lang)
                        .replace("{label}", AppStrings.get(filter.labelKey, lang))
                } else {
                    AppStrings.get(filter.labelKey, lang)
                },
                onClick = { onSelectFilter(filter) }
            )
        },
        cancelText = AppStrings.get("cancel", lang)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleTesterSheet(
    lang: String,
    rules: List<ParsedRule>,
    testerInput: String,
    onTesterInputChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var debouncedQuery by remember { mutableStateOf(testerInput.trim()) }
    LaunchedEffect(testerInput, rules.size) {
        val trimmed = testerInput.trim()
        if (trimmed.isEmpty() || rules.size <= 500) {
            debouncedQuery = trimmed
        } else {
            delay(150L)
            debouncedQuery = trimmed
        }
    }
    val testerMatch = remember(debouncedQuery, rules) {
        if (debouncedQuery.isEmpty()) null else matchRule(debouncedQuery, rules)
    }

    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("rules_test", lang),
        doneText = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LoonSpacing.xl)
                .padding(top = 6.dp, bottom = LoonSpacing.md)
        ) {
            CupertinoTextField(
                value = testerInput,
                onValueChange = onTesterInputChange,
                placeholder = AppStrings.get("rules_test_hint", lang),
                singleLine = true
            )

            if (debouncedQuery.isNotEmpty()) {
                Spacer(modifier = Modifier.height(LoonSpacing.lg))
                if (testerMatch == null) {
                    Text(
                        text = AppStrings.get("rules_test_no_match", lang),
                        color = LoonTextSecondary,
                        style = LoonTextStyles.callout()
                    )
                } else {
                    RuleTesterMatchCard(testerMatch = testerMatch, lang = lang)
                }
            }
        }
    }
}

@Composable
private fun RuleTesterMatchCard(
    testerMatch: RuleMatchResult,
    lang: String
) {
    val matched = testerMatch.rule
    Text(
        text = AppStrings.get("rules_test_match", lang),
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
            label = AppStrings.get("rules_field_type", lang),
            value = "${matched.type} (${getRuleTypeDescription(matched.type, lang)})"
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_payload", lang),
            value = matched.payload.ifBlank { AppStrings.get("rules_match_all", lang) }
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_target", lang),
            value = matched.target,
            valueColor = when {
                matched.target.equals("REJECT", ignoreCase = true) -> LoonDangerText
                matched.target.equals("DIRECT", ignoreCase = true) -> LoonTextSecondary
                else -> LoonInfoText
            }
        )
        CupertinoDetailRow(
            label = AppStrings.get("rules_field_order", lang),
            value = AppStrings.get("rules_order_value", lang).replace("{n}", (testerMatch.index + 1).toString())
        )
    }
    if (testerMatch.skippedDynamicRules > 0) {
        Spacer(modifier = Modifier.height(LoonSpacing.sm))
        Text(
            text = AppStrings.get("rules_test_skipped_notice", lang)
                .replace("{n}", testerMatch.skippedDynamicRules.toString()),
            color = LoonWarningText,
            style = LoonTextStyles.subtitle()
        )
    }
}

@Composable
private fun RuleCell(
    indexedRule: IndexedRule,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val rule = indexedRule.rule

    val (targetColor, targetText) = when {
        rule.target.equals("REJECT", ignoreCase = true) -> Pair(LoonDangerText, "REJECT")
        rule.target.equals("DIRECT", ignoreCase = true) -> Pair(LoonTextSecondary, "DIRECT")
        else -> Pair(LoonInfoText, rule.target)
    }

    val displayPayload = when {
        rule.type.equals("MATCH", ignoreCase = true) -> AppStrings.get("rules_match_all_badge", lang)
        rule.payload.isBlank() -> rule.type
        else -> rule.payload
    }

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
                text = displayPayload,
                color = LoonTextPrimary,
                style = LoonTextStyles.body().copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(LoonSpacing.sm))

            Text(
                text = targetText,
                color = targetColor,
                style = LoonTextStyles.callout().copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(LoonSpacing.xs))
        RuleCellMetaRow(index = indexedRule.index, rule = rule)
    }
}

@Composable
private fun RuleCellMetaRow(
    index: Int,
    rule: ParsedRule
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#${index + 1}",
            color = LoonTextMuted,
            style = LoonTextStyles.subtitle()
        )
        Text(text = " · ", color = LoonTextMuted, style = LoonTextStyles.subtitle())
        Text(
            text = rule.type,
            color = LoonTextSecondary,
            style = LoonTextStyles.subtitle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (rule.noResolve) {
            Text(text = " · ", color = LoonTextMuted, style = LoonTextStyles.subtitle())
            Text(text = "no-resolve", color = LoonTextMuted, style = LoonTextStyles.subtitle())
        }
    }
}

private fun getRuleTypeDescription(type: String, lang: String): String =
    when (type.uppercase(Locale.ROOT)) {
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

internal data class RuleMatchResult(
    val rule: ParsedRule,
    val index: Int,
    val skippedDynamicRules: Int
)

private val DYNAMIC_RULE_TYPES = setOf(
    "GEOIP", "GEOSITE", "RULE-SET", "PROCESS-NAME", "PROCESS-PATH", "AND", "OR", "NOT", "SUB-RULE"
)

private class RuleMatchTarget(
    val hostPart: String,
    val lowerHost: String,
    val isDomainHost: Boolean,
    val ipBytes: ByteArray?,
    val portPart: Int?
)

/**
 * Pure local rule matching for the tester (no core round-trip).
 */
internal fun matchRule(target: String, rules: List<ParsedRule>): RuleMatchResult? {
    val raw = target.trim()
    if (raw.isEmpty()) return null

    val (hostPart, portPart) = splitHostAndPort(raw)
    val ipBytes = if (hostPart.isNotEmpty()) parseIpLiteral(hostPart) else null
    val matchTarget = RuleMatchTarget(
        hostPart = hostPart,
        lowerHost = hostPart.lowercase(Locale.ROOT),
        isDomainHost = hostPart.isNotEmpty() && ipBytes == null,
        ipBytes = ipBytes,
        portPart = portPart
    )
    var skippedDynamic = 0

    rules.forEachIndexed { index, rule ->
        val ruleType = rule.type.uppercase(Locale.ROOT)
        if (ruleType in DYNAMIC_RULE_TYPES) {
            skippedDynamic++
        } else if (matchesStaticRule(ruleType, rule.payload, matchTarget)) {
            return RuleMatchResult(rule, index, skippedDynamic)
        }
    }
    return null
}

private fun matchesStaticRule(
    ruleType: String,
    payload: String,
    target: RuleMatchTarget
): Boolean = when (ruleType) {
    "DOMAIN" -> target.isDomainHost && payload.equals(target.hostPart, ignoreCase = true)
    "DOMAIN-SUFFIX" -> target.isDomainHost &&
        isDomainSuffixMatch(target.lowerHost, payload.lowercase(Locale.ROOT))
    "DOMAIN-KEYWORD" -> target.isDomainHost &&
        target.lowerHost.contains(payload.lowercase(Locale.ROOT))
    "IP-CIDR", "IP-CIDR6" -> target.ipBytes != null && isIpInCidr(target.ipBytes, payload)
    "DST-PORT", "PORT" -> target.portPart != null && isPortMatch(target.portPart, payload)
    "MATCH" -> true
    else -> false
}

private fun splitHostAndPort(input: String): Pair<String, Int?> {
    val purePort = input.toIntOrNull()
    if (purePort != null && purePort in 1..65535) {
        return "" to purePort
    }
    if (input.startsWith("[")) {
        val closeIdx = input.indexOf(']')
        if (closeIdx > 1) {
            val host = input.substring(1, closeIdx)
            val rest = input.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull()?.takeIf { num -> num in 1..65535 } else null
            return host to port
        }
    }
    val firstColon = input.indexOf(':')
    val lastColon = input.lastIndexOf(':')
    if (firstColon > 0 && firstColon == lastColon) {
        val maybePort = input.substring(lastColon + 1).toIntOrNull()
        if (maybePort != null && maybePort in 1..65535) {
            return input.substring(0, lastColon) to maybePort
        }
    }
    return input to null
}

private fun isPortMatch(port: Int, payload: String): Boolean {
    val tokens = payload.split('/', ',')
    for (rawToken in tokens) {
        val token = rawToken.trim()
        if (token.isEmpty()) continue
        val dashIdx = token.indexOf('-')
        if (dashIdx > 0) {
            val start = token.substring(0, dashIdx).trim().toIntOrNull() ?: continue
            val end = token.substring(dashIdx + 1).trim().toIntOrNull() ?: continue
            if (port in start..end) return true
        } else {
            val exact = token.toIntOrNull() ?: continue
            if (port == exact) return true
        }
    }
    return false
}

private fun isDomainSuffixMatch(input: String, suffix: String): Boolean {
    if (suffix.isEmpty()) return false
    return input == suffix || input.endsWith(".$suffix")
}

private fun isIpInCidr(ipBytes: ByteArray, cidr: String): Boolean {
    val parts = cidr.split('/')
    if (parts.size != 2) return false
    val prefixLen = parts[1].trim().toIntOrNull() ?: return false
    val networkBytes = parseIpLiteral(parts[0].trim()) ?: return false
    if (networkBytes.size != ipBytes.size) return false
    if (prefixLen < 0 || prefixLen > networkBytes.size * 8) return false

    var remaining = prefixLen
    for (i in ipBytes.indices) {
        if (remaining <= 0) break
        val address = ipBytes[i].toInt() and 0xFF
        val network = networkBytes[i].toInt() and 0xFF
        if (remaining >= 8) {
            if (address != network) return false
            remaining -= 8
        } else {
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            if ((address and mask) != (network and mask)) return false
            remaining = 0
        }
    }
    return true
}

/**
 * Hand-written IP literal parsing instead of InetAddress.getByName: the latter performs
 * DNS resolution for hostnames, which throws NetworkOnMainThreadException on the UI thread.
 */
private fun parseIpLiteral(value: String): ByteArray? {
    if (value.contains(':')) return parseIpv6Bytes(value)
    return parseIpv4Bytes(value)
}

private fun parseIpv4Bytes(raw: String): ByteArray? {
    val parts = raw.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    parts.forEachIndexed { index, part ->
        val octet = part.toIntOrNull() ?: return null
        if (octet < 0 || octet > 255) return null
        bytes[index] = octet.toByte()
    }
    return bytes
}

private fun parseIpv6Bytes(raw: String): ByteArray? {
    var address = raw.trim()
    val zoneIndex = address.indexOf('%')
    if (zoneIndex >= 0) address = address.substring(0, zoneIndex)
    if (address.isEmpty()) return null

    val halves = address.split("::")
    if (halves.size > 2) return null
    val isShorthand = halves.size == 2

    val headGroups = expandIpv6Groups(halves[0]) ?: return null
    val tailGroups = if (isShorthand) (expandIpv6Groups(halves[1]) ?: return null) else emptyList()

    val groupCount = headGroups.size + tailGroups.size
    if (groupCount > 8) return null
    if (!isShorthand && groupCount != 8) return null

    val bytes = ByteArray(16)
    headGroups.forEachIndexed { index, group -> writeIpv6Group(bytes, index, group) }
    val zeroPadding = 8 - groupCount
    tailGroups.forEachIndexed { index, group -> writeIpv6Group(bytes, zeroPadding + headGroups.size + index, group) }
    return bytes
}

private fun expandIpv6Groups(text: String): List<Int>? {
    if (text.isEmpty()) return emptyList()
    val groups = mutableListOf<Int>()
    val tokens = text.split(':')
    tokens.forEachIndexed { index, token ->
        if (token.isEmpty()) return null
        if (token.contains('.')) {
            if (index != tokens.lastIndex) return null
            val v4 = parseIpv4Bytes(token) ?: return null
            groups.add(((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF))
            groups.add(((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF))
        } else {
            if (token.length > 4) return null
            val value = token.toIntOrNull(16) ?: return null
            if (value < 0 || value > 0xFFFF) return null
            groups.add(value)
        }
    }
    return groups
}

private fun writeIpv6Group(bytes: ByteArray, groupIndex: Int, value: Int) {
    bytes[groupIndex * 2] = ((value shr 8) and 0xFF).toByte()
    bytes[groupIndex * 2 + 1] = (value and 0xFF).toByte()
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
