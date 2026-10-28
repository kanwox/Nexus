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
        .addInterceptor(createSafeRedirectInterceptor {
            runCatching { SettingsManager(com.github.kr328.clash.common.Global.application).allowPrivateSubscriptionHosts }
                .getOrDefault(true)
        })
        .build()

    // Client that routes through the local mixed-port proxy (port is user-configurable).
    // Used when app traffic bypasses VPN (the app is always excluded from its own tunnel).
    //
    // Only the client for the CURRENT port is kept: every port change used to add another
    // entry, and each OkHttpClient owns its own connection pool + dispatcher, so the map
    // grew without bound (a leak that survives until the process dies).
    private val proxyClients = java.util.concurrent.ConcurrentHashMap<Int, OkHttpClient>()

    private fun localProxyClient(port: Int): OkHttpClient {
        proxyClients[port]?.let { return it }
        synchronized(proxyClients) {
            proxyClients[port]?.let { return it }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .proxy(
                    java.net.Proxy(
                        java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress("127.0.0.1", port)
                    )
                )
                .build()
            // Evict the clients of the previous ports and release their resources.
            val stale = proxyClients.keys.filter { it != port }
            stale.forEach { old ->
                proxyClients.remove(old)?.let { releaseClient(it) }
            }
            proxyClients[port] = client
            return client
        }
    }

    /** Releases the threads and connection pool a discarded OkHttpClient holds. */
    private fun releaseClient(client: OkHttpClient) {
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            if (client.cache != null) {
                client.cache?.close()
            }
        }.onFailure { Log.d(TAG, "Failed to release a stale proxy client: ${it.message}") }
    }

    /**
     * Attempt to fetch a URL, with special handling for raw.githubusercontent.com:
     * 1. First try through the local mixed-port proxy - works when the core is running
     * 2. Then try the DIRECT raw.githubusercontent.com URL
     * 3. Finally fall back to mirror URLs
     */
    private fun fetchUrl(url: String, mixedPort: Int): ScriptFetch {
        val isGithubRaw = url.contains("raw.githubusercontent.com")

        if (isGithubRaw) {
            // Strategy 1: Local proxy (covers the case where VPN is on but app bypasses it).
            // This is the ORIGINAL source, so its payload is trusted as the baseline.
            val viaProxy = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                localProxyClient(mixedPort).newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        readScriptBody(resp)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (viaProxy != null) {
                return ScriptFetch(viaProxy, fromMirror = false)
            }
            Log.d(TAG, "Local proxy failed for ${redactUrl(url)}")

            // Strategy 2: the direct raw.githubusercontent.com URL. The mirror is only used
            // when the ORIGINAL source cannot be reached, so the pinned hash is taken
            // from the original whenever possible.
            val direct = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) readScriptBody(resp) else null
                }
            }.getOrNull()
            if (direct != null) {
                return ScriptFetch(direct, fromMirror = false)
            }

            // Strategy 3: Mirror URLs (direct connection, mirrors bypass the GFW).
            // A mirror is a third party that can alter the payload, and the returned code
            // is executed with full JS privileges. The fetch therefore only happens when
            // the original source failed, and the fact is recorded so it can be reported
            // and so later divergence from the pinned hash is detected.
            val rawPath = url.removePrefix("https://raw.githubusercontent.com/")
            for (mirror in GITHUB_MIRRORS) {
                val mirrorUrl = mirror + rawPath
                val body = runCatching {
                    val req = Request.Builder().url(mirrorUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) readScriptBody(resp) else null
                    }
                }.getOrNull()
                if (body != null) {
                    Log.w(TAG, "Mirror fetch succeeded for ${redactUrl(url)}; the payload did not come from the original source")
                    LogRepository.addLog(
                        "warning",
                        "脚本内容来自第三方镜像（原始地址不可达），其指纹已记录，后续内容变化将被检测"
                    )
                    return ScriptFetch(body, fromMirror = true)
                }
            }
        }

        // Strategy 4 (or primary for non-GitHub URLs): Direct download
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP 错误: ${resp.code} ${resp.message}")
            return ScriptFetch(readScriptBody(resp), fromMirror = false)
        }
    }

    /** The body of a fetched script plus where it came from. */
    private data class ScriptFetch(val body: String, val fromMirror: Boolean)

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

    suspend fun fetchRemoteScriptContent(
        url: String,
        allowPrivateHosts: Boolean = true,
        mixedPort: Int = 7890
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = url.trim()
            requireSecureRemoteUrl(cleanUrl, allowPrivateHosts)
            fetchUrl(cleanUrl, mixedPort).body
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
            // The file name is stored in the script list, so it can come from a restored
            // backup or a tampered SharedPreferences. It is resolved through
            // SafeFileName, which rejects anything that is not a plain single-segment
            // name — otherwise "../../databases/x" would escape the scripts directory.
            val existingFileName = if (existingIdx >= 0) current[existingIdx].scriptFile else ""
            val fileName = SafeFileName.sanitize(existingFileName) ?: "$id.js"
            val file = SafeFileName.resolveChild(getScriptsDir(context), fileName)
                ?: error("无法为脚本创建安全的文件路径")
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
            requireSecureRemoteUrl(url, SettingsManager(context).allowPrivateSubscriptionHosts)
            val fetched = fetchUrl(url, SettingsManager(context).mixedPort)
            val body = fetched.body

            val id = java.util.UUID.randomUUID().toString()
            val fileName = "$id.js"
            val file = SafeFileName.resolveChild(getScriptsDir(context), fileName)
                ?: error("无法为脚本创建安全的文件路径")
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
        val file = SafeFileName.resolveChild(getScriptsDir(context), fileName)
            ?: error("无法为脚本创建安全的文件路径")
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

    /**
     * Reads the on-disk code of a script.
     *
     * `scriptFile` is persisted metadata (and arrives from restored backups), so it is
     * resolved through [SafeFileName.resolveChild]: a value like `../../databases/x`
     * would otherwise let a crafted backup read a file outside the scripts directory.
     * An unsafe name yields an empty script, which the engine treats as "nothing to run".
     */
    fun readScriptCode(context: Context, script: ScriptItem): String {
        val file = SafeFileName.resolveChild(getScriptsDir(context), script.scriptFile) ?: return ""
        return if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
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
        val file = SafeFileName.resolveChild(getScriptsDir(context), script.scriptFile) ?: return
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
            requireSecureRemoteUrl(script.url, SettingsManager(context).allowPrivateSubscriptionHosts)
            val fetched = fetchUrl(script.url, SettingsManager(context).mixedPort)
            val body = fetched.body

            val newHash = sha256(body)
            if (script.contentHash.isNotBlank() && script.contentHash != newHash) {
                // Expected for a genuine update, but if the content came from a third-party
                // mirror this is also the fingerprint of a possible tampered payload; the new
                // hash is pinned either way, so any later divergence becomes detectable.
                Log.i(TAG, "Script ${script.name} content changed after remote update")
            }
            if (fetched.fromMirror) {
                // A mirror's payload is never silently trusted as the new baseline: the
                // origin is surfaced and the hash is pinned so a later divergence shows up.
                Log.w(TAG, "Script ${script.name} was updated from a third-party mirror")
            }

            val file = SafeFileName.resolveChild(getScriptsDir(context), script.scriptFile)
                ?: error("无法为脚本创建安全的文件路径")
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
                // SafeFileName: a crafted scriptFile must not make the delete reach
                // outside the scripts directory.
                SafeFileName.resolveChild(getScriptsDir(context), item.scriptFile)?.let { file ->
                    if (file.exists()) file.delete()
                }
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
