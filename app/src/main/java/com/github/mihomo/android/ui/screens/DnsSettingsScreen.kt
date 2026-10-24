package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class DnsPreset(val label: String, val nameservers: String, val fallbacks: String) {
    BALANCED(
        label = "标准推荐 (阿里 · 腾讯)",
        nameservers = "https://223.5.5.5/dns-query, https://doh.pub/dns-query, 223.5.5.5, 119.29.29.29",
        fallbacks = "https://1.1.1.1/dns-query, https://8.8.8.8/dns-query, 1.1.1.1, 8.8.8.8"
    ),
    STRICT_DOH(
        label = "纯加密防污染 (Cloudflare · Google)",
        nameservers = "https://223.5.5.5/dns-query, https://doh.pub/dns-query",
        fallbacks = "https://1.1.1.1/dns-query, https://8.8.8.8/dns-query, https://dns.quad9.net/dns-query"
    ),
    CLASSIC(
        label = "经典公共 (114 · 阿里)",
        nameservers = "223.5.5.5, 119.29.29.29, 114.114.114.114",
        fallbacks = "1.1.1.1, 8.8.8.8"
    )
}

@Composable
fun DnsSettingsScreen(
    fakeIpEnabled: Boolean = true,
    onFakeIpEnabledChanged: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val settingsManager = remember { SettingsManager(context) }

    var currentFakeIp by remember { mutableStateOf(fakeIpEnabled) }
    var currentIpv6 by remember { mutableStateOf(settingsManager.dnsIpv6Enabled) }
    var nameserversText by remember { mutableStateOf(settingsManager.dnsNameservers) }
    var fallbackText by remember { mutableStateOf(settingsManager.dnsFallbackServers) }

    var editingNameserversDialog by remember { mutableStateOf(false) }
    var tempNameserversInput by remember { mutableStateOf("") }

    var editingFallbackDialog by remember { mutableStateOf(false) }
    var tempFallbackInput by remember { mutableStateOf("") }

    var showPresetSheet by remember { mutableStateOf(false) }

    val applyDnsChanges = {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                ClashCore.reloadActiveConfig(context)
            }
        }
        CupertinoToast.show("DNS 配置已更新", isDestructive = false)
    }

    // Helper for compact preview
    val formatPreview = { raw: String ->
        val list = raw.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        when {
            list.isEmpty() -> "未配置"
            list.size == 1 -> list.first().removePrefix("https://").substringBefore('/')
            else -> {
                val first = list[0].removePrefix("https://").substringBefore('/')
                val second = list[1].removePrefix("https://").substringBefore('/')
                "$first · $second"
            }
        }
    }

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
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // Group 1: Resolution Mode
            item {
                CupertinoGroupedSection(header = "解析模式") {
                    CupertinoCell(
                        title = "Fake-IP 模式",
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentFakeIp,
                                onCheckedChange = { checked ->
                                    haptic.lightTick()
                                    currentFakeIp = checked
                                    settingsManager.fakeIpEnabled = checked
                                    onFakeIpEnabledChanged?.invoke(checked)
                                    applyDnsChanges()
                                }
                            )
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "IPv6 解析",
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentIpv6,
                                onCheckedChange = { checked ->
                                    haptic.lightTick()
                                    currentIpv6 = checked
                                    settingsManager.dnsIpv6Enabled = checked
                                    applyDnsChanges()
                                }
                            )
                        }
                    )
                }
            }

            // Group 2: DNS Servers
            item {
                Spacer(modifier = Modifier.height(16.dp))
                CupertinoGroupedSection(header = "DNS 服务器") {
                    CupertinoCell(
                        title = "国内 DNS",
                        value = formatPreview(nameserversText),
                        showChevron = true,
                        onClick = {
                            haptic.selection()
                            tempNameserversInput = nameserversText.split(',', '\n')
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                            editingNameserversDialog = true
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "国外 DNS",
                        value = formatPreview(fallbackText),
                        showChevron = true,
                        onClick = {
                            haptic.selection()
                            tempFallbackInput = fallbackText.split(',', '\n')
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                            editingFallbackDialog = true
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = "预设方案",
                        value = "快速切换",
                        showChevron = true,
                        onClick = {
                            haptic.selection()
                            showPresetSheet = true
                        }
                    )
                }
            }
        }
    }

    // Modal: Edit Domestic Nameservers
    if (editingNameserversDialog) {
        CupertinoAlertDialog(
            title = "国内 DNS 服务器",
            onDismissRequest = { editingNameserversDialog = false },
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                val clean = tempNameserversInput.split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                val effective = clean.ifBlank { SettingsManager.DEFAULT_NAMESERVERS }
                nameserversText = effective
                settingsManager.dnsNameservers = effective
                editingNameserversDialog = false
                applyDnsChanges()
            },
            onDismiss = { editingNameserversDialog = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                CupertinoTextField(
                    value = tempNameserversInput,
                    onValueChange = { tempNameserversInput = it },
                    placeholder = "https://223.5.5.5/dns-query\n119.29.29.29",
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.height(96.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                CupertinoButton(
                    text = "恢复默认",
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    style = CupertinoButtonStyle.Tinted,
                    onClick = {
                        tempNameserversInput = SettingsManager.DEFAULT_NAMESERVERS.split(',').joinToString("\n") { it.trim() }
                    }
                )
            }
        }
    }

    // Modal: Edit Foreign Fallback Servers
    if (editingFallbackDialog) {
        CupertinoAlertDialog(
            title = "国外 DNS 服务器",
            onDismissRequest = { editingFallbackDialog = false },
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                val clean = tempFallbackInput.split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                val effective = clean.ifBlank { SettingsManager.DEFAULT_FALLBACK_SERVERS }
                fallbackText = effective
                settingsManager.dnsFallbackServers = effective
                editingFallbackDialog = false
                applyDnsChanges()
            },
            onDismiss = { editingFallbackDialog = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                CupertinoTextField(
                    value = tempFallbackInput,
                    onValueChange = { tempFallbackInput = it },
                    placeholder = "https://1.1.1.1/dns-query\n8.8.8.8",
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.height(96.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                CupertinoButton(
                    text = "恢复默认",
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    style = CupertinoButtonStyle.Tinted,
                    onClick = {
                        tempFallbackInput = SettingsManager.DEFAULT_FALLBACK_SERVERS.split(',').joinToString("\n") { it.trim() }
                    }
                )
            }
        }
    }

    // ActionSheet: Presets
    if (showPresetSheet) {
        CupertinoActionSheet(
            title = "DNS 预设方案",
            actions = DnsPreset.entries.map { preset ->
                CupertinoActionSheetItem(
                    text = preset.label,
                    onClick = {
                        nameserversText = preset.nameservers
                        fallbackText = preset.fallbacks
                        settingsManager.dnsNameservers = preset.nameservers
                        settingsManager.dnsFallbackServers = preset.fallbacks
                        showPresetSheet = false
                        applyDnsChanges()
                    }
                )
            },
            onDismissRequest = { showPresetSheet = false }
        )
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
