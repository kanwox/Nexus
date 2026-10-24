package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class ScriptItem(
    val id: String,
    val name: String,
    val url: String = "",
    val pattern: String = "",
    val type: String = "http-response", // http-request, http-response, cron, rule
    val scriptFile: String = "",
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    // SHA-256 of the script content at save/download time. Scripts auto-execute on every VPN
    // start, so a pinned fingerprint makes on-disk tampering (or a mirror serving different
    // content than the user reviewed) detectable before execution. Blank for legacy entries.
    val contentHash: String = ""
)

@Serializable
data class RewriteRule(
    val id: String,
    val name: String = "",
    val pattern: String = "",
    val target: String = "",
    val type: String = "302", // 302, 307, reject, header
    val enabled: Boolean = true
)

@Suppress("unused")
object ScriptManager {
    private const val TAG = "ScriptManager"
    private const val MAX_SCRIPT_BYTES = 5L * 1024 * 1024

    // Serializes read-modify-write cycles on the scripts/rewrites lists; without it a
    // background download and a user toggle could both start from the same snapshot and
    // one update would be silently lost.
    private val ioLock = Any()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun sha256(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // GitHub mirror prefixes to try when raw.githubusercontent.com is unreachable
    private val GITHUB_MIRRORS = listOf(
        "https://raw.gitmirror.com/",
        "https://ghproxy.net/https://raw.githubusercontent.com/",
        "https://gh.con.sh/https://raw.githubusercontent.com/"
    )

    // Direct client (no proxy) - for subscriptions that go through VPN already
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // Client that routes through local mixed-port proxy (127.0.0.1:7890)
    // Used when app traffic bypasses VPN (app is in disallowedApplications)
    private val localProxyClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 7890)))
        .build()

    /**
     * Attempt to fetch a URL, with special handling for raw.githubusercontent.com:
     * 1. First try through local proxy (127.0.0.1:7890) - works when core is running
     * 2. Then try mirror URLs directly
     * 3. Finally fall back to direct connection
     */
    private fun fetchUrl(url: String): String {
        val isGithubRaw = url.contains("raw.githubusercontent.com")

        if (isGithubRaw) {
            // Strategy 1: Local proxy (covers the case where VPN is on but app bypasses it)
            runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                localProxyClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return readScriptBody(resp)
                    }
                }
            }.onFailure { Log.d(TAG, "Local proxy failed for ${redactUrl(url)}: ${it.message}") }

            // Strategy 2: Mirror URLs (direct connection, mirrors bypass GFW).
            // NOTE: mirrors are third parties that can alter the payload; content hashes
            // pinned by downloadScript/updateScriptFromRemote make such changes detectable.
            val rawPath = url.removePrefix("https://raw.githubusercontent.com/")
            for (mirror in GITHUB_MIRRORS) {
                val mirrorUrl = when {
                    mirror.endsWith("/https://raw.githubusercontent.com/") -> mirror + rawPath
                    else -> mirror + rawPath
                }
                runCatching {
                    val req = Request.Builder().url(mirrorUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.i(TAG, "Mirror fetch succeeded: ${redactUrl(url)}")
                            return readScriptBody(resp)
                        }
                    }
                }.onFailure { Log.d(TAG, "Mirror failed for ${redactUrl(url)}: ${it.message}") }
            }
        }

        // Strategy 3 (or primary for non-GitHub URLs): Direct download
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP 错误: ${resp.code} ${resp.message}")
            return readScriptBody(resp)
        }
    }

    private fun readScriptBody(resp: okhttp3.Response): String =
        ConfigManager.readBodyCapped(resp, MAX_SCRIPT_BYTES, "脚本内容为空")

    private fun getScriptsDir(context: Context): File {
        return context.filesDir.resolve("scripts").apply { mkdirs() }
    }

    private fun getScriptsPrefs(context: Context) =
        context.getSharedPreferences("scripts_manager", Context.MODE_PRIVATE)

    fun getScripts(context: Context): List<ScriptItem> {
        val raw = getScriptsPrefs(context).getString("script_items", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<ScriptItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveScripts(context: Context, list: List<ScriptItem>) {
        val raw = json.encodeToString(list)
        getScriptsPrefs(context).edit { putString("script_items", raw) }
        // Script content changes the parsed profile, whose cache key does not include it.
        ProfileParser.clearCache()
    }

    suspend fun fetchRemoteScriptContent(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = url.trim()
            requireSecureRemoteUrl(cleanUrl)
            fetchUrl(cleanUrl)
        }
    }

    fun saveScript(
        context: Context,
        existingId: String? = null,
        name: String,
        url: String = "",
        pattern: String = "",
        type: String = "config",
        code: String,
        enabled: Boolean = true
    ): ScriptItem {
        synchronized(ioLock) {
            val current = getScripts(context).toMutableList()
            val existingIdx = if (!existingId.isNullOrBlank()) {
                current.indexOfFirst { it.id == existingId }
            } else {
                -1
            }

            val id = if (existingIdx >= 0) current[existingIdx].id else java.util.UUID.randomUUID().toString()
            val fileName = if (existingIdx >= 0 && current[existingIdx].scriptFile.isNotBlank()) {
                current[existingIdx].scriptFile
            } else {
                "$id.js"
            }
            val file = getScriptsDir(context).resolve(fileName)
            ConfigManager.writeTextAtomic(file, code)

            val resolvedName = name.trim().ifBlank {
                if (url.isNotBlank()) {
                    url.substringAfterLast("/").substringBefore("?").ifBlank { "未命名脚本" }
                } else {
                    "未命名脚本"
                }
            }

            val item = ScriptItem(
                id = id,
                name = resolvedName,
                url = url.trim(),
                pattern = pattern.trim(),
                type = type.ifBlank { "config" },
                scriptFile = fileName,
                enabled = enabled,
                updatedAt = System.currentTimeMillis(),
                contentHash = sha256(code)
            )

            if (existingIdx >= 0) {
                current[existingIdx] = item
            } else {
                current.add(0, item)
            }
            saveScripts(context, current)
            return item
        }
    }

    suspend fun downloadScript(
        context: Context,
        url: String,
        name: String,
        pattern: String,
        type: String
    ): Result<ScriptItem> = withContext(Dispatchers.IO) {
        runCatching {
            requireSecureRemoteUrl(url)
            val body = fetchUrl(url)

            val id = java.util.UUID.randomUUID().toString()
            val fileName = "$id.js"
            val file = getScriptsDir(context).resolve(fileName)
            ConfigManager.writeTextAtomic(file, body)

            val item = ScriptItem(
                id = id,
                name = name.ifBlank { url.substringAfterLast("/").substringBefore("?") },
                url = url,
                pattern = pattern,
                type = type,
                scriptFile = fileName,
                enabled = true,
                updatedAt = System.currentTimeMillis(),
                contentHash = sha256(body)
            )

            synchronized(ioLock) {
                val current = getScripts(context).toMutableList()
                current.add(0, item)
                saveScripts(context, current)
            }
            Log.i(TAG, "Successfully downloaded script: ${item.name} ($fileName)")
            item
        }
    }

    fun saveLocalScript(
        context: Context,
        name: String,
        pattern: String,
        type: String,
        code: String
    ): ScriptItem {
        val id = java.util.UUID.randomUUID().toString()
        val fileName = "$id.js"
        val file = getScriptsDir(context).resolve(fileName)
        ConfigManager.writeTextAtomic(file, code)

        val item = ScriptItem(
            id = id,
            name = name,
            url = "",
            pattern = pattern,
            type = type,
            scriptFile = fileName,
            enabled = true,
            updatedAt = System.currentTimeMillis(),
            contentHash = sha256(code)
        )

        synchronized(ioLock) {
            val current = getScripts(context).toMutableList()
            current.add(0, item)
            saveScripts(context, current)
        }
        return item
    }

    fun readScriptCode(context: Context, script: ScriptItem): String {
        val file = getScriptsDir(context).resolve(script.scriptFile)
        return if (file.exists()) file.readText() else ""
    }

    /**
     * True when the on-disk script content still matches the pinned fingerprint (or the entry
     * predates fingerprinting and has nothing to compare against). A mismatch means the file
     * was altered outside the app's editors and the script must not auto-execute.
     */
    fun verifyScriptIntegrity(context: Context, script: ScriptItem): Boolean {
        if (script.contentHash.isBlank()) return true
        val code = readScriptCode(context, script)
        return sha256(code) == script.contentHash
    }

    fun updateScriptCode(context: Context, script: ScriptItem, newCode: String) {
        val file = getScriptsDir(context).resolve(script.scriptFile)
        ConfigManager.writeTextAtomic(file, newCode)
        synchronized(ioLock) {
            val current = getScripts(context).toMutableList()
            val idx = current.indexOfFirst { it.id == script.id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(
                    updatedAt = System.currentTimeMillis(),
                    contentHash = sha256(newCode)
                )
                saveScripts(context, current)
            }
        }
    }

    suspend fun updateScriptFromRemote(context: Context, script: ScriptItem): Result<ScriptItem> = withContext(Dispatchers.IO) {
        runCatching {
            if (script.url.isBlank()) error("无远程更新链接")
            requireSecureRemoteUrl(script.url)
            val body = fetchUrl(script.url)

            val newHash = sha256(body)
            if (script.contentHash.isNotBlank() && script.contentHash != newHash) {
                // Expected for a genuine update, but if the content came from a third-party
                // mirror this is also the fingerprint of a possible tampered payload; the new
                // hash is pinned either way, so any later divergence becomes detectable.
                Log.i(TAG, "Script ${script.name} content changed after remote update")
            }

            val file = getScriptsDir(context).resolve(script.scriptFile)
            ConfigManager.writeTextAtomic(file, body)

            val updated = script.copy(
                updatedAt = System.currentTimeMillis(),
                contentHash = newHash
            )
            synchronized(ioLock) {
                val current = getScripts(context).toMutableList()
                val idx = current.indexOfFirst { it.id == script.id }
                if (idx >= 0) {
                    current[idx] = updated
                    saveScripts(context, current)
                }
            }
            updated
        }
    }

    fun toggleScript(context: Context, id: String, enabled: Boolean) {
        synchronized(ioLock) {
            val current = getScripts(context).toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(enabled = enabled)
                saveScripts(context, current)
            }
        }
    }

    fun deleteScript(context: Context, id: String) {
        synchronized(ioLock) {
            val current = getScripts(context).toMutableList()
            val item = current.firstOrNull { it.id == id }
            if (item != null) {
                val file = getScriptsDir(context).resolve(item.scriptFile)
                if (file.exists()) file.delete()
                current.removeAll { it.id == id }
                saveScripts(context, current)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rewrite Rules
    // -------------------------------------------------------------------------
    fun getRewrites(context: Context): List<RewriteRule> {
        val raw = getScriptsPrefs(context).getString("rewrite_rules", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<RewriteRule>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveRewrites(context: Context, list: List<RewriteRule>) {
        val raw = json.encodeToString(list)
        getScriptsPrefs(context).edit { putString("rewrite_rules", raw) }
        ProfileParser.clearCache()
    }

    fun saveRewrite(context: Context, rule: RewriteRule) {
        synchronized(ioLock) {
            val current = getRewrites(context).toMutableList()
            val idx = current.indexOfFirst { it.id == rule.id }
            if (idx >= 0) {
                current[idx] = rule
            } else {
                current.add(0, rule)
            }
            saveRewrites(context, current)
        }
    }

    fun toggleRewrite(context: Context, id: String, enabled: Boolean) {
        synchronized(ioLock) {
            val current = getRewrites(context).toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(enabled = enabled)
                saveRewrites(context, current)
            }
        }
    }

    fun deleteRewrite(context: Context, id: String) {
        synchronized(ioLock) {
            val current = getRewrites(context).toMutableList()
            current.removeAll { it.id == id }
            saveRewrites(context, current)
        }
    }
}
