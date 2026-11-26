package com.github.mihomo.android.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSingleSelectOption
import com.github.mihomo.android.ui.components.CupertinoSingleSelectSheet
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import java.util.Locale

@Immutable
data class VpnTunnelUiState(
    val tunStack: String,
    val tunMtu: Int,
    val mixedPort: Int,
    val ipv6Enabled: Boolean,
    val killSwitchEnabled: Boolean,
    val allowBypassEnabled: Boolean,
    val allowPrivateSubs: Boolean,
    val bootOnStartup: Boolean
)

@Immutable
data class VpnTunnelCallbacks(
    val onTunStackChanged: (String) -> Unit,
    val onTunMtuChanged: (Int) -> Unit,
    val onMixedPortChanged: (Int) -> Unit,
    val onIpv6EnabledChanged: (Boolean) -> Unit,
    val onKillSwitchChanged: (Boolean) -> Unit,
    val onAllowBypassChanged: (Boolean) -> Unit,
    val onAllowPrivateSubsChanged: (Boolean) -> Unit,
    val onBootOnStartupChanged: (Boolean) -> Unit
)

/** VPN & tunnel second-level settings: stack, MTU, ports, protections and boot behavior. */
@Composable
fun VpnTunnelScreen(
    state: VpnTunnelUiState,
    callbacks: VpnTunnelCallbacks,
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

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            VpnTunnelList(
                state = state,
                callbacks = callbacks,
                lang = lang,
                context = context,
                onOpenTunDialog = { showTunDialog = true },
                onOpenMtuDialog = { showTunMtuDialog = true },
                onOpenMixedPortDialog = { showMixedPortDialog = true }
            )
        }
    }

    VpnTunnelOverlays(
        state = state,
        callbacks = callbacks,
        lang = lang,
        showTunDialog = showTunDialog,
        showMixedPortDialog = showMixedPortDialog,
        showTunMtuDialog = showTunMtuDialog,
        onDismissTun = { showTunDialog = false },
        onDismissMixedPort = { showMixedPortDialog = false },
        onDismissTunMtu = { showTunMtuDialog = false }
    )
}

@Suppress("LongParameterList")
@Composable
private fun VpnTunnelList(
    state: VpnTunnelUiState,
    callbacks: VpnTunnelCallbacks,
    lang: String,
    context: Context,
    onOpenTunDialog: () -> Unit,
    onOpenMtuDialog: () -> Unit,
    onOpenMixedPortDialog: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            VpnCoreSection(
                state = state,
                callbacks = callbacks,
                lang = lang,
                onOpenTunDialog = onOpenTunDialog,
                onOpenMtuDialog = onOpenMtuDialog,
                onOpenMixedPortDialog = onOpenMixedPortDialog
            )
        }
        item {
            VpnProtectionSection(
                state = state,
                callbacks = callbacks,
                lang = lang,
                context = context
            )
        }
        item {
            VpnSystemSection(
                bootOnStartup = state.bootOnStartup,
                onBootOnStartupChanged = callbacks.onBootOnStartupChanged,
                lang = lang,
                context = context
            )
        }
    }
}

@Composable
private fun VpnCoreSection(
    state: VpnTunnelUiState,
    callbacks: VpnTunnelCallbacks,
    lang: String,
    onOpenTunDialog: () -> Unit,
    onOpenMtuDialog: () -> Unit,
    onOpenMixedPortDialog: () -> Unit
) {
    CupertinoGroupedSection {
        CupertinoCell(
            title = AppStrings.get("settings_tun_stack", lang),
            value = if (state.tunStack.equals("gvisor", ignoreCase = true)) "gVisor" else "Mips",
            showChevron = true,
            onClick = onOpenTunDialog
        )
        CupertinoDivider()
        CupertinoCell(
            title = AppStrings.get("settings_tun_mtu", lang),
            value = state.tunMtu.toString(),
            showChevron = true,
            onClick = onOpenMtuDialog
        )
        CupertinoDivider()
        CupertinoCell(
            title = AppStrings.get("settings_mixed_port", lang),
            value = state.mixedPort.toString(),
            showChevron = true,
            onClick = onOpenMixedPortDialog
        )
        CupertinoDivider()
        CupertinoSwitchCell(
            title = "IPv6",
            checked = state.ipv6Enabled,
            onCheckedChange = callbacks.onIpv6EnabledChanged
        )
    }
}

