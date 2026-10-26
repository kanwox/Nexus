@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

/** Streams the release apk into cacheDir/updates/, replacing any previously downloaded one. */
private const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024

private val updateHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .build()
}

private suspend fun downloadUpdateApk(context: Context, asset: UpdateChecker.ReleaseAsset): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            // Never derive the destination from the URL: a hostile release asset name could
            // contain path separators. The file is always cache/updates/nexus-update.apk.
            val target = File(dir, "nexus-update.apk")
            val request = Request.Builder().url(asset.browserDownloadUrl).build()
            val mixedPort = SettingsManager(context).mixedPort

            // Try direct first, then fall back to local mixed proxy if direct connection fails
            val call = runCatching { updateHttpClient.newCall(request).execute() }.getOrNull()
            val response = if (call != null && call.isSuccessful) {
                call
            } else {
                call?.close()
                UpdateChecker.localProxyClient(mixedPort).newCall(request).execute()
            }

            response.use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Empty response body")
                if (body.contentLength() > MAX_UPDATE_APK_BYTES) {
                    error(AppStrings.get("update_apk_too_large", SettingsManager(context).appLanguage))
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_UPDATE_APK_BYTES) {
                                error(AppStrings.get("update_apk_too_large", SettingsManager(context).appLanguage))
                            }
                            output.write(buffer, 0, read)
                        }
                    }
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
            AppStrings.get("unknown_sources_required", lang),
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
                    .replace("{reason}", AppStrings.get("download_failed_short", lang)),
                isDestructive = true
            )
        } else {
            CupertinoToast.show(AppStrings.get("settings_update_ready", lang))
            installUpdateApk(context, apk, lang)
        }
    }
}

/** About & maintenance second-level page: update check, backup, log level, core reset. */
@Composable
fun AboutScreen(
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    var showLogLevelSheet by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var currentLogLevel by remember { mutableStateOf(settingsManager.logLevel) }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }

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
                    CupertinoCell(
                        title = AppStrings.get("settings_check_update", lang),
                        value = "v$installedVersion",
                        showChevron = false,
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
                                currentLogLevel = lvlKey
                                settingsManager.logLevel = lvlKey
                                showLogLevelSheet = false
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
                    }
                    withContext(Dispatchers.IO) { ClashCore.reset() }
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
                            isDownloadingUpdate = true
                            beginUpdateDownload(context, asset, lang, scope) { isDownloadingUpdate = it }
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
                                if (info.releaseNotes.isNotBlank()) {
                                    Surface(
                                        color = LoonBg,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = info.releaseNotes.take(300),
                                            color = LoonTextSecondary,
                                            fontSize = LoonTypeScale.footnote,
                                            lineHeight = 16.sp,
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
