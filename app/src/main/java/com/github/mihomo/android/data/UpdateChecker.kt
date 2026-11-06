package com.github.mihomo.android.data

import android.content.Context
import android.os.Build
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer version of the app.
 * Repository: https://github.com/kanwox/Nexus
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/kanwox/Nexus/releases/latest"

    /** Minimum interval between automatic background update checks (24 hours). */
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)

    /**
     * Most recent newer release discovered during this process lifetime, or null when the
     * installed build is up to date (or no check has succeeded yet). Observed by the Settings
     * and About screens to render a non-intrusive badge without modal popups on startup.
     */
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val proxyClients = java.util.concurrent.ConcurrentHashMap<Int, OkHttpClient>()

    /** Client that routes through the local mixed port when direct physical connections to GitHub fail. */
    fun localProxyClient(port: Int): OkHttpClient = proxyClients.getOrPut(port) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .proxy(
                java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress("127.0.0.1", port)
                )
            )
            .build()
    }

    /**
     * Ordered list of HTTP clients to try for GitHub API / asset requests.
     * Because the app's own UID is excluded from the TUN interface to prevent routing loops,
     * direct sockets bypass the tunnel. When the tunnel is RUNNING, routing through the local
     * mixed port first avoids a 10s TCP timeout on networks where GitHub is unreachable directly,
     * while still falling back to direct if the proxy port fails. When the tunnel is stopped,
     * only the direct client is used so a closed local port does not mask real HTTP/network errors.
     */
    fun candidateClients(mixedPort: Int, vpnRunning: Boolean): List<OkHttpClient> =
        if (vpnRunning) listOf(localProxyClient(mixedPort), httpClient)
        else listOf(httpClient)

    private val json = Json { ignoreUnknownKeys = true }

    /** One downloadable release artifact (GitHub release asset). */
    @Serializable
    data class ReleaseAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = ""
    )

    data class UpdateInfo(
        /** Latest tag name, e.g. "v1.0.2" */
        val tagName: String,
        /** Human-readable release name */
        val releaseName: String,
        /** Release notes body */
        val releaseNotes: String,
        /** Browser URL of the release page */
        val htmlUrl: String,
        /**
         * First .apk asset of the release, when present — enables the in-app download and
         * install path instead of opening the browser. Null when the release ships no apk.
         */
        val apkAsset: ReleaseAsset? = null,
        /**
         * Name of the selected apk asset (e.g. "Nexus-arm64-v8a-release.apk") so the UI
         * can tell the user exactly which artifact is about to be downloaded. Null when
         * the release ships no apk.
         */
        val apkName: String? = null,
        /** SHA256SUMS asset when the release ships one — enables download verification. */
        val sumsAsset: ReleaseAsset? = null,
        /** Whether the latest release is newer than the installed version */
        val isNewer: Boolean
    )

    sealed class CheckResult {
        data class Success(val info: UpdateInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /**
     * Reads the installed version from the package manager instead of a hand-maintained
     * constant, so bumping versionName in the build script can never drift from it.
     */
    fun installedVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    /**
     * Installed versionCode as a Long regardless of API level (the Int versionCode is
     * deprecated in favor of longVersionCode since API 28). The pre-install gate in the
     * update flow uses it to reject an archive older than what is already on the device;
     * -1 means the installed version could not be read, which the gate treats as
     * "cannot prove downgrade" and allows (a downgrade is not a security risk, a
     * hijacked package name is — and that is checked separately by signature).
     */
    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(-1L)

    /**
     * Silent startup check: runs [checkForUpdate] at most once every 24 hours when the user
     * has enabled automatic update checks. Network failures are ignored so startup is never
     * interrupted by a modal or toast.
     */
    suspend fun checkIfDue(context: Context) {
        val settings = SettingsManager(context)
        if (!settings.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckTime < AUTO_CHECK_INTERVAL_MS) return
        checkForUpdate(context)
    }

    /**
     * Fetches the latest release from GitHub and compares against the installed version.
     * Routes through the local mixed port first when the tunnel is active (since the app's own
     * UID bypasses TUN), falling back to direct connection; uses direct only when stopped.
     * Must be called from a coroutine (runs on [Dispatchers.IO] internally).
     */
    suspend fun checkForUpdate(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val currentVersion = installedVersion(context)
        val settings = SettingsManager(context)
        val mixedPort = settings.mixedPort
        val vpnRunning = MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING

        fun tryFetch(client: OkHttpClient): String? {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Nexus-Android/$currentVersion")
                .build()
            return runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.string() else null
                }
            }.getOrNull()
        }

        try {
            var body: String? = null
            for (client in candidateClients(mixedPort, vpnRunning)) {
                body = tryFetch(client)
                if (body != null) break
            }

            if (body == null) {
                return@withContext CheckResult.Error("Network error: failed to connect to GitHub")
            }

            val obj = json.parseToJsonElement(body).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: ""
            val releaseName = obj["name"]?.jsonPrimitive?.content ?: tagName
            val releaseNotes = (obj["body"]?.jsonPrimitive?.content ?: "").take(2000)
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
            val assetsList: List<ReleaseAsset> = obj["assets"]?.let { assetsElement ->
                runCatching {
                    json.decodeFromJsonElement<List<ReleaseAsset>>(assetsElement)
                }.getOrDefault(emptyList())
            } ?: emptyList()
            // ABI-aware selection: the device's own SUPPORTED_ABIS order (best-first)
            // picks the exact per-ABI artifact instead of whatever asset happens to be
            // listed first on the release page — installing the wrong architecture
            // fails far away from the cause. Degrades to the first .apk asset when the
            // release does not follow the Nexus-<abi>-release.apk naming.
            val apkAsset = UpdateVerifier.selectApkAsset(
                assetsList.map { it.name to it.browserDownloadUrl },
                Build.SUPPORTED_ABIS.toList()
            )?.let { (name, url) -> ReleaseAsset(name = name, browserDownloadUrl = url) }
            // A sums asset turns on integrity verification for the download path.
            val sumsAsset = assetsList.firstOrNull {
                it.name.startsWith("SHA256SUMS", ignoreCase = true)
            }

            val latestVersion = tagName.trimStart('v', 'V')
            val isNewer = isVersionNewer(latestVersion, currentVersion)

            val info = UpdateInfo(
                tagName = tagName,
                releaseName = releaseName,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                apkAsset = apkAsset,
                apkName = apkAsset?.name,
                sumsAsset = sumsAsset,
                isNewer = isNewer
            )
            _availableUpdate.value = if (isNewer) info else null
            settings.lastUpdateCheckTime = System.currentTimeMillis()
            CheckResult.Success(info)
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Returns true if [candidate] is a strictly newer semantic version than [current].
     * Compares major.minor.patch numerically; a stable release (no pre-release suffix)
     * is considered newer than a pre-release of the same base version (e.g. 1.0.3 > 1.0.3-Beta.3).
     */
    internal fun isVersionNewer(candidate: String, current: String): Boolean {
        val (cBase, cPre) = splitPreRelease(candidate)
        val (curBase, curPre) = splitPreRelease(current)

        val cNums = parseVersionNumbers(cBase)
        val curNums = parseVersionNumbers(curBase)
        // An unparsable installed version (e.g. the "unknown" fallback) must never compare
        // as older, otherwise the app nags about an update on every check.
        if (curNums.isEmpty()) return false
        for (i in 0 until maxOf(cNums.size, curNums.size)) {
            val cv = cNums.getOrElse(i) { 0 }
            val curv = curNums.getOrElse(i) { 0 }
            if (cv > curv) return true
            if (cv < curv) return false
        }

        // Same base version: stable > pre-release
        if (cPre.isEmpty() && curPre.isNotEmpty()) return true
        if (cPre.isNotEmpty() && curPre.isEmpty()) return false
        if (cPre.isEmpty() && curPre.isEmpty()) return false

        val cPreNums = parseVersionNumbers(cPre)
        val curPreNums = parseVersionNumbers(curPre)
        for (i in 0 until maxOf(cPreNums.size, curPreNums.size)) {
            val cv = cPreNums.getOrElse(i) { 0 }
            val curv = curPreNums.getOrElse(i) { 0 }
            if (cv > curv) return true
            if (cv < curv) return false
        }
        return cPre.compareTo(curPre, ignoreCase = true) > 0
    }

    private fun splitPreRelease(v: String): Pair<String, String> {
        val dashIdx = v.indexOf('-')
        return if (dashIdx >= 0) {
            v.substring(0, dashIdx) to v.substring(dashIdx + 1)
        } else {
            v to ""
        }
    }

    private fun parseVersionNumbers(v: String): List<Int> =
        v.split(".", "-").mapNotNull { part ->
            part.filter { ch -> ch.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
        }
}
