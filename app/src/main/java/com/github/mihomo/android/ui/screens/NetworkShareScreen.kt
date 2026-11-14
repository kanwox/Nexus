@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoButton
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.rememberClipboardCopy
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

private data class NetworkShareInfoData(
    val lang: String,
    val allowLan: Boolean,
    val allowLanPassword: String,
    val passwordDraft: String,
    val vpnStatus: VpnState.Status,
    val proxyPort: Int,
    val localIp: String?,
    val fullProxyAddress: String
)

private data class NetworkShareCallbacks(
    val onAllowLanChanged: (Boolean) -> Unit,
    val onPasswordDraftChange: (String) -> Unit,
    val onSavePassword: () -> Unit,
    val copyToClipboard: (String) -> Unit
)

@Composable
fun NetworkShareScreen(
    allowLan: Boolean,
    onAllowLanChanged: (Boolean) -> Unit,
    allowLanPassword: String,
    onAllowLanPasswordChanged: (String) -> Unit,
    vpnStatus: VpnState.Status,
    mixedPort: Int = 7890,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val copyToClipboard = rememberClipboardCopy()
    val haptic = rememberIosHaptic()

    var passwordDraft by remember(allowLanPassword) { mutableStateOf(allowLanPassword) }
    var localIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(allowLan, vpnStatus, mixedPort) {
        localIp = withContext(Dispatchers.IO) { getLocalIpAddress(context) }
    }

    val fullProxyAddress = remember(localIp, mixedPort) {
        if (!localIp.isNullOrBlank()) "$localIp:$mixedPort" else "127.0.0.1:$mixedPort"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("network_share_screen_title", lang),
            showBackButton = true,
            onBack = onBack
        )

        LoonResponsiveContent {
            NetworkShareListContent(
                info = NetworkShareInfoData(
                    lang = lang,
                    allowLan = allowLan,
                    allowLanPassword = allowLanPassword,
                    passwordDraft = passwordDraft,
                    vpnStatus = vpnStatus,
                    proxyPort = mixedPort,
                    localIp = localIp,
                    fullProxyAddress = fullProxyAddress
                ),
                haptic = haptic,
                callbacks = NetworkShareCallbacks(
                    onAllowLanChanged = onAllowLanChanged,
                    onPasswordDraftChange = { next -> passwordDraft = next },
                    onSavePassword = {
                        onAllowLanPasswordChanged(passwordDraft)
                        CupertinoToast.show(AppStrings.get("saved_toast", lang))
                    },
                    copyToClipboard = copyToClipboard
                )
            )
        }
    }
}

@Composable
private fun NetworkShareListContent(
    info: NetworkShareInfoData,
    haptic: IosHapticHelper,
    callbacks: NetworkShareCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        item {
            CupertinoGroupedSection(
                header = AppStrings.get("share_settings_section", info.lang),
                footer = AppStrings.get("share_settings_footer", info.lang)
            ) {
                CupertinoSwitchCell(
                    title = AppStrings.get("share_allow_lan", info.lang),
                    subtitle = AppStrings.get("share_listen_hint", info.lang),
                    checked = info.allowLan,
                    onCheckedChange = callbacks.onAllowLanChanged
                )
            }
        }

        if (info.allowLan) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                ShareAuthCard(
                    lang = info.lang,
                    passwordDraft = info.passwordDraft,
                    savedPassword = info.allowLanPassword,
                    onPasswordDraftChange = callbacks.onPasswordDraftChange,
                    onSavePassword = callbacks.onSavePassword
                )
            }

            item {
                ShareStatusBanner(
                    lang = info.lang,
                    isRunning = info.vpnStatus == VpnState.Status.RUNNING,
                    proxyPort = info.proxyPort
                )
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                ShareParamsSection(
                    lang = info.lang,
                    localIp = info.localIp,
                    proxyPort = info.proxyPort,
                    fullProxyAddress = info.fullProxyAddress,
                    hasPassword = info.allowLanPassword.isNotBlank(),
                    haptic = haptic,
                    copyToClipboard = callbacks.copyToClipboard
                )
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                ShareGuidesSection(
                    lang = info.lang,
                    proxyPort = info.proxyPort,
                    fullProxyAddress = info.fullProxyAddress,
                    allowLanPassword = info.allowLanPassword,
                    haptic = haptic,
                    copyToClipboard = callbacks.copyToClipboard
                )
            }
        }
    }
}

