package com.github.mihomo.android.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
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

@Suppress("UnusedParameter", "UNUSED_PARAMETER")
@Composable
fun NetworkShareScreen(
    allowLan: Boolean,
    onAllowLanChanged: (Boolean) -> Unit,
    vpnState: VpnState,
    onBack: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = rememberIosHaptic()

    val localIp = remember(allowLan, vpnState.status) {
        getLocalIpAddress(context)
    }

    val proxyPort = 7890
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
                    header = "共享设置",
                    footer = "开启后，同一局域网内的其他设备（如电脑、Switch/PS5 游戏主机、平板等）可通过填入本机 IP 与端口接入代理网络。"
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
                                text = "允许局域网连接",
                                color = LoonTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "监听 0.0.0.0 开放混合代理端口",
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

            // Contextual Status Alert
            if (allowLan) {
                item {
                    val isRunning = vpnState.status == VpnState.Status.RUNNING

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
                                text = if (isRunning) "共享服务运行中 · 端口 $proxyPort 已对外监听" else "VPN 核心未运行",
                                color = if (isRunning) IosGreen else IosOrange,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isRunning) {
                                    "局域网设备按下方参数配置即可正常走代理"
                                } else {
                                    "共享开关已开启，但需启动 VPN 外部设备方可正常访问互联网"
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
                        header = "连接参数",
                        footer = "请确保被共享的终端设备与此手机处于同一个 Wi-Fi 局域网或连接了此手机的热点。"
                    ) {
                        ShareParamRow(
                            label = "本机局域网 IP",
                            value = localIp ?: "未能获取 (请确认已连接 Wi-Fi)",
                            onCopy = if (localIp != null) {
                                {
                                    haptic.lightTick()
                                    clipboardManager.setText(AnnotatedString(localIp))
                                    CupertinoToast.show("已复制本机 IP: $localIp")
                                }
                            } else null
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = "代理混合端口",
                            value = "$proxyPort",
                            onCopy = {
                                haptic.lightTick()
                                clipboardManager.setText(AnnotatedString("$proxyPort"))
                                CupertinoToast.show("已复制端口: $proxyPort")
                            }
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = "完整代理地址",
                            value = fullProxyAddress,
                            onCopy = {
                                haptic.lightTick()
                                clipboardManager.setText(AnnotatedString(fullProxyAddress))
                                CupertinoToast.show("已复制完整代理地址: $fullProxyAddress")
                            }
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        ShareParamRow(
                            label = "支持协议",
                            value = "HTTP / HTTPS / SOCKS5",
                            onCopy = null
                        )
                    }
                }

                // Setup Guides Section
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    CupertinoGroupedSection(
                        header = "终端接入指南"
                    ) {
                        GuideRow(
                            title = "Windows / macOS 电脑",
                            steps = "系统设置 ➔ 网络 ➔ 代理 ➔ 开启手动代理 ➔ 地址填入上方本机 IP ➔ 端口填入 $proxyPort"
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        GuideRow(
                            title = "Nintendo Switch / 游戏主机",
                            steps = "设置 ➔ 互联网设置 ➔ 选择当前 Wi-Fi ➔ 更改设置 ➔ 代理服务器设为【使用】➔ 输入上方 IP 与 $proxyPort"
                        )

                        CupertinoIndentedDivider(startIndent = 16.dp)

                        val curlCmd = "export http_proxy=\"http://$fullProxyAddress\" && export https_proxy=\"http://$fullProxyAddress\""
                        GuideRow(
                            title = "命令行终端代理 (Terminal)",
                            steps = curlCmd,
                            isCommand = true,
                            onCopy = {
                                haptic.lightTick()
                                clipboardManager.setText(AnnotatedString(curlCmd))
                                CupertinoToast.show("已复制终端代理命令")
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
                    contentDescription = "复制",
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
                    contentDescription = "复制",
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
        vpnState = VpnState(VpnState.Status.RUNNING),
        onBack = {}
    )
}
