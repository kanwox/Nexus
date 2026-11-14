@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.UpdateDownloadManager
import com.github.mihomo.android.ui.UpdateDownloadUiState
import com.github.mihomo.android.ui.canReuseReadyDownload
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSingleSelectOption
import com.github.mihomo.android.ui.components.CupertinoSingleSelectSheet
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonChevronTint
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

@Immutable
private data class AboutUpdateUiState(
    val installedVersion: String,
    val discoveredUpdate: UpdateChecker.UpdateInfo?,
    val isCheckingUpdate: Boolean,
    val isDownloadingUpdate: Boolean,
    val updateDownloadProgress: Int?,
    val canInstallReady: Boolean,
    val autoCheckUpdate: Boolean,
    val currentLogLevel: String
)

private class AboutScreenActions(
    val onCheckUpdateClick: () -> Unit,
    val onAutoCheckToggle: (Boolean) -> Unit,
    val onExportClick: () -> Unit,
    val onImportClick: () -> Unit,
    val onLogLevelClick: () -> Unit,
    val onResetClick: () -> Unit
)

@Immutable
private data class AboutOverlayState(
    val showLogLevelSheet: Boolean,
    val currentLogLevel: String,
    val showResetDialog: Boolean,
    val updateResult: UpdateChecker.CheckResult?,
    val installedVersion: String,
    val isDownloadingUpdate: Boolean,
    val pendingImportUri: Uri?
)

private class AboutOverlayActions(
    val onSelectLogLevel: (String) -> Unit,
    val onDismissLogLevel: () -> Unit,
    val onConfirmReset: () -> Unit,
    val onDismissReset: () -> Unit,
    val onDismissUpdate: () -> Unit,
    val onConfirmImport: (Uri) -> Unit,
    val onDismissImport: () -> Unit
)

private class AboutScreenLocalState(initialLogLevel: String, initialAutoCheck: Boolean) {
    var showLogLevelSheet by mutableStateOf(false)
    var showResetDialog by mutableStateOf(false)
    var currentLogLevel by mutableStateOf(initialLogLevel)
    var autoCheckUpdate by mutableStateOf(initialAutoCheck)
    var isCheckingUpdate by mutableStateOf(false)
    var updateResult by mutableStateOf<UpdateChecker.CheckResult?>(null)
    var showUpdateDialog by mutableStateOf(false)
    var pendingImportUri by mutableStateOf<Uri?>(null)
}

private class AboutUpdateCheckParams(
    val context: Context,
    val lang: String,
    val scope: CoroutineScope,
    val availableUpdate: UpdateChecker.UpdateInfo?,
    val readyUpdate: UpdateDownloadUiState.ReadyToInstall?,
    val downloadState: UpdateDownloadUiState,
    val unknownSourcesLauncher: ActivityResultLauncher<Intent>
)

@Composable
private fun AboutUpdateDownloadEffect(
    context: Context,
    lang: String,
    downloadState: UpdateDownloadUiState,
    unknownSourcesLauncher: ActivityResultLauncher<Intent>
) {
    LaunchedEffect(downloadState) {
        val ready = downloadState as? UpdateDownloadUiState.ReadyToInstall ?: return@LaunchedEffect
        if (!ready.file.isFile) {
            UpdateDownloadManager.clearFinishedState()
        } else if (ready.promptInstall) {
            UpdateDownloadManager.consumeInstallPrompt()
            launchApkInstall(context, ready.file, lang, unknownSourcesLauncher)
        }
    }
}

private fun handleAboutCheckUpdateClick(
    state: AboutScreenLocalState,
    params: AboutUpdateCheckParams
) {
    val cachedUpdate = params.availableUpdate
    when {
        state.isCheckingUpdate || params.downloadState is UpdateDownloadUiState.Downloading -> Unit
        params.readyUpdate != null && canReuseReadyDownload(params.downloadState, cachedUpdate?.apkName) ->
            launchApkInstall(params.context, params.readyUpdate.file, params.lang, params.unknownSourcesLauncher)
        cachedUpdate != null && state.updateResult == null -> {
            state.updateResult = UpdateChecker.CheckResult.Success(cachedUpdate)
            state.showUpdateDialog = true
        }
        else -> {
            state.isCheckingUpdate = true
            state.updateResult = null
            params.scope.launch {
                state.updateResult = UpdateChecker.checkForUpdate(params.context)
                state.isCheckingUpdate = false
                state.showUpdateDialog = true
            }
        }
    }
}

