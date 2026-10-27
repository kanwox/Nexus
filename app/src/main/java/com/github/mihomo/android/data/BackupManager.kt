package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One exported profile: the YAML payload plus every piece of metadata the app layered on
 * top of it. [url] is null/blank for local profiles.
 */
@Serializable
data class BackupProfile(
    val name: String,
    val url: String? = null,
    val scriptEnabled: Boolean = false,
    val scriptIds: List<String> = emptyList(),
    val autoUpdate: Boolean = false,
    val autoUpdateInterval: Int = 1440,
    val prependRules: List<String> = emptyList(),
    val hostsEntries: List<String> = emptyList(),
    val dnsServersOverride: List<String> = emptyList(),
    val content: String = ""
)

/** One exported script: [ScriptItem] fields plus the on-disk code. */
@Serializable
data class BackupScript(
    val id: String,
    val name: String,
    val url: String = "",
    val pattern: String = "",
    val type: String = "config",
    val scriptFile: String = "",
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
    val contentHash: String = "",
    val code: String = ""
)

/**
 * Full backup document. [settings] maps [SettingsManager] property names to their string
 * form so new fields can be added without breaking older backup files (missing keys are
 * simply skipped on restore).
 */
@Serializable
data class BackupData(
    val version: Int = 1,
    val profiles: List<BackupProfile> = emptyList(),
    val scripts: List<BackupScript> = emptyList(),
    val settings: Map<String, String> = emptyMap()
)

/**
 * Outcome of a restore: how many items were imported and which ones failed.
 *
 * The failures are plain, user-facing strings so the UI can show them directly; the
 * technical detail stays in logcat.
 */
data class BackupImportResult(
    val imported: Int = 0,
    val failures: List<String> = emptyList(),
) {
    val hasFailures: Boolean get() = failures.isNotEmpty()
}