@Composable
private fun VpnProtectionSection(
    state: VpnTunnelUiState,
    callbacks: VpnTunnelCallbacks,
    lang: String,
    context: Context
) {
    CupertinoGroupedSection {
        // Real Kill Switch on Android is enforced by the OS ("Block connections without VPN").
        // Direct users to the system VPN settings rather than toggling a non-functional TUN flag.
        CupertinoCell(
            title = AppStrings.get("settings_kill_switch", lang),
            subtitle = AppStrings.get("settings_always_on_vpn_desc", lang),
            showChevron = true,
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }
        )
        CupertinoDivider()
        CupertinoSwitchCell(
            title = AppStrings.get("settings_allow_bypass", lang),
            checked = state.allowBypassEnabled,
            onCheckedChange = callbacks.onAllowBypassChanged
        )
        CupertinoDivider()
        CupertinoSwitchCell(
            title = AppStrings.get("settings_allow_private_subs", lang),
            checked = state.allowPrivateSubs,
            onCheckedChange = callbacks.onAllowPrivateSubsChanged
        )
    }
}

@Composable
private fun VpnSystemSection(
    bootOnStartup: Boolean,
    onBootOnStartupChanged: (Boolean) -> Unit,
    lang: String,
    context: Context
) {
    CupertinoGroupedSection {
        CupertinoSwitchCell(
            title = AppStrings.get("settings_boot_startup", lang),
            checked = bootOnStartup,
            onCheckedChange = onBootOnStartupChanged
        )
        CupertinoDivider()
        CupertinoCell(
            title = AppStrings.get("settings_system_vpn", lang),
            showChevron = true,
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }
        )
        CupertinoDivider()
        CupertinoCell(
            title = AppStrings.get("settings_always_on_vpn", lang),
            subtitle = AppStrings.get("settings_always_on_vpn_desc", lang),
            showChevron = true,
            onClick = {
                // The system always-on screen is the REAL system-level kill switch
                // ("Block connections without VPN"); this app cannot grant it for itself.
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun VpnTunnelOverlays(
    state: VpnTunnelUiState,
    callbacks: VpnTunnelCallbacks,
    lang: String,
    showTunDialog: Boolean,
    showMixedPortDialog: Boolean,
    showTunMtuDialog: Boolean,
    onDismissTun: () -> Unit,
    onDismissMixedPort: () -> Unit,
    onDismissTunMtu: () -> Unit
) {
    if (showTunDialog) {
        val stacks = listOf(
            CupertinoSingleSelectOption("mips", "Mips", AppStrings.get("tun_desc_mips", lang)),
            CupertinoSingleSelectOption("gvisor", "gVisor", AppStrings.get("tun_desc_gvisor", lang))
        )
        CupertinoSingleSelectSheet(
            title = AppStrings.get("tun_stack_dialog_title", lang),
            options = stacks,
            selectedValue = state.tunStack.lowercase(Locale.ROOT),
            onSelect = { stackKey ->
                callbacks.onTunStackChanged(stackKey)
                onDismissTun()
            },
            onDismissRequest = onDismissTun
        )
    }

    if (showMixedPortDialog) {
        NumberValueDialog(
            title = AppStrings.get("settings_mixed_port", lang),
            initialValue = state.mixedPort,
            range = 1024..65535,
            rangeHint = AppStrings.get("port_range_hint", lang),
            lang = lang,
            onConfirm = { port ->
                callbacks.onMixedPortChanged(port)
                onDismissMixedPort()
            },
            onDismiss = onDismissMixedPort
        )
    }

    if (showTunMtuDialog) {
        NumberValueDialog(
            title = AppStrings.get("settings_tun_mtu", lang),
            initialValue = state.tunMtu,
            range = 1280..65535,
            rangeHint = AppStrings.get("mtu_range_hint", lang),
            lang = lang,
            onConfirm = { mtu ->
                callbacks.onTunMtuChanged(mtu)
                onDismissTunMtu()
            },
            onDismiss = onDismissTunMtu
        )
    }
}

/** Cupertino alert with a numeric-only text field, validated against [range] on confirm. */
@Composable
private fun NumberValueDialog(
    title: String,
    initialValue: Int,
    range: IntRange,
    rangeHint: String,
    lang: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    CupertinoAlertDialog(
        title = title,
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
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
                placeholder = "${range.first} - ${range.last}",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    )
}
