package com.github.mihomo.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

/**
 * Strings that have no AppStrings entry yet (they live outside the shared table so the
 * dictionaries stay untouched) are picked by the active app language.
 */
private fun localeText(lang: String, zh: String, en: String): String {
    val effective = if (lang == "SYSTEM") Locale.getDefault().language else lang
    return if (effective.startsWith("zh", ignoreCase = true)) zh else en
}

/** Streams the release apk into cacheDir/updates/, replacing any previously downloaded one. */
private suspend fun downloadUpdateApk(context: Context, asset: UpdateChecker.ReleaseAsset): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val fileName = asset.browserDownloadUrl.substringAfterLast('/').ifBlank { "nexus-update.apk" }
            val target = File(dir, fileName)
            val request = Request.Builder().url(asset.browserDownloadUrl).build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        }.getOrNull()
    }

/** Fires the package installer for [apk], routing through the unknown-sources gate first. */
private fun installUpdateApk(context: Context, apk: File, lang: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                )
            )
        }
        CupertinoToast.show(
            localeText(lang, "请先允许安装未知来源应用", "Allow installing unknown apps first"),
            isDestructive = true
        )
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        CupertinoToast.show(
            AppStrings.get("settings_update_install_failed", lang).replace("{reason}", it.message ?: ""),
            isDestructive = true
        )
    }
}