private fun buildAboutOverlayActions(
    context: Context,
    lang: String,
    scope: CoroutineScope,
    settingsManager: SettingsManager,
    state: AboutScreenLocalState,
    onCoreReset: () -> Unit,
    onImportBackup: (Uri) -> Unit
): AboutOverlayActions = AboutOverlayActions(
    onSelectLogLevel = { level ->
        val prev = state.currentLogLevel
        state.currentLogLevel = level
        settingsManager.logLevel = level
        state.showLogLevelSheet = false
        scope.applyLogLevel(context, settingsManager, prev, lang) { restored ->
            state.currentLogLevel = restored
        }
    },
    onDismissLogLevel = { state.showLogLevelSheet = false },
    onConfirmReset = {
        state.showResetDialog = false
        scope.resetCoreSafely(context, lang, onCoreReset)
    },
    onDismissReset = { state.showResetDialog = false },
    onDismissUpdate = { state.showUpdateDialog = false },
    onConfirmImport = { uri ->
        state.pendingImportUri = null
        onImportBackup(uri)
    },
    onDismissImport = { state.pendingImportUri = null }
)

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
    val state = remember {
        AboutScreenLocalState(settingsManager.logLevel, settingsManager.autoCheckUpdate)
    }

    val availableUpdate by UpdateChecker.availableUpdate.collectAsStateWithLifecycle()
    val downloadState by UpdateDownloadManager.state.collectAsStateWithLifecycle()
    val isDownloadingUpdate = downloadState is UpdateDownloadUiState.Downloading
    val readyUpdate = (downloadState as? UpdateDownloadUiState.ReadyToInstall)
        ?.takeIf { item -> item.file.isFile }
    val installedVersion = remember { UpdateChecker.installedVersion(context) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = UpdateDownloadManager.takePendingUnknownSourcesApkIfInstallable(context)
        if (apk != null) UpdateDownloadManager.installApk(context, apk, lang)
    }
    AboutUpdateDownloadEffect(context, lang, downloadState, unknownSourcesLauncher)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportBackup(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) state.pendingImportUri = uri }

    val checkParams = AboutUpdateCheckParams(
        context, lang, scope, availableUpdate, readyUpdate, downloadState, unknownSourcesLauncher
    )
    AboutScreenLayout(
        lang = lang,
        updateUiState = AboutUpdateUiState(
            installedVersion = installedVersion,
            discoveredUpdate = availableUpdate,
            isCheckingUpdate = state.isCheckingUpdate,
            isDownloadingUpdate = isDownloadingUpdate,
            updateDownloadProgress = (downloadState as? UpdateDownloadUiState.Downloading)?.progress,
            canInstallReady = readyUpdate != null && canReuseReadyDownload(downloadState, availableUpdate?.apkName),
            autoCheckUpdate = state.autoCheckUpdate,
            currentLogLevel = state.currentLogLevel
        ),
        actions = AboutScreenActions(
            onCheckUpdateClick = { handleAboutCheckUpdateClick(state, checkParams) },
            onAutoCheckToggle = { enabled ->
                state.autoCheckUpdate = enabled
                settingsManager.autoCheckUpdate = enabled
                if (enabled) scope.launch { UpdateChecker.checkIfDue(context) }
            },
            onExportClick = { exportLauncher.launch("nexus-backup.json") },
            onImportClick = { importLauncher.launch(arrayOf("application/json")) },
            onLogLevelClick = { state.showLogLevelSheet = true },
            onResetClick = { state.showResetDialog = true }
        ),
        onBack = onBack
    )

    AboutScreenOverlays(
        lang = lang,
        state = AboutOverlayState(
            showLogLevelSheet = state.showLogLevelSheet,
            currentLogLevel = state.currentLogLevel,
            showResetDialog = state.showResetDialog,
            updateResult = if (state.showUpdateDialog) state.updateResult else null,
            installedVersion = installedVersion,
            isDownloadingUpdate = isDownloadingUpdate,
            pendingImportUri = state.pendingImportUri
        ),
        actions = buildAboutOverlayActions(
            context, lang, scope, settingsManager, state, onCoreReset, onImportBackup
        )
    )
}

