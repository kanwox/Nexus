@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUBTITLE_PREVIEW_LIMIT = 24

/** One trimmed, non-empty line per entry. */
private fun parseOverrideLines(raw: String): List<String> {
    return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Normalizes "domain: target" lines; the part before ':' is the key, invalid lines are dropped. */
private fun parseHostEntries(raw: String): List<String> {
    return raw.split('\n').mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            null
        } else {
            val domain = trimmed.substringBefore(':').trim()
            val target = trimmed.substringAfter(':', "").trim()
            if (domain.isEmpty() || target.isEmpty()) null else "$domain: $target"
        }
    }
}

/** Shows the configured entry count, or a truncated hint when nothing is configured yet. */
private fun overrideSubtitle(count: Int, desc: String): String {
    return if (count > 0) {
        count.toString()
    } else if (desc.length > SUBTITLE_PREVIEW_LIMIT) {
        desc.take(SUBTITLE_PREVIEW_LIMIT) + "…"
    } else {
        desc
    }
}

/** Multi-line iOS-styled code area (tertiary surface + global typography inheritance). */
@Composable
private fun OverrideTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = LoonTertiaryBg
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 220.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(
                    text = placeholder,
                    color = LoonTextMuted,
                    fontSize = LoonTypeScale.callout,
                    lineHeight = 19.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = LocalTextStyle.current.copy(
                    color = LoonTextPrimary,
                    fontSize = LoonTypeScale.callout,
                    lineHeight = 19.sp
                ),
                cursorBrush = SolidColor(IosBlue),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ProfileOverrideScreen(
    profile: ProfileItem,
    onBack: () -> Unit,
    onNavigateToScripts: () -> Unit,
    onProfileUpdated: (ProfileItem) -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allScripts by remember { mutableStateOf(emptyList<ScriptItem>()) }

    LaunchedEffect(Unit) {
        allScripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }
    val selectedScriptIds = remember(profile) { profile.scriptIds.toMutableStateList() }

    // Structured override drafts; parsed and persisted by the top-right save action.
    var rulesText by remember(profile) { mutableStateOf(profile.prependRules.joinToString("\n")) }
    var hostsText by remember(profile) { mutableStateOf(profile.hostsEntries.joinToString("\n")) }
    var dnsText by remember(profile) { mutableStateOf(profile.dnsServersOverride.joinToString("\n")) }
    var editingOverride by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("profile_override_title", lang),
            subtitle = profile.name,
            showBackButton = true,
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val updated = profile.copy(
                                scriptEnabled = selectedScriptIds.isNotEmpty(),
                                scriptIds = selectedScriptIds.toList(),
                                prependRules = parseOverrideLines(rulesText),
                                hostsEntries = parseHostEntries(hostsText),
                                dnsServersOverride = parseOverrideLines(dnsText)
                            )
                            ConfigManager.updateProfile(context, updated)
                            ProfileParser.clearCache()
                            onProfileUpdated(updated)
                            scope.launch(Dispatchers.IO) {
                                val settings = SettingsManager(context)
                                val profiles = ConfigManager.getProfiles(context)
                                val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
                                if (activeProfile?.id == profile.id && ClashCore.isCoreLoaded) {
                                    ClashCore.reloadActiveConfig(context)
                                }
                            }
                            CupertinoToast.show(AppStrings.get("override_saved", lang))
                            onBack()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = AppStrings.get("action_save", lang),
                        color = IosBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = LoonTypeScale.title3
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("override_profile_info", lang),
                    footer = AppStrings.get("override_desc", lang)
                ) {
                    CupertinoCell(
                        title = profile.name,
                        value = if (profile.url != null) AppStrings.get("override_remote", lang) else AppStrings.get("override_local", lang),
                        icon = Icons.Default.FolderZip,
                        iconBg = IosPurple,
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("override_go_scripts", lang),
                        subtitle = AppStrings.get("override_scripts_hint", lang),
                        icon = Icons.Default.Code,
                        iconBg = IosIndigo,
                        showChevron = true,
                        onClick = onNavigateToScripts
                    )
                }
            }

            item(key = "override_rules_section") {
                CupertinoGroupedSection(
                    header = AppStrings.current("profile_override_title")
                ) {
                    CupertinoCell(
                        title = AppStrings.current("override_rules_section"),
                        subtitle = overrideSubtitle(parseOverrideLines(rulesText).size, AppStrings.current("override_rules_desc")),
                        icon = Icons.Default.Rule,
                        iconBg = IosBlue,
                        showChevron = true,
                        onClick = { editingOverride = "rules" }
                    )
                    CupertinoIndentedDivider()
                    CupertinoCell(
                        title = AppStrings.current("override_hosts_section"),
                        subtitle = overrideSubtitle(parseHostEntries(hostsText).size, AppStrings.current("override_hosts_desc")),
                        icon = Icons.Default.Public,
                        iconBg = IosTeal,
                        showChevron = true,
                        onClick = { editingOverride = "hosts" }
                    )
                    CupertinoIndentedDivider()
                    CupertinoCell(
                        title = AppStrings.current("override_dns_section"),
                        subtitle = overrideSubtitle(parseOverrideLines(dnsText).size, AppStrings.current("override_dns_desc")),
                        icon = Icons.Default.Dns,
                        iconBg = IosIndigo,
                        showChevron = true,
                        onClick = { editingOverride = "dns" }
                    )
                }
            }

            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("override_select_scripts", lang)
                ) {
                    if (allScripts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = AppStrings.get("override_no_scripts_title", lang),
                                    fontSize = LoonTypeScale.subhead,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = AppStrings.get("override_empty_hint", lang).replace("{link}", AppStrings.get("override_go_scripts", lang)),
                                    fontSize = LoonTypeScale.footnote,
                                    color = LoonTextSecondary
                                )
                            }
                        }
                    } else {
                        allScripts.forEachIndexed { idx, script ->
                            val isChecked = selectedScriptIds.contains(script.id)
                            CupertinoCell(
                                title = script.name,
                                subtitle = if (script.url.isNotBlank()) AppStrings.get("script_type_remote", lang) else AppStrings.get("script_type_local", lang),
                                icon = Icons.Default.Javascript,
                                iconBg = if (isChecked) IosBlue else IosGray,
                                trailing = {
                                    CupertinoSwitch(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedScriptIds.add(script.id)
                                            else selectedScriptIds.remove(script.id)
                                        }
                                    )
                                },
                                onClick = {
                                    if (isChecked) selectedScriptIds.remove(script.id)
                                    else selectedScriptIds.add(script.id)
                                }
                            )
                            if (idx < allScripts.size - 1) {
                                CupertinoDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingOverride != null) {
        val sheetTitle: String
        val sheetDesc: String
        val sheetValue: String
        val onSheetValue: (String) -> Unit
        when (editingOverride) {
            "rules" -> {
                sheetTitle = AppStrings.current("override_rules_section")
                sheetDesc = AppStrings.current("override_rules_desc")
                sheetValue = rulesText
                onSheetValue = { rulesText = it }
            }
            "hosts" -> {
                sheetTitle = AppStrings.current("override_hosts_section")
                sheetDesc = AppStrings.current("override_hosts_desc")
                sheetValue = hostsText
                onSheetValue = { hostsText = it }
            }
            else -> {
                sheetTitle = AppStrings.current("override_dns_section")
                sheetDesc = AppStrings.current("override_dns_desc")
                sheetValue = dnsText
                onSheetValue = { dnsText = it }
            }
        }

        CupertinoModalBottomSheet(
            onDismissRequest = { editingOverride = null },
            title = sheetTitle,
            doneText = AppStrings.current("action_save")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                OverrideTextArea(
                    value = sheetValue,
                    onValueChange = onSheetValue
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sheetDesc,
                    color = LoonTextSecondary,
                    fontSize = LoonTypeScale.footnote,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProfileOverrideScreenPreview() {
    val sampleProfile = ProfileItem(
        id = "sample_1",
        name = "Sample Subscription",
        url = "https://subscribe.example.com/clash",
        file = java.io.File("/dummy/path.yaml"),
        scriptEnabled = true,
        scriptIds = listOf("script_1")
    )
    MihomoTheme {
        ProfileOverrideScreen(
            profile = sampleProfile,
            onBack = {},
            onNavigateToScripts = {},
            onProfileUpdated = {}
        )
    }
}
