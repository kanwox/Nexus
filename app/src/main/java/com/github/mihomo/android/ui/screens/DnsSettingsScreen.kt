package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pure & focused DNS settings: Fake-IP mode, IPv6 resolution, and DNS leak prevention.
 * Upstream nameservers use optimized built-in concurrent resolvers.
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

    var currentFakeIp by remember(fakeIpEnabled) { mutableStateOf(fakeIpEnabled) }
    var currentIpv6 by remember { mutableStateOf(settingsManager.dnsIpv6Enabled) }
    var currentBlockThirdParty by remember(blockThirdPartyDns) { mutableStateOf(blockThirdPartyDns) }

    fun reloadCore() {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { ClashCore.reloadActiveConfig(context) }
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
                                    reloadCore()
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
                    footer = AppStrings.get("dns_block_third_party_desc", lang)
                ) {
                    CupertinoCell(
                        title = AppStrings.get("dns_block_third_party", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentBlockThirdParty,
                                onCheckedChange = { checked ->
                                    currentBlockThirdParty = checked
                                    settingsManager.blockThirdPartyDns = checked
                                    onSaveDns(nameservers, fallbackServers, checked)
                                    reloadCore()
                                }
                            )
                        }
                    )
                }
            }
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
