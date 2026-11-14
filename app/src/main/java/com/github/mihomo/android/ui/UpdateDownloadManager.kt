@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.data.UpdateDownloadResult
import com.github.mihomo.android.data.UpdateVerifier
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024
private const val UPDATE_COPY_BUFFER_BYTES = 64 * 1024
private const val UPDATE_PROGRESS_CHUNK_BYTES = 512L * 1024
private const val READY_TOAST_DURATION_MS = 2000L
private const val UNVERIFIED_TOAST_DURATION_MS = 3500L

/** Application-scoped UI state for the in-app APK update downloader. */
sealed interface UpdateDownloadUiState {
    data object Idle : UpdateDownloadUiState

    data class Downloading(
        val assetName: String,
        val progress: Int? = null
    ) : UpdateDownloadUiState

    data class ReadyToInstall(
        val assetName: String,
        val file: File,
        val verified: Boolean,
        val promptInstall: Boolean = false
    ) : UpdateDownloadUiState

    data class Failed(
        val assetName: String,
        val reason: String
    ) : UpdateDownloadUiState
}

internal fun computeUpdateProgressPercent(bytesRead: Long, totalBytes: Long): Int? {
    if (totalBytes <= 0L) return null
    return ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

internal fun canReuseReadyDownload(
    state: UpdateDownloadUiState,
    requestedAssetName: String?
): Boolean =
    state is UpdateDownloadUiState.ReadyToInstall &&
        state.file.isFile &&
        (requestedAssetName == null || state.assetName == requestedAssetName)

/**
 * Owns APK update download, checksum verification, and pre-install verification outside
 * any screen's composition scope. Leaving AboutScreen does not cancel an active download,
 * and every [StateFlow] update happens on the main thread.
 */
object UpdateDownloadManager {

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private val _state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
    val state: StateFlow<UpdateDownloadUiState> = _state.asStateFlow()

    private val activeToken = AtomicLong(0L)
    private var downloadJob: Job? = null
    private var pendingUnknownSourcesApk: File? = null

    private val updateHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private fun downloadCandidateClients(mixedPort: Int, vpnRunning: Boolean): List<OkHttpClient> =
        if (vpnRunning) listOf(UpdateChecker.localProxyClient(mixedPort), updateHttpClient)
        else listOf(updateHttpClient)

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

    /** Starts an application-scoped APK download if one is not already active. */
    fun startDownload(
        context: Context,
        asset: UpdateChecker.ReleaseAsset,
        sumsAsset: UpdateChecker.ReleaseAsset?,
        lang: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        val current = _state.value
        if (current is UpdateDownloadUiState.Downloading && downloadJob?.isActive == true) {
            return
        }
        val appContext = context.applicationContext
        val token = activeToken.incrementAndGet()
        downloadJob?.cancel()
        downloadJob = scope.launch {
            pendingUnknownSourcesApk = null
            _state.value = UpdateDownloadUiState.Downloading(assetName = asset.name, progress = null)
            CupertinoToast.show(AppStrings.get("settings_update_downloading", lang))
            var lastPercent = -1
            val result = downloadUpdateApk(
                context = appContext,
                asset = asset,
                sumsAsset = sumsAsset,
                lang = lang,
                ioDispatcher = ioDispatcher
            ) { bytesRead, totalBytes ->
                val percent = computeUpdateProgressPercent(bytesRead, totalBytes)
                if (percent != null && percent != lastPercent) {
                    lastPercent = percent
                    scope.launch {
                        if (activeToken.get() != token) return@launch
                        val cur = _state.value
                        if (cur is UpdateDownloadUiState.Downloading && cur.assetName == asset.name) {
                            _state.value = cur.copy(progress = percent)
                        }
                    }
                }
            }
            if (activeToken.get() != token) return@launch
            when (result) {
                is UpdateDownloadResult.Success -> {
                    val failureReason = withContext(ioDispatcher) {
                        preInstallCheck(appContext, result.file, lang)
                    }
                    if (activeToken.get() != token) return@launch
                    if (failureReason != null) {
                        _state.value = UpdateDownloadUiState.Failed(asset.name, failureReason)
                        CupertinoToast.show(failureReason, isDestructive = true)
                    } else {
                        val readyMessage = if (result.verified) {
                            AppStrings.get("settings_update_ready", lang)
                        } else {
                            AppStrings.get("settings_update_not_verified", lang)
                        }
                        val toastDuration = if (result.verified) {
                            READY_TOAST_DURATION_MS
                        } else {
                            UNVERIFIED_TOAST_DURATION_MS
                        }
                        _state.value = UpdateDownloadUiState.ReadyToInstall(
                            assetName = asset.name,
                            file = result.file,
                            verified = result.verified,
                            promptInstall = true
                        )
                        CupertinoToast.show(readyMessage, durationMs = toastDuration)
                    }
                }
                is UpdateDownloadResult.Failure -> {
                    _state.value = UpdateDownloadUiState.Failed(asset.name, result.reason)
                    CupertinoToast.show(
                        AppStrings.get("settings_update_install_failed", lang)
                            .replace("{reason}", result.reason),
                        isDestructive = true
                    )
                }
            }
        }
    }

    /** Marks the automatic one-shot install prompt on a finished download as handled. */
    fun consumeInstallPrompt() {
        val current = _state.value
        if (current is UpdateDownloadUiState.ReadyToInstall && current.promptInstall) {
            _state.value = current.copy(promptInstall = false)
        }
    }

    /** Clears a non-running terminal state (e.g. if the cached APK was removed externally). */
    fun clearFinishedState() {
        val current = _state.value
        if (current !is UpdateDownloadUiState.Downloading) {
            pendingUnknownSourcesApk = null
            _state.value = UpdateDownloadUiState.Idle
        }
    }

    /** Remembers the APK awaiting user authorization for unknown-source installs. */
    fun recordPendingUnknownSourcesApk(apk: File?) {
        pendingUnknownSourcesApk = apk
    }

    /** Returns and clears the pending APK when the unknown-sources permission has been granted. */
    fun takePendingUnknownSourcesApkIfInstallable(context: Context): File? {
        val apk = pendingUnknownSourcesApk ?: return null
        if (!apk.isFile || !context.packageManager.canRequestPackageInstalls()) {
            return null
        }
        pendingUnknownSourcesApk = null
        return apk
    }

    /** Fires the package installer for [apk], routing through the unknown-sources gate first. */
    fun installApk(
        context: Context,
        apk: File,
        lang: String,
        onRequestUnknownSources: ((File) -> Unit)? = null
    ) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            if (onRequestUnknownSources != null) {
                onRequestUnknownSources(apk)
            } else {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri()
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
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
        }.onFailure { err ->
            CupertinoToast.show(
                AppStrings.get("settings_update_install_failed", lang)
                    .replace("{reason}", err.message ?: ""),
                isDestructive = true
            )
        }
    }

    private suspend fun downloadUpdateApk(
        context: Context,
        asset: UpdateChecker.ReleaseAsset,
        sumsAsset: UpdateChecker.ReleaseAsset?,
        lang: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): UpdateDownloadResult = withContext(ioDispatcher) {
        try {
            val mixedPort = SettingsManager(context).mixedPort
            val vpnRunning = MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING
            val expectedHash = resolveExpectedHash(sumsAsset, asset.name, mixedPort, vpnRunning)

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "nexus-update.apk")
            if (isCachedApkValid(target, expectedHash)) {
                return@withContext UpdateDownloadResult.Success(target, verified = true)
            }

            clearDirectoryContents(dir)
            val response = openSuccessfulApkResponse(asset.browserDownloadUrl, mixedPort, vpnRunning)
            response.use { resp ->
                copyApkBody(
                    body = resp.body,
                    target = target,
                    tooLargeMessage = AppStrings.get("update_apk_too_large", lang),
                    checkActive = { coroutineContext.let { ensureActive() } },
                    onProgress = onProgress
                )
            }
            verifyDownloadedApk(context, target, expectedHash, lang)
        } catch (e: CancellationException) {
            clearUpdatesDir(context)
            throw e
        } catch (_: Exception) {
            clearUpdatesDir(context)
            UpdateDownloadResult.Failure(AppStrings.get("download_failed_short", lang))
        }
    }

    private fun resolveExpectedHash(
        sumsAsset: UpdateChecker.ReleaseAsset?,
        assetName: String,
        mixedPort: Int,
        vpnRunning: Boolean
    ): String? = sumsAsset?.let { sums ->
        fetchTextAsset(sums, mixedPort, vpnRunning)?.let { text ->
            UpdateVerifier.parseSha256Sums(text)[assetName]
        }
    }

    private fun isCachedApkValid(target: File, expectedHash: String?): Boolean {
        if (expectedHash == null || !target.isFile) return false
        val cachedHash = UpdateVerifier.sha256HexOfFile(target) ?: return false
        return UpdateVerifier.verifySha256(cachedHash, expectedHash)
    }

    private fun clearDirectoryContents(dir: File) {
        dir.listFiles()?.forEach { entry -> entry.delete() }
    }

    private fun clearUpdatesDir(context: Context) {
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
    }

    private fun openSuccessfulApkResponse(
        url: String,
        mixedPort: Int,
        vpnRunning: Boolean
    ): okhttp3.Response {
        val request = Request.Builder().url(url).build()
        var lastError: Throwable? = null
        for (client in downloadCandidateClients(mixedPort, vpnRunning)) {
            val attempt = runCatching { client.newCall(request).execute() }
            val resp = attempt.getOrNull()
            if (resp != null && resp.isSuccessful) {
                return resp
            }
            resp?.close()
            lastError = attempt.exceptionOrNull()
        }
        if (lastError != null) throw lastError else error("Download failed")
    }

    private fun copyApkBody(
        body: okhttp3.ResponseBody,
        target: File,
        tooLargeMessage: String,
        checkActive: () -> Unit,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_UPDATE_APK_BYTES) {
            error(tooLargeMessage)
        }
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(UPDATE_COPY_BUFFER_BYTES)
                var total = 0L
                var lastReported = 0L
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_UPDATE_APK_BYTES) {
                        error(tooLargeMessage)
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

    private fun verifyDownloadedApk(
        context: Context,
        target: File,
        expectedHash: String?,
        lang: String
    ): UpdateDownloadResult {
        if (expectedHash == null) {
            return UpdateDownloadResult.Success(target, verified = false)
        }
        val actual = UpdateVerifier.sha256HexOfFile(target)
        if (actual == null || !UpdateVerifier.verifySha256(actual, expectedHash)) {
            clearUpdatesDir(context)
            return UpdateDownloadResult.Failure(AppStrings.get("settings_update_verify_failed", lang))
        }
        return UpdateDownloadResult.Success(target, verified = true)
    }

    private fun preInstallCheck(context: Context, apk: File, lang: String): String? {
        val pm = context.packageManager
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
                val installedCerts = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners?.map { sig -> sig.toByteArray() } ?: emptyList()
                val incomingCerts = archiveInfo.signingInfo?.apkContentsSigners?.map { sig ->
                    sig.toByteArray()
                } ?: emptyList()
                UpdateVerifier.signaturesMatch(installedCerts, incomingCerts)
            } else {
                @Suppress("DEPRECATION")
                val installedCerts = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures?.map { sig -> sig.toByteArray() } ?: emptyList()
                @Suppress("DEPRECATION")
                val incomingCerts = archiveInfo.signatures?.map { sig -> sig.toByteArray() } ?: emptyList()
                UpdateVerifier.signaturesMatch(installedCerts, incomingCerts)
            }
        }.getOrDefault(false)
        if (!signaturesMatch) {
            runCatching { apk.delete() }
            return AppStrings.get("settings_update_signature_mismatch", lang)
        }
        return null
    }
}