@Composable
private fun AboutScreenLayout(
    lang: String,
    updateUiState: AboutUpdateUiState,
    actions: AboutScreenActions,
    onBack: () -> Unit
) {
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

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AboutScreenList(
                lang = lang,
                state = updateUiState,
                actions = actions
            )
        }
    }
}

@Composable
private fun AboutScreenList(
    lang: String,
    state: AboutUpdateUiState,
    actions: AboutScreenActions
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CupertinoGroupedSection {
                AboutUpdateCell(
                    lang = lang,
                    state = state,
                    onClick = actions.onCheckUpdateClick
                )
                CupertinoDivider()
                CupertinoSwitchCell(
                    title = AppStrings.get("settings_auto_check_update", lang),
                    checked = state.autoCheckUpdate,
                    onCheckedChange = actions.onAutoCheckToggle
                )
            }
        }
        item {
            CupertinoGroupedSection {
                CupertinoCell(
                    title = AppStrings.get("settings_backup_export", lang),
                    showChevron = true,
                    onClick = actions.onExportClick
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_backup_import", lang),
                    showChevron = true,
                    onClick = actions.onImportClick
                )
            }
        }
        item {
            CupertinoGroupedSection {
                CupertinoCell(
                    title = AppStrings.get("settings_log_level_short", lang),
                    value = state.currentLogLevel.uppercase(Locale.ROOT),
                    showChevron = true,
                    onClick = actions.onLogLevelClick
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_reset_core_short", lang),
                    showChevron = true,
                    onClick = actions.onResetClick
                )
            }
        }
    }
}

@Composable
private fun AboutUpdateCell(
    lang: String,
    state: AboutUpdateUiState,
    onClick: () -> Unit
) {
    val discovered = state.discoveredUpdate
    val valueText = if (discovered != null) {
        AppStrings.get("settings_update_available_short", lang).replace("{v}", discovered.tagName)
    } else {
        "v${state.installedVersion}"
    }
    CupertinoCell(
        title = AppStrings.get("settings_check_update", lang),
        value = valueText,
        showChevron = false,
        trailing = {
            when {
                state.isCheckingUpdate -> CupertinoActivityIndicator(
                    modifier = Modifier.size(16.dp),
                    color = IosBlue
                )
                // While the apk streams, the trailing slot doubles as the progress readout so the
                // user sees the download is alive without a blocking dialog.
                state.isDownloadingUpdate -> Text(
                    text = state.updateDownloadProgress?.let { percent -> "$percent%" }
                        ?: AppStrings.get("settings_update_downloading", lang),
                    style = LoonTextStyles.subtitle(),
                    color = LoonTextSecondary
                )
                state.canInstallReady -> Text(
                    text = AppStrings.get("settings_update_ready", lang),
                    style = LoonTextStyles.subtitle(),
                    color = LoonInfoText
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (discovered != null) {
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
        },
        onClick = onClick
    )
}

@Composable
private fun AboutScreenOverlays(
    lang: String,
    state: AboutOverlayState,
    actions: AboutOverlayActions
) {
    if (state.showLogLevelSheet) {
        val levels = listOf(
            CupertinoSingleSelectOption("info", "INFO", AppStrings.get("loglevel_desc_info", lang)),
            CupertinoSingleSelectOption("warning", "WARN", AppStrings.get("loglevel_desc_warning", lang)),
            CupertinoSingleSelectOption("error", "ERROR", AppStrings.get("loglevel_desc_error", lang)),
            CupertinoSingleSelectOption("debug", "DEBUG", AppStrings.get("loglevel_desc_debug", lang))
        )
        CupertinoSingleSelectSheet(
            title = AppStrings.get("log_detail_level_title", lang),
            options = levels,
            selectedValue = state.currentLogLevel.lowercase(Locale.ROOT),
            onSelect = actions.onSelectLogLevel,
            onDismissRequest = actions.onDismissLogLevel
        )
    }

    if (state.showResetDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("settings_reset_core_short", lang),
            message = AppStrings.get("settings_reset_confirm_long", lang),
            confirmText = AppStrings.get("reset_action", lang),
            dismissText = AppStrings.get("cancel", lang),
            isDestructive = true,
            onConfirm = actions.onConfirmReset,
            onDismissRequest = actions.onDismissReset
        )
    }

    if (state.updateResult != null) {
        AboutUpdateDialog(
            result = state.updateResult,
            lang = lang,
            installedVersion = state.installedVersion,
            isDownloadingUpdate = state.isDownloadingUpdate,
            onDismiss = actions.onDismissUpdate
        )
    }

    if (state.pendingImportUri != null) {
        CupertinoAlertDialog(
            title = AppStrings.get("settings_backup_import", lang),
            message = AppStrings.get("backup_import_confirm", lang),
            confirmText = AppStrings.get("confirm", lang),
            isDestructive = true,
            onConfirm = { actions.onConfirmImport(state.pendingImportUri) },
            onDismissRequest = actions.onDismissImport
        )
    }
}

@Composable
private fun AboutUpdateDialog(
    result: UpdateChecker.CheckResult,
    lang: String,
    installedVersion: String,
    isDownloadingUpdate: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isNewer = result is UpdateChecker.CheckResult.Success && result.info.isNewer
    CupertinoAlertDialog(
        title = AppStrings.get("settings_check_update", lang),
        onDismissRequest = onDismiss,
        confirmText = if (isNewer) AppStrings.get("action_download", lang) else AppStrings.get("action_ok", lang),
        dismissText = if (isNewer) AppStrings.get("later", lang) else null,
        onConfirm = {
            onDismiss()
            if (result is UpdateChecker.CheckResult.Success && result.info.isNewer) {
                val asset = result.info.apkAsset
                when {
                    asset != null && !isDownloadingUpdate -> UpdateDownloadManager.startDownload(
                        context = context,
                        asset = asset,
                        sumsAsset = result.info.sumsAsset,
                        lang = lang
                    )
                    asset == null -> runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, result.info.htmlUrl.toUri()))
                    }
                }
            }
        },
        onDismiss = onDismiss,
        content = {
            AboutUpdateDialogContent(
                result = result,
                lang = lang,
                installedVersion = installedVersion
            )
        }
    )
}

