@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private const val MIN_VALID_PORT = 1
    private const val MAX_VALID_PORT = 65535
    private const val GITHUB_RAW_HOST = "raw.githubusercontent.com"
    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // Serializes read-modify-write cycles on the scripts/rewrites lists; without it a
    // background download and a user toggle could both start from the same snapshot and
    // one update would be silently lost.
    private val ioLock = Any()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    internal fun sha256(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    internal data class RemoteScriptDownloadTrust(
        val enabled: Boolean,
        val contentHash: String
    )

    internal fun resolveRemoteScriptDownloadTrust(
        body: String,
        fromMirror: Boolean
    ): RemoteScriptDownloadTrust = if (fromMirror) {
        RemoteScriptDownloadTrust(enabled = false, contentHash = "")
    } else {
        RemoteScriptDownloadTrust(enabled = true, contentHash = sha256(body))
    }

    internal fun canAcceptRemoteUpdate(
        existingContentHash: String,
        newHash: String,
        fromMirror: Boolean
    ): Boolean = !fromMirror || (existingContentHash.isNotBlank() && existingContentHash == newHash)

    /**
     * Returns the encoded path (+ query) when [url] has an exact `raw.githubusercontent.com` host
     * and `http`/`https` scheme; otherwise null so mirror fallbacks cannot be abused by host
     * confusion (`https://evil.com/?raw.githubusercontent.com` or `raw.githubusercontent.com.evil.com`).
     */
    internal fun extractGithubRawPath(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        val isGithubHost = parsed.host.equals(GITHUB_RAW_HOST, ignoreCase = true)
        val isHttpScheme = parsed.scheme == "https" || parsed.scheme == "http"
        return if (isGithubHost && isHttpScheme) {
            val path = parsed.encodedPath.trimStart('/')
            val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
            (path + query).takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    private fun allowPrivateHosts(): Boolean =
        runCatching {
            SettingsManager(com.github.kr328.clash.common.Global.application).allowPrivateSubscriptionHosts
        }.getOrDefault(true)

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
        .withSsrfProtection(allowPrivateSupplier = ::allowPrivateHosts, validateDns = true)
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
            // Keep validateDns = false on the proxy client so connecting to 127.0.0.1:mixedPort
            // is unaffected, while followRedirects(false) + redirect check still validates hop targets.
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
                .withSsrfProtection(allowPrivateSupplier = ::allowPrivateHosts, validateDns = false)
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

    private fun executeRequestOrNull(client: OkHttpClient, url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) readScriptBody(resp) else null
            }
        } catch (e: SsrfBlockedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFromMirrors(originalUrl: String, rawPath: String): ScriptFetch? {
        for (mirror in GITHUB_MIRRORS) {
            val mirrorUrl = mirror + rawPath
            requireSecureRemoteUrl(mirrorUrl, allowPrivateHosts = false)
            val body = executeRequestOrNull(httpClient, mirrorUrl)
            if (body != null) {
                Log.w(TAG, "Mirror fetch succeeded for ${redactUrl(originalUrl)}; payload from third-party mirror")
                LogRepository.addLog(
                    "warning",
                    "脚本内容来自第三方镜像（原始地址不可达），未自动信任执行，请先核验内容"
                )
                return ScriptFetch(body, fromMirror = true)
            }
        }
        return null
    }

    private fun fetchGithubRawOrNull(url: String, rawPath: String, mixedPort: Int): ScriptFetch? {
        val viaProxy = if (mixedPort in MIN_VALID_PORT..MAX_VALID_PORT) {
            executeRequestOrNull(localProxyClient(mixedPort), url)
        } else {
            null
        }
        if (viaProxy != null) return ScriptFetch(viaProxy, fromMirror = false)
        Log.d(TAG, "Local proxy failed for ${redactUrl(url)}")

        val direct = executeRequestOrNull(httpClient, url)
        return if (direct != null) {
            ScriptFetch(direct, fromMirror = false)
        } else {
            fetchFromMirrors(url, rawPath)
        }
    }

    /**
     * Attempt to fetch a URL, with special handling for raw.githubusercontent.com:
     * 1. First try through the local mixed-port proxy - works when the core is running
     * 2. Then try the DIRECT raw.githubusercontent.com URL
     * 3. Finally fall back to mirror URLs
     */
    private fun fetchUrl(url: String, mixedPort: Int): ScriptFetch {
        val rawPath = extractGithubRawPath(url)
        val githubFetch = rawPath?.let { fetchGithubRawOrNull(url, it, mixedPort) }
        if (githubFetch != null) return githubFetch

        // Strategy 4 (or primary for non-GitHub URLs): Direct download
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
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

    private inline fun <T> runIoCatching(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getScripts(context: Context): List<ScriptItem> = synchronized(ioLock) {
        val raw = getScriptsPrefs(context).getString("script_items", "[]") ?: "[]"
        runCatching { json.decodeFromString<List<ScriptItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveScripts(context: Context, list: List<ScriptItem>) {
        synchronized(ioLock) {
            val raw = json.encodeToString(list)
            getScriptsPrefs(context).edit { putString("script_items", raw) }
        }
        ProfileParser.clearCache()
    }

    suspend fun fetchRemoteScriptContent(
        url: String,
        allowPrivateHosts: Boolean = true,
        mixedPort: Int = 7890
    ): Result<String> = withContext(Dispatchers.IO) {
        runIoCatching {
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
        runIoCatching {
            val settings = SettingsManager(context)
            requireSecureRemoteUrl(url, settings.allowPrivateSubscriptionHosts)
            val fetched = fetchUrl(url, settings.mixedPort)
            val body = fetched.body
            val trust = resolveRemoteScriptDownloadTrust(body, fetched.fromMirror)

            val id = java.util.UUID.randomUUID().toString()
            val fileName = "$id.js"
            val item = ScriptItem(
                id = id,
                name = name.ifBlank { url.substringAfterLast("/").substringBefore("?") },
                url = url,
                pattern = pattern,
                type = type,
                scriptFile = fileName,
                enabled = trust.enabled,
                updatedAt = System.currentTimeMillis(),
                contentHash = trust.contentHash
            )

            synchronized(ioLock) {
                val file = SafeFileName.resolveChild(getScriptsDir(context), fileName)
                    ?: error("无法为脚本创建安全的文件路径")
                ConfigManager.writeTextAtomic(file, body)
                val current = getScripts(context).toMutableList()
                current.add(0, item)
                saveScripts(context, current)
            }
            if (fetched.fromMirror) {
                Log.w(TAG, "Downloaded script ${item.name} from mirror; keeping disabled without trusted hash")
            } else {
                Log.i(TAG, "Successfully downloaded script: ${item.name} ($fileName)")
            }
            item
        }
    }

    fun saveLocalScript(
        context: Context,
        name: String,
        pattern: String,
        type: String,
        code: String
    ): ScriptItem = synchronized(ioLock) {
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

        val current = getScripts(context).toMutableList()
        current.add(0, item)
        saveScripts(context, current)
        item
    }

    /** Reads the on-disk code of a script inside [ioLock] so file and hash stay consistent. */
    fun readScriptCode(context: Context, script: ScriptItem): String = synchronized(ioLock) {
        val file = SafeFileName.resolveChild(getScriptsDir(context), script.scriptFile) ?: return@synchronized ""
        if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
    }

    /**
     * Fails closed: returns `true` only when the script carries a non-blank SHA-256 fingerprint
     * and the on-disk script matches it. Unverified mirror downloads or tampered files return `false`.
     */
    internal fun verifyScriptIntegrityCode(script: ScriptItem, code: String): Boolean =
        script.contentHash.isNotBlank() && code.isNotEmpty() && sha256(code) == script.contentHash

    fun verifyScriptIntegrity(context: Context, script: ScriptItem): Boolean = synchronized(ioLock) {
        val latest = getScripts(context).firstOrNull { it.id == script.id } ?: script
        if (latest.contentHash.isBlank()) return@synchronized false
        verifyScriptIntegrityCode(latest, readScriptCode(context, latest))
    }

    fun updateScriptCode(context: Context, script: ScriptItem, newCode: String) {
        synchronized(ioLock) {
            val file = SafeFileName.resolveChild(getScriptsDir(context), script.scriptFile)
            if (file != null) {
                val hash = sha256(newCode)
                ConfigManager.writeTextAtomic(file, newCode)
                val current = getScripts(context).toMutableList()
                val idx = current.indexOfFirst { it.id == script.id }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(
                        updatedAt = System.currentTimeMillis(),
                        contentHash = hash
                    )
                    saveScripts(context, current)
                }
            }
        }
    }

    suspend fun updateScriptFromRemote(context: Context, script: ScriptItem): Result<ScriptItem> =
        withContext(Dispatchers.IO) {
            runIoCatching {
                if (script.url.isBlank()) error("无远程更新链接")
                val settings = SettingsManager(context)
                requireSecureRemoteUrl(script.url, settings.allowPrivateSubscriptionHosts)
                val fetched = fetchUrl(script.url, settings.mixedPort)
                val body = fetched.body
                val newHash = sha256(body)

                synchronized(ioLock) {
                    val current = getScripts(context).toMutableList()
                    val idx = current.indexOfFirst { it.id == script.id }
                    val latest = if (idx >= 0) current[idx] else script
                    if (!canAcceptRemoteUpdate(latest.contentHash, newHash, fetched.fromMirror)) {
                        Log.w(TAG, "Rejected mirror script update for ${latest.name}: hash mismatch or missing baseline")
                        LogRepository.addLog(
                            "warning",
                            "已拒绝脚本「${latest.name}」的镜像更新：镜像内容与已校验指纹不一致或缺少基准指纹"
                        )
                        error("第三方镜像内容与已校验指纹不一致，已拒绝覆盖（请直连或经代理从原始地址更新）")
                    }

                    val file = SafeFileName.resolveChild(getScriptsDir(context), latest.scriptFile)
                        ?: error("无法为脚本创建安全的文件路径")
                    ConfigManager.writeTextAtomic(file, body)

                    val updated = latest.copy(
                        updatedAt = System.currentTimeMillis(),
                        contentHash = newHash
                    )
                    if (idx >= 0) {
                        current[idx] = updated
                        saveScripts(context, current)
                    }
                    updated
                }
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
    fun getRewrites(context: Context): List<RewriteRule> = synchronized(ioLock) {
        val raw = getScriptsPrefs(context).getString("rewrite_rules", "[]") ?: "[]"
        runCatching { json.decodeFromString<List<RewriteRule>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveRewrites(context: Context, list: List<RewriteRule>) {
        synchronized(ioLock) {
            val raw = json.encodeToString(list)
            getScriptsPrefs(context).edit { putString("rewrite_rules", raw) }
        }
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