/**
 * Exports/imports profiles, scripts and app settings as a single JSON document.
 * Import rebuilds everything through the regular public APIs ([ConfigManager],
 * [ScriptManager], [SettingsManager]) so caches, content hashes and metadata stores stay
 * consistent; any single failing item is skipped without aborting the whole restore.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val MAX_BACKUP_BYTES = 50L * 1024 * 1024

    private val MAGIC_HEADER = "NEXUS_ENC_V1\n".toByteArray(Charsets.UTF_8)
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val PBKDF2_ITERATIONS = 65_536
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128

    /**
     * Backup document versions this build can restore. An unknown version is rejected
     * with a clear message instead of being parsed field-by-field, which would silently
     * drop whatever the newer format added.
     */
    private val SUPPORTED_VERSIONS = setOf(1)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    internal fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainBytes)

        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC_HEADER)
        out.write(salt)
        out.write(iv)
        out.write(cipherText)
        return out.toByteArray()
    }

    internal fun decrypt(rawBytes: ByteArray, password: String?): String {
        if (isEncrypted(rawBytes)) {
            if (password.isNullOrBlank()) {
                error("Backup is encrypted; password is required")
            }
            val headerLen = MAGIC_HEADER.size
            if (rawBytes.size < headerLen + SALT_SIZE + IV_SIZE + 16) {
                error("Corrupted encrypted backup file")
            }
            val salt = rawBytes.copyOfRange(headerLen, headerLen + SALT_SIZE)
            val ivOffset = headerLen + SALT_SIZE
            val iv = rawBytes.copyOfRange(ivOffset, ivOffset + IV_SIZE)
            val cipherOffset = ivOffset + IV_SIZE
            val cipherText = rawBytes.copyOfRange(cipherOffset, rawBytes.size)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = runCatching { cipher.doFinal(cipherText) }
                .getOrElse { error("Incorrect backup password or corrupted file") }
            return plainBytes.toString(Charsets.UTF_8)
        }
        return rawBytes.toString(Charsets.UTF_8)
    }

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_HEADER.size) return false
        for (i in MAGIC_HEADER.indices) {
            if (bytes[i] != MAGIC_HEADER[i]) return false
        }
        return true
    }

    /**
     * Decides whether a URL from a backup may be restored.
     *
     * A backup is untrusted input: the URL is restored into a profile that
     * [SubscriptionUpdateWorker] then fetches periodically, so an unvalidated URL turns
     * the restore into a remote-code-fetch primitive. The URL is therefore checked with
     * the same guard the app uses for manually entered URLs.
     *
     * Returns the URL when it is acceptable, or `null` when it must be dropped.
     */
    internal fun sanitizeRestoredUrl(url: String?, allowPrivateHosts: Boolean): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        return if (runCatching { requireSecureRemoteUrl(candidate, allowPrivateHosts) }.isSuccess) {
            candidate
        } else {
            Log.w(TAG, "Rejected unsafe URL during restore: ${redactUrl(candidate)}")
            null
        }
    }

    /**
     * Decides whether LAN sharing may be enabled by a restore.
     *
     * "LAN sharing on + empty password" produces a config without an `authentication`
     * block, which opens the mixed port to the whole LAN completely unauthenticated. When
     * the backup asks for LAN sharing but carries no usable password, LAN sharing stays
     * OFF.
     *
     * @param backupLanPassword the password stored in the backup (may be null/blank)
     * @param currentPassword the password already configured on this device
     */
    internal fun decideLanRestore(
        wantsLan: Boolean,
        backupLanPassword: String?,
        currentPassword: String
    ): LanRestoreDecision {
        if (!wantsLan) return LanRestoreDecision(allowLan = false, password = backupLanPassword?.trim())
        val effective = backupLanPassword?.trim()?.takeIf { it.isNotEmpty() }
            ?: currentPassword.trim().takeIf { it.isNotEmpty() }
        return if (effective.isNullOrEmpty()) {
            LanRestoreDecision(allowLan = false, password = null, rejected = true)
        } else {
            LanRestoreDecision(allowLan = true, password = effective)
        }
    }

    /** The outcome of [decideLanRestore]. */
    internal data class LanRestoreDecision(
        val allowLan: Boolean,
        val password: String?,
        /** True when LAN sharing was requested but refused for lack of a password. */
        val rejected: Boolean = false,
    )

    /** Bounded read: a malformed/huge backup must fail cleanly instead of OOMing the process. */
    private fun readBounded(input: InputStream, tooLargeMessage: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_BYTES) error(tooLargeMessage)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Writes a full backup to [output] (the stream is closed here).
     *
     * If [password] is provided (and not blank), the payload is encrypted with
     * AES-256-GCM (PBKDF2-HMAC-SHA256, 65536 iterations) and the file starts with the
     * `NEXUS_ENC_V1` magic header. A BLANK password is treated as "no password" and
     * produces a PLAINTEXT document.
     *
     * Profiles keep [ConfigManager.getProfiles] order; scripts carry their full code so the
     * restore does not need the network.
     */
    fun exportBackup(
        context: Context,
        output: OutputStream,
        password: String? = null
    ): Result<Unit> = runCatching {
        val settings = SettingsManager(context)

        val profiles = ConfigManager.getProfiles(context).map { profile ->
            BackupProfile(
                name = profile.name,
                url = profile.url,
                scriptEnabled = profile.scriptEnabled,
                scriptIds = profile.scriptIds,
                autoUpdate = profile.autoUpdate,
                autoUpdateInterval = profile.autoUpdateInterval,
                prependRules = profile.prependRules,
                hostsEntries = profile.hostsEntries,
                dnsServersOverride = profile.dnsServersOverride,
                content = runCatching { profile.file.readText() }.getOrDefault("")
            )
        }

        val scripts = ScriptManager.getScripts(context).map { script ->
            val codeFile = scriptCodeFile(context, script.scriptFile)
            BackupScript(
                id = script.id,
                name = script.name,
                url = script.url,
                pattern = script.pattern,
                type = script.type,
                scriptFile = script.scriptFile,
                enabled = script.enabled,
                updatedAt = script.updatedAt,
                contentHash = script.contentHash,
                code = if (codeFile != null && codeFile.exists()) {
                    runCatching { codeFile.readText() }.getOrDefault("")
                } else {
                    ""
                }
            )
        }

        val settingsMap = buildSettingsSnapshot(settings)
        val document = BackupData(
            version = 1,
            profiles = profiles,
            scripts = scripts,
            settings = settingsMap
        )

        val plainBytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        val finalBytes = if (!password.isNullOrBlank()) {
            encrypt(plainBytes, password)
        } else {
            plainBytes
        }

        output.use { stream ->
            stream.write(finalBytes)
            stream.flush()
        }
    }

    /**
     * Writes a **PLAINTEXT** backup to [output] (the stream is closed here).
     *
     * The resulting JSON contains the subscription URLs (including their access tokens)
     * and the LAN proxy password in clear text, so it must only be used when the user
     * explicitly opts out of encryption. Prefer [exportBackup] with a password.
     */
    fun exportBackup(context: Context, output: OutputStream): Result<Unit> =
        exportBackup(context, output, password = null)

    /**
     * Restores a backup from [input] (the stream is closed here) and returns the number of
     * successfully imported items (profiles + scripts).
     *
     * [password] is required for an encrypted backup; use [isEncrypted] on the raw bytes to
     * find out whether a password is needed before calling this.
     *
     * Scripts are rebuilt first because [ScriptManager.saveScript] always mints a fresh id,
     * so profile script bindings are remapped old-id -> new-id; profiles are then created
     * with [ConfigManager.saveLocalProfile] and enriched via [ConfigManager.updateProfile].
     */
    fun importBackup(
        context: Context,
        input: InputStream,
        tooLargeMessage: String = "Backup file is too large",
        password: String? = null
    ): Result<Int> = importBackupDetailed(context, input, tooLargeMessage, password).map { it.imported }

    /**
     * Restores a backup and reports WHICH items failed, so the UI can tell the user what
     * did not make it in instead of only showing a count.
     *
     * A backup is untrusted input: it is a file the user picked, and it may have been
     * crafted. Two things are therefore validated before anything is restored:
     *  - every restored URL is checked with [requireSecureRemoteUrl]; a URL that fails is
     *    DROPPED (and the profile's auto-update is disabled with it) instead of being
     *    restored, because [SubscriptionUpdateWorker] would otherwise fetch it periodically;
     *  - "LAN sharing on + empty password" is rejected, because that combination opens the
     *    mixed port on the LAN completely unauthenticated.
     */
    fun importBackupDetailed(
        context: Context,
        input: InputStream,
        tooLargeMessage: String = "Backup file is too large",
        password: String? = null
    ): Result<BackupImportResult> = runCatching {
        val rawBytes = input.use { stream -> readBounded(stream, tooLargeMessage) }
        val text = decrypt(rawBytes, password)
        val data = json.decodeFromString<BackupData>(text)
        if (data.version !in SUPPORTED_VERSIONS) {
            error("不支持的备份版本：${data.version}（支持 ${SUPPORTED_VERSIONS.joinToString(", ")}）")
        }

        val settings = SettingsManager(context)
        val allowPrivateHosts = settings.allowPrivateSubscriptionHosts
        var imported = 0
        val failures = mutableListOf<String>()

        // Scripts first: collect the id remapping for the profile bindings below.
        val scriptIdMap = mutableMapOf<String, String>()
        for (script in data.scripts) {
            runCatching {
                if (script.code.isBlank()) return@runCatching
                // A restored script URL is untrusted: an attacker could otherwise make the
                // app fetch an arbitrary address on the next "update script" run.
                val safeUrl = script.url.takeIf { it.isNotBlank() }
                    ?.let { candidate ->
                        sanitizeRestoredUrl(candidate, allowPrivateHosts) ?: run {
                            failures += "脚本「${script.name}」的远程地址不安全，已忽略该地址"
                            null
                        }
                    } ?: ""
                val rebuilt = ScriptManager.saveScript(
                    context = context,
                    existingId = null,
                    name = script.name,
                    url = safeUrl,
                    pattern = script.pattern,
                    type = script.type,
                    code = script.code,
                    enabled = script.enabled
                )
                scriptIdMap[script.id] = rebuilt.id
                imported++
            }.onFailure {
                Log.w(TAG, "Failed to restore script ${script.name}", it)
                failures += "脚本「${script.name}」恢复失败：${it.message ?: it.javaClass.simpleName}"
            }
        }

        for (profile in data.profiles) {
            runCatching {
                if (profile.content.isBlank()) return@runCatching
                // The subscription URL is restored untrusted. A URL that fails validation is
                // dropped and auto-update is disabled with it: the worker would otherwise
                // fetch an attacker-chosen address every 30 minutes.
                val restoredUrl = profile.url?.takeIf { it.isNotBlank() }?.let { candidate ->
                    sanitizeRestoredUrl(candidate, allowPrivateHosts) ?: run {
                        failures += "订阅「${profile.name}」的地址不安全，已忽略该地址并关闭自动更新"
                        null
                    }
                }
                val created = ConfigManager.saveLocalProfile(context, profile.name, profile.content)
                val updated = created.copy(
                    url = restoredUrl,
                    scriptEnabled = profile.scriptEnabled,
                    scriptIds = profile.scriptIds.mapNotNull { scriptIdMap[it] },
                    // Without a URL there is nothing to update, so auto-update stays off.
                    autoUpdate = profile.autoUpdate && restoredUrl != null,
                    autoUpdateInterval = profile.autoUpdateInterval,
                    prependRules = profile.prependRules,
                    hostsEntries = profile.hostsEntries,
                    dnsServersOverride = profile.dnsServersOverride
                )
                ConfigManager.updateProfile(context, updated)
                imported++
            }.onFailure {
                Log.w(TAG, "Failed to restore profile ${profile.name}", it)
                failures += "订阅「${profile.name}」恢复失败：${it.message ?: it.javaClass.simpleName}"
            }
        }

        runCatching { restoreSettings(settings, data.settings) }
            .onFailure {
                Log.w(TAG, "Failed to restore settings", it)
                failures += "设置恢复失败：${it.message ?: it.javaClass.simpleName}"
            }

        // The profile listing cache must be dropped or the restored profiles stay invisible.
        ConfigManager.invalidateProfilesCache()
        BackupImportResult(imported = imported, failures = failures)
    }

    /**
     * Resolves a script's code file inside the scripts directory.
     *
     * `scriptFile` comes from deserialized JSON (a restored backup or a tampered
     * SharedPreferences), so it is resolved through [SafeFileName.resolveChild]: a value
     * like `../../databases/x` must never escape the scripts directory.
     */
    private fun scriptCodeFile(context: Context, scriptFile: String): File? {
        val dir = context.filesDir.resolve("scripts")
        return SafeFileName.resolveChild(dir, scriptFile)
    }

    /**
     * The canonical list of backed-up settings keys.
     *
     * Both [buildSettingsSnapshot] and [restoreSettings] work from this list, so the two
     * can never drift apart (a key that is written but never read, or vice versa, used
     * to silently drop settings on restore).
     */
    internal val SETTINGS_SNAPSHOT_KEYS: List<String> = listOf(
        "tunnelMode", "tunStack", "perAppProxyMode", "selectedPackages", "bootOnStartup",
        "allowLan", "fakeIpEnabled", "ipv6Enabled", "allowLanPassword", "dnsIpv6Enabled",
        "dnsNameservers", "dnsFallbackServers", "mixedPort", "tunMtu", "killSwitchEnabled",
        "allowBypassEnabled", "allowPrivateSubscriptionHosts", "blockThirdPartyDns",
        "proxiesViewMode", "visibleShortcuts", "shortcutOrder", "scriptingEnabled",
        "showGroupIcons", "nodeColumns", "nodeSortMode", "logLevel", "themeMode",
        "appLanguage", "appFont"
    )

    /** Explicit snapshot of every backed-up [SettingsManager] key, plus per-group node picks. */
    private fun buildSettingsSnapshot(settings: SettingsManager): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["tunnelMode"] = settings.tunnelMode
        map["tunStack"] = settings.tunStack
        map["perAppProxyMode"] = settings.perAppProxyMode.name
        map["selectedPackages"] = settings.selectedPackages.joinToString(",")
        map["bootOnStartup"] = settings.bootOnStartup.toString()
        map["allowLan"] = settings.allowLan.toString()
        map["fakeIpEnabled"] = settings.fakeIpEnabled.toString()
        map["ipv6Enabled"] = settings.ipv6Enabled.toString()
        map["allowLanPassword"] = settings.allowLanPassword
        map["dnsIpv6Enabled"] = settings.dnsIpv6Enabled.toString()
        map["dnsNameservers"] = settings.dnsNameservers
        map["dnsFallbackServers"] = settings.dnsFallbackServers
        map["mixedPort"] = settings.mixedPort.toString()
        map["tunMtu"] = settings.tunMtu.toString()
        map["killSwitchEnabled"] = settings.killSwitchEnabled.toString()
        map["allowBypassEnabled"] = settings.allowBypassEnabled.toString()
        map["allowPrivateSubscriptionHosts"] = settings.allowPrivateSubscriptionHosts.toString()
        map["blockThirdPartyDns"] = settings.blockThirdPartyDns.toString()
        map["proxiesViewMode"] = settings.proxiesViewMode
        map["visibleShortcuts"] = settings.visibleShortcuts.joinToString(",")
        map["shortcutOrder"] = settings.shortcutOrder.joinToString(",")
        map["scriptingEnabled"] = settings.scriptingEnabled.toString()
        map["showGroupIcons"] = settings.showGroupIcons.toString()
        map["nodeColumns"] = settings.nodeColumns.toString()
        map["nodeSortMode"] = settings.nodeSortMode
        map["logLevel"] = settings.logLevel
        map["themeMode"] = settings.themeMode.name
        map["appLanguage"] = settings.appLanguage
        map["appFont"] = settings.appFont.name
        for ((group, node) in settings.getAllSelectedNodes()) {
            map["sel_node_$group"] = node
        }
        return map
    }

    /** Writes each present key back; absent or malformed values are skipped silently. */
    private fun restoreSettings(settings: SettingsManager, map: Map<String, String>) {
        map["tunnelMode"]?.let { settings.tunnelMode = it }
        map["tunStack"]?.let { settings.tunStack = it }
        map["perAppProxyMode"]?.let {
            runCatching { settings.perAppProxyMode = PerAppProxyMode.valueOf(it) }
        }
        map["selectedPackages"]?.let {
            settings.selectedPackages = it.split(',').filter { pkg -> pkg.isNotBlank() }.toSet()
        }
        map["bootOnStartup"]?.let { settings.bootOnStartup = it == "true" }
        // The password MUST be restored before the switch is decided: `allow-lan: true`
        // without a password produces a config without an `authentication` block, which
        // opens the mixed port to the whole LAN completely unauthenticated. Restoring the
        // two independently (as it happened before) allowed a crafted backup to do
        // exactly that.
        val decision = decideLanRestore(
            wantsLan = map["allowLan"] == "true",
            backupLanPassword = map["allowLanPassword"],
            currentPassword = settings.allowLanPassword
        )
        if (decision.rejected) {
            Log.w(TAG, "Backup requested LAN sharing without a password; keeping LAN sharing disabled")
            LogRepository.addLog(
                "warning",
                "备份中启用了局域网共享但没有访问密码，已保持局域网共享关闭（否则混合端口会在局域网上完全无鉴权开放）"
            )
        }
        decision.password?.let { settings.allowLanPassword = it }
        settings.allowLan = decision.allowLan
        map["fakeIpEnabled"]?.let { settings.fakeIpEnabled = it == "true" }
        map["ipv6Enabled"]?.let { settings.ipv6Enabled = it == "true" }
        map["dnsIpv6Enabled"]?.let { settings.dnsIpv6Enabled = it == "true" }
        map["dnsNameservers"]?.let { settings.dnsNameservers = it }
        map["dnsFallbackServers"]?.let { settings.dnsFallbackServers = it }
        map["mixedPort"]?.let { value -> value.toIntOrNull()?.let { settings.mixedPort = it } }
        map["tunMtu"]?.let { value -> value.toIntOrNull()?.let { settings.tunMtu = it } }
        map["killSwitchEnabled"]?.let { settings.killSwitchEnabled = it == "true" }
        map["allowBypassEnabled"]?.let { settings.allowBypassEnabled = it == "true" }
        map["allowPrivateSubscriptionHosts"]?.let {
            settings.allowPrivateSubscriptionHosts = it == "true"
        }
        map["blockThirdPartyDns"]?.let { settings.blockThirdPartyDns = it == "true" }
        map["proxiesViewMode"]?.let { settings.proxiesViewMode = it }
        map["visibleShortcuts"]?.let {
            settings.visibleShortcuts = it.split(',').filter { id -> id.isNotBlank() }.toSet()
        }
        map["shortcutOrder"]?.let {
            settings.shortcutOrder = it.split(',').filter { id -> id.isNotBlank() }
        }
        map["scriptingEnabled"]?.let { settings.scriptingEnabled = it == "true" }
        map["showGroupIcons"]?.let { settings.showGroupIcons = it == "true" }
        map["nodeColumns"]?.let { value -> value.toIntOrNull()?.let { settings.nodeColumns = it } }
        map["nodeSortMode"]?.let { settings.nodeSortMode = it }
        map["logLevel"]?.let { settings.logLevel = it }
        map["themeMode"]?.let { runCatching { settings.themeMode = ThemeMode.valueOf(it) } }
        map["appLanguage"]?.let { settings.appLanguage = it }
        map["appFont"]?.let { runCatching { settings.appFont = AppFont.valueOf(it) } }
        for ((key, value) in map) {
            if (key.startsWith("sel_node_") && value.isNotBlank()) {
                settings.setSelectedNode(key.removePrefix("sel_node_"), value)
            }
        }
    }
}
