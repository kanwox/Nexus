package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class DnsServerSection { NAMESERVERS, FALLBACK }

/** Comma/newline separated list → trimmed, non-empty entries. */
private fun parseServers(raw: String): List<String> =
    raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** Back to the single string the config header expects; empty falls back to built-ins. */
private fun joinServers(servers: List<String>): String =
    servers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

/**
 * DNS settings: resolution-mode switches plus independently editable upstream/fallback server
 * lists. Each entry is added, edited and removed on its own — the previous single-value editor
 * overwrote the whole list with its first entry on every save (and on a block-DNS toggle), so
 * the other configured servers were silently lost.
 */
@Composable
fun DnsSettingsScreen(
    fakeIpEnabled: Boolean = true,
    onFakeIpEnabledChanged: ((Boolean) -> Unit)? = null,
    nameservers: String = SettingsManager.DEFAULT_NAMESERVERS,
    fallbackServers: String = SettingsManager.DEFAULT_FALLBACK_SERVERS,
    blockThirdPartyDns: Boolean = false,
    onSaveDns: (nameservers: String, fallback: String, blockThirdPartyDns: Boolean) -> Unit = { _, _, _ -> },
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    var currentFakeIp by remember { mutableStateOf(fakeIpEnabled) }
    var currentIpv6 by remember { mutableStateOf(settingsManager.dnsIpv6Enabled) }
    var upstreamServers by remember(nameservers) { mutableStateOf(parseServers(nameservers)) }
    var fallbackList by remember(fallbackServers) { mutableStateOf(parseServers(fallbackServers)) }
    var currentBlockThirdParty by remember(blockThirdPartyDns) { mutableStateOf(blockThirdPartyDns) }

    var editingSection by remember { mutableStateOf<DnsServerSection?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var serverInput by remember { mutableStateOf("") }

    fun reloadCore() {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { ClashCore.reloadActiveConfig(context) }
        }
    }

    fun persistServers(upstream: List<String>, fallback: List<String>, block: Boolean) {
        onSaveDns(joinServers(upstream), joinServers(fallback), block)
    }

    fun openEditor(section: DnsServerSection, index: Int?, current: String) {
        editingSection = section
        editingIndex = index
        serverInput = current
    }

    fun serversOf(section: DnsServerSection): List<String> =
        if (section == DnsServerSection.NAMESERVERS) upstreamServers else fallbackList

    fun applyServers(section: DnsServerSection, updated: List<String>) {
        if (section == DnsServerSection.NAMESERVERS) {
            upstreamServers = updated
            persistServers(updated, fallbackList, currentBlockThirdParty)
        } else {
            fallbackList = updated
            persistServers(upstreamServers, updated, currentBlockThirdParty)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("settings_dns_section", lang),
            showBackButton = true,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CupertinoGroupedSection(header = AppStrings.get("dns_settings_mode_section", lang)) {
                    CupertinoCell(
                        title = AppStrings.get("dns_fakeip_mode", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentFakeIp,
                                onCheckedChange = { checked ->
                                    currentFakeIp = checked
                                    settingsManager.fakeIpEnabled = checked
                                    onFakeIpEnabledChanged?.invoke(checked)
                                }
                            )
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("dns_ipv6_resolve", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentIpv6,
                                onCheckedChange = { checked ->
                                    currentIpv6 = checked
                                    settingsManager.dnsIpv6Enabled = checked
                                    reloadCore()
                                }
                            )
                        }
                    )
                }
            }

            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("dns_nameservers_title", lang),
                    footer = AppStrings.get("dns_nameservers_footer", lang)
                ) {
                    if (upstreamServers.isEmpty()) {
                        CupertinoCell(
                            title = AppStrings.get("dns_server_unset", lang),
                            showChevron = false
                        )
                    } else {
                        upstreamServers.forEachIndexed { index, server ->
                            DnsServerRow(
                                server = server,
                                onEdit = { openEditor(DnsServerSection.NAMESERVERS, index, server) },
                                onDelete = {
                                    applyServers(
                                        DnsServerSection.NAMESERVERS,
                                        upstreamServers.filterIndexed { i, _ -> i != index }
                                    )
                                }
                            )
                            CupertinoIndentedDivider(startIndent = 16.dp)
                        }
                    }
                    CupertinoCell(
                        title = AppStrings.get("dns_server_add", lang),
                        icon = Icons.Default.Add,
                        iconBg = IosBlue,
                        showChevron = false,
                        onClick = { openEditor(DnsServerSection.NAMESERVERS, null, "") }
                    )
                }
            }

            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("dns_fallback_title", lang),
                    footer = AppStrings.get("dns_fallback_footer", lang)
                ) {
                    if (fallbackList.isEmpty()) {
                        CupertinoCell(
                            title = AppStrings.get("dns_server_unset", lang),
                            showChevron = false
                        )
                    } else {
                        fallbackList.forEachIndexed { index, server ->
                            DnsServerRow(
                                server = server,
                                onEdit = { openEditor(DnsServerSection.FALLBACK, index, server) },
                                onDelete = {
                                    applyServers(
                                        DnsServerSection.FALLBACK,
                                        fallbackList.filterIndexed { i, _ -> i != index }
                                    )
                                }
                            )
                            CupertinoIndentedDivider(startIndent = 16.dp)
                        }
                    }
                    CupertinoCell(
                        title = AppStrings.get("dns_server_add", lang),
                        icon = Icons.Default.Add,
                        iconBg = IosGreen,
                        showChevron = false,
                        onClick = { openEditor(DnsServerSection.FALLBACK, null, "") }
                    )
                }
            }

            item {
                CupertinoGroupedSection {
                    CupertinoCell(
                        title = AppStrings.get("dns_block_third_party", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentBlockThirdParty,
                                onCheckedChange = { checked ->
                                    currentBlockThirdParty = checked
                                    persistServers(upstreamServers, fallbackList, checked)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    editingSection?.let { section ->
        val isNew = editingIndex == null
        CupertinoAlertDialog(
            title = AppStrings.get(
                if (isNew) "dns_server_add" else "dns_server_edit",
                lang
            ),
            confirmText = AppStrings.get("action_save", lang),
            dismissText = AppStrings.get("cancel", lang),
            onConfirm = {
                val server = serverInput.trim()
                if (server.isNotEmpty()) {
                    val current = serversOf(section)
                    val updated = buildList {
                        addAll(current)
                        val index = editingIndex
                        if (index != null && index in current.indices) {
                            this[index] = server
                        } else {
                            add(server)
                        }
                    }.distinct()
                    applyServers(section, updated)
                    CupertinoToast.show(AppStrings.get("saved_toast", lang))
                    editingSection = null
                    editingIndex = null
                }
            },
            onDismiss = {
                editingSection = null
                editingIndex = null
            },
            onDismissRequest = {
                editingSection = null
                editingIndex = null
            }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = serverInput,
                onValueChange = { serverInput = it },
                placeholder = AppStrings.get("dns_server_hint", lang),
                singleLine = true
            )
        }
    }
}

@Composable
private fun DnsServerRow(
    server: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val lang = LocalAppLanguage.current
    CupertinoCell(
        title = server,
        showChevron = false,
        onClick = onEdit,
        trailing = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = AppStrings.get("profile_delete", lang),
                    tint = RedDanger,
                    modifier = Modifier.size(17.dp)
                )
            }
        },
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun DnsSettingsScreenPreview() {
    MihomoTheme {
        DnsSettingsScreen(
            fakeIpEnabled = true,
            onFakeIpEnabledChanged = {},
            onBack = {}
        )
    }
}