@Composable
private fun ShareAuthCard(
    lang: String,
    passwordDraft: String,
    savedPassword: String,
    onPasswordDraftChange: (String) -> Unit,
    onSavePassword: () -> Unit
) {
    CupertinoGroupedSection(
        header = AppStrings.get("share_auth_section", lang),
        footer = AppStrings.get("share_auth_footer", lang)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CupertinoTextField(
                value = passwordDraft,
                onValueChange = onPasswordDraftChange,
                placeholder = AppStrings.get("share_password_hint", lang),
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoButton(
                text = AppStrings.get("action_save", lang),
                onClick = onSavePassword,
                enabled = passwordDraft != savedPassword,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun ShareStatusBanner(
    lang: String,
    isRunning: Boolean,
    proxyPort: Int
) {
    val accentTint = if (isRunning) IosGreen else IosOrange
    val textTint = if (isRunning) LoonSuccessText else LoonWarningText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accentTint.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Wifi else Icons.Default.Warning,
            contentDescription = null,
            tint = textTint,
            modifier = Modifier.size(LoonSizes.icon)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isRunning) {
                    AppStrings.get("share_running_title", lang).replace("{n}", proxyPort.toString())
                } else {
                    AppStrings.get("share_vpn_not_running", lang)
                },
                color = textTint,
                style = LoonTextStyles.calloutLarge(),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isRunning) {
                    AppStrings.get("share_running_hint", lang)
                } else {
                    AppStrings.get("share_offline_hint", lang)
                },
                color = textTint,
                style = LoonTextStyles.caption()
            )
        }
    }
}

