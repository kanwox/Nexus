package com.github.mihomo.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
     * Fetches the latest release from GitHub and compares against the installed version.
     * Tries direct connection first; falls back to the local mixed proxy if direct connection fails.
     * Must be called from a coroutine (runs on [Dispatchers.IO] internally).
     */
    suspend fun checkForUpdate(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val currentVersion = installedVersion(context)
        val mixedPort = SettingsManager(context).mixedPort

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
            // 1. Try direct connection first
            var body = tryFetch(httpClient)

            // 2. If direct fails (e.g. GitHub blocked/polluted while app traffic bypasses VPN),
            // retry via local mixed port proxy if available
            if (body == null) {
                body = tryFetch(localProxyClient(mixedPort))
            }

            if (body == null) {
                return@withContext CheckResult.Error("Network error: failed to connect to GitHub")
            }

            val obj = json.parseToJsonElement(body).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: ""
            val releaseName = obj["name"]?.jsonPrimitive?.content ?: tagName
            val releaseNotes = (obj["body"]?.jsonPrimitive?.content ?: "").take(1000)
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
            val apkAsset = obj["assets"]?.let { assetsElement ->
                runCatching {
                    json.decodeFromJsonElement<List<ReleaseAsset>>(assetsElement)
                }.getOrDefault(emptyList())
            }?.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

            val latestVersion = tagName.trimStart('v', 'V')
            val isNewer = isVersionNewer(latestVersion, currentVersion)

            CheckResult.Success(
                UpdateInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    htmlUrl = htmlUrl,
                    apkAsset = apkAsset,
                    isNewer = isNewer
                )
            )
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
