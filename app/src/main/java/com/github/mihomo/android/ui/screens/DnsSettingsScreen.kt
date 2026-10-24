package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DnsSettingsScreen(
    fakeIpEnabled: Boolean = true,
    onFakeIpEnabledChanged: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    var currentFakeIp by remember { mutableStateOf(fakeIpEnabled) }
    var currentIpv6 by remember { mutableStateOf(settingsManager.dnsIpv6Enabled) }

    val applyDnsChanges = {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                ClashCore.reloadActiveConfig(context)
            }
        }
        CupertinoToast.show("DNS 配置已更新", isDestructive = false)
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
            // Group: Resolution Mode
            item {
                CupertinoGroupedSection(header = "解析模式") {
                    CupertinoCell(
                        title = "Fake-IP 模式",
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = currentFakeIp,
                                onCheckedChange = { checked ->
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
                                    currentIpv6 = checked
                                    settingsManager.dnsIpv6Enabled = checked
                                    applyDnsChanges()
                                }
                            )
                        }
                    )
                }
            }
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
