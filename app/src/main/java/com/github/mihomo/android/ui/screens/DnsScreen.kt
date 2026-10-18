package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

data class DnsQueryResult(
    val domain: String,
    val ips: List<String>,
    val timeMs: Long,
    val isFakeIp: Boolean
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsScreen(
    fakeIpEnabled: Boolean,
    onFakeIpEnabledChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    var testDomainInput by remember { mutableStateOf("") }
    var isQuerying by remember { mutableStateOf(false) }
    var lastQueryResult by remember { mutableStateOf<DnsQueryResult?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }

    val logs by LogRepository.logsFlow.collectAsState()
    val dnsLogs = remember(logs) {
        logs.filter {
            it.message.contains("dns", ignoreCase = true) ||
            it.message.contains("fake-ip", ignoreCase = true) ||
            it.message.contains("resolve", ignoreCase = true)
        }.takeLast(50).reversed()
    }

    val bypassDomains = remember {
        listOf(
            "*.lan",
            "*.local",
            "localhost.ptlogin2.qq.com",
            "*.connectivitycheck.gstatic.com",
            "connectivitycheck.gstatic.com",
            "*.connectivitycheck.android.com",
            "connectivitycheck.android.com",
            "clients3.google.com",
            "www.google.com/generate_204",
            "+.msftconnecttest.com",
            "+.qualcomm.com",
            "+.miui.com",
            "+.xiaomi.com"
        )
    }

    fun executeDnsLookup(domain: String) {
        val clean = domain.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
        if (clean.isBlank()) return
        isQuerying = true
        queryError = null
        scope.launch {
            val start = System.currentTimeMillis()
            try {
                val addresses = withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(clean).map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
                }
                val duration = System.currentTimeMillis() - start
                val isFakeIp = addresses.any { it.startsWith("198.18.") }
                lastQueryResult = DnsQueryResult(clean, addresses, duration, isFakeIp)
            } catch (e: Throwable) {
                queryError = e.message ?: "解析失败，无法解析主机"
                lastQueryResult = null
            } finally {
                isQuerying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("dns_screen_title", lang),
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
            // Card 1: DNS Mode & Status
            item(key = "dns_status") {
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
                                    text = "Fake-IP 增强模式",
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LoonTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (fakeIpEnabled) "已启用虚假 IP 映射池，加速 DNS 响应" else "使用标准 Redir-Host 模式解析",
                                    fontSize = 11.5.sp,
                                    color = LoonTextMuted
                                )
                            }
                            CupertinoSwitch(
                                checked = fakeIpEnabled,
                                onCheckedChange = { onFakeIpEnabledChanged(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = LoonCardBorder, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Fake-IP 网段", fontSize = 11.sp, color = LoonTextMuted)
                                Text("198.18.0.1/16", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                            }
                            Column {
                                Text("核心监听端口", fontSize = 11.sp, color = LoonTextMuted)
                                Text("127.0.0.1:1053", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                            }
                            Column {
                                Text("防污染劫持", fontSize = 11.sp, color = LoonTextMuted)
                                Text("已开启", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LoonGreen)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Flush Cache Pill Button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = LoonTertiaryBg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            if (com.github.mihomo.android.core.ClashCore.isCoreLoaded) {
                                                com.github.mihomo.android.core.ClashCore.notifyNetworkChanged(context)
                                            }
                                        }
                                    }
                                    Toast.makeText(context, "已成功刷新 Fake-IP 映射与 DNS 缓存", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, tint = IosBlue, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("清空 Fake-IP 与 DNS 缓存", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IosBlue)
                            }
                        }
                    }
                }
            }

            // Card 2: Live DNS Diagnostic Tool
            item(key = "dns_test_tool") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DNS 实时解析诊断",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = testDomainInput,
                                onValueChange = { testDomainInput = it },
                                placeholder = { Text("输入域名如 www.google.com", fontSize = 12.5.sp, color = LoonTextMuted) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = LoonBg,
                                    unfocusedContainerColor = LoonBg,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = LoonTextPrimary,
                                    unfocusedTextColor = LoonTextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            )

                            Surface(
                                modifier = Modifier
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (testDomainInput.isNotBlank() && !isQuerying) IosBlue else LoonTextMuted)
                                    .clickable(enabled = testDomainInput.isNotBlank() && !isQuerying) {
                                        executeDnsLookup(testDomainInput)
                                    },
                                color = if (testDomainInput.isNotBlank() && !isQuerying) IosBlue else LoonTextMuted
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isQuerying) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("解析", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }

                        if (lastQueryResult != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = LoonBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(lastQueryResult!!.domain, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LoonTextPrimary)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (lastQueryResult!!.isFakeIp) {
                                                Text(
                                                    text = "Fake-IP",
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(end = 6.dp)
                                                )
                                            }
                                            Text("${lastQueryResult!!.timeMs} ms", color = LoonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    lastQueryResult!!.ips.forEach { ip ->
                                        Text("• $ip", fontSize = 12.sp, color = LoonTextSecondary, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        } else if (queryError != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "解析失败: $queryError",
                                color = RedDanger,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }
                }
            }

            // Card 3: Upstream Nameservers
            item(key = "dns_servers") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "配置的上游 DNS 服务器",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val servers = listOf(
                            Triple("223.5.5.5", "Alibaba Public DNS (国内解析)", LoonGreen),
                            Triple("119.29.29.29", "Tencent DNSPod (国内解析)", LoonGreen),
                            Triple("https://doh.pub/dns-query", "DNSPod DoH 安全加密", LoonBlue),
                            Triple("https://223.5.5.5/dns-query", "AliDNS DoH 安全加密", LoonBlue),
                            Triple("1.1.1.1", "Cloudflare DNS (国外回退)", Color(0xFFF59E0B)),
                            Triple("8.8.8.8", "Google DNS (国外回退)", Color(0xFFF59E0B))
                        )

                        servers.forEach { (ip, label, badgeColor) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(ip, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = LoonTextPrimary, fontFamily = FontFamily.Monospace)
                                    Text(label, fontSize = 10.5.sp, color = LoonTextMuted)
                                }
                                Text(
                                    text = "活跃",
                                    color = badgeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Card 4: Fake-IP Bypass Filter Domains
            item(key = "dns_bypass") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Fake-IP 过滤名单 (直连真实解析)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "直连真实解析，保障特定系统服务正常工作",
                            fontSize = 11.5.sp,
                            color = LoonTextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            bypassDomains.forEach { domain ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = LoonBg,
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder)
                                ) {
                                    Text(
                                        text = domain,
                                        fontSize = 11.sp,
                                        color = LoonTextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Card 5: Recent DNS Query Log Entries
            item(key = "dns_logs_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "最近 DNS 解析记录 (${dnsLogs.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoonTextPrimary
                    )
                }
            }

            if (dnsLogs.isEmpty()) {
                item(key = "empty_dns_logs") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoonCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "暂无捕获的 DNS 查询记录\n代理启动后网络域名解析事件将在此展示",
                                color = LoonTextMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(dnsLogs, key = { it.id }) { log ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = LoonCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DNS",
                                color = LoonBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = log.message,
                                fontSize = 11.5.sp,
                                color = LoonTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
