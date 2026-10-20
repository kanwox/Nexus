package com.github.mihomo.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer version of the app.
 * Repository: https://github.com/kanwox/Nexus
 */
object UpdateChecker {

    // Current app version — must match versionName in build.gradle.kts
    const val CURRENT_VERSION = "1.0.3-Beta.2"

    private const val RELEASES_API =
        "https://api.github.com/repos/kanwox/Nexus/releases/latest"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    data class UpdateInfo(
        /** Latest tag name, e.g. "v1.0.2" */
        val tagName: String,
        /** Human-readable release name */
        val releaseName: String,
        /** Release notes body */
        val releaseNotes: String,
        /** Browser URL of the release page */
        val htmlUrl: String,
        /** Whether the latest release is newer than the installed version */
        val isNewer: Boolean
    )

    sealed class CheckResult {
        data class Success(val info: UpdateInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /**
     * Fetches the latest release from GitHub and compares against [CURRENT_VERSION].
     * Must be called from a coroutine (runs on [Dispatchers.IO] internally).
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Nexus-Android/$CURRENT_VERSION")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext CheckResult.Error("HTTP ${response.code}")
            }

            val body = response.body?.string()
                ?: return@withContext CheckResult.Error("Empty response")

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "").take(1000)
            val htmlUrl = json.optString("html_url", "")

            val latestVersion = tagName.trimStart('v', 'V')
            val isNewer = isVersionNewer(latestVersion, CURRENT_VERSION)

            CheckResult.Success(
                UpdateInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    htmlUrl = htmlUrl,
                    isNewer = isNewer
                )
            )
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Returns true if [candidate] is a strictly newer semantic version than [current].
     * Compares major.minor.patch numerically; non-numeric parts are treated as 0.
     */
    private fun isVersionNewer(candidate: String, current: String): Boolean {
        val c = parseVersion(candidate)
        val cur = parseVersion(current)
        for (i in 0 until maxOf(c.size, cur.size)) {
            val cv = c.getOrElse(i) { 0 }
            val curv = cur.getOrElse(i) { 0 }
            if (cv > curv) return true
            if (cv < curv) return false
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
}