/** Downloads the release apk off the main thread, then either installs it or reports the failure. */
private fun beginUpdateDownload(
    context: Context,
    asset: UpdateChecker.ReleaseAsset,
    lang: String,
    scope: CoroutineScope,
    setDownloading: (Boolean) -> Unit
) {
    CupertinoToast.show(AppStrings.get("settings_update_downloading", lang))
    scope.launch {
        val apk = downloadUpdateApk(context, asset)
        setDownloading(false)
        if (apk == null) {
            CupertinoToast.show(
                AppStrings.get("settings_update_install_failed", lang)
                    .replace("{reason}", localeText(lang, "下载失败", "download failed")),
                isDestructive = true
            )
        } else {
            CupertinoToast.show(AppStrings.get("settings_update_ready", lang))
            installUpdateApk(context, apk, lang)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    scrollState: ScrollState,
    tunStack: String,
    bootOnStartup: Boolean,
    allowLan: Boolean,
    fakeIpEnabled: Boolean,
    ipv6Enabled: Boolean,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onTunStackChanged: (String) -> Unit,
    onBootOnStartupChanged: (Boolean) -> Unit,
    onAllowLanChanged: (Boolean) -> Unit,
    onIpv6EnabledChanged: (Boolean) -> Unit = {},
    onNavigateToScripts: () -> Unit = {},
    onNavigateToProfiles: () -> Unit,
    onNavigateToAppRouting: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToDnsSettings: () -> Unit = {},
    killSwitchEnabled: Boolean = true,
    onKillSwitchChanged: (Boolean) -> Unit = {},
    allowBypassEnabled: Boolean = true,
    onAllowBypassChanged: (Boolean) -> Unit = {},
    mixedPort: Int = 7890,
    onMixedPortChanged: (Int) -> Unit = {},
    tunMtu: Int = 9000,
    onTunMtuChanged: (Int) -> Unit = {},
    allowPrivateSubs: Boolean = true,
    onAllowPrivateSubsChanged: (Boolean) -> Unit = {},
    appLockEnabled: Boolean = false,
    onAppLockChanged: (Boolean) -> Unit = {},
    preventScreenshots: Boolean = false,
    onPreventScreenshotsChanged: (Boolean) -> Unit = {},
    onNavigateToCore: () -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri) -> Unit = {}
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
    var isDownloadingUpdate by remember { mutableStateOf(false) }

    // Numeric-network-field dialogs (mixed port / TUN MTU)
    var showMixedPortDialog by remember { mutableStateOf(false) }
    var showTunMtuDialog by remember { mutableStateOf(false) }

    // Backup import needs an explicit confirmation before overwriting local data
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportBackup(uri)
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    val installedVersion = remember {
        UpdateChecker.installedVersion(context)
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
    ) {
        // iOS Large Title Header (Unified position & typography with ProxiesScreen)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AppStrings.get("settings_title", lang),
                color = LoonTextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (LocalAppFont.current == com.github.mihomo.android.data.AppFont.IOS) (-0.45).sp else 0.sp
            )
        }

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
                header = AppStrings.get("settings_section_network_core", lang)
            ) {
                CupertinoCell(
                    title = AppStrings.get("settings_tun_stack", lang),
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
                    title = AppStrings.get("settings_boot_startup", lang),
                    icon = Icons.Default.PowerSettingsNew,
                    iconBg = IosGreen,
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
                    title = AppStrings.get("settings_lan_share_plain", lang),
                    icon = Icons.Default.WifiTethering,
                    iconBg = IosCyan,
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
                    title = "IPv6",
                    icon = Icons.Default.Router,
                    iconBg = IosMint,
                    trailing = {
                        CupertinoSwitch(
                            checked = ipv6Enabled,
                            onCheckedChange = onIpv6EnabledChanged
                        )
                    },
                    onClick = { onIpv6EnabledChanged(!ipv6Enabled) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_kill_switch", lang),
                    subtitle = AppStrings.get("settings_kill_switch_desc", lang),
                    icon = Icons.Default.Shield,
                    iconBg = IosRed,
                    trailing = {
                        CupertinoSwitch(
                            checked = killSwitchEnabled,
                            onCheckedChange = onKillSwitchChanged
                        )
                    },
                    onClick = { onKillSwitchChanged(!killSwitchEnabled) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_allow_bypass", lang),
                    subtitle = AppStrings.get("settings_allow_bypass_desc", lang),
                    icon = Icons.Default.CallSplit,
                    iconBg = IosTeal,
                    trailing = {
                        CupertinoSwitch(
                            checked = allowBypassEnabled,
                            onCheckedChange = onAllowBypassChanged
                        )
                    },
                    onClick = { onAllowBypassChanged(!allowBypassEnabled) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_mixed_port", lang),
                    value = mixedPort.toString(),
                    icon = Icons.Default.SettingsEthernet,
                    iconBg = IosBlue,
                    showChevron = true,
                    onClick = { showMixedPortDialog = true }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_tun_mtu", lang),
                    value = tunMtu.toString(),
                    icon = Icons.Default.NetworkCheck,
                    iconBg = IosIndigo,
                    showChevron = true,
                    onClick = { showTunMtuDialog = true }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_allow_private_subs", lang),
                    subtitle = AppStrings.get("settings_allow_private_subs_desc", lang),
                    icon = Icons.Default.Lan,
                    iconBg = IosMint,
                    trailing = {
                        CupertinoSwitch(
                            checked = allowPrivateSubs,
                            onCheckedChange = onAllowPrivateSubsChanged
                        )
                    },
                    onClick = { onAllowPrivateSubsChanged(!allowPrivateSubs) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_core_providers", lang),
                    icon = Icons.Default.Dns,
                    iconBg = IosGray,
                    showChevron = true,
                    onClick = onNavigateToCore
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_system_vpn", lang),
                    value = AppStrings.get("always_on", lang),
                    icon = Icons.Default.Security,
                    iconBg = IosOrange,
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
                header = AppStrings.get("settings_section_routing_services", lang)
            ) {
                CupertinoCell(
                    title = AppStrings.get("settings_app_routing", lang),
                    icon = Icons.Default.Apps,
                    iconBg = IosOrange,
                    showChevron = true,
                    onClick = onNavigateToAppRouting
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_dns_section", lang),
                    value = if (fakeIpEnabled) "Fake-IP" else "Redir-Host",
                    icon = Icons.Default.Public,
                    iconBg = IosTeal,
                    showChevron = true,
                    onClick = {
                        onNavigateToDnsSettings()
                    }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_scripts_center", lang),
                    value = AppStrings.get("script_count", lang).replace("{n}", scripts.size.toString()),
                    icon = Icons.Default.Code,
                    iconBg = IosIndigo,
                    showChevron = true,
                    onClick = onNavigateToScripts
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_profiles_subs_plain", lang),
                    icon = Icons.Default.FolderZip,
                    iconBg = IosPurple,
                    showChevron = true,
                    onClick = onNavigateToProfiles
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: 个性化与调试 (Preferences & Debugging)
            CupertinoGroupedSection(
                header = AppStrings.get("settings_section_personalize_debug", lang)
            ) {
                CupertinoCell(
                    title = AppStrings.get("settings_appearance", lang),
                    value = when (themeMode) {
                        ThemeMode.SYSTEM -> AppStrings.get("theme_system", lang)
                        ThemeMode.LIGHT -> AppStrings.get("theme_light_short", lang)
                        ThemeMode.DARK -> AppStrings.get("theme_dark_short", lang)
                    },
                    icon = Icons.Default.Palette,
                    iconBg = IosPink,
                    showChevron = true,
                    onClick = onNavigateToAppearance
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("shortcut_logs", lang),
                    icon = Icons.Default.Terminal,
                    iconBg = IosGray,
                    showChevron = true,
                    onClick = onNavigateToLogs
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_log_level_short", lang),
                    value = currentLogLevel.uppercase(),
                    icon = Icons.Default.Tune,
                    iconBg = IosIndigo,
                    showChevron = true,
                    onClick = { showLogLevelDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3.5: 隐私与安全 (Privacy & Security)
            CupertinoGroupedSection(
                header = AppStrings.get("settings_security_section", lang)
            ) {
                CupertinoCell(
                    title = AppStrings.get("settings_app_lock", lang),
                    subtitle = AppStrings.get("settings_app_lock_desc", lang),
                    icon = Icons.Default.Lock,
                    iconBg = IosBlue,
                    trailing = {
                        CupertinoSwitch(
                            checked = appLockEnabled,
                            onCheckedChange = onAppLockChanged
                        )
                    },
                    onClick = { onAppLockChanged(!appLockEnabled) }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_prevent_screenshots", lang),
                    icon = Icons.Default.VisibilityOff,
                    iconBg = IosPurple,
                    trailing = {
                        CupertinoSwitch(
                            checked = preventScreenshots,
                            onCheckedChange = onPreventScreenshotsChanged
                        )
                    },
                    onClick = { onPreventScreenshotsChanged(!preventScreenshots) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 4: 关于与维护 (About & Maintenance)
            CupertinoGroupedSection(
                header = AppStrings.get("settings_section_maintenance_about", lang)
            ) {
                CupertinoCell(
                    title = AppStrings.get("settings_check_update", lang),
                    value = "v$installedVersion",
                    icon = Icons.Default.SystemUpdate,
                    iconBg = IosGreen,
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
                                tint = LoonChevronTint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateResult = null
                            scope.launch {
                                val result = UpdateChecker.checkForUpdate(context)
                                updateResult = result
                                isCheckingUpdate = false
                                showUpdateDialog = true
                            }
                        }
                    }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_core_version_short", lang),
                    value = coreVersion.ifBlank { "v1.18.x" },
                    icon = Icons.Default.Memory,
                    iconBg = IosGray,
                    showChevron = false
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_reset_core_short", lang),
                    icon = Icons.Default.RestartAlt,
                    iconBg = IosRed,
                    showChevron = true,
                    onClick = { showResetDialog = true }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_backup_export", lang),
                    icon = Icons.Default.FileUpload,
                    iconBg = IosGreen,
                    showChevron = true,
                    onClick = { exportBackupLauncher.launch("nexus-backup.json") }
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_backup_import", lang),
                    icon = Icons.Default.FileDownload,
                    iconBg = IosOrange,
                    showChevron = true,
                    onClick = { importBackupLauncher.launch(arrayOf("application/json")) }
                )
            }
        }
    }

    // TUN Stack Selection BottomSheet
    if (showTunDialog) {
        val stacks = listOf(
            Triple("mixed", "Mixed", AppStrings.get("tun_desc_mixed", lang)),
            Triple("gvisor", "gVisor", AppStrings.get("tun_desc_gvisor", lang)),
            Triple("system", "System", AppStrings.get("tun_desc_system", lang))
        )
        CupertinoModalBottomSheet(
            onDismissRequest = { showTunDialog = false },
            title = AppStrings.get("tun_stack_dialog_title", lang)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
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
            Triple("info", "INFO", AppStrings.get("loglevel_desc_info", lang)),
            Triple("warning", "WARN", AppStrings.get("loglevel_desc_warning", lang)),
            Triple("error", "ERROR", AppStrings.get("loglevel_desc_error", lang)),
            Triple("debug", "DEBUG", AppStrings.get("loglevel_desc_debug", lang))
        )
        CupertinoModalBottomSheet(
            onDismissRequest = { showLogLevelDialog = false },
            title = AppStrings.get("log_detail_level_title", lang)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
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
                                if (ClashCore.isCoreLoaded) {
                                    scope.launch(Dispatchers.IO) {
                                        ClashCore.reloadActiveConfig(context)
                                    }
                                }
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
            title = AppStrings.get("settings_reset_core_short", lang),
            message = AppStrings.get("settings_reset_confirm_long", lang),
            confirmText = AppStrings.get("reset_action", lang),
            dismissText = AppStrings.get("cancel", lang),
            isDestructive = true,
            onConfirm = {
                showResetDialog = false
                scope.launch {
                    if (com.github.mihomo.android.service.MihomoVpnService.vpnState.value.status != com.github.mihomo.android.service.VpnState.Status.STOPPED) {
                        com.github.mihomo.android.service.MihomoVpnService.stop(context)
                    }
                    withContext(Dispatchers.IO) { ClashCore.reset() }
                    CupertinoToast.show(AppStrings.get("core_reset_done", lang))
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
            title = AppStrings.get("settings_check_update", lang),
            onDismissRequest = { showUpdateDialog = false },
            confirmText = if (isNewer) AppStrings.get("action_download", lang) else AppStrings.get("action_ok", lang),
            dismissText = if (isNewer) AppStrings.get("later", lang) else null,
            onConfirm = {
                showUpdateDialog = false
                if (result is UpdateChecker.CheckResult.Success && result.info.isNewer) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, result.info.htmlUrl.toUri())
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
                                        text = AppStrings.get("update_found", lang).replace("{v}", info.tagName),
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
                                // In-app download path: fetch the release apk straight into
                                // the cache and hand it to the package installer.
                                info.apkAsset?.let { asset ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CupertinoButton(
                                            text = AppStrings.get("settings_update_download", lang),
                                            onClick = {
                                                if (!isDownloadingUpdate) {
                                                    isDownloadingUpdate = true
                                                    beginUpdateDownload(context, asset, lang, scope) {
                                                        isDownloadingUpdate = it
                                                    }
                                                }
                                            }
                                        )
                                        if (isDownloadingUpdate) {
                                            CupertinoActivityIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = IosBlue
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = AppStrings.get("update_up_to_date", lang).replace("{v}", installedVersion),
                                    color = LoonTextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        is UpdateChecker.CheckResult.Error -> {
                            Text(
                                text = AppStrings.get("update_check_failed", lang).replace("{msg}", result.message),
                                color = RedDanger,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }
        )
    }

    // Mixed Port Input Dialog (1024..65535, validated locally before persisting)
    if (showMixedPortDialog) {
        var portInput by remember { mutableStateOf(mixedPort.toString()) }
        CupertinoAlertDialog(
            title = AppStrings.get("settings_mixed_port", lang),
            confirmText = AppStrings.get("action_save", lang),
            onConfirm = {
                val parsed = portInput.trim().toIntOrNull()
                if (parsed != null && parsed in 1024..65535) {
                    onMixedPortChanged(parsed)
                    showMixedPortDialog = false
                } else {
                    CupertinoToast.show(
                        localeText(lang, "端口需在 1024 - 65535 之间", "Port must be between 1024 and 65535"),
                        isDestructive = true
                    )
                }
            },
            onDismissRequest = { showMixedPortDialog = false },
            content = {
                CupertinoTextField(
                    value = portInput,
                    onValueChange = { newValue -> portInput = newValue.filter { ch -> ch.isDigit() } },
                    placeholder = "1024 - 65535"
                )
            }
        )
    }

    // TUN MTU Input Dialog (1280..65535)
    if (showTunMtuDialog) {
        var mtuInput by remember { mutableStateOf(tunMtu.toString()) }
        CupertinoAlertDialog(
            title = AppStrings.get("settings_tun_mtu", lang),
            confirmText = AppStrings.get("action_save", lang),
            onConfirm = {
                val parsed = mtuInput.trim().toIntOrNull()
                if (parsed != null && parsed in 1280..65535) {
                    onTunMtuChanged(parsed)
                    showTunMtuDialog = false
                } else {
                    CupertinoToast.show(
                        localeText(lang, "MTU 需在 1280 - 65535 之间", "MTU must be between 1280 and 65535"),
                        isDestructive = true
                    )
                }
            },
            onDismissRequest = { showTunMtuDialog = false },
            content = {
                CupertinoTextField(
                    value = mtuInput,
                    onValueChange = { newValue -> mtuInput = newValue.filter { ch -> ch.isDigit() } },
                    placeholder = "1280 - 65535"
                )
            }
        )
    }

    // Backup Import Confirmation (restore overwrites all local data)
    pendingImportUri?.let { importUri ->
        CupertinoAlertDialog(
            title = AppStrings.get("settings_backup_import", lang),
            message = localeText(
                lang,
                "导入将覆盖当前全部配置、脚本与设置，确定继续吗？",
                "Importing will overwrite all profiles, scripts and settings. Continue?"
            ),
            confirmText = AppStrings.get("confirm", lang),
            isDestructive = true,
            onConfirm = {
                pendingImportUri = null
                onImportBackup(importUri)
            },
            onDismissRequest = { pendingImportUri = null }
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
            ipv6Enabled = false,
            themeMode = ThemeMode.SYSTEM,
            onTunStackChanged = {},
            onBootOnStartupChanged = {},
            onAllowLanChanged = {},
            onNavigateToScripts = {},
            onNavigateToProfiles = {},
            onNavigateToAppRouting = {},
            onNavigateToLogs = {},
            onNavigateToAppearance = {}
        )
    }
}
