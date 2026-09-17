package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val expireTimestamp: Long = 0L
)

data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L
)

object ConfigManager {
    private const val TAG = "ConfigManager"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        return if (total > 0L || expire > 0L) SubscriptionUserInfo(upload, download, total, expire) else null
    }

    private fun extractProfileName(name: String?, response: okhttp3.Response?, body: String, url: String): String {
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
                    if (pair.size == 2 && (pair[0].equals("name", ignoreCase = true) || pair[0].equals("title", ignoreCase = true))) {
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
            requireSecureRemoteUrl(url)
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

                val body = response.body?.string() ?: error("响应内容为空")
                val id = java.util.UUID.randomUUID().toString()
                val file = getProfilesDir(context).resolve("$id.yaml")
                file.writeText(body)

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
            requireSecureRemoteUrl(url)
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

                val body = response.body?.string() ?: error("响应内容为空")
                // Overwrite the EXISTING file without creating a duplicate ID!
                val file = getProfilesDir(context).resolve("$id.yaml")
                file.writeText(body)

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
                    expireTimestamp = userInfo?.expire ?: (existing?.expireTimestamp ?: 0L)
                )
                saveProfileMetadata(context, item)
                Log.i(TAG, "Successfully overwritten existing profile id=$id ($name)")
                item
            }
        }
    }

    fun saveLocalProfile(context: Context, name: String, content: String): ProfileItem {
        val id = System.currentTimeMillis().toString()
        val file = getProfilesDir(context).resolve("$id.yaml")
        file.writeText(content)
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
        cachedProfiles = null
        ProfileParser.clearCache()
    }

    fun getProfiles(context: Context, forceReload: Boolean = false): List<ProfileItem> {
        if (!forceReload) {
            cachedProfiles?.let { return it }
        }

        val dir = getProfilesDir(context)
        val files = dir.listFiles { f -> f.extension == "yaml" } ?: emptyArray()
        val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)

        val items = files.map { file ->
            val id = file.nameWithoutExtension
            val name = prefs.getString("${id}_name", file.nameWithoutExtension) ?: file.nameWithoutExtension
            val url = prefs.getString("${id}_url", null)
            val updated = prefs.getLong("${id}_updated", file.lastModified())
            val scriptEnabled = prefs.getBoolean("${id}_script_enabled", false)
            val scriptIdsRaw = prefs.getString("${id}_script_ids", "") ?: ""
            val scriptIds = if (scriptIdsRaw.isBlank()) emptyList() else scriptIdsRaw.split(",").filter { it.isNotBlank() }
            val autoUpdate = prefs.getBoolean("${id}_auto_update", false)
            val autoUpdateInterval = prefs.getInt("${id}_auto_update_interval", 1440)
            val uploadBytes = prefs.getLong("${id}_upload_bytes", 0L)
            val downloadBytes = prefs.getLong("${id}_download_bytes", 0L)
            val totalBytes = prefs.getLong("${id}_total_bytes", 0L)
            val expireTimestamp = prefs.getLong("${id}_expire_timestamp", 0L)
            ProfileItem(
                id, name, url, file, updated, scriptEnabled, scriptIds, autoUpdate, autoUpdateInterval,
                uploadBytes, downloadBytes, totalBytes, expireTimestamp
            )
        }

        val orderRaw = prefs.getString("custom_profile_order", null)
        val result = if (!orderRaw.isNullOrBlank()) {
            val orderList = orderRaw.split(",").filter { it.isNotBlank() }
            items.sortedWith(compareBy {
                val idx = orderList.indexOf(it.id)
                if (idx != -1) idx else Int.MAX_VALUE
            })
        } else {
            items.sortedByDescending { it.lastUpdated }
        }
        cachedProfiles = result
        return result
    }

    fun saveProfileOrder(context: Context, orderIds: List<String>) {
        val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_profile_order", orderIds.joinToString(",")).apply()
        invalidateProfilesCache()
    }

    fun updateProfile(context: Context, item: ProfileItem) {
        saveProfileMetadata(context, item)
    }

    fun saveProfileContent(context: Context, id: String, content: String): Boolean {
        return runCatching {
            val file = getProfilesDir(context).resolve("$id.yaml")
            file.writeText(content)
            val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)
            prefs.edit().putLong("${id}_updated", System.currentTimeMillis()).apply()
            invalidateProfilesCache()
            true
        }.getOrDefault(false)
    }

    private fun saveProfileMetadata(context: Context, item: ProfileItem) {
        val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("${item.id}_name", item.name)
            .putString("${item.id}_url", item.url)
            .putLong("${item.id}_updated", item.lastUpdated)
            .putBoolean("${item.id}_script_enabled", item.scriptEnabled)
            .putString("${item.id}_script_ids", item.scriptIds.joinToString(","))
            .putBoolean("${item.id}_auto_update", item.autoUpdate)
            .putInt("${item.id}_auto_update_interval", item.autoUpdateInterval)
            .putLong("${item.id}_upload_bytes", item.uploadBytes)
            .putLong("${item.id}_download_bytes", item.downloadBytes)
            .putLong("${item.id}_total_bytes", item.totalBytes)
            .putLong("${item.id}_expire_timestamp", item.expireTimestamp)
            .apply()
        invalidateProfilesCache()
    }

    fun deleteProfile(context: Context, id: String) {
        val file = getProfilesDir(context).resolve("$id.yaml")
        if (file.exists()) file.delete()
        val prefs = context.getSharedPreferences("profiles_meta", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${id}_name")
            .remove("${id}_url")
            .remove("${id}_updated")
            .remove("${id}_script_enabled")
            .remove("${id}_script_ids")
            .remove("${id}_auto_update")
            .remove("${id}_auto_update_interval")
            .remove("${id}_upload_bytes")
            .remove("${id}_download_bytes")
            .remove("${id}_total_bytes")
            .remove("${id}_expire_timestamp")
            .apply()
        invalidateProfilesCache()
    }

    /**
     * Runtime-critical top-level keys belong to the injected header. A subscription must not be
     * able to reintroduce or override them, so they are removed structurally after parsing: text
     * matching is trivially defeated by quoting a key (`"external-controller": 0.0.0.0:9090`).
     */
    internal fun sanitizeUserConfig(raw: String): String {
        return try {
            val yaml = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.DumperOptions().apply {
                defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
            })
            val parsed = yaml.load<Any?>(raw)
            if (parsed !is Map<*, *>) return raw

            val sanitized = LinkedHashMap<String, Any?>()
            for ((key, value) in parsed) {
                val keyStr = key?.toString() ?: continue
                if (keyStr.lowercase() in PROTECTED_TOP_LEVEL_KEYS) continue
                sanitized[keyStr] = value
            }
            yaml.dump(sanitized)
        } catch (e: Throwable) {
            Log.w(TAG, "Structural config sanitization failed, falling back to text filter", e)
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

    fun prepareConfig(context: Context, sourceFile: File?): File {
        val targetFile = getConfigFile(context)
        val settings = SettingsManager(context)

        val header = """
            # Mihomo Android Injected Runtime Config
            mode: ${settings.tunnelMode}
            log-level: ${settings.logLevel}
            ipv6: false
            mixed-port: 7890
            allow-lan: ${settings.allowLan}
            geodata-mode: false
            geo-auto-update: false
            geox-url:
              geoip: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb"
              geosite: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat"
              mmdb: "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/country.mmdb"
            dns:
              enable: true
              listen: 127.0.0.1:1053
              ipv6: false
              enhanced-mode: ${if (settings.fakeIpEnabled) "fake-ip" else "redir-host"}
              fake-ip-range: 198.18.0.1/16
              default-nameserver:
                - 223.5.5.5
                - 119.29.29.29
              nameserver:
                - https://223.5.5.5/dns-query
                - https://doh.pub/dns-query
                - 223.5.5.5
                - 119.29.29.29
                - 180.76.76.76
              fallback:
                - https://1.1.1.1/dns-query
                - https://8.8.8.8/dns-query
                - 1.1.1.1
                - 8.8.8.8
              fake-ip-filter:
                - "*.lan"
                - "*.local"
                - "localhost.ptlogin2.qq.com"
                - "*.connectivitycheck.android.com"
                - "*.connectivitycheck.gstatic.com"
                - "connectivitycheck.gstatic.com"
                - "connectivitycheck.android.com"
                - "clients3.google.com"
                - "www.google.com/generate_204"
                - "+.msftconnecttest.com"
                - "+.msftncsi.com"
                - "+.qualcomm.com"
                - "+.miui.com"
                - "+.xiaomi.com"

        """.trimIndent()

        if (sourceFile != null && sourceFile.exists()) {
            var rawContent = sourceFile.readText()
            val activeProfile = getProfiles(context).find { it.file.absolutePath == sourceFile.absolutePath }
            val targetIds = if (activeProfile != null && activeProfile.scriptEnabled && activeProfile.scriptIds.isNotEmpty()) {
                activeProfile.scriptIds
            } else null
            val shouldRunScripts = targetIds != null || settings.scriptingEnabled
            if (shouldRunScripts) {
                Log.i(TAG, "Applying config rewrite scripts for profile: ${activeProfile?.name ?: sourceFile.name}...")
                rawContent = ConfigScriptEngine.executeScripts(
                    context = context,
                    rawYaml = rawContent,
                    targetScriptIds = targetIds
                )
            }
            val userContent = sanitizeUserConfig(rawContent)
            // Cleanly prepend runtime settings without mangling proxies, rules or formatting
            targetFile.writeText("$header\n$userContent")
            Log.i(TAG, "Prepared synthesized config with user profile: ${sourceFile.name}")
        } else {
            val defaultBody = """
                port: 7890
                socks-port: 7891
                allow-lan: ${settings.allowLan}
                proxies: []
                proxy-groups:
                  - name: GLOBAL
                    type: select
                    proxies:
                      - DIRECT
                rules:
                  - MATCH,DIRECT
            """.trimIndent()
            targetFile.writeText("$header\n$defaultBody")
            Log.i(TAG, "Prepared default fallback config")
        }

        runCatching {
            com.github.kr328.clash.core.bridge.Bridge.init(context)
            val clashHomeConfig = context.filesDir.resolve("clash").apply { mkdirs() }.resolve("config.yaml")
            clashHomeConfig.writeText(targetFile.readText())
        }

        return targetFile
    }
}
