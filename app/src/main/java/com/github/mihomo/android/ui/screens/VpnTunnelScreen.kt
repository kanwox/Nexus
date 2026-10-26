@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*

/** VPN & tunnel second-level settings: stack, MTU, ports, protections and boot behavior. */
@Composable
fun VpnTunnelScreen(
    tunStack: String,
    onTunStackChanged: (String) -> Unit,
    tunMtu: Int,
    onTunMtuChanged: (Int) -> Unit,
    mixedPort: Int,
    onMixedPortChanged: (Int) -> Unit,
    ipv6Enabled: Boolean,
    onIpv6EnabledChanged: (Boolean) -> Unit,
    killSwitchEnabled: Boolean,
    onKillSwitchChanged: (Boolean) -> Unit,
    allowBypassEnabled: Boolean,
    onAllowBypassChanged: (Boolean) -> Unit,
    allowPrivateSubs: Boolean,
    onAllowPrivateSubsChanged: (Boolean) -> Unit,
    bootOnStartup: Boolean,
    onBootOnStartupChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current

    var showTunDialog by remember { mutableStateOf(false) }
    var showMixedPortDialog by remember { mutableStateOf(false) }
    var showTunMtuDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("settings_vpn_tunnel", lang),
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
                CupertinoGroupedSection {
                    TunStackCell(
                        tunStack = tunStack,
                        lang = lang,
                        onClick = { showTunDialog = true }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_tun_mtu", lang),
                        value = tunMtu.toString(),
                        showChevron = true,
                        onClick = { showTunMtuDialog = true }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_mixed_port", lang),
                        value = mixedPort.toString(),
                        showChevron = true,
                        onClick = { showMixedPortDialog = true }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "IPv6",
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = ipv6Enabled,
                                onCheckedChange = onIpv6EnabledChanged
                            )
                        },
                        onClick = { onIpv6EnabledChanged(!ipv6Enabled) }
                    )
                }
            }

            item {
                CupertinoGroupedSection {
                    CupertinoCell(
                        title = AppStrings.get("settings_kill_switch", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = killSwitchEnabled,
                                onCheckedChange = onKillSwitchChanged
                            )
                        },
                        onClick = { onKillSwitchChanged(!killSwitchEnabled) }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_allow_bypass", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = allowBypassEnabled,
                                onCheckedChange = onAllowBypassChanged
                            )
                        },
                        onClick = { onAllowBypassChanged(!allowBypassEnabled) }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_allow_private_subs", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = allowPrivateSubs,
                                onCheckedChange = onAllowPrivateSubsChanged
                            )
                        },
                        onClick = { onAllowPrivateSubsChanged(!allowPrivateSubs) }
                    )
                }
            }

            item {
                CupertinoGroupedSection {
                    CupertinoCell(
                        title = AppStrings.get("settings_boot_startup", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = bootOnStartup,
                                onCheckedChange = onBootOnStartupChanged
                            )
                        },
                        onClick = { onBootOnStartupChanged(!bootOnStartup) }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_system_vpn", lang),
                        showChevron = true,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                            }
                        }
                    )
                }
            }
        }
    }

    if (showTunDialog) {
        val stacks = listOf(
            Triple("mixed", "Mixed", AppStrings.get("tun_desc_mixed", lang)),
            Triple("gvisor", "gVisor", AppStrings.get("tun_desc_gvisor", lang)),
            Triple("system", "System", AppStrings.get("tun_desc_system", lang))
        )
        CupertinoModalBottomSheet(
            onDismissRequest = { showTunDialog = false },
            title = AppStrings.get("tun_stack_dialog_title", lang)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                stacks.forEachIndexed { index, (stackKey, label, desc) ->
                    val isSelected = tunStack.equals(stackKey, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onTunStackChanged(stackKey)
                                showTunDialog = false
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                fontSize = LoonTypeScale.body,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) IosBlue else LoonTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = LoonTypeScale.footnote,
                                color = LoonTextSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (index < stacks.size - 1) {
                        HorizontalDivider(
                            color = LoonCardBorder,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (showMixedPortDialog) {
        NumberValueDialog(
            title = AppStrings.get("settings_mixed_port", lang),
            initialValue = mixedPort,
            range = 1024..65535,
            rangeHint = AppStrings.get("port_range_hint", lang),
            onConfirm = {
                onMixedPortChanged(it)
                showMixedPortDialog = false
            },
            onDismiss = { showMixedPortDialog = false }
        )
    }

    if (showTunMtuDialog) {
        NumberValueDialog(
            title = AppStrings.get("settings_tun_mtu", lang),
            initialValue = tunMtu,
            range = 1280..65535,
            rangeHint = AppStrings.get("mtu_range_hint", lang),
            onConfirm = {
                onTunMtuChanged(it)
                showTunMtuDialog = false
            },
            onDismiss = { showTunMtuDialog = false }
        )
    }
}

@Composable
private fun TunStackCell(
    tunStack: String,
    lang: String,
    onClick: () -> Unit
) {
    CupertinoCell(
        title = AppStrings.get("settings_tun_stack", lang),
        value = when (tunStack.lowercase()) {
            "mixed" -> "Mixed"
            "gvisor" -> "gVisor"
            else -> "System"
        },
        showChevron = true,
        onClick = onClick
    )
}

/** Cupertino alert with a numeric-only text field, validated against [range] on confirm. */
@Composable
private fun NumberValueDialog(
    title: String,
    initialValue: Int,
    range: IntRange,
    rangeHint: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialValue.toString()) }
    CupertinoAlertDialog(
        title = title,
        confirmText = AppStrings.get("action_save", com.github.mihomo.android.i18n.LocalAppLanguage.current),
        dismissText = AppStrings.get("cancel", com.github.mihomo.android.i18n.LocalAppLanguage.current),
        onConfirm = {
            val parsed = input.trim().toIntOrNull()
            if (parsed != null && parsed in range) {
                onConfirm(parsed)
            } else {
                CupertinoToast.show(rangeHint, isDestructive = true)
            }
        },
        onDismiss = onDismiss,
        onDismissRequest = onDismiss,
        content = {
            CupertinoTextField(
                value = input,
                onValueChange = { newValue -> input = newValue.filter { ch -> ch.isDigit() } },
                placeholder = "${range.first} - ${range.last}"
            )
        }
    )
}
