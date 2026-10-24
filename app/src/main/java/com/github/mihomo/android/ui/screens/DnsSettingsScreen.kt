package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@Composable
fun DnsSettingsScreen(
    fakeIpEnabled: Boolean,
    onFakeIpEnabledChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val haptic = rememberIosHaptic()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = "DNS 设置",
            showBackButton = true,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)
        ) {
            item {
                CupertinoGroupedSection(
                    header = "解析与增强模式",
                    footer = if (fakeIpEnabled) {
                        "Fake-IP 模式为每个域名分配虚拟 IP（198.18.0.0/16），省去远端握手前的本地 DNS 等待，有效杜绝 DNS 污染。"
                    } else {
                        "当前为 Redir-Host 模式，对所有请求进行直接真实 IP 域名解析。"
                    }
                ) {
                    CupertinoCell(
                        title = "Fake-IP 增强模式",
                        subtitle = "198.18.0.1/16",
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = fakeIpEnabled,
                                onCheckedChange = {
                                    haptic.lightTick()
                                    onFakeIpEnabledChanged(it)
                                }
                            )
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "当前解析协议",
                        value = if (fakeIpEnabled) "Fake-IP (推荐)" else "Redir-Host",
                        showChevron = false
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                CupertinoGroupedSection(
                    header = "预置上游服务器 (Nameservers)",
                    footer = "包含国内高性能 AliDNS / DNSPod 加密 DoH 与公共解析服务器。"
                ) {
                    CupertinoCell(
                        title = "主加密 DNS (DoH)",
                        value = "阿里 223.5.5.5",
                        subtitle = "https://223.5.5.5/dns-query",
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "备加密 DNS (DoH)",
                        value = "腾讯 DNSPod",
                        subtitle = "https://doh.pub/dns-query",
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "直连解析服务器",
                        value = "223.5.5.5, 119.29.29.29",
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "境外降级服务器 (Fallback)",
                        value = "Cloudflare & Google DoH",
                        subtitle = "https://1.1.1.1/dns-query",
                        showChevron = false
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                CupertinoGroupedSection(
                    header = "核心特性与网络",
                    footer = "DNS 查询日志将在开启实时监控时自动捕获并展示在「DNS 记录」视图中。"
                ) {
                    CupertinoCell(
                        title = "内核 DNS 监听端口",
                        value = "127.0.0.1:1053",
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "本地 DNS 智能缓存",
                        value = "已启用 (自适应 TTL)",
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "IPv6 DNS 解析",
                        value = "已禁用 (防泄露)",
                        showChevron = false
                    )
                }
            }
        }
    }
}
