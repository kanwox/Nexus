@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.data.UpdateDownloadResult
import com.github.mihomo.android.data.UpdateVerifier
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

/** Streams the release apk into cacheDir/updates/, replacing any previously downloaded one. */
private const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024

/** Copy buffer for the download loop; also the granularity of the streaming hash. */
private const val UPDATE_COPY_BUFFER_BYTES = 64 * 1024

/** Progress callback granularity when the server sends no Content-Length. */
private const val UPDATE_PROGRESS_CHUNK_BYTES = 512L * 1024

private val updateHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .build()
}

private fun downloadCandidateClients(mixedPort: Int, vpnRunning: Boolean): List<OkHttpClient> =
    if (vpnRunning) listOf(UpdateChecker.localProxyClient(mixedPort), updateHttpClient)
    else listOf(updateHttpClient)

/**
 * Fetches a small release asset (SHA256SUMS) fully into memory as text, using the same
 * proxy/direct candidate order as the apk download itself. Returns null when the
 * asset cannot be fetched for any reason — a missing sums file only downgrades the
 * result to "unverified", it must never block the update.
 */
private fun fetchTextAsset(
    asset: UpdateChecker.ReleaseAsset,
    mixedPort: Int,
    vpnRunning: Boolean
): String? {
    val request = Request.Builder().url(asset.browserDownloadUrl).build()
    for (client in downloadCandidateClients(mixedPort, vpnRunning)) {
        val text = runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        }.getOrNull()
        if (text != null) return text
    }
    return null
}

/**
 * Streams the release apk into cacheDir/updates/, verifies it against the release's
 * SHA256SUMS asset when one exists, and reports download progress through [onProgress]
 * (bytes downloaded, total bytes or -1 when the server sends no Content-Length).
 *
 * Verification contract:
 * - sums asset present and the apk has an entry → hash must match, else the file is
 *   deleted and [UpdateDownloadResult.Failure] is returned;
 * - sums asset missing or has no entry for this apk → the download proceeds but the
 *   result is flagged [UpdateDownloadResult.Success.verified] = false, so the UI can
 *   tell the user the artifact was not checked.
 *
 * Content-Length is only used for the pre-download size cap (and as the progress
 * denominator); it is not treated as an integrity signal — only the digest decides.
 */