@Composable
private fun ShareParamsSection(
    lang: String,
    localIp: String?,
    proxyPort: Int,
    fullProxyAddress: String,
    hasPassword: Boolean,
    haptic: IosHapticHelper,
    copyToClipboard: (String) -> Unit
) {
    CupertinoGroupedSection(
        header = AppStrings.get("share_params_section", lang),
        footer = AppStrings.get("share_params_footer", lang)
    ) {
        ShareParamRow(
            label = AppStrings.get("share_field_local_ip", lang),
            value = localIp ?: AppStrings.get("share_ip_unavailable", lang),
            onCopy = if (localIp != null) {
                {
                    haptic.lightTick()
                    copyToClipboard(localIp)
                    CupertinoToast.show(AppStrings.get("share_copied_ip", lang).replace("{v}", localIp))
                }
            } else {
                null
            }
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        ShareParamRow(
            label = AppStrings.get("share_field_port", lang),
            value = "$proxyPort",
            onCopy = {
                haptic.lightTick()
                copyToClipboard("$proxyPort")
                CupertinoToast.show(AppStrings.get("share_copied_port", lang).replace("{v}", proxyPort.toString()))
            }
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        ShareParamRow(
            label = AppStrings.get("share_field_full_address", lang),
            value = fullProxyAddress,
            onCopy = {
                haptic.lightTick()
                copyToClipboard(fullProxyAddress)
                CupertinoToast.show(AppStrings.get("share_copied_address", lang).replace("{v}", fullProxyAddress))
            }
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        ShareParamRow(
            label = AppStrings.get("share_field_auth", lang),
            value = if (hasPassword) AppStrings.get("share_auth_enabled", lang) else AppStrings.get("share_auth_none", lang),
            onCopy = null
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        ShareParamRow(
            label = AppStrings.get("share_field_protocols", lang),
            value = "HTTP / HTTPS / SOCKS5",
            onCopy = null
        )
    }
}

@Composable
private fun ShareGuidesSection(
    lang: String,
    proxyPort: Int,
    fullProxyAddress: String,
    allowLanPassword: String,
    haptic: IosHapticHelper,
    copyToClipboard: (String) -> Unit
) {
    CupertinoGroupedSection(header = AppStrings.get("share_guide_section", lang)) {
        GuideRow(
            title = AppStrings.get("share_guide_desktop", lang),
            steps = AppStrings.get("share_guide_desktop_steps", lang).replace("{n}", proxyPort.toString())
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        GuideRow(
            title = AppStrings.get("share_guide_console", lang),
            steps = AppStrings.get("share_guide_console_steps", lang).replace("{n}", proxyPort.toString())
        )
        CupertinoIndentedDivider(startIndent = 16.dp)
        val proxyUrlForCurl = if (allowLanPassword.isNotBlank()) {
            "http://nexus:${Uri.encode(allowLanPassword)}@$fullProxyAddress"
        } else {
            "http://$fullProxyAddress"
        }
        val curlCmd = "export http_proxy=\"$proxyUrlForCurl\" && export https_proxy=\"$proxyUrlForCurl\""
        GuideRow(
            title = AppStrings.get("share_guide_terminal", lang),
            steps = curlCmd,
            isCommand = true,
            onCopy = {
                haptic.lightTick()
                copyToClipboard(curlCmd)
                CupertinoToast.show(AppStrings.get("share_copied_command", lang))
            }
        )
    }
}

@Composable
private fun ShareParamRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    val lang = LocalAppLanguage.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .then(
                if (onCopy != null) {
                    Modifier
                        .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                        .clickable(onClick = onCopy)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = LoonTextStyles.calloutLarge(),
            color = LoonTextSecondary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = value,
                style = LoonTextStyles.calloutLarge(),
                fontWeight = FontWeight.SemiBold,
                color = LoonTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = AppStrings.get("copy_desc", lang),
                    tint = LoonInfoText,
                    modifier = Modifier.size(LoonSizes.iconSmall)
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    title: String,
    steps: String,
    isCommand: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    val lang = LocalAppLanguage.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .then(
                if (onCopy != null) {
                    Modifier
                        .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                        .clickable(onClick = onCopy)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = LoonTextStyles.calloutLarge(),
                fontWeight = FontWeight.SemiBold,
                color = LoonTextPrimary
            )
            if (onCopy != null) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = AppStrings.get("copy_desc", lang),
                    tint = LoonInfoText,
                    modifier = Modifier.size(LoonSizes.iconSmall)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = steps,
            style = LoonTextStyles.subtitle(),
            color = if (isCommand) LoonInfoText else LoonTextSecondary
        )
    }
}

/**
 * Retrieve the active Wi-Fi or LAN IPv4 address off the main thread.
 */
private fun getLocalIpAddress(context: Context): String? =
    queryActiveNetworkIpv4(context) ?: queryInterfaceLanIpv4()

private fun queryActiveNetworkIpv4(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val activeNet = cm?.activeNetwork
    val caps = activeNet?.let { net -> cm.getNetworkCapabilities(net) }
    val validNonVpn = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    if (!validNonVpn) {
        null
    } else {
        cm.getLinkProperties(activeNet)?.linkAddresses
            ?.mapNotNull { linkAddress -> linkAddress.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { addr -> !addr.isLoopbackAddress && addr.hostAddress?.startsWith("172.19.") != true }
            ?.hostAddress
            ?.takeIf { ip -> ip.isNotBlank() }
    }
}.getOrNull()

private fun queryInterfaceLanIpv4(): String? = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filterNot { intf ->
            val name = intf.name.lowercase(Locale.ROOT)
            name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("p2p")
        }
        .flatMap { intf -> Collections.list(intf.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { addr -> !addr.isLoopbackAddress }
        .mapNotNull { addr -> addr.hostAddress }
        .firstOrNull { host -> isPrivateLanHost(host) }
}.getOrNull()

private fun isPrivateLanHost(host: String): Boolean {
    if (host.startsWith("172.19.")) return false
    return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
}

@Preview(showBackground = true)
@Composable
fun NetworkShareScreenPreview() {
    NetworkShareScreen(
        allowLan = true,
        onAllowLanChanged = {},
        allowLanPassword = "",
        onAllowLanPasswordChanged = {},
        vpnStatus = VpnState.Status.RUNNING,
        onBack = {}
    )
}
