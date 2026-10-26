package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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

/** First configured server of a (comma/newline separated) list; empty when none. */
private fun firstServer(raw: String): String =
    raw.split(',', '\n')
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

/**
 * DNS settings: resolution-mode switches plus two server entries (upstream / fallback),
 * each edited in place through a tap-to-edit dialog with a single value.
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
    var currentNameserver by remember(nameservers) { mutableStateOf(firstServer(nameservers)) }
    var currentFallback by remember(fallbackServers) { mutableStateOf(firstServer(fallbackServers)) }
    var currentBlockThirdParty by remember(blockThirdPartyDns) { mutableStateOf(blockThirdPartyDns) }

    var editingSection by remember { mutableStateOf<DnsServerSection?>(null) }
    var serverInput by remember { mutableStateOf("") }

    val applyDnsChanges = {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                ClashCore.reloadActiveConfig(context)
            }
        }
        CupertinoToast.show(AppStrings.get("dns_settings_updated", lang), isDestructive = false)
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

            item {
                CupertinoGroupedSection(header = AppStrings.get("dns_nameservers_title", lang)) {
                    CupertinoCell(
                        title = AppStrings.get("dns_server_edit", lang),
                        value = currentNameserver.ifBlank {
                            AppStrings.get("dns_server_unset", lang)
                        },
                        showChevron = true,
                        onClick = {
                            serverInput = currentNameserver
                            editingSection = DnsServerSection.NAMESERVERS
                        }
                    )
                }
            }

            item {
                CupertinoGroupedSection(header = AppStrings.get("dns_fallback_title", lang)) {
                    CupertinoCell(
                        title = AppStrings.get("dns_server_edit", lang),
                        value = currentFallback.ifBlank {
                            AppStrings.get("dns_server_unset", lang)
                        },
                        showChevron = true,
                        onClick = {
                            serverInput = currentFallback
                            editingSection = DnsServerSection.FALLBACK
                        }
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
                                    onSaveDns(currentNameserver, currentFallback, checked)
                                    applyDnsChanges()
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    editingSection?.let { section ->
        CupertinoAlertDialog(
            title = AppStrings.get(
                if (section == DnsServerSection.NAMESERVERS) "dns_nameservers_title" else "dns_fallback_title",
                lang
            ),
            confirmText = AppStrings.get("confirm", lang),
            dismissText = AppStrings.get("cancel", lang),
            onConfirm = {
                val server = serverInput.trim()
                if (section == DnsServerSection.NAMESERVERS) {
                    currentNameserver = server
                } else {
                    currentFallback = server
                }
                editingSection = null
                onSaveDns(currentNameserver, currentFallback, currentBlockThirdParty)
                applyDnsChanges()
            },
            onDismiss = { editingSection = null },
            onDismissRequest = { editingSection = null }
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