private suspend fun downloadUpdateApk(
    context: Context,
    asset: UpdateChecker.ReleaseAsset,
    sumsAsset: UpdateChecker.ReleaseAsset?,
    lang: String,
    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
): UpdateDownloadResult = withContext(Dispatchers.IO) {
    val fail = { reason: String ->
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
        UpdateDownloadResult.Failure(reason)
    }

    runCatching {
        val mixedPort = SettingsManager(context).mixedPort
        val vpnRunning = MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING

        // ---- 1. Checksum table: fetched before the apk so a mismatched download can be
        // rejected immediately instead of after 200 MB have been pulled.
        val expected = sumsAsset?.let { sums ->
            fetchTextAsset(sums, mixedPort, vpnRunning)?.let { text ->
                UpdateVerifier.parseSha256Sums(text)[asset.name]
            }
        }

        // ---- 2. Reuse an already-downloaded and verified APK if present in cache (e.g. when
        // returning from the unknown-sources permission screen or retrying install).
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Never derive the destination from the URL: a hostile release asset name could
        // contain path separators. The file is always cache/updates/nexus-update.apk.
        val target = File(dir, "nexus-update.apk")
        if (expected != null && target.isFile) {
            val cachedHash = UpdateVerifier.sha256HexOfFile(target)
            if (cachedHash != null && UpdateVerifier.verifySha256(cachedHash, expected)) {
                return@withContext UpdateDownloadResult.Success(target, verified = true)
            }
        }

        dir.listFiles()?.forEach { it.delete() }
        val request = Request.Builder().url(asset.browserDownloadUrl).build()

        var response: okhttp3.Response? = null
        var lastError: Throwable? = null
        for (client in downloadCandidateClients(mixedPort, vpnRunning)) {
            val attempt = runCatching { client.newCall(request).execute() }
            val resp = attempt.getOrNull()
            if (resp != null && resp.isSuccessful) {
                response = resp
                break
            } else {
                resp?.close()
                lastError = attempt.exceptionOrNull()
            }
        }
        val activeResponse = response ?: run {
            if (lastError != null) throw lastError else error("Download failed")
        }

        activeResponse.use { resp ->
            // okhttp 5 always has a body on a response.
            val body = resp.body
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_UPDATE_APK_BYTES) {
                error(AppStrings.get("update_apk_too_large", lang))
            }
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(UPDATE_COPY_BUFFER_BYTES)
                    var total = 0L
                    // Throttle the UI progress updates: recomposing a percentage on
                    // every 64 KiB chunk is wasted work, one tick per 512 KiB (or on
                    // completion) is smooth enough for a ~100 MB download.
                    var lastReported = 0L
                    while (true) {
                        // Cooperative cancellation: leaving the loop on cancellation
                        // lets the withContext block unwind before the file is
                        // cleaned up by the caller below.
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_UPDATE_APK_BYTES) {
                            error(AppStrings.get("update_apk_too_large", lang))
                        }
                        output.write(buffer, 0, read)
                        if (total - lastReported >= UPDATE_PROGRESS_CHUNK_BYTES) {
                            lastReported = total
                            onProgress(total, declaredLength)
                        }
                    }
                    onProgress(total, declaredLength)
                }
            }
        }

        // ---- 3. Verify the digest (streamed, so the apk is never loaded into memory).
        if (expected != null) {
            val actual = UpdateVerifier.sha256HexOfFile(target)
            if (actual == null || !UpdateVerifier.verifySha256(actual, expected)) {
                return@withContext fail(AppStrings.get("settings_update_verify_failed", lang))
            }
            UpdateDownloadResult.Success(target, verified = true)
        } else {
            // No sums entry for this apk (or no sums asset at all): continue unverified.
            UpdateDownloadResult.Success(target, verified = false)
        }
    }.getOrElse { throwable ->
        if (throwable is CancellationException) {
            // The coroutine was cancelled on purpose (new check started / screen left):
            // remove the half-written apk so no truncated file can ever be mistaken for
            // a complete one, then rethrow — converting cancellation into a Failure
            // would swallow the cancellation and let the scope keep the stale job.
            runCatching { File(context.cacheDir, "updates").deleteRecursively() }
            throw throwable
        }
        fail(AppStrings.get("download_failed_short", lang))
    }
}

/**
 * Pre-install gate: the downloaded archive must be this app's package, not older than
 * what is installed, and signed with the same key. Why each check matters:
 * - package mismatch → the release asset could be any apk (typosquatting the release);
 * - downgrade → a rollback to a vulnerable version;
 * - signature mismatch → an attacker-published apk claiming to be the update. Android's
 *   installer would also reject a signature change, but refusing before handing the
 *   file to the installer keeps the failure local and the error message precise.
 * On any failure the apk is deleted and the reason returned; null means all checks
 * passed.
 */
private fun preInstallCheck(context: Context, apk: File, lang: String): String? {
    val pm = context.packageManager
    // API 28+ keeps signing certs in signingInfo; 26/27 only expose the legacy
    // signatures array, so the flag is chosen per API level.
    val useSigningInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val flags = if (useSigningInfo) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    }
    val archiveInfo = runCatching {
        pm.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()
    if (archiveInfo == null) {
        // Unparsable apk: corrupt download or not an apk at all.
        runCatching { apk.delete() }
        return AppStrings.get("settings_update_verify_failed", lang)
    }
    if (archiveInfo.packageName != context.packageName) {
        runCatching { apk.delete() }
        return AppStrings.get("settings_update_signature_mismatch", lang)
    }
    val installedCode = UpdateChecker.installedVersionCode(context)
    val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        archiveInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION") archiveInfo.versionCode.toLong()
    }
    if (installedCode >= 0 && archiveCode < installedCode) {
        runCatching { apk.delete() }
        return AppStrings.get("settings_update_older_version", lang)
    }
    val signaturesMatch = runCatching {
        if (useSigningInfo) {
            val installedCerts = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
            val incomingCerts = archiveInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                ?: emptyList()
            UpdateVerifier.signaturesMatch(installedCerts, incomingCerts)
        } else {
            @Suppress("DEPRECATION")
            val installedCerts = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() } ?: emptyList()
            @Suppress("DEPRECATION")
            val incomingCerts = archiveInfo.signatures?.map { it.toByteArray() } ?: emptyList()
            UpdateVerifier.signaturesMatch(installedCerts, incomingCerts)
        }
    }.getOrDefault(false)
    if (!signaturesMatch) {
        runCatching { apk.delete() }
        return AppStrings.get("settings_update_signature_mismatch", lang)
    }
    return null
}

