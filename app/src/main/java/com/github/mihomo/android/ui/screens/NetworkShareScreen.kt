package com.github.mihomo.android.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

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
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()

    // Local draft: the parent must only see (and reload the core for) a committed value.
    // Writing on every keystroke used to reload the whole tunnel on each typing pause.
    var passwordDraft by remember(allowLanPassword) { mutableStateOf(allowLanPassword) }

    val localIp = remember(allowLan, vpnStatus) {
        getLocalIpAddress(context)
    }

    val proxyPort = mixedPort
    val fullProxyAddress = remember(localIp) {
        if (!localIp.isNullOrBlank()) "$localIp:$proxyPort" else "127.0.0.1:$proxyPort"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Navigation Bar: Centered title, back button
        CupertinoNavBar(
            title = AppStrings.get("network_share_screen_title", lang),
            showBackButton = true,
            onBack = onBack
        )

        // Scroll Container
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            // Main Switch Card (iOS Inset Grouped Section)
            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("share_settings_section", lang),
                    footer = AppStrings.get("share_settings_footer", lang)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = AppStrings.get("share_allow_lan", lang),
                                color = LoonTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = AppStrings.get("share_listen_hint", lang),
                                color = LoonTextSecondary,
                                fontSize = 12.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        CupertinoSwitch(
                            checked = allowLan,
                            onCheckedChange = { checked ->
                                haptic.selection()
                                onAllowLanChanged(checked)
                            }
                        )
                    }
                }
            }

            // Access Authentication Section
            if (allowLan) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
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
                                onValueChange = { passwordDraft = it },
                                placeholder = AppStrings.get("share_password_hint", lang),
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            CupertinoButton(
                                text = AppStrings.get("action_save", lang),
                                onClick = {
                                    onAllowLanPasswordChanged(passwordDraft)
                                    CupertinoToast.show(AppStrings.get("saved_toast", lang))
                                },
                                enabled = passwordDraft != allowLanPassword,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }

            // Contextual Status Alert
            if (allowLan) {
                item {
                    val isRunning = vpnStatus == VpnState.Status.RUNNING

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isRunning) IosGreen.copy(alpha = 0.12f)
                                else IosOrange.copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Wifi else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isRunning) IosGreen else IosOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isRunning) AppStrings.get("share_running_title", lang).replace("{n}", proxyPort.toString()) else AppStrings.get("share_vpn_not_running", lang),
                                color = if (isRunning) IosGreen else IosOrange,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isRunning) {
                                    AppStrings.get("share_running_hint", lang)
                                } else {
                                    AppStrings.get("share_offline_hint", lang)
                                },
                                color = if (isRunning) IosGreen.copy(alpha = 0.85f) else IosOrange.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Connection Parameters Section
                item {
                    Spacer(modifier = Modifier.height(10.dp))
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
                                    clipboardManager.setText(AnnotatedString(localIp))
                                    CupertinoToast.show(AppStrings.get("share_copied_ip", lang).replace("{v}", localIp))
                                }
                            } else null
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = AppStrings.get("share_field_port", lang),
                            value = "$proxyPort",
                            onCopy = {
                                haptic.lightTick()
                                clipboardManager.setText(AnnotatedString("$proxyPort"))
                                CupertinoToast.show(AppStrings.get("share_copied_port", lang).replace("{v}", proxyPort.toString()))
                            }
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = AppStrings.get("share_field_full_address", lang),
                            value = fullProxyAddress,
                            onCopy = {
                                haptic.lightTick()
                                clipboardManager.setText(AnnotatedString(fullProxyAddress))
                                CupertinoToast.show(AppStrings.get("share_copied_address", lang).replace("{v}", fullProxyAddress))
                            }
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = AppStrings.get("share_field_auth", lang),
                            value = if (allowLanPassword.isNotBlank()) AppStrings.get("share_auth_enabled", lang) else AppStrings.get("share_auth_none", lang),
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

                // Setup Guides Section
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    CupertinoGroupedSection(
                        header = AppStrings.get("share_guide_section", lang)
                    ) {
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
                            "http://nexus:${android.net.Uri.encode(allowLanPassword)}@$fullProxyAddress"
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
                                clipboardManager.setText(AnnotatedString(curlCmd))
                                CupertinoToast.show(AppStrings.get("share_copied_command", lang))
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean Parameter Row with optional Copy action.
 */
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
            .then(
                if (onCopy != null) {
                    Modifier.clickable(onClick = onCopy)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.5.sp,
            color = LoonTextSecondary
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = value,
                fontSize = 14.5.sp,
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
                    tint = IosBlue,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Clean Guide Row explaining steps for other devices.
 */
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
            .then(
                if (onCopy != null) {
                    Modifier.clickable(onClick = onCopy)
                } else Modifier
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
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = LoonTextPrimary
            )

            if (onCopy != null) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = AppStrings.get("copy_desc", lang),
                    tint = IosBlue,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = steps,
            fontSize = 12.5.sp,
            color = if (isCommand) IosBlue else LoonTextSecondary,
            lineHeight = 17.sp
        )
    }
}

/**
 * Retrieve the active Wi-Fi or LAN IPv4 address.
 */
private fun getLocalIpAddress(context: Context): String? {
    runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val linkProps = cm?.getLinkProperties(activeNet)
        val ip = linkProps?.linkAddresses?.mapNotNull { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
        if (!ip.isNullOrBlank()) return ip
    }

    runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress ?: ""
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                        return host
                    }
                }
            }
        }
    }
    return null
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
