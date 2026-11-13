@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.UpdateDownloadManager
import com.github.mihomo.android.ui.UpdateDownloadUiState
import com.github.mihomo.android.ui.canReuseReadyDownload
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** About & maintenance second-level page: update check, backup, log level, core reset. */
@Composable
fun AboutScreen(
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onBack: () -> Unit,
    onCoreReset: () -> Unit = {}
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    var showLogLevelSheet by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var currentLogLevel by remember { mutableStateOf(settingsManager.logLevel) }
    var autoCheckUpdate by remember { mutableStateOf(settingsManager.autoCheckUpdate) }

    val availableUpdate by UpdateChecker.availableUpdate.collectAsStateWithLifecycle()
    val downloadState by UpdateDownloadManager.state.collectAsStateWithLifecycle()
    val isDownloadingUpdate = downloadState is UpdateDownloadUiState.Downloading
    val updateDownloadProgress = (downloadState as? UpdateDownloadUiState.Downloading)?.progress
    val readyUpdate = (downloadState as? UpdateDownloadUiState.ReadyToInstall)
        ?.takeIf { it.file.isFile }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = UpdateDownloadManager.takePendingUnknownSourcesApkIfInstallable(context)
        if (apk != null) {
            UpdateDownloadManager.installApk(context, apk, lang)
        }
    }

    val requestInstall = { apk: File ->
        UpdateDownloadManager.installApk(
            context = context,
            apk = apk,
            lang = lang,
            onRequestUnknownSources = { pendingApk ->
                UpdateDownloadManager.recordPendingUnknownSourcesApk(pendingApk)
                runCatching {
                    unknownSourcesLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri()
                        )
                    )
                }
            }
        )
    }

    LaunchedEffect(downloadState) {
        val ready = downloadState as? UpdateDownloadUiState.ReadyToInstall ?: return@LaunchedEffect
        if (!ready.file.isFile) {
            UpdateDownloadManager.clearFinishedState()
            return@LaunchedEffect
        }
        if (ready.promptInstall) {
            UpdateDownloadManager.consumeInstallPrompt()
            requestInstall(ready.file)
        }
    }

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

    val installedVersion = remember { UpdateChecker.installedVersion(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("settings_about_page", lang),
            showBackButton = true,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CupertinoGroupedSection {
                    val discoveredUpdate = availableUpdate
                    CupertinoCell(
                        title = AppStrings.get("settings_check_update", lang),
                        value = if (discoveredUpdate != null) {
                            AppStrings.get("settings_update_available_short", lang)
                                .replace("{v}", discoveredUpdate.tagName)
                        } else {
                            "v$installedVersion"
                        },
                        showChevron = false,
                        trailing = {
                            when {
                                isCheckingUpdate -> {
                                    CupertinoActivityIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = IosBlue
                                    )
                                }
                                // While the apk streams, the trailing slot doubles as
                                // the progress readout: the user sees the download is
                                // alive without a separate blocking dialog.
                                isDownloadingUpdate -> {
                                    Text(
                                        text = updateDownloadProgress?.let { "$it%" }
                                            ?: AppStrings.get("settings_update_downloading", lang),
                                        fontSize = LoonTypeScale.footnote,
                                        color = LoonTextSecondary
                                    )
                                }
                                readyUpdate != null &&
                                    canReuseReadyDownload(downloadState, discoveredUpdate?.apkName) -> {
                                    Text(
                                        text = AppStrings.get("settings_update_ready", lang),
                                        fontSize = LoonTypeScale.footnote,
                                        color = IosBlue
                                    )
                                }
                                else -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (discoveredUpdate != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(IosBlue)
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = LoonChevronTint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            if (!isCheckingUpdate && !isDownloadingUpdate) {
                                if (readyUpdate != null && canReuseReadyDownload(downloadState, discoveredUpdate?.apkName)) {
                                    requestInstall(readyUpdate.file)
                                } else if (discoveredUpdate != null && updateResult == null) {
                                    updateResult = UpdateChecker.CheckResult.Success(discoveredUpdate)
                                    showUpdateDialog = true
                                } else {
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
                        }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_auto_check_update", lang),
                        showChevron = false,
                        trailing = {
                            CupertinoSwitch(
                                checked = autoCheckUpdate,
                                onCheckedChange = { enabled ->
                                    autoCheckUpdate = enabled
                                    settingsManager.autoCheckUpdate = enabled
                                    if (enabled) {
                                        scope.launch { UpdateChecker.checkIfDue(context) }
                                    }
                                }
                            )
                        }
                    )
                }
            }

            item {
                CupertinoGroupedSection {
                    CupertinoCell(
                        title = AppStrings.get("settings_backup_export", lang),
                        showChevron = true,
                        onClick = { exportBackupLauncher.launch("nexus-backup.json") }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_backup_import", lang),
                        showChevron = true,
                        onClick = { importBackupLauncher.launch(arrayOf("application/json")) }
                    )
                }
            }

            item {
                CupertinoGroupedSection {
                    CupertinoCell(
                        title = AppStrings.get("settings_log_level_short", lang),
                        value = currentLogLevel.uppercase(),
                        showChevron = true,
                        onClick = { showLogLevelSheet = true }
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("settings_reset_core_short", lang),
                        showChevron = true,
                        onClick = { showResetDialog = true }
                    )
                }
            }
        }
    }

    if (showLogLevelSheet) {
        val levels = listOf(
            Triple("info", "INFO", AppStrings.get("loglevel_desc_info", lang)),
            Triple("warning", "WARN", AppStrings.get("loglevel_desc_warning", lang)),
            Triple("error", "ERROR", AppStrings.get("loglevel_desc_error", lang)),
            Triple("debug", "DEBUG", AppStrings.get("loglevel_desc_debug", lang))
        )
        CupertinoModalBottomSheet(
            onDismissRequest = { showLogLevelSheet = false },
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
                                val prevLevel = currentLogLevel
                                currentLogLevel = lvlKey
                                settingsManager.logLevel = lvlKey
                                showLogLevelSheet = false
                                if (ClashCore.isCoreLoaded) {
                                    scope.launch(Dispatchers.IO) {
                                        val ok = ClashCore.reloadActiveConfig(context)
                                        if (!ok) {
                                            withContext(Dispatchers.Main) {
                                                currentLogLevel = prevLevel
                                                settingsManager.logLevel = prevLevel
                                                CupertinoToast.show(
                                                    AppStrings.get("core_reload_failed", lang),
                                                    isDestructive = true
                                                )
                                            }
                                        }
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
                                fontSize = LoonTypeScale.body,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) IosBlue else LoonTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = LoonTypeScale.footnote,
                                color = LoonTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    if (MihomoVpnService.vpnState.value.status != VpnState.Status.STOPPED) {
                        MihomoVpnService.stop(context)
                        withTimeoutOrNull(3_000L) {
                            MihomoVpnService.vpnState.first { it.status == VpnState.Status.STOPPED }
                        }
                    }
                    withContext(Dispatchers.IO) { ClashCore.reset() }
                    onCoreReset()
                    CupertinoToast.show(AppStrings.get("core_reset_done", lang))
                }
            },
            onDismissRequest = { showResetDialog = false }
        )
    }

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
                    val asset = result.info.apkAsset
                    when {
                        asset != null && !isDownloadingUpdate -> {
                            // Prefer the in-app path so the user does not have to find the right
                            // asset on the release page; fall back to the browser only when the
                            // release ships no apk.
                            UpdateDownloadManager.startDownload(
                                context = context,
                                asset = asset,
                                sumsAsset = result.info.sumsAsset,
                                lang = lang
                            )
                        }
                        asset == null -> {
                            val browserIntent = Intent(Intent.ACTION_VIEW, result.info.htmlUrl.toUri())
                            runCatching { context.startActivity(browserIntent) }
                        }
                        // A download is already in flight: ignore the extra tap.
                    }
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
                                        color = LoonSuccessText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = LoonTypeScale.body
                                    )
                                }
                                // Tell the user exactly which artifact was picked for
                                // this device, so a wrong-ABI selection is visible
                                // before the download starts. The asset name itself is
                                // language-neutral — no string key needed.
                                info.apkName?.let { name ->
                                    Text(
                                        text = name,
                                        color = LoonTextSecondary,
                                        fontSize = LoonTypeScale.footnote
                                    )
                                }
                                if (info.releaseNotes.isNotBlank()) {
                                    Surface(
                                        color = LoonBg,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = info.releaseNotes.trim(),
                                            color = LoonTextSecondary,
                                            fontSize = LoonTypeScale.footnote,
                                            lineHeight = LoonLineHeight.footnote,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = AppStrings.get("update_up_to_date", lang).replace("{v}", installedVersion),
                                    color = LoonTextPrimary,
                                    fontSize = LoonTypeScale.subhead
                                )
                            }
                        }
                        is UpdateChecker.CheckResult.Error -> {
                            Text(
                                text = AppStrings.get("update_check_failed", lang).replace("{msg}", result.message),
                                color = LoonDangerText,
                                fontSize = LoonTypeScale.calloutLarge
                            )
                        }
                    }
                }
            }
        )
    }

    pendingImportUri?.let { importUri ->
        CupertinoAlertDialog(
            title = AppStrings.get("settings_backup_import", lang),
            message = AppStrings.get("backup_import_confirm", lang),
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