/** Fires the package installer for [apk], routing through the unknown-sources gate first. */
private fun installUpdateApk(
    context: Context,
    apk: File,
    lang: String,
    onRequestUnknownSources: ((File) -> Unit)? = null
) {
    // minSdk is 26, so the unknown-sources gate is always present on every supported
    // device (no SDK_INT check needed).
    if (!context.packageManager.canRequestPackageInstalls()) {
        if (onRequestUnknownSources != null) {
            onRequestUnknownSources(apk)
        } else {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri()
                    )
                )
            }
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

/**
 * Downloads the release apk off the main thread, verifies it, then either installs it
 * or reports the failure. The returned [Job] lets the caller cancel an in-flight
 * download (e.g. when the screen is left); the half-written file is removed by the
 * download function's unwind path.
 */
private fun beginUpdateDownload(
    context: Context,
    asset: UpdateChecker.ReleaseAsset,
    sumsAsset: UpdateChecker.ReleaseAsset?,
    lang: String,
    scope: CoroutineScope,
    setDownloading: (Boolean) -> Unit,
    setProgress: (Int?) -> Unit,
    onRequestUnknownSources: (File) -> Unit
): Job = scope.launch {
    CupertinoToast.show(AppStrings.get("settings_update_downloading", lang))
    var lastToastPercent = -1
    val result = try {
        downloadUpdateApk(context, asset, sumsAsset, lang) { bytesRead, totalBytes ->
            // Map the byte counts to a coarse 0-100 percentage for the UI; totalBytes is
            // -1 when the server sends no Content-Length, in which case no percentage is
            // shown (an indeterminate state would need a different UI affordance).
            if (totalBytes > 0) {
                val percent = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                // Same throttling as the downloader: only surface round-number steps.
                if (percent != lastToastPercent) {
                    lastToastPercent = percent
                    setProgress(percent)
                }
            }
        }
    } finally {
        setDownloading(false)
        setProgress(null)
    }
    when (result) {
        is UpdateDownloadResult.Success -> {
            // The pre-install gate runs on the main thread here but touches only
            // metadata, so it stays fast; delete-before-install is handled inside it.
            val failureReason = preInstallCheck(context, result.file, lang)
            if (failureReason != null) {
                CupertinoToast.show(failureReason, isDestructive = true)
            } else if (!result.verified) {
                // Installed without a checksum match available — say so once.
                CupertinoToast.show(AppStrings.get("settings_update_not_verified", lang), durationMs = 3500)
                installUpdateApk(context, result.file, lang, onRequestUnknownSources)
            } else {
                CupertinoToast.show(AppStrings.get("settings_update_ready", lang))
                installUpdateApk(context, result.file, lang, onRequestUnknownSources)
            }
        }
        is UpdateDownloadResult.Failure -> {
            CupertinoToast.show(
                AppStrings.get("settings_update_install_failed", lang).replace("{reason}", result.reason),
                isDestructive = true
            )
        }
    }
}

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
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    // 0-100 while the update apk is streaming, null otherwise. An indeterminate server
    // (no Content-Length) keeps it null for the whole download.
    var updateDownloadProgress by remember { mutableStateOf<Int?>(null) }
    var pendingInstallApk by remember { mutableStateOf<File?>(null) }

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingInstallApk
        if (apk != null && apk.isFile && context.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            installUpdateApk(context, apk, lang)
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
                                if (discoveredUpdate != null && updateResult == null) {
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
                            isDownloadingUpdate = true
                            updateDownloadProgress = null
                            beginUpdateDownload(
                                context = context,
                                asset = asset,
                                sumsAsset = result.info.sumsAsset,
                                lang = lang,
                                scope = scope,
                                setDownloading = { isDownloadingUpdate = it },
                                setProgress = { updateDownloadProgress = it },
                                onRequestUnknownSources = { apk ->
                                    pendingInstallApk = apk
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
