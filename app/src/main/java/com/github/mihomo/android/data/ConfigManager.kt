@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.github.mihomo.android.core.TunConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProfileItem(
    val id: String,
    val name: String,
    val url: String?,
    val file: File,
    val lastUpdated: Long = System.currentTimeMillis(),
    val scriptEnabled: Boolean = false,
    val scriptIds: List<String> = emptyList(),
    val autoUpdate: Boolean = false,
    val autoUpdateInterval: Int = 1440,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L,
    // Structured overrides applied after script rewriting (see [ConfigOverrides]).
    val prependRules: List<String> = emptyList(),
    val hostsEntries: List<String> = emptyList(),
    val dnsServersOverride: List<String> = emptyList(),
)

/**
 * Per-profile structured overrides layered on top of the (script-rewritten) subscription:
 * - [prependRules] are inserted at the very top of the rule list (highest priority).
 * - [hostsEntries] are "domain: target" lines merged into the hosts mapping (profile entries
 *   win over subscription hosts on key conflicts).
 * - [dnsServersOverride] replaces the upstream nameserver list when non-empty.
 */
data class ConfigOverrides(
    val prependRules: List<String> = emptyList(),
    val hostsEntries: List<String> = emptyList(),
    val dnsServersOverride: List<String> = emptyList(),
)

data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
)

private const val TAG = "ConfigManager"

object ConfigManager {
    private const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val BYTES_PER_MB = 1024 * 1024
    private const val MAX_LISTEN_PORT = 65535
    private const val MIN_MIXED_PORT = 1024
    private const val FALLBACK_NAME_MOD = 1000
    private const val SUBSCRIPTION_USER_AGENT = "ClashMeta/1.18.0 Mihomo/1.18.0 Clash/1.0.0"

    private val prepareLock = Mutex()
    private val profileLocks = ConcurrentHashMap<String, Mutex>()

    private fun profileLock(id: String): Mutex = profileLocks.computeIfAbsent(id) { Mutex() }

    private val httpClient: OkHttpClient by lazy {
        NetworkClients.directClient(
            connectTimeoutSec = 15,
            readTimeoutSec = 30,
            callTimeoutSec = 60,
            allowPrivateSupplier = {
                runCatching {
                    val app = com.github.kr328.clash.common.Global.application
                    SettingsManager(app).allowPrivateSubscriptionHosts
                }.getOrDefault(false)
            }
        )
    }

