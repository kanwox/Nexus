package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun NetworkShareScreen(
    allowLan: Boolean,
    onAllowLanChanged: (Boolean) -> Unit,
    vpnState: VpnState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val clipboardManager = LocalClipboardManager.current
    val isRunning = vpnState.status == VpnState.Status.RUNNING

    val localIps = remember {
        val ips = mutableListOf<String>()
        val wifiIps = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in interfaces) {
                val ifName = iface.name.lowercase()
                if (iface.isLoopback || !iface.isUp) continue
                val ignoredPrefixes = listOf("tun", "tap", "dummy", "rmnet", "sit", "p2p")
                if (ignoredPrefixes.any { ifName.contains(it) }) continue

                val wifiKeywords = listOf("wlan", "eth", "wifi", "ap", "en")
                val isWifi = wifiKeywords.any { ifName.contains(it) }

                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.") || host.startsWith("198.18.") || host.startsWith("172.19.")) continue
                        if (isWifi) {
                            wifiIps.add(host)
                        } else {
                            ips.add(host)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val combined = (wifiIps + ips).distinct()
        if (combined.isEmpty()) listOf("192.168.1.X") else combined
    }

    val primaryIp = localIps.firstOrNull() ?: "127.0.0.1"
    val port = "7890"

    var selectedPlatform by remember { mutableStateOf("WINDOWS") }

    fun copyToClipboard(label: String, text: String) {
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制 $label 到剪贴板", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("network_share_screen_title", lang),
            showBackButton = true,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card 1: Switch & Status
            item(key = "lan_switch") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "允许局域网连接",
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LoonTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (allowLan) "已允许同 Wi-Fi 下的其他设备通过本机代理上网" else "未开启，仅限本机应用通过核心代理",
                                    fontSize = 11.5.sp,
                                    color = LoonTextMuted
                                )
                            }
                            CupertinoSwitch(
                                checked = allowLan,
                                onCheckedChange = { onAllowLanChanged(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = LoonCardBorder, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusColor = if (allowLan && isRunning) LoonGreen else RedDanger
                            val statusText = if (allowLan && isRunning) "服务运行中" else if (!isRunning) "核心未启动" else "共享未开启"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text("混合代理端口: 7890", fontSize = 12.sp, color = LoonTextSecondary, fontWeight = FontWeight.Medium)
                            }

                            Text(
                                text = "HTTP + SOCKS5",
                                fontSize = 11.sp,
                                color = LoonTextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Card 2: Detected LAN IP Addresses
            item(key = "lan_ips") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "本机局域网 IP 与代理地址",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = LoonTextPrimary
                            )
                            Icon(Icons.Default.Lan, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        localIps.forEach { ip ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = LoonBg,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "$ip:$port",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LoonTextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text("局域网代理主机与端口", fontSize = 10.5.sp, color = LoonTextMuted)
                                    }

                                    IconButton(
                                        onClick = { copyToClipboard("代理地址", "$ip:$port") },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", tint = LoonBlue, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Card 3: One-Tap Terminal Proxy Commands
            item(key = "terminal_commands") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "终端一键配置代理命令",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "在电脑终端中执行下列命令即可将网络请求转至本机：",
                            fontSize = 11.5.sp,
                            color = LoonTextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val commands = listOf(
                            Triple(
                                "Windows PowerShell",
                                "\$env:http_proxy=\"http://$primaryIp:$port\"; \$env:https_proxy=\"http://$primaryIp:$port\"",
                                "PowerShell 命令"
                            ),
                            Triple(
                                "macOS / Linux Shell",
                                "export http_proxy=http://$primaryIp:$port https_proxy=http://$primaryIp:$port",
                                "Shell 命令"
                            ),
                            Triple(
                                "Windows CMD",
                                "set http_proxy=http://$primaryIp:$port & set https_proxy=http://$primaryIp:$port",
                                "CMD 命令"
                            )
                        )

                        commands.forEach { (title, cmd, label) ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = LoonBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LoonBlue)
                                        IconButton(
                                            onClick = { copyToClipboard(label, cmd) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "复制", tint = LoonTextSecondary, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = cmd,
                                        fontSize = 11.sp,
                                        color = LoonTextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Card 4: Device Configuration Guide
            item(key = "device_guide") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "设备 Wi-Fi 代理设置指南",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Platform Selector Segmented Control
                        val platforms = listOf("WINDOWS", "MACOS", "IOS", "ANDROID")
                        CupertinoSegmentedControl(
                            items = platforms,
                            selectedItem = selectedPlatform,
                            onItemSelected = { selectedPlatform = it },
                            labelProvider = { key ->
                                when (key) {
                                    "WINDOWS" -> "Windows"
                                    "MACOS" -> "macOS"
                                    "IOS" -> "iOS"
                                    else -> "Android"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val instructions = when (selectedPlatform) {
                            "WINDOWS" -> listOf(
                                "1. 确保电脑连接到与本手机相同的 Wi-Fi 或手机热点网络",
                                "2. 打开系统 [设置] -> [网络和 Internet] -> [代理]",
                                "3. 在手动设置代理中开启 [使用代理服务器]",
                                "4. 代理 IP 地址填写: $primaryIp",
                                "5. 端口填写: $port ，保存生效"
                            )
                            "MACOS" -> listOf(
                                "1. 确保 Mac 连接到与本手机相同的 Wi-Fi 或热点",
                                "2. 打开 [系统设置] -> [网络] -> 选择当前连接的 Wi-Fi -> [详细信息]",
                                "3. 点击左侧 [代理] 标签页，勾选 [网页代理 (HTTP)] 与 [安全网页代理 (HTTPS)]",
                                "4. 服务器地址填写: $primaryIp ，端口填写: $port",
                                "5. 点击 [好] 保存后即可生效"
                            )
                            "IOS" -> listOf(
                                "1. iPhone / iPad 连接与本手机相同的 Wi-Fi",
                                "2. 打开 [设置] -> [无线局域网] -> 点击当前 Wi-Fi 右侧的 ⓘ 图标",
                                "3. 滑动至底部找到 [配置代理]，选择 [手动]",
                                "4. 服务器填写: $primaryIp ，端口填写: $port",
                                "5. 点击右上角 [存储] 即可生效"
                            )
                            else -> listOf(
                                "1. 另一台安卓手机连接相同 Wi-Fi",
                                "2. 在 Wi-Fi 详情中找到 [高级选项] -> [代理] 改为 [手动]",
                                "3. 代理主机名填写: $primaryIp ，代理端口填写: $port",
                                "4. 保存即可连接"
                            )
                        }

                        instructions.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 12.sp,
                                color = LoonTextSecondary,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
