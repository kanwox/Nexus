@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.MihomoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            DnsSettingsContent(
                lang = lang,
                currentFakeIp = currentFakeIp,
                currentIpv6 = currentIpv6,
                currentBlockThirdParty = currentBlockThirdParty,
                onFakeIpToggle = { checked ->
                    currentFakeIp = checked
                    settingsManager.fakeIpEnabled = checked
                    onFakeIpEnabledChanged?.invoke(checked)
                },
                onIpv6Toggle = { checked ->
                    val prev = currentIpv6
                    currentIpv6 = checked
                    settingsManager.dnsIpv6Enabled = checked
                    coroutineScope.launch {
                        val reloaded = reloadDnsSetting(context)
                        if (!reloaded) {
                            settingsManager.dnsIpv6Enabled = prev
                            currentIpv6 = prev
                            CupertinoToast.show(
                                AppStrings.get("core_reload_failed", lang),
                                isDestructive = true
                            )
                        }
                    }
                },
                onBlockThirdPartyToggle = { checked ->
                    currentBlockThirdParty = checked
                    settingsManager.blockThirdPartyDns = checked
                    onSaveDns(nameservers, fallbackServers, checked)
                }
            )
        }
    }
}

@Composable
private fun DnsSettingsContent(
    lang: String,
    currentFakeIp: Boolean,
    currentIpv6: Boolean,
    currentBlockThirdParty: Boolean,
    onFakeIpToggle: (Boolean) -> Unit,
    onIpv6Toggle: (Boolean) -> Unit,
    onBlockThirdPartyToggle: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CupertinoGroupedSection(header = AppStrings.get("dns_settings_mode_section", lang)) {
                CupertinoSwitchCell(
                    title = AppStrings.get("dns_fakeip_mode", lang),
                    checked = currentFakeIp,
                    onCheckedChange = onFakeIpToggle
                )
                CupertinoDivider()
                CupertinoSwitchCell(
                    title = AppStrings.get("dns_ipv6_resolve", lang),
                    checked = currentIpv6,
                    onCheckedChange = onIpv6Toggle
                )
            }
        }

        item {
            CupertinoGroupedSection(
                footer = AppStrings.get("dns_block_third_party_desc", lang)
            ) {
                CupertinoSwitchCell(
                    title = AppStrings.get("dns_block_third_party", lang),
                    checked = currentBlockThirdParty,
                    onCheckedChange = onBlockThirdPartyToggle
                )
            }
        }
    }
}

private suspend fun reloadDnsSetting(context: android.content.Context): Boolean {
    val isRunning = MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING
    if (!isRunning || !ClashCore.isCoreLoaded) return true
    return withContext(Dispatchers.IO) {
        try {
            ClashCore.reloadActiveConfig(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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