    /**
     * Writes via a unique temp file + atomic move so a crash or concurrent write can never
     * corrupt or truncate the destination file.
     */
    internal fun writeTextAtomic(file: File, content: String) {
        val parent = file.absoluteFile.parentFile?.apply { mkdirs() }
            ?: throw IOException("Missing parent directory for ${file.path}")
        val tmp = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            ProfileStore.moveReplacingFile(tmp, file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Reads a response body fully but refuses payloads above [maxBytes] instead of OOMing. */
    internal fun readBodyCapped(response: Response, maxBytes: Long, emptyError: String): String {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) {
            error("内容过大 (${declared / BYTES_PER_MB} MB, 上限 ${maxBytes / BYTES_PER_MB} MB)")
        }
        return readStreamCapped(
            stream = body.byteStream(),
            initialHint = declared.coerceIn(0L, BYTES_PER_MB.toLong()).toInt(),
            maxBytes = maxBytes,
            emptyError = emptyError
        )
    }

    private fun readStreamCapped(
        stream: InputStream,
        initialHint: Int,
        maxBytes: Long,
        emptyError: String
    ): String {
        val out = ByteArrayOutputStream(initialHint.coerceAtLeast(256))
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    error("内容超过 ${maxBytes / BYTES_PER_MB} MB 上限")
                }
                out.write(buf, 0, n)
            }
        }
        val text = out.toByteArray().toString(Charsets.UTF_8)
        if (text.isBlank()) error(emptyError)
        return text
    }

    internal fun isValidSubscriptionPayload(content: String): Boolean =
        ConfigValidation.isValidSubscriptionPayload(content)

    internal fun validateConfigStructure(content: String): String? =
        ConfigValidation.validateConfigStructure(content)

    internal fun extractProfileName(name: String?, response: Response?, body: String, url: String): String =
        ConfigValidation.extractProfileName(name, response, body, url)

    fun parseSubscriptionUserInfo(header: String?): SubscriptionUserInfo? {
        if (header.isNullOrBlank()) return null
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        for (part in header.split(";")) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val kv = trimmed.split("=", limit = 2)
            if (kv.size != 2) continue
            val key = kv[0].trim().lowercase(Locale.ROOT)
            if (key !in setOf("upload", "download", "total", "expire")) continue
            val value = kv[1].trim().toLongOrNull() ?: return null
            when (key) {
                "upload" -> upload = value
                "download" -> download = value
                "total" -> total = value
                "expire" -> expire = value
            }
        }
        return if (total > 0L || expire > 0L) SubscriptionUserInfo(upload, download, total, expire) else null
    }

    fun getConfigFile(context: Context): File = ProfileStore.getConfigFile(context)

    internal fun newestProfileHistoryFile(context: Context, id: String): File? =
        ProfileStore.newestProfileHistoryFile(context, id)

    internal fun archiveKnownGoodVersion(context: Context, id: String) =
        ProfileStore.archiveKnownGoodVersion(context, id)

    fun restoreProfileFromHistory(context: Context, id: String, source: File): Result<Unit> =
        ProfileStore.restoreProfileFromHistory(context, id, source)

    fun rollbackSubscriptionUpdate(context: Context, id: String): Boolean =
        ProfileStore.rollbackSubscriptionUpdate(context, id)

    suspend fun downloadSubscription(
        context: Context,
        url: String,
        name: String
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        try {
            requireSecureRemoteUrl(url, SettingsManager(context).allowPrivateSubscriptionHosts)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", SUBSCRIPTION_USER_AGENT)
                .build()

            val downloaded = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP 错误: ${response.code} ${response.message}")
                }
                val userInfo = parseSubscriptionUserInfo(
                    response.header("subscription-userinfo") ?: response.header("Subscription-Userinfo")
                )
                val body = readBodyCapped(response, MAX_SUBSCRIPTION_BYTES, "响应内容为空")
                val resolvedYaml = ConfigValidation.resolveSubscriptionYaml(body)
                    ?: error("响应内容不是有效的配置或节点数据 (疑似网页、拦截错误页或不受支持的 Base64 节点列表)")
                val resolvedName = extractProfileName(name, response, resolvedYaml, url)
                Triple(resolvedYaml, resolvedName, userInfo)
            }

            val (resolvedYaml, resolvedName, userInfo) = downloaded
            validateConfigStructure(resolvedYaml)?.let { reason ->
                error("订阅配置结构有误：$reason")
            }

            val id = UUID.randomUUID().toString()
            val file = ProfileStore.profileFile(context, id) ?: error("无法为订阅创建安全的文件路径")
            writeTextAtomic(file, resolvedYaml)
            val item = ProfileItem(
                id = id,
                name = resolvedName,
                url = url,
                file = file,
                lastUpdated = System.currentTimeMillis(),
                uploadBytes = userInfo?.upload ?: 0L,
                downloadBytes = userInfo?.download ?: 0L,
                totalBytes = userInfo?.total ?: 0L,
                expireTimestamp = userInfo?.expire ?: 0L
            )
            ProfileStore.saveProfileMetadata(context, item)
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun updateSubscription(
        context: Context,
        id: String,
        name: String,
        url: String
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        profileLock(id).withLock {
            updateSubscriptionLocked(context, id, name, url)
        }
    }

    private fun updateSubscriptionLocked(
        context: Context,
        id: String,
        name: String,
        url: String
    ): Result<ProfileItem> = try {
        requireSecureRemoteUrl(url, SettingsManager(context).allowPrivateSubscriptionHosts)
        val file = ProfileStore.profileFile(context, id) ?: error("无法为订阅创建安全的文件路径")
        val stagedFile = ProfileStore.profileStagedFile(context, id) ?: error("无法为订阅创建安全的文件路径")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SUBSCRIPTION_USER_AGENT)
            .build()

        val (resolvedYaml, userInfo) = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP 错误: ${response.code} ${response.message}")
            }
            val info = parseSubscriptionUserInfo(
                response.header("subscription-userinfo") ?: response.header("Subscription-Userinfo")
            )
            val body = readBodyCapped(response, MAX_SUBSCRIPTION_BYTES, "响应内容为空")
            val yaml = ConfigValidation.resolveSubscriptionYaml(body)
                ?: error("更新失败：响应内容不是有效的配置或节点数据 (疑似网页、拦截错误页或不受支持的 Base64 节点列表)")
            yaml to info
        }

        val item = try {
            writeTextAtomic(stagedFile, resolvedYaml)
            val problem = validateConfigStructure(resolvedYaml)
            if (problem != null) {
                error("更新已取消：$problem；正在运行的旧配置未受影响")
            }
            ProfileStore.commitSubscriptionUpdate(
                context = context,
                id = id,
                fallbackName = name,
                url = url,
                file = file,
                stagedFile = stagedFile,
                backupFile = ProfileStore.profileBackupFile(context, id),
                userInfo = userInfo
            )
        } finally {
            if (stagedFile.exists()) stagedFile.delete()
        }
        Log.i(TAG, "Successfully overwritten existing profile id=$id (${item.name})")
        Result.success(item)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    fun saveLocalProfile(context: Context, name: String, content: String): ProfileItem {
        val id = UUID.randomUUID().toString()
        val file = ProfileStore.profileFile(context, id) ?: error("无法为本地配置创建安全的文件路径")
        writeTextAtomic(file, content)
        val item = ProfileItem(id, name, null, file, System.currentTimeMillis())
        ProfileStore.saveProfileMetadata(context, item)
        return item
    }

    fun importProfileFromUri(context: Context, uri: Uri): Result<ProfileItem> = runCatching {
        val displayName = readImportDisplayNameAndCheckSize(context, uri)
        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            readStreamCapped(stream, STREAM_BUFFER_BYTES, MAX_SUBSCRIPTION_BYTES, "配置文件内容为空")
        } ?: error("无法读取文件内容")
        val resolvedYaml = ConfigValidation.resolveSubscriptionYaml(content)
            ?: error("所选文件不是有效的 Clash / Mihomo YAML 配置")
        validateConfigStructure(resolvedYaml)?.let { reason ->
            error("配置文件结构有误：$reason")
        }
        saveLocalProfile(context, displayName, resolvedYaml)
    }

    private fun readImportDisplayNameAndCheckSize(context: Context, uri: Uri): String {
        var displayName = "本地配置-${System.currentTimeMillis() % FALLBACK_NAME_MOD}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                val fileSize = cursor.getLong(sizeIdx)
                if (fileSize > MAX_SUBSCRIPTION_BYTES) {
                    error("配置文件过大 (${fileSize / BYTES_PER_MB} MB, 上限 ${MAX_SUBSCRIPTION_BYTES / BYTES_PER_MB} MB)")
                }
            }
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx != -1) {
                val fName = cursor.getString(nameIdx)
                if (!fName.isNullOrBlank()) {
                    val trimmed = fName.removeSuffix(".yaml").removeSuffix(".yml").removeSuffix(".txt")
                    ConfigSanitizer.stripControlAndLineBreaks(trimmed).trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { displayName = it }
                }
            }
        }
        return displayName
    }

    fun invalidateProfilesCache() = ProfileStore.invalidateProfilesCache()

    fun getProfiles(context: Context, forceReload: Boolean = false): List<ProfileItem> =
        ProfileStore.getProfiles(context, forceReload)

    fun saveProfileOrder(context: Context, orderIds: List<String>) =
        ProfileStore.saveProfileOrder(context, orderIds)

    fun updateProfile(context: Context, item: ProfileItem) =
        ProfileStore.saveProfileMetadata(context, item)

    fun deleteProfile(context: Context, id: String) {
        ProfileStore.deleteProfile(context, id)
        profileLocks.remove(id)
    }

    fun migrateLegacyScriptScopes(context: Context) =
        ProfileStore.migrateLegacyScriptScopes(context)

    internal fun buildDnsRejectRule(): String {
        val v4 = "${TunConfig.IPV4_PORTAL}/32"
        val v6 = "${TunConfig.IPV6_PORTAL}/128"
        return "AND,((OR,((NETWORK,UDP),(NETWORK,TCP))),(DST-PORT,53)," +
            "(NOT,((IP-CIDR,$v4,no-resolve))),(NOT,((IP-CIDR,$v6,no-resolve)))),REJECT"
    }

    internal fun sanitizeUserConfig(
        raw: String,
        overrides: ConfigOverrides = ConfigOverrides(),
        blockThirdPartyDns: Boolean = false
    ): String = ConfigValidation.sanitizeUserConfig(raw, overrides, blockThirdPartyDns)

    data class RuntimeHeaderOptions(
        val tunnelMode: String,
        val logLevel: String,
        val ipv6Enabled: Boolean,
        val allowLan: Boolean,
        val lanPassword: String,
        val dnsIpv6Enabled: Boolean,
        val fakeIpEnabled: Boolean,
        val mixedPort: Int = 7890,
        val externalController: String? = null,
        val apiSecret: String? = null
    )

    internal fun buildLanAuthBlock(allowLan: Boolean, lanPassword: String): String {
        val cleanPassword = ConfigSanitizer.stripControlAndLineBreaks(lanPassword)
        return if (!allowLan || cleanPassword.isBlank()) {
            ""
        } else {
            val safePassword = ConfigSanitizer.escapeYamlDoubleQuoted(cleanPassword)
            listOf(
                "authentication:",
                "  - \"nexus:$safePassword\"",
                "skip-auth-prefixes:",
                "  - 127.0.0.1",
                "  - ::1"
            ).joinToString("\n") { "            $it" } + "\n"
        }
    }

    internal fun buildRuntimeHeader(
        options: RuntimeHeaderOptions,
        nameserversYaml: String,
        fallbackYaml: String
    ): String {
        val safeMode = when (options.tunnelMode.lowercase(Locale.ROOT).trim()) {
            "global" -> "global"
            "direct" -> "direct"
            else -> "rule"
        }
        val safeLogLevel = when (options.logLevel.lowercase(Locale.ROOT).trim()) {
            "silent" -> "silent"
            "error" -> "error"
            "warning" -> "warning"
            "debug" -> "debug"
            else -> "info"
        }
        val safeMixedPort = options.mixedPort.coerceIn(MIN_MIXED_PORT, MAX_LISTEN_PORT)
        val lanAuthYaml = buildLanAuthBlock(options.allowLan, options.lanPassword)
        val controllerYaml = buildControllerBlock(options.externalController, options.apiSecret)
        val safeNameserversYaml = ConfigSanitizer.sanitizeDnsYamlBlock(
            nameserversYaml,
            ConfigSanitizer.DEFAULT_NAMESERVERS
        )
        val safeFallbackYaml = ConfigSanitizer.sanitizeDnsYamlBlock(
            fallbackYaml,
            ConfigSanitizer.DEFAULT_FALLBACK_SERVERS
        )
        return """
            # Mihomo Android Injected Runtime Config
            mode: $safeMode
            log-level: $safeLogLevel
            ipv6: ${options.ipv6Enabled}
            mixed-port: $safeMixedPort
            allow-lan: ${options.allowLan}
$lanAuthYaml$controllerYaml
            geodata-mode: false
            geo-auto-update: false
            geox-url:
              geoip: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb"
              geosite: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat"
              mmdb: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb"
            dns:
              enable: true
              listen: 127.0.0.1:1053
              ipv6: ${options.dnsIpv6Enabled}
              enhanced-mode: ${if (options.fakeIpEnabled) "fake-ip" else "redir-host"}
              fake-ip-range: 198.18.0.1/16
              default-nameserver:
                - 223.5.5.5
                - 119.29.29.29
              nameserver:
$safeNameserversYaml
              fallback:
$safeFallbackYaml
              fake-ip-filter:
                - "*.lan"
                - "*.local"
                - "localhost.ptlogin2.qq.com"
                - "*.connectivitycheck.android.com"
                - "*.connectivitycheck.gstatic.com"
                - "connectivitycheck.gstatic.com"
                - "connectivitycheck.android.com"
                - "clients3.google.com"
                - "+.msftconnecttest.com"
                - "+.msftncsi.com"
                - "+.qualcomm.com"
                - "+.miui.com"
                - "+.xiaomi.com"

        """.trimIndent()
    }

    internal fun buildControllerBlock(listenAt: String?, secret: String?): String {
        val safeListen = ConfigSanitizer.sanitizeControllerListen(listenAt)
        val safeSecret = secret?.let(ConfigSanitizer::stripControlAndLineBreaks)?.takeIf { it.isNotBlank() }
        return if (safeListen == null || safeSecret == null) {
            ""
        } else {
            listOf(
                "external-controller: $safeListen",
                "secret: \"${ConfigSanitizer.escapeYamlDoubleQuoted(safeSecret)}\""
            ).joinToString("\n") { "            $it" } + "\n"
        }
    }

    suspend fun prepareConfig(context: Context, sourceFile: File?): File {
        return prepareLock.withLock {
            prepareConfigLocked(context, sourceFile)
        }
    }

    private suspend fun prepareConfigLocked(context: Context, sourceFile: File?): File {
        val settings = SettingsManager(context)
        val activeProfile = findActiveProfile(context, sourceFile)
        val nameserversYaml = if (activeProfile?.dnsServersOverride.isNullOrEmpty()) {
            ConfigSanitizer.formatDnsSettingYaml(settings.dnsNameservers, ConfigSanitizer.DEFAULT_NAMESERVERS)
        } else {
            ConfigSanitizer.formatDnsServersYaml(
                activeProfile.dnsServersOverride,
                ConfigSanitizer.DEFAULT_NAMESERVERS
            )
        }
        val fallbackYaml = ConfigSanitizer.formatDnsSettingYaml(
            settings.dnsFallbackServers,
            ConfigSanitizer.DEFAULT_FALLBACK_SERVERS
        )

        ApiController.acquire()
        if (!ApiController.isReady) {
            LogRepository.addLog(
                "warning",
                "本机 9090-9199 端口均被占用，external-controller 不可用；请求记录与内核状态将为空"
            )
        }

        val header = buildRuntimeHeader(
            RuntimeHeaderOptions(
                tunnelMode = settings.tunnelMode,
                logLevel = settings.logLevel,
                ipv6Enabled = settings.ipv6Enabled,
                allowLan = settings.allowLan,
                lanPassword = settings.allowLanPassword,
                dnsIpv6Enabled = settings.dnsIpv6Enabled,
                fakeIpEnabled = settings.fakeIpEnabled,
                mixedPort = settings.mixedPort,
                externalController = if (ApiController.isReady) "127.0.0.1:${ApiController.port}" else null,
                apiSecret = ApiController.secret.ifBlank { null }
            ),
            nameserversYaml,
            fallbackYaml
        )

        val userBody = buildUserConfigBody(context, settings, sourceFile, activeProfile)
        val finalContent = "$header\n$userBody"
        val targetFile = getConfigFile(context)
        writeTextAtomic(targetFile, finalContent)
        syncConfigToClashHome(context, finalContent)
        return targetFile
    }
}