@Composable
private fun AboutUpdateDialogContent(
    result: UpdateChecker.CheckResult,
    lang: String,
    installedVersion: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                            style = LoonTextStyles.body().copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    info.apkName?.let { name ->
                        Text(
                            text = name,
                            color = LoonTextSecondary,
                            style = LoonTextStyles.subtitle()
                        )
                    }
                    if (info.releaseNotes.isNotBlank()) {
                        Surface(
                            color = LoonBg,
                            shape = RoundedCornerShape(LoonRadii.control),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = info.releaseNotes.trim(),
                                color = LoonTextSecondary,
                                style = LoonTextStyles.subtitle(),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = AppStrings.get("update_up_to_date", lang).replace("{v}", installedVersion),
                        color = LoonTextPrimary,
                        style = LoonTextStyles.value()
                    )
                }
            }
            is UpdateChecker.CheckResult.Error -> Text(
                text = AppStrings.get("update_check_failed", lang).replace("{msg}", result.message),
                color = LoonDangerText,
                style = LoonTextStyles.calloutLarge()
            )
        }
    }
}

private fun launchApkInstall(
    context: Context,
    apk: File,
    lang: String,
    unknownSourcesLauncher: ActivityResultLauncher<Intent>
) {
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

private fun CoroutineScope.applyLogLevel(
    context: Context,
    settingsManager: SettingsManager,
    prevLevel: String,
    lang: String,
    onRevert: (String) -> Unit
) {
    if (!ClashCore.isCoreLoaded) return
    launch(Dispatchers.IO) {
        val ok = ClashCore.reloadActiveConfig(context)
        if (!ok) {
            withContext(Dispatchers.Main) {
                onRevert(prevLevel)
                settingsManager.logLevel = prevLevel
                CupertinoToast.show(AppStrings.get("core_reload_failed", lang), isDestructive = true)
            }
        }
    }
}

private fun CoroutineScope.resetCoreSafely(
    context: Context,
    lang: String,
    onCoreReset: () -> Unit
) {
    launch {
        if (MihomoVpnService.vpnState.value.status != VpnState.Status.STOPPED) {
            MihomoVpnService.stop(context)
            withTimeoutOrNull(3_000L) {
                MihomoVpnService.vpnState.first { state -> state.status == VpnState.Status.STOPPED }
            }
        }
        withContext(Dispatchers.IO) { ClashCore.reset() }
        onCoreReset()
        CupertinoToast.show(AppStrings.get("core_reset_done", lang))
    }
}
