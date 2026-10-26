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
     * If [password] is provided, the payload is encrypted with AES-256-GCM.
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
                code = if (codeFile.exists()) runCatching { codeFile.readText() }.getOrDefault("") else ""
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
     * Restores a backup from [input] (the stream is closed here) and returns the number of
     * successfully imported items (profiles + scripts).
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
    ): Result<Int> = runCatching {
        val rawBytes = input.use { stream -> readBounded(stream, tooLargeMessage) }
        val text = decrypt(rawBytes, password)
        val data = json.decodeFromString<BackupData>(text)
        var imported = 0

        // Scripts first: collect the id remapping for the profile bindings below.
        val scriptIdMap = mutableMapOf<String, String>()
        for (script in data.scripts) {
            runCatching {
                if (script.code.isBlank()) return@runCatching
                val rebuilt = ScriptManager.saveScript(
                    context = context,
                    existingId = null,
                    name = script.name,
                    url = script.url,
                    pattern = script.pattern,
                    type = script.type,
                    code = script.code,
                    enabled = script.enabled
                )
                scriptIdMap[script.id] = rebuilt.id
                imported++
            }.onFailure { Log.w(TAG, "Failed to restore script ${script.name}", it) }
        }

        for (profile in data.profiles) {
            runCatching {
                if (profile.content.isBlank()) return@runCatching
                val created = ConfigManager.saveLocalProfile(context, profile.name, profile.content)
                val updated = created.copy(
                    url = profile.url?.takeIf { it.isNotBlank() },
                    scriptEnabled = profile.scriptEnabled,
                    scriptIds = profile.scriptIds.mapNotNull { scriptIdMap[it] },
                    autoUpdate = profile.autoUpdate,
                    autoUpdateInterval = profile.autoUpdateInterval,
                    prependRules = profile.prependRules,
                    hostsEntries = profile.hostsEntries,
                    dnsServersOverride = profile.dnsServersOverride
                )
                ConfigManager.updateProfile(context, updated)
                imported++
            }.onFailure { Log.w(TAG, "Failed to restore profile ${profile.name}", it) }
        }

        runCatching { restoreSettings(SettingsManager(context), data.settings) }
            .onFailure { Log.w(TAG, "Failed to restore settings", it) }

        // The profile listing cache must be dropped or the restored profiles stay invisible.
        ConfigManager.invalidateProfilesCache()
        imported
    }

    private fun scriptCodeFile(context: Context, scriptFile: String): File {
        return context.filesDir.resolve("scripts").resolve(scriptFile)
    }

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
        map["allowLan"]?.let { settings.allowLan = it == "true" }
        map["fakeIpEnabled"]?.let { settings.fakeIpEnabled = it == "true" }
        map["ipv6Enabled"]?.let { settings.ipv6Enabled = it == "true" }
        map["allowLanPassword"]?.let { settings.allowLanPassword = it }
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