private suspend fun executeProfileScriptsSafely(
    context: Context,
    rawYaml: String,
    targetIds: List<String>
): String {
    return try {
        val scriptResult = ConfigScriptEngine.executeScripts(
            context = context,
            rawYaml = rawYaml,
            targetScriptIds = targetIds
        )
        if (scriptResult.hasFailures) {
            Log.w(
                TAG,
                "Config scripts partially failed (${scriptResult.failed.size}/${scriptResult.total}); " +
                    "falling back to the original config"
            )
        }
        scriptResult.yaml
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Log.w(TAG, "Config script execution crashed; falling back to original config", t)
        LogRepository.addLog(
            "warning",
            "配置脚本执行异常，已安全回退到原始配置：${t.message ?: t.javaClass.simpleName}"
        )
        rawYaml
    }
}

private fun findActiveProfile(context: Context, sourceFile: File?): ProfileItem? {
    if (sourceFile == null) return null
    return ConfigManager.getProfiles(context).find {
        it.file.absolutePath == sourceFile.absolutePath ||
            it.file.name == sourceFile.name ||
            it.id == sourceFile.nameWithoutExtension
    }
}

private suspend fun buildUserConfigBody(
    context: Context,
    settings: SettingsManager,
    sourceFile: File?,
    activeProfile: ProfileItem?
): String {
    if (sourceFile == null || !sourceFile.exists()) {
        Log.i(TAG, "Prepared default fallback config")
        return """
            proxies: []
            proxy-groups:
              - name: GLOBAL
                type: select
                proxies:
                  - DIRECT
            rules:
              - MATCH,DIRECT
        """.trimIndent()
    }

    val maxLocalBytes = 20L * 1024 * 1024
    if (sourceFile.length() > maxLocalBytes) {
        error("本地配置文件过大 (${sourceFile.length() / (1024 * 1024)} MB, 上限 20 MB)")
    }
    val originalContent = sourceFile.readText()
    val targetIds = if (settings.scriptingEnabled && activeProfile != null && activeProfile.scriptEnabled) {
        activeProfile.scriptIds
    } else {
        emptyList()
    }
    val rawContent = if (targetIds.isNotEmpty()) {
        Log.i(
            TAG,
            "Applying config rewrite scripts for profile: ${activeProfile?.name ?: sourceFile.name} " +
                "(${targetIds.size} scripts)..."
        )
        executeProfileScriptsSafely(context, originalContent, targetIds)
    } else {
        originalContent
    }
    val sanitized = ConfigManager.sanitizeUserConfig(
        rawContent,
        overrides = ConfigOverrides(
            prependRules = activeProfile?.prependRules ?: emptyList(),
            hostsEntries = activeProfile?.hostsEntries ?: emptyList(),
            dnsServersOverride = activeProfile?.dnsServersOverride ?: emptyList()
        ),
        blockThirdPartyDns = settings.blockThirdPartyDns
    )
    Log.i(TAG, "Prepared synthesized config with user profile: ${sourceFile.name}")
    return sanitized
}

private fun syncConfigToClashHome(context: Context, finalContent: String) {
    try {
        com.github.kr328.clash.core.bridge.Bridge.init(context)
        val clashHomeConfig = context.filesDir.resolve("clash").apply { mkdirs() }.resolve("config.yaml")
        ConfigManager.writeTextAtomic(clashHomeConfig, finalContent)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to sync Clash home config.yaml", t)
    }
}
