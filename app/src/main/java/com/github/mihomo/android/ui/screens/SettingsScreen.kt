package com.github.mihomo.android.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.data.UpdateChecker
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    scrollState: ScrollState,
    tunStack: String,
    bootOnStartup: Boolean,
    allowLan: Boolean,
    fakeIpEnabled: Boolean,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onTunStackChanged: (String) -> Unit,
    onBootOnStartupChanged: (Boolean) -> Unit,
    onAllowLanChanged: (Boolean) -> Unit,
    onNavigateToScripts: () -> Unit = {},
    onNavigateToProfiles: () -> Unit,
    onNavigateToAppRouting: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToDns: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    var showTunDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var currentLogLevel by remember { mutableStateOf(settingsManager.logLevel) }

    var scripts by remember { mutableStateOf(emptyList<ScriptItem>()) }
    var coreVersion by remember { mutableStateOf("") }

    // Update-check state
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val installedVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: UpdateChecker.CURRENT_VERSION
        } catch (_: PackageManager.NameNotFoundException) {
            UpdateChecker.CURRENT_VERSION
        }
    }

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
        coreVersion = withContext(Dispatchers.IO) { ClashCore.getCoreVersion() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(top = 10.dp)
    ) {
        // iOS Large Title Header
        Text(
            text = AppStrings.get("settings_title", lang),
            color = LoonTextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Scrollable content area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .bounceOverscroll()
                .verticalScroll(scrollState)
                .padding(bottom = 120.dp)
        ) {
            // Section 1: 网络与核心 (Network & Core)
            CupertinoGroupedSection(
                header = "网络与核心"
            ) {
                CupertinoCell(
                    title = "TUN 堆栈",
                    value = when (tunStack.lowercase()) {
                        "mixed" -> "Mixed"
                        "gvisor" -> "gVisor"
                        else -> "System"
                    },
                    icon = Icons.Default.VpnKey,
                    iconBg = IosBlue,
                    showChevron = true,
                    onClick = { showTunDialog = true }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "开机自启动",
                    icon = Icons.Default.PowerSettingsNew,
                    iconBg = Color(0xFF34C759),
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
                    title = "局域网共享",
                    icon = Icons.Default.WifiTethering,
                    iconBg = Color(0xFF32ADE6),
                    trailing = {
                        CupertinoSwitch(
                            checked = allowLan,
                            onCheckedChange = onAllowLanChanged
                        )
                    },
                    onClick = { onAllowLanChanged(!allowLan) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "系统 VPN",
                    value = "始终开启",
                    icon = Icons.Default.Security,
                    iconBg = Color(0xFFFF9500),
                    showChevron = true,
                    onClick = {
                        runCatching {
                            val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 2: 分流与服务 (Routing & Services)
            CupertinoGroupedSection(
                header = "分流与服务"
            ) {
                CupertinoCell(
                    title = "应用分流",
                    icon = Icons.Default.Apps,
                    iconBg = Color(0xFFFF9500),
                    showChevron = true,
                    onClick = onNavigateToAppRouting
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "DNS 设置",
                    value = if (fakeIpEnabled) "Fake-IP" else "Redir-Host",
                    icon = Icons.Default.Public,
                    iconBg = Color(0xFF30B0C7),
                    showChevron = true,
                    onClick = onNavigateToDns
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "脚本中心",
                    value = "${scripts.size} 个脚本",
                    icon = Icons.Default.Code,
                    iconBg = Color(0xFF5856D6),
                    showChevron = true,
                    onClick = onNavigateToScripts
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "配置与订阅",
                    icon = Icons.Default.FolderZip,
                    iconBg = Color(0xFFAF52DE),
                    showChevron = true,
                    onClick = onNavigateToProfiles
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: 个性化与调试 (Preferences & Debugging)
            CupertinoGroupedSection(
                header = "个性化与调试"
            ) {
                CupertinoCell(
                    title = "外观与显示",
                    value = when (themeMode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    icon = Icons.Default.Palette,
                    iconBg = Color(0xFFFF2D55),
                    showChevron = true,
                    onClick = onNavigateToAppearance
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "核心日志",
                    icon = Icons.Default.Terminal,
                    iconBg = Color(0xFF8E8E93),
                    showChevron = true,
                    onClick = onNavigateToLogs
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "日志级别",
                    value = currentLogLevel.uppercase(),
                    icon = Icons.Default.Tune,
                    iconBg = Color(0xFF5856D6),
                    showChevron = true,
                    onClick = { showLogLevelDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 4: 关于与维护 (About & Maintenance)
            CupertinoGroupedSection(
                header = "维护与关于"
            ) {
                CupertinoCell(
                    title = "检查更新",
                    value = "v$installedVersion",
                    icon = Icons.Default.SystemUpdate,
                    iconBg = Color(0xFF34C759),
                    trailing = {
                        if (isCheckingUpdate) {
                            CupertinoActivityIndicator(
                                modifier = Modifier.size(16.dp),
                                color = IosBlue
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = LoonTextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    },
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateResult = null
                            scope.launch {
                                val result = UpdateChecker.checkForUpdate()
                                updateResult = result
                                isCheckingUpdate = false
                                showUpdateDialog = true
                            }
                        }
                    }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "核心版本",
                    value = coreVersion.ifBlank { "v1.18.x" },
                    icon = Icons.Default.Memory,
                    iconBg = Color(0xFF8E8E93),
                    showChevron = false
                )
                CupertinoDivider()
                CupertinoCell(
                    title = "重置核心状态",
                    icon = Icons.Default.RestartAlt,
                    iconBg = Color(0xFFFF3B30),
                    showChevron = true,
                    onClick = { showResetDialog = true }
                )
            }
        }
    }

    // TUN Stack Selection BottomSheet
    if (showTunDialog) {
        val stacks = listOf(
            Triple("mixed", "Mixed", "兼顾性能与稳定性的混合模式"),
            Triple("gvisor", "gVisor", "用户态网络协议栈隔离"),
            Triple("system", "System", "操作系统原生网络栈")
        )
        ModalBottomSheet(
            onDismissRequest = { showTunDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "TUN 协议栈",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

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
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) IosBlue else LoonTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = 12.sp,
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

    // Log Level Selection BottomSheet
    if (showLogLevelDialog) {
        val levels = listOf(
            Triple("info", "INFO", "记录标准网络调度与运行输出"),
            Triple("warning", "WARN", "仅记录警告告警与异常"),
            Triple("error", "ERROR", "仅记录严重崩溃与错误"),
            Triple("debug", "DEBUG", "输出底层全部调试报文")
        )
        ModalBottomSheet(
            onDismissRequest = { showLogLevelDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "日志详细程度",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                levels.forEachIndexed { index, (lvlKey, label, desc) ->
                    val isSelected = currentLogLevel.equals(lvlKey, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                currentLogLevel = lvlKey
                                settingsManager.logLevel = lvlKey
                                showLogLevelDialog = false
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) IosBlue else LoonTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = 12.sp,
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
                    if (index < levels.size - 1) {
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

    // Core Reset Confirmation
    if (showResetDialog) {
        CupertinoAlertDialog(
            title = "重置核心状态",
            message = "确定要重置底层核心状态吗？当前活动的网络连接将被断开并重新初始化。",
            confirmText = "重置",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                showResetDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { ClashCore.reset() }
                    CupertinoToast.show("核心已重置")
                }
            },
            onDismissRequest = { showResetDialog = false }
        )
    }

    // Update Check Result Dialog
    if (showUpdateDialog && updateResult != null) {
        val result = updateResult!!
        val isNewer = result is UpdateChecker.CheckResult.Success && result.info.isNewer
        CupertinoAlertDialog(
            title = "检查更新",
            onDismissRequest = { showUpdateDialog = false },
            confirmText = if (isNewer) "前往下载" else "好",
            dismissText = if (isNewer) "稍后再说" else null,
            onConfirm = {
                showUpdateDialog = false
                if (result is UpdateChecker.CheckResult.Success && result.info.isNewer) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.info.htmlUrl))
                    runCatching { context.startActivity(browserIntent) }
                }
            },
            onDismiss = { showUpdateDialog = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (result) {
                        is UpdateChecker.CheckResult.Success -> {
                            val info = result.info
                            if (info.isNewer) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(IosGreen)
                                    )
                                    Text(
                                        text = "发现新版本 ${info.tagName}",
                                        color = IosGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                if (info.releaseNotes.isNotBlank()) {
                                    Surface(
                                        color = LoonBg,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = info.releaseNotes.take(300),
                                            color = LoonTextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "当前已是最新版本 (v$installedVersion)",
                                    color = LoonTextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        is UpdateChecker.CheckResult.Error -> {
                            Text(
                                text = "检查更新失败: ${result.message}",
                                color = RedDanger,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    MihomoTheme {
        SettingsScreen(
            scrollState = androidx.compose.foundation.rememberScrollState(),
            tunStack = "mixed",
            bootOnStartup = true,
            allowLan = false,
            fakeIpEnabled = true,
            themeMode = ThemeMode.SYSTEM,
            onTunStackChanged = {},
            onBootOnStartupChanged = {},
            onAllowLanChanged = {},
            onNavigateToScripts = {},
            onNavigateToProfiles = {},
            onNavigateToAppRouting = {},
            onNavigateToLogs = {},
            onNavigateToAppearance = {},
            onNavigateToDns = {}
        )
    }
}
