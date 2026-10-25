package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class DnsServerSection { NAMESERVERS, FALLBACK }

/** Splits the persisted comma + newline separated server string into display rows. */
private fun parseServerList(raw: String): List<String> =
    raw.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }

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

    // Upstream DNS editor state: edits stay local until the nav bar Save action commits them.
    var nameserverList by remember(nameservers) { mutableStateOf(parseServerList(nameservers)) }
    var fallbackList by remember(fallbackServers) { mutableStateOf(parseServerList(fallbackServers)) }
    var currentBlockThirdParty by remember(blockThirdPartyDns) { mutableStateOf(blockThirdPartyDns) }

    var addDialogSection by remember { mutableStateOf<DnsServerSection?>(null) }
    var serverInput by remember { mutableStateOf("") }

    val applyDnsChanges = {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                ClashCore.reloadActiveConfig(context)
            }
        }
        CupertinoToast.show(AppStrings.get("dns_settings_updated", lang), isDestructive = false)
    }

    val saveDns = {
        onSaveDns(
            nameserverList.joinToString(","),
            fallbackList.joinToString(","),
            currentBlockThirdParty
        )
        CupertinoToast.show(AppStrings.get("dns_saved", lang), isDestructive = false)
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
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .iosPressable { saveDns() }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.get("action_save", lang),
                        color = IosBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group: Resolution Mode
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
                                    applyDnsChanges()
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
                                    applyDnsChanges()
                                }
                            )
                        }
                    )
                }
            }

            // Group: Upstream DNS Servers
            item {
                DnsServerGroup(
                    header = AppStrings.get("dns_nameservers_title", lang),
                    footer = AppStrings.get("dns_server_hint", lang),
                    servers = nameserverList,
                    onRemoveServer = { index ->
                        nameserverList = nameserverList.filterIndexed { i, _ -> i != index }
                    },
                    onAddServer = {
                        serverInput = ""
                        addDialogSection = DnsServerSection.NAMESERVERS
                    }
                )
            }

            // Group: Fallback DNS Servers
            item {
                DnsServerGroup(
                    header = AppStrings.get("dns_fallback_title", lang),
                    footer = AppStrings.get("dns_server_hint", lang),
                    servers = fallbackList,
                    onRemoveServer = { index ->
                        fallbackList = fallbackList.filterIndexed { i, _ -> i != index }
                    },
                    onAddServer = {
                        serverInput = ""
                        addDialogSection = DnsServerSection.FALLBACK
                    }
                )
            }

            // Group: Third-party Plaintext DNS Blocking
            item {
                CupertinoGroupedSection(footer = AppStrings.get("dns_block_third_party_desc", lang)) {
                    CupertinoCell(
                        title = AppStrings.get("dns_block_third_party", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentBlockThirdParty,
                                onCheckedChange = { checked ->
                                    currentBlockThirdParty = checked
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    // Add Server Dialog (shared by both upstream groups)
    addDialogSection?.let { section ->
        CupertinoAlertDialog(
            title = AppStrings.get("dns_server_add", lang),
            confirmText = AppStrings.get("confirm", lang),
            dismissText = AppStrings.get("cancel", lang),
            onConfirm = {
                val server = serverInput.trim()
                if (server.isNotEmpty()) {
                    when (section) {
                        DnsServerSection.NAMESERVERS -> nameserverList = nameserverList + server
                        DnsServerSection.FALLBACK -> fallbackList = fallbackList + server
                    }
                }
                addDialogSection = null
            },
            onDismiss = { addDialogSection = null },
            onDismissRequest = { addDialogSection = null }
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

/**
 * iOS grouped section listing one row per configured DNS server, each with a red
 * destructive remove action, plus an "Add Server" trailing cell.
 */
@Composable
private fun DnsServerGroup(
    header: String,
    footer: String,
    servers: List<String>,
    onRemoveServer: (Int) -> Unit,
    onAddServer: () -> Unit
) {
    CupertinoGroupedSection(header = header, footer = footer) {
        servers.forEachIndexed { index, server ->
            if (index > 0) CupertinoIndentedDivider(startIndent = 16.dp)
            DnsServerRow(server = server, onDelete = { onRemoveServer(index) })
        }
        if (servers.isNotEmpty()) CupertinoIndentedDivider(startIndent = 16.dp)
        CupertinoCell(
            title = AppStrings.get("dns_server_add", LocalAppLanguage.current),
            showChevron = true,
            onClick = onAddServer
        )
    }
}

@Composable
private fun DnsServerRow(
    server: String,
    onDelete: () -> Unit
) {
    CupertinoCell(
        title = server,
        trailing = {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .iosPressable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RemoveCircle,
                    contentDescription = AppStrings.get("profile_delete", LocalAppLanguage.current),
                    tint = RedDanger,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
