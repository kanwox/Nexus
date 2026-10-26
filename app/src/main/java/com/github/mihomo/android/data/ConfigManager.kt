package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
 * - dnsServersOverride lives on [ProfileItem] and replaces the injected header's nameservers.
 * - [dnsServersOverride] replaces the upstream nameserver list when non-empty.
 * Applying them inside the YAML sanitizer means one parse/dump pass covers stripping and
 * overriding, and the overrides cannot be defeated by key quoting either.
 */
data class ConfigOverrides(
    val prependRules: List<String> = emptyList(),
    val hostsEntries: List<String> = emptyList(),
)

data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
)

object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // A server that drips one byte per read timeout would otherwise stall an auto-update
        // channel indefinitely; cap the total call duration as well.
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Writes via a temp file + rename so a crash mid-write can never leave a truncated file. */
    internal fun writeTextAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }

    /** Reads a response body fully but refuses payloads above [maxBytes] instead of OOMing. */
    internal fun readBodyCapped(response: okhttp3.Response, maxBytes: Long, emptyError: String): String {
        val body = response.body ?: error(emptyError)
        val declared = body.contentLength()
        if (declared > maxBytes) error("内容过大 (${declared / 1024 / 1024} MB, 上限 ${maxBytes / 1024 / 1024} MB)")
        // Chunked responses report -1: coerce to a sane initial buffer size instead of
        // constructing StringWriter with a negative capacity (which throws).
        val out = java.io.StringWriter(declared.coerceIn(0, 1024 * 1024).toInt())
        body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) error("内容超过 ${maxBytes / 1024 / 1024} MB 上限")
                out.write(buf, 0, n)
            }
        }
        val text = out.toString()
        if (text.isBlank()) error(emptyError)
        return text
    }

    /** Top-level config keys that only the injected runtime header may set. */
    private val PROTECTED_TOP_LEVEL_KEYS = setOf(
        "mode", "log-level", "ipv6", "mixed-port", "port", "socks-port",
        "redir-port", "tproxy-port", "allow-lan", "bind-address",
        "external-controller", "external-controller-cors", "secret",
        "authentication", "skip-auth-prefixes", "lan-allowed-ips", "lan-disallowed-ips",
        "external-ui", "external-ui-name", "external-ui-url",
        "tun", "dns", "interface-name", "routing-mark",
        "geodata-mode", "geo-auto-update", "geox-url"
    )

    fun parseSubscriptionUserInfo(header: String?): SubscriptionUserInfo? {
        if (header.isNullOrBlank()) return null
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        for (part in header.split(";")) {
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val k = kv[0].trim().lowercase()
                val v = kv[1].trim().toLongOrNull() ?: 0L
                when (k) {
                    "upload" -> upload = v
                    "download" -> download = v
                    "total" -> total = v
                    "expire" -> expire = v
                }
            }
        }
        return if ((total > 0L) || (expire > 0L)) SubscriptionUserInfo(upload, download, total, expire) else null
    }

    internal fun extractProfileName(name: String?, response: okhttp3.Response?, body: String, url: String): String {
        if (!name.isNullOrBlank()) return name.trim()

        // 1. Check URL Fragment (#Name)
        runCatching {
            val uri = java.net.URI(url)
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank()) {
                val decoded = runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
                if (decoded.isNotBlank()) return decoded.trim()
            }
        }

        // 2. Check URL Query param (name=... or title=...)
        runCatching {
            val uri = java.net.URI(url)
            val query = uri.query
            if (!query.isNullOrBlank()) {
                for (param in query.split("&")) {
                    val pair = param.split("=")
                    if ((pair.size == 2) && (pair[0].equals("name", ignoreCase = true) || pair[0].equals("title", ignoreCase = true))) {
                        val decoded = runCatching { java.net.URLDecoder.decode(pair[1], "UTF-8") }.getOrDefault(pair[1])
                        if (decoded.isNotBlank()) return decoded.trim()
                    }
                }
            }
        }

        // 3. Check response header: profile-title
        val profileTitle = response?.header("profile-title") ?: response?.header("Profile-Title")
        if (!profileTitle.isNullOrBlank()) {
            val decoded = runCatching { java.net.URLDecoder.decode(profileTitle, "UTF-8") }.getOrDefault(profileTitle)
            if (decoded.isNotBlank()) return decoded.trim()
        }

        // 4. Check Content-Disposition (filename*=UTF-8''... or filename=...)
        val contentDisp = response?.header("content-disposition") ?: response?.header("Content-Disposition")
        if (!contentDisp.isNullOrBlank()) {
            if (contentDisp.contains("filename*=", ignoreCase = true)) {
                val rawEncoded = contentDisp.substringAfter("filename*=").substringAfter("''").trim('"', '\'', ' ')
                val decoded = runCatching { java.net.URLDecoder.decode(rawEncoded, "UTF-8") }.getOrDefault(rawEncoded)
                val clean = decoded.removeSuffix(".yaml").removeSuffix(".yml")
                if (clean.isNotBlank()) return clean.trim()
            }
            if (contentDisp.contains("filename=", ignoreCase = true)) {
                val fname = contentDisp.substringAfter("filename=").trim('"', '\'', ' ')
                val cleanFname = fname.removeSuffix(".yaml").removeSuffix(".yml")
                if (cleanFname.isNotBlank()) return cleanFname.trim()
            }
        }

        // 5. Check top 25 lines of YAML body for title/name comment
        val lines = body.lineSequence().take(25)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val afterHash = trimmed.removePrefix("#").trim()
                val prefixes = listOf("name:", "title:", "名称:", "订阅名称:")
                if (prefixes.any { afterHash.startsWith(it, ignoreCase = true) }) {
                    val n = afterHash.substringAfter(":").trim()
                    if (n.isNotBlank()) return n
                }
            }
        }

        // 6. Hostname fallback
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            return host.removePrefix("www.").substringBefore(":")
        }

        return "订阅-${System.currentTimeMillis() % 1000}"
    }

    fun getConfigFile(context: Context): File {
        return context.filesDir.resolve("config.yaml")
    }

    fun getProfilesDir(context: Context): File {
        return context.filesDir.resolve("profiles").apply { mkdirs() }
    }

    suspend fun downloadSubscription(
        context: Context,
        url: String,
        name: String
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        runCatching {
            requireSecureRemoteUrl(url, SettingsManager(context).allowPrivateSubscriptionHosts)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ClashMeta/1.18.0 Mihomo/1.18.0 Clash/1.0.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP 错误: ${response.code} ${response.message}")
                }

                val userInfo = parseSubscriptionUserInfo(
                    response.header("subscription-userinfo") ?: response.header("Subscription-Userinfo")
                )

                val body = readBodyCapped(response, MAX_SUBSCRIPTION_BYTES, "响应内容为空")
                val id = java.util.UUID.randomUUID().toString()
                val file = getProfilesDir(context).resolve("$id.yaml")
                writeTextAtomic(file, body)

                val resolvedName = extractProfileName(name, response, body, url)
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
                saveProfileMetadata(context, item)
                item
            }
        }
    }

    suspend fun updateSubscription(
        context: Context,
        id: String,
        name: String,
        url: String
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        runCatching {
            requireSecureRemoteUrl(url, SettingsManager(context).allowPrivateSubscriptionHosts)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ClashMeta/1.18.0 Mihomo/1.18.0 Clash/1.0.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP 错误: ${response.code} ${response.message}")
                }

                val userInfo = parseSubscriptionUserInfo(
                    response.header("subscription-userinfo") ?: response.header("Subscription-Userinfo")
                )

                // A capped, atomic replacement: a failed or oversized download must never
                // destroy the currently working profile file.
                val body = readBodyCapped(response, MAX_SUBSCRIPTION_BYTES, "响应内容为空")
                // Overwrite the EXISTING file without creating a duplicate ID!
                val file = getProfilesDir(context).resolve("$id.yaml")
                writeTextAtomic(file, body)

                val existing = getProfiles(context).find { it.id == id }
                val item = ProfileItem(
                    id = id,
                    name = name,
                    url = url,
                    file = file,
                    lastUpdated = System.currentTimeMillis(),
                    scriptEnabled = existing?.scriptEnabled ?: false,
                    scriptIds = existing?.scriptIds ?: emptyList(),
                    autoUpdate = existing?.autoUpdate ?: false,
                    autoUpdateInterval = existing?.autoUpdateInterval ?: 1440,
                    uploadBytes = userInfo?.upload ?: (existing?.uploadBytes ?: 0L),
                    downloadBytes = userInfo?.download ?: (existing?.downloadBytes ?: 0L),
                    totalBytes = userInfo?.total ?: (existing?.totalBytes ?: 0L),
                    expireTimestamp = userInfo?.expire ?: (existing?.expireTimestamp ?: 0L),
                    prependRules = existing?.prependRules ?: emptyList(),
                    hostsEntries = existing?.hostsEntries ?: emptyList(),
                    dnsServersOverride = existing?.dnsServersOverride ?: emptyList()
                )
                saveProfileMetadata(context, item)
                Log.i(TAG, "Successfully overwritten existing profile id=$id ($name)")
                item
            }
        }
    }

    fun saveLocalProfile(context: Context, name: String, content: String): ProfileItem {
        val id = java.util.UUID.randomUUID().toString()
        val file = getProfilesDir(context).resolve("$id.yaml")
        writeTextAtomic(file, content)
        val item = ProfileItem(id, name, null, file, System.currentTimeMillis())
        saveProfileMetadata(context, item)
        return item
    }

    fun importProfileFromUri(context: Context, uri: android.net.Uri): Result<ProfileItem> = runCatching {
        val contentResolver = context.contentResolver
        var displayName = "本地配置-${System.currentTimeMillis() % 1000}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1) {
                    val fName = cursor.getString(nameIdx)
                    if (!fName.isNullOrBlank()) {
                        displayName = fName.removeSuffix(".yaml").removeSuffix(".yml").removeSuffix(".txt")
                    }
                }
            }
        }
        val content = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("无法读取文件内容")
        saveLocalProfile(context, displayName, content)
    }

    @Volatile
    private var cachedProfiles: List<ProfileItem>? = null

    fun invalidateProfilesCache() {
        synchronized(storeLock) { cachedProfiles = null }
        ProfileParser.clearCache()
    }

    @Serializable
    private data class ProfileMeta(
        val name: String = "",
        val url: String? = null,
        val updated: Long = 0L,
        val scriptEnabled: Boolean = false,
        val scriptIds: List<String> = emptyList(),
        val autoUpdate: Boolean = false,
        val autoUpdateInterval: Int = 1440,
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val expireTimestamp: Long = 0L,
        val prependRules: List<String> = emptyList(),
        val hostsEntries: List<String> = emptyList(),
        val dnsServersOverride: List<String> = emptyList()
    )

    /** Single durable store for all profile metadata plus the manual ordering. */
    @Serializable
    private data class ProfilesStore(
        val profiles: Map<String, ProfileMeta> = emptyMap(),
        val order: List<String> = emptyList()
    )

    private val storeLock = Any()
    private val storeJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var storeCache: ProfilesStore? = null

    private fun storeFile(context: Context): File = context.filesDir.resolve("profiles_meta.json")

    private fun storeBackupFile(context: Context): File =
        context.filesDir.resolve("profiles_meta.json.bak")

    private fun loadStore(context: Context): ProfilesStore {
        storeCache?.let { return it }
        synchronized(storeLock) {
            storeCache?.let { return it }
            storeCache = readStoreFromDisk(context)
            return storeCache!!
        }
    }

    private fun readStoreFromDisk(context: Context): ProfilesStore {
        val file = storeFile(context)
        if (!file.exists()) return migrateLegacyPrefsStore(context)
        val parsed = runCatching { storeJson.decodeFromString<ProfilesStore>(file.readText()) }
        if (parsed.isSuccess) return parsed.getOrThrow()
        Log.w(TAG, "Failed to read profiles metadata store, trying backup", parsed.exceptionOrNull())
        val backup = runCatching { storeJson.decodeFromString<ProfilesStore>(storeBackupFile(context).readText()) }
        if (backup.isSuccess) {
            Log.w(TAG, "Recovered profiles metadata from backup")
            return backup.getOrThrow()
        }
        Log.w(TAG, "Profiles metadata unrecoverable; starting an empty store")
        return ProfilesStore()
    }

    /** One-time migration from the legacy per-key SharedPreferences metadata store. */
    private fun migrateLegacyPrefsStore(context: Context): ProfilesStore {
        val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)
        val metas = mutableMapOf<String, ProfileMeta>()
        val files = getProfilesDir(context).listFiles { f -> f.extension == "yaml" } ?: emptyArray()
        for (file in files) {
            val id = file.nameWithoutExtension
            metas[id] = ProfileMeta(
                name = prefs.getString("${id}_name", null) ?: id,
                url = prefs.getString("${id}_url", null),
                updated = prefs.getLong("${id}_updated", file.lastModified()),
                scriptEnabled = prefs.getBoolean("${id}_script_enabled", false),
                scriptIds = prefs.getString("${id}_script_ids", "")
                    ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                autoUpdate = prefs.getBoolean("${id}_auto_update", false),
                autoUpdateInterval = prefs.getInt("${id}_auto_update_interval", 1440),
                uploadBytes = prefs.getLong("${id}_upload_bytes", 0L),
                downloadBytes = prefs.getLong("${id}_download_bytes", 0L),
                totalBytes = prefs.getLong("${id}_total_bytes", 0L),
                expireTimestamp = prefs.getLong("${id}_expire_timestamp", 0L)
            )
        }
        val order = prefs.getString("custom_profile_order", null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val store = ProfilesStore(profiles = metas, order = order)
        val written = runCatching { writeTextAtomic(storeFile(context), storeJson.encodeToString(store)) }
            .onFailure { Log.w(TAG, "Failed to persist migrated profiles metadata", it) }
            .isSuccess
        // Only drop the legacy keys once the JSON store is durable on disk.
        if (written) {
            prefs.edit().clear().apply()
        }
        return store
    }

    private fun persistStore(context: Context, store: ProfilesStore) {
        synchronized(storeLock) {
            storeCache = store
            // The cached profile listing is derived from this store (plus the directory
            // scan): every store mutation must drop it, otherwise a freshly imported or
            // downloaded profile stays invisible until the process restarts.
            cachedProfiles = null
            runCatching {
                val json = storeJson.encodeToString(store)
                writeTextAtomic(storeFile(context), json)
                // Keep the previous good copy around so a corrupted main file can be recovered.
                writeTextAtomic(storeBackupFile(context), json)
            }.onFailure { Log.w(TAG, "Failed to persist profiles metadata", it) }
        }
    }

    fun getProfiles(context: Context, forceReload: Boolean = false): List<ProfileItem> {
        if (!forceReload) {
            cachedProfiles?.let { return it }
        }
        // Hold the store lock across the whole listing so a concurrent delete/save cannot
        // slip in between the directory scan and the cache write, which resurrected ghost
        // entries in the UI.
        synchronized(storeLock) {
            if (!forceReload) {
                cachedProfiles?.let { return it }
            }

            val dir = getProfilesDir(context)
            val files = dir.listFiles { f -> f.extension == "yaml" } ?: emptyArray()
            val store = loadStore(context)

            val items = files.map { file ->
                val id = file.nameWithoutExtension
                val meta = store.profiles[id] ?: ProfileMeta(name = id, updated = file.lastModified())
                ProfileItem(
                    id = id,
                    name = meta.name,
                    url = meta.url,
                    file = file,
                    lastUpdated = if (meta.updated > 0L) meta.updated else file.lastModified(),
                    scriptEnabled = meta.scriptEnabled,
                    scriptIds = meta.scriptIds,
                    autoUpdate = meta.autoUpdate,
                    autoUpdateInterval = meta.autoUpdateInterval,
                    uploadBytes = meta.uploadBytes,
                    downloadBytes = meta.downloadBytes,
                    totalBytes = meta.totalBytes,
                    expireTimestamp = meta.expireTimestamp,
                    prependRules = meta.prependRules,
                    hostsEntries = meta.hostsEntries,
                    dnsServersOverride = meta.dnsServersOverride
                )
            }

            val result = if (store.order.isNotEmpty()) {
                items.sortedWith(compareBy {
                    val idx = store.order.indexOf(it.id)
                    if (idx != -1) idx else Int.MAX_VALUE
                })
            } else {
                items.sortedByDescending { it.lastUpdated }
            }
            cachedProfiles = result
            return result
        }
    }

    fun saveProfileOrder(context: Context, orderIds: List<String>) {
        synchronized(storeLock) {
            val store = loadStore(context)
            persistStore(context, store.copy(order = orderIds))
        }
        invalidateProfilesCache()
    }

    fun updateProfile(context: Context, item: ProfileItem) {
        saveProfileMetadata(context, item)
    }

    @Suppress("unused")
    fun saveProfileContent(context: Context, id: String, content: String): Boolean {
        return runCatching {
            val file = getProfilesDir(context).resolve("$id.yaml")
            writeTextAtomic(file, content)
            synchronized(storeLock) {
                val store = loadStore(context)
                store.profiles[id]?.let { meta ->
                    persistStore(context, store.copy(profiles = store.profiles + (id to meta.copy(updated = System.currentTimeMillis()))))
                }
            }
            invalidateProfilesCache()
            true
        }.getOrDefault(defaultValue = false)
    }

    private fun saveProfileMetadata(context: Context, item: ProfileItem) {
        synchronized(storeLock) {
            val store = loadStore(context)
            val meta = ProfileMeta(
                name = item.name,
                url = item.url,
                updated = item.lastUpdated,
                scriptEnabled = item.scriptEnabled,
                scriptIds = item.scriptIds,
                autoUpdate = item.autoUpdate,
                autoUpdateInterval = item.autoUpdateInterval,
                uploadBytes = item.uploadBytes,
                downloadBytes = item.downloadBytes,
                totalBytes = item.totalBytes,
                expireTimestamp = item.expireTimestamp,
                prependRules = item.prependRules,
                hostsEntries = item.hostsEntries,
                dnsServersOverride = item.dnsServersOverride
            )
            persistStore(context, store.copy(profiles = store.profiles + (item.id to meta)))
        }
    }

    fun deleteProfile(context: Context, id: String) {
        getProfilesDir(context).resolve("$id.yaml").delete()
        synchronized(storeLock) {
            val store = loadStore(context)
            persistStore(
                context,
                store.copy(profiles = store.profiles - id, order = store.order.filter { it != id })
            )
        }
        invalidateProfilesCache()
    }

    /**
     * Runtime-critical top-level keys belong to the injected header. A subscription must not be
     * able to reintroduce or override them, so they are removed structurally after parsing: text
     * matching is trivially defeated by quoting a key (`"external-controller": 0.0.0.0:9090`).
     *
     * The same single parse/dump pass also applies the structured [overrides] (rule prepending
     * and hosts merging) and the optional plaintext-DNS reject rule, so profile overrides get
     * the same "cannot be defeated by quoting" guarantee as the key stripping.
     */
    internal fun sanitizeUserConfig(
        raw: String,
        overrides: ConfigOverrides = ConfigOverrides(),
        blockThirdPartyDns: Boolean = false
    ): String {
        return try {
            // Shared, hardened SnakeYAML setup: 100 MB code point limit (the default 3 MB
            // silently rejected large subscriptions) and YAML 1.2 booleans, so nodes such as
            // `name: on` or a password `yes` survive the parse/dump round-trip.
            val yaml = YamlSupport.create()
            val parsed = yaml.load<Any?>(raw)
            if (parsed !is Map<*, *>) return raw

            val sanitized = LinkedHashMap<String, Any?>()
            for ((key, value) in parsed) {
                val keyStr = key?.toString() ?: continue
                if (keyStr.lowercase() in PROTECTED_TOP_LEVEL_KEYS) continue
                sanitized[keyStr] = value
            }

            if (blockThirdPartyDns) {
                val existingRules = (sanitized["rules"] as? List<*>) ?: emptyList<Any>()
                val dnsRejectRule =
                    "- AND,((NETWORK,UDP),(DST-PORT,53),(NOT,((DST-IP,172.19.0.2))),(NOT,((DST-IP,fdfe:dcba:9876::2)))),REJECT"
                sanitized["rules"] = listOf(dnsRejectRule) + existingRules
            }

            if (overrides.prependRules.isNotEmpty()) {
                val existingRules = (sanitized["rules"] as? List<*>) ?: emptyList<Any>()
                sanitized["rules"] = overrides.prependRules + existingRules
            }

            if (overrides.hostsEntries.isNotEmpty()) {
                val hosts = LinkedHashMap<String, Any?>()
                for (entry in overrides.hostsEntries) {
                    val domain = entry.substringBefore(':').trim()
                    val target = entry.substringAfter(':', "").trim()
                    if (domain.isNotBlank() && target.isNotBlank()) {
                        hosts[domain] = target
                    }
                }
                if (hosts.isNotEmpty()) {
                    val existingHosts = sanitized["hosts"] as? Map<*, *>
                    if (existingHosts != null) {
                        for ((key, value) in existingHosts) {
                            hosts.putIfAbsent(key?.toString() ?: continue, value)
                        }
                    }
                    sanitized["hosts"] = hosts
                }
            }

            yaml.dump(sanitized)
        } catch (e: Throwable) {
            Log.w(TAG, "Structural config sanitization failed, falling back to text filter", e)
            // The text fallback cannot apply the structured overrides or the plaintext-DNS
            // reject rule, so surface it instead of silently loading a partially sanitized
            // config. LogRepository deduplicates repeated identical lines.
            LogRepository.addLog(
                "warning",
                "配置 YAML 结构解析失败，已回退到文本清洗；本次不会应用前置规则、Hosts 覆写与第三方 DNS 拦截"
            )
            sanitizeUserConfigByText(raw)
        }
    }

    /** Last-resort filter for configs that are not valid YAML on their own. */
    private fun sanitizeUserConfigByText(raw: String): String {
        val builder = StringBuilder()
        var skipBlock = false
        raw.lineSequence().forEach { line ->
            val isTopLevel = line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") &&
                !line.startsWith("#") && !line.startsWith("-") && line.contains(":")
            if (isTopLevel) {
                val key = line.substringBefore(":").trim().trim('"', '\'')
                skipBlock = key.lowercase() in PROTECTED_TOP_LEVEL_KEYS
            }
            if (!skipBlock) {
                builder.appendLine(line)
            }
        }
        return builder.toString()
    }

    /** Options injected into the runtime header; pulled out so the template is unit-testable. */
    internal data class RuntimeHeaderOptions(
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

    /**
     * LAN sharing with an access password: HTTP/SOCKS clients must authenticate, while
     * loopback stays exempt so the phone itself is never prompted. These keys are in
     * PROTECTED_TOP_LEVEL_KEYS, so subscriptions cannot override the injected auth.
     *
     * Lines are pre-indented by 12 spaces to survive the header template's trimIndent(),
     * which computes its common indent AFTER interpolation (same trick as the DNS lists).
     */
    internal fun buildLanAuthBlock(allowLan: Boolean, lanPassword: String): String {
        if (!allowLan || lanPassword.isBlank()) return ""
        val safePassword = lanPassword.replace("\\", "\\\\").replace("\"", "\\\"")
        return listOf(
            "authentication:",
            "  - \"nexus:$safePassword\"",
            "skip-auth-prefixes:",
            "  - 127.0.0.1",
            "  - ::1"
        ).joinToString("\n") { "            $it" } + "\n"
    }

    internal fun buildRuntimeHeader(
        options: RuntimeHeaderOptions,
        nameserversYaml: String,
        fallbackYaml: String
    ): String {
        val lanAuthYaml = buildLanAuthBlock(options.allowLan, options.lanPassword)
        val controllerYaml = buildControllerBlock(options.externalController, options.apiSecret)
        return """
            # Mihomo Android Injected Runtime Config
            mode: ${options.tunnelMode}
            log-level: ${options.logLevel}
            ipv6: ${options.ipv6Enabled}
            mixed-port: ${options.mixedPort}
            allow-lan: ${options.allowLan}
$lanAuthYaml$controllerYaml
            geodata-mode: false
            geo-auto-update: false
            geox-url:
              geoip: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb"
              geosite: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat"
              mmdb: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/country.mmdb"
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
$nameserversYaml
              fallback:
$fallbackYaml
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

    /**
     * The external-controller block gives the app a structured view of the core (connections,
     * providers, memory) and must never be reachable from outside the device. Keys are in
     * PROTECTED_TOP_LEVEL_KEYS, so subscriptions cannot rebind the controller or replace the
     * random per-process secret. Lines are pre-indented to survive trimIndent().
     */
    internal fun buildControllerBlock(listenAt: String?, secret: String?): String {
        if (listenAt.isNullOrBlank() || secret.isNullOrBlank()) return ""
        return listOf(
            "external-controller: $listenAt",
            "secret: \"$secret\""
        ).joinToString("\n") { "            $it" } + "\n"
    }

    private val prepareLock = Mutex()

    suspend fun prepareConfig(context: Context, sourceFile: File?): File {
        // Serializes whole-file synthesis: a UI-triggered reload and a VPN start running
        // concurrently would otherwise interleave and one write could clobber the other's
        // header/body combination in config.yaml.
        return prepareLock.withLock {
            prepareConfigLocked(context, sourceFile)
        }
    }

    private suspend fun prepareConfigLocked(context: Context, sourceFile: File?): File {
        val settings = SettingsManager(context)
        val finalContent: String

        val formatDnsList = { raw: String, fallback: List<String> ->
            val items = raw.split(',', '\n')
                .asSequence()
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
                .toList()
            val effective = items.ifEmpty { fallback }
            effective.joinToString("\n") { "                - $it" }
        }

        val activeProfile = if (sourceFile != null) {
            getProfiles(context).find {
                it.file.absolutePath == sourceFile.absolutePath || it.file.name == sourceFile.name || it.id == sourceFile.nameWithoutExtension
            }
        } else null

        // Per-profile DNS override: when set it replaces the global upstream list entirely.
        val nameserversYaml = if (activeProfile?.dnsServersOverride.isNullOrEmpty()) {
            formatDnsList(
                settings.dnsNameservers,
                listOf("https://223.5.5.5/dns-query", "https://doh.pub/dns-query", "223.5.5.5", "119.29.29.29")
            )
        } else {
            activeProfile.dnsServersOverride.joinToString("\n") { "                - $it" }
        }
        val fallbackYaml = formatDnsList(
            settings.dnsFallbackServers,
            listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "1.1.1.1", "8.8.8.8")
        )

        // Bring the REST controller up with the same credentials on every config load.
        ApiController.acquire()
        if (!ApiController.isReady) {
            // Request records, DNS and the core status page all read from the controller;
            // without it those views stay silently empty, so make the cause visible once.
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

        if ((sourceFile != null) && sourceFile.exists()) {
            var rawContent = sourceFile.readText()
            // Empty scriptIds means "no scripts bound" (explicit binding wins); legacy
            // profiles that predate bindings are migrated in MihomoApplication.
            val targetIds = if (activeProfile != null && activeProfile.scriptEnabled) {
                activeProfile.scriptIds
            } else emptyList()
            if (targetIds.isNotEmpty()) {
                Log.i(TAG, "Applying config rewrite scripts for profile: ${activeProfile?.name ?: sourceFile.name} (${targetIds.size} scripts)...")
                rawContent = ConfigScriptEngine.executeScripts(
                    context = context,
                    rawYaml = rawContent,
                    targetScriptIds = targetIds
                )
            }
            val userContent = sanitizeUserConfig(
                rawContent,
                overrides = ConfigOverrides(
                    prependRules = activeProfile?.prependRules ?: emptyList(),
                    hostsEntries = activeProfile?.hostsEntries ?: emptyList()
                ),
                blockThirdPartyDns = settings.blockThirdPartyDns
            )
            // Cleanly prepend runtime settings without mangling proxies, rules or formatting
            finalContent = "$header\n$userContent"
            Log.i(TAG, "Prepared synthesized config with user profile: ${sourceFile.name}")
        } else {
            val defaultBody = """
                proxies: []
                proxy-groups:
                  - name: GLOBAL
                    type: select
                    proxies:
                      - DIRECT
                rules:
                  - MATCH,DIRECT
            """.trimIndent()
            finalContent = "$header\n$defaultBody"
            Log.i(TAG, "Prepared default fallback config")
        }

        val targetFile = getConfigFile(context)
        writeTextAtomic(targetFile, finalContent)
        runCatching {
            com.github.kr328.clash.core.bridge.Bridge.init(context)
            val clashHomeConfig = context.filesDir.resolve("clash").apply { mkdirs() }.resolve("config.yaml")
            // Write the same content to the core's home copy without reading it back.
            writeTextAtomic(clashHomeConfig, finalContent)
        }

        return targetFile
    }
}
