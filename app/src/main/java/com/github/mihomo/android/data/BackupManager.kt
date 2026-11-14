package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val BACKUP_TAG = "BackupManager"
private const val MAX_BACKUP_BYTES = 50L * 1024 * 1024
private const val STREAM_BUFFER_SIZE = 16 * 1024
private const val SALT_SIZE = 16
private const val IV_SIZE = 12
private const val ITERATIONS_FIELD_SIZE = 4
private const val GCM_TAG_BYTES = 16
private const val KEY_LENGTH_BITS = 256
private const val GCM_TAG_LENGTH_BITS = 128
private const val MIN_PBKDF2_ITERATIONS = 10_000
private const val MAX_PBKDF2_ITERATIONS = 5_000_000

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
    internal val LEGACY_MAGIC_HEADER = "NEXUS_ENC_V1\n".toByteArray(Charsets.UTF_8)
    internal val MAGIC_HEADER = "NEXUS_ENC_V2\n".toByteArray(Charsets.UTF_8)
    private val MAGIC_PREFIX = "NEXUS_ENC_".toByteArray(Charsets.UTF_8)

    internal const val LEGACY_PBKDF2_ITERATIONS = 65_536
    internal const val PBKDF2_ITERATIONS = 210_000

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

    internal fun encrypt(
        plainBytes: ByteArray,
        password: String,
        iterations: Int = PBKDF2_ITERATIONS,
        aad: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream(plainBytes.size + MAGIC_HEADER.size + 64)
        encryptStream(
            input = ByteArrayInputStream(plainBytes),
            output = out,
            password = password,
            iterations = iterations,
            aadOverride = aad
        )
        return out.toByteArray()
    }

    /** Produces a legacy V1 encrypted payload (65,536 iterations, no AAD) for compatibility tests. */
    internal fun encryptLegacyV1(plainBytes: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val out = ByteArrayOutputStream(plainBytes.size + LEGACY_MAGIC_HEADER.size + 64)
        out.write(LEGACY_MAGIC_HEADER)
        out.write(salt)
        out.write(iv)
        runCipherWithWipedKey(
            password = password,
            salt = salt,
            iterations = LEGACY_PBKDF2_ITERATIONS,
            iv = iv,
            mode = Cipher.ENCRYPT_MODE,
            aad = null
        ) { cipher ->
            streamThroughCipher(ByteArrayInputStream(plainBytes), out, cipher, maxBytes = Long.MAX_VALUE, "")
        }
        return out.toByteArray()
    }

    internal fun decrypt(rawBytes: ByteArray, password: String?, aad: ByteArray? = null): String =
        decryptStream(
            input = ByteArrayInputStream(rawBytes),
            tooLargeMessage = "Backup file is too large",
            password = password,
            aadOverride = aad
        )

    fun isEncrypted(bytes: ByteArray): Boolean =
        hasPrefix(bytes, MAGIC_HEADER) ||
            hasPrefix(bytes, LEGACY_MAGIC_HEADER) ||
            hasPrefix(bytes, MAGIC_PREFIX)

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
        return try {
            requireSecureRemoteUrl(candidate, allowPrivateHosts)
            candidate
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(BACKUP_TAG, "Rejected unsafe URL during restore: ${redactUrl(candidate)}")
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
     */
    internal fun decideLanRestore(
        wantsLan: Boolean,
        backupLanPassword: String?,
        currentPassword: String
    ): LanRestoreDecision {
        val cleanBackupPw = ConfigSanitizer.sanitizeSingleLineScalar(backupLanPassword)
        val cleanCurrentPw = ConfigSanitizer.sanitizeSingleLineScalar(currentPassword)
        val effective = cleanBackupPw ?: cleanCurrentPw
        return when {
            !wantsLan -> LanRestoreDecision(allowLan = false, password = cleanBackupPw)
            effective == null -> LanRestoreDecision(allowLan = false, password = null, rejected = true)
            else -> LanRestoreDecision(allowLan = true, password = effective)
        }
    }

    internal fun sanitizeRestoredOverrides(
        prependRules: List<String>,
        hostsEntries: List<String>,
        dnsServersOverride: List<String>
    ): SanitizedOverrides {
        val safePrepend = ConfigSanitizer.sanitizePrependRules(prependRules)
        val safeHosts = ConfigSanitizer.sanitizeHostsEntries(hostsEntries)
        val safeDns = ConfigSanitizer.sanitizeDnsServerEntries(dnsServersOverride)
        val dropped = safePrepend.size < prependRules.size ||
            safeHosts.size < hostsEntries.size ||
            safeDns.size < dnsServersOverride.size
        return SanitizedOverrides(safePrepend, safeHosts, safeDns, dropped)
    }

    internal data class SanitizedOverrides(
        val prependRules: List<String>,
        val hostsEntries: List<String>,
        val dnsServersOverride: List<String>,
        val hadDroppedEntries: Boolean
    )

    /** The outcome of [decideLanRestore]. */
    internal data class LanRestoreDecision(
        val allowLan: Boolean,
        val password: String?,
        /** True when LAN sharing was requested but refused for lack of a password. */
        val rejected: Boolean = false,
    )

    /**
     * Writes a full backup to [output] (the stream is closed here).
     *
     * If [password] is provided (and not blank), the payload is encrypted with
     * AES-256-GCM (PBKDF2-HMAC-SHA256, 210,000 iterations) and the file starts with the
     * `NEXUS_ENC_V2` magic header (bound as AAD). A BLANK or null password is treated as
     * "no password" and produces a PLAINTEXT JSON document.
     */
    fun exportBackup(
        context: Context,
        output: OutputStream,
        password: String? = null
    ): Result<Unit> = runBackupCatching {
        val settings = SettingsManager(context)
        val profiles = collectBackupProfiles(context)
        val scripts = collectBackupScripts(context)
        val document = BackupData(
            version = 1,
            profiles = profiles,
            scripts = scripts,
            settings = buildSettingsSnapshot(settings)
        )

        val plainJson = json.encodeToString(document)
        output.use { stream ->
            if (!password.isNullOrBlank()) {
                encryptTextStream(plainJson, stream, password, PBKDF2_ITERATIONS)
            } else {
                val writer = OutputStreamWriter(stream, Charsets.UTF_8)
                writer.write(plainJson)
                writer.flush()
            }
            stream.flush()
        }
    }

    /**
     * Writes a **PLAINTEXT** backup to [output] (the stream is closed here).
     */
    fun exportBackup(context: Context, output: OutputStream): Result<Unit> =
        exportBackup(context, output, password = null)

    /**
     * Restores a backup from [input] (the stream is closed here) and returns the number of
     * successfully imported items (profiles + scripts).
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
     */
    fun importBackupDetailed(
        context: Context,
        input: InputStream,
        tooLargeMessage: String = "Backup file is too large",
        password: String? = null
    ): Result<BackupImportResult> = runBackupCatching {
        val text = input.use { stream ->
            decryptStream(
                input = stream,
                tooLargeMessage = tooLargeMessage,
                password = password,
                aadOverride = null
            )
        }
        val data = json.decodeFromString<BackupData>(text)
        if (data.version !in SUPPORTED_VERSIONS) {
            error("不支持的备份版本：${data.version}（支持 ${SUPPORTED_VERSIONS.joinToString(", ")}）")
        }

        val settings = SettingsManager(context)
        val allowPrivateHosts = settings.allowPrivateSubscriptionHosts
        val failures = mutableListOf<String>()

        val scriptRestore = restoreScriptsFromBackup(context, data.scripts, allowPrivateHosts, failures)
        val profilesImported = restoreProfilesFromBackup(
            context = context,
            profiles = data.profiles,
            scriptIdMap = scriptRestore.idMap,
            allowPrivateHosts = allowPrivateHosts,
            failures = failures
        )

        runBackupCatching { restoreSettings(settings, data.settings) }
            .onFailure {
                Log.w(BACKUP_TAG, "Failed to restore settings", it)
                failures += "设置恢复失败：${it.message ?: it.javaClass.simpleName}"
            }

        ConfigManager.invalidateProfilesCache()
        BackupImportResult(
            imported = scriptRestore.importedCount + profilesImported,
            failures = failures
        )
    }

    /**
     * The canonical list of backed-up settings keys.
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
}

private inline fun <T> runBackupCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private data class ScriptRestoreSummary(
    val idMap: Map<String, String>,
    val importedCount: Int
)

private class CipherChunkOutputStream(
    private val target: OutputStream,
    private val cipher: Cipher
) : OutputStream() {
    private val singleByte = ByteArray(1)
    private val outBuffer = ByteArray(STREAM_BUFFER_SIZE + GCM_TAG_BYTES * 2)
    private var totalPlainBytes = 0L

    override fun write(b: Int) {
        singleByte[0] = b.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        totalPlainBytes += len
        if (totalPlainBytes > MAX_BACKUP_BYTES) {
            error("Backup file is too large")
        }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val chunk = minOf(remaining, STREAM_BUFFER_SIZE)
            val produced = cipher.update(b, offset, chunk, outBuffer)
            if (produced > 0) {
                target.write(outBuffer, 0, produced)
            }
            offset += chunk
            remaining -= chunk
        }
    }

    fun finish() {
        try {
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) {
                target.write(finalBytes)
                Arrays.fill(finalBytes, 0.toByte())
            }
        } finally {
            Arrays.fill(singleByte, 0.toByte())
            Arrays.fill(outBuffer, 0.toByte())
        }
    }
}

private fun collectBackupProfiles(context: Context): List<BackupProfile> =
    ConfigManager.getProfiles(context).map { profile ->
        if (!profile.file.exists()) {
            error("配置文件不存在：${profile.name}")
        }
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
            content = profile.file.readText()
        )
    }

private fun collectBackupScripts(context: Context): List<BackupScript> =
    ScriptManager.getScripts(context).map { script ->
        val codeFile = scriptCodeFile(context, script.scriptFile)
        val code = if (script.scriptFile.isNotBlank()) {
            if (codeFile == null || !codeFile.exists()) {
                error("脚本文件不存在：${script.name}")
            }
            codeFile.readText()
        } else {
            ""
        }
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
            code = code
        )
    }

private fun restoreScriptsFromBackup(
    context: Context,
    scripts: List<BackupScript>,
    allowPrivateHosts: Boolean,
    failures: MutableList<String>
): ScriptRestoreSummary {
    val scriptIdMap = mutableMapOf<String, String>()
    var imported = 0
    for (script in scripts) {
        runBackupCatching {
            if (script.code.isBlank()) return@runBackupCatching
            val safeUrl = script.url.takeIf { it.isNotBlank() }?.let { candidate ->
                BackupManager.sanitizeRestoredUrl(candidate, allowPrivateHosts) ?: run {
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
            Log.w(BACKUP_TAG, "Failed to restore script ${script.name}", it)
            failures += "脚本「${script.name}」恢复失败：${it.message ?: it.javaClass.simpleName}"
        }
    }
    return ScriptRestoreSummary(scriptIdMap, imported)
}

private fun restoreProfilesFromBackup(
    context: Context,
    profiles: List<BackupProfile>,
    scriptIdMap: Map<String, String>,
    allowPrivateHosts: Boolean,
    failures: MutableList<String>
): Int {
    var imported = 0
    for (profile in profiles) {
        runBackupCatching {
            if (profile.content.isBlank()) return@runBackupCatching
            val restoredUrl = profile.url?.takeIf { it.isNotBlank() }?.let { candidate ->
                BackupManager.sanitizeRestoredUrl(candidate, allowPrivateHosts) ?: run {
                    failures += "订阅「${profile.name}」的地址不安全，已忽略该地址并关闭自动更新"
                    null
                }
            }
            val restoredOverrides = BackupManager.sanitizeRestoredOverrides(
                prependRules = profile.prependRules,
                hostsEntries = profile.hostsEntries,
                dnsServersOverride = profile.dnsServersOverride
            )
            if (restoredOverrides.hadDroppedEntries) {
                failures += "订阅「${profile.name}」包含无效覆写条目，已自动过滤"
            }
            val created = ConfigManager.saveLocalProfile(context, profile.name, profile.content)
            val updated = created.copy(
                url = restoredUrl,
                scriptEnabled = profile.scriptEnabled,
                scriptIds = profile.scriptIds.mapNotNull { scriptIdMap[it] },
                autoUpdate = profile.autoUpdate && restoredUrl != null,
                autoUpdateInterval = profile.autoUpdateInterval,
                prependRules = restoredOverrides.prependRules,
                hostsEntries = restoredOverrides.hostsEntries,
                dnsServersOverride = restoredOverrides.dnsServersOverride
            )
            ConfigManager.updateProfile(context, updated)
            imported++
        }.onFailure {
            Log.w(BACKUP_TAG, "Failed to restore profile ${profile.name}", it)
            failures += "订阅「${profile.name}」恢复失败：${it.message ?: it.javaClass.simpleName}"
        }
    }
    return imported
}

private fun scriptCodeFile(context: Context, scriptFile: String): File? {
    val dir = context.filesDir.resolve("scripts")
    return SafeFileName.resolveChild(dir, scriptFile)
}

private fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean {
    if (bytes.size < prefix.size) return false
    for (i in prefix.indices) {
        if (bytes[i] != prefix[i]) return false
    }
    return true
}

private fun deriveKeyBytes(passwordChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    if (iterations !in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
        error("Corrupted encrypted backup file")
    }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(passwordChars, salt, iterations, KEY_LENGTH_BITS)
    val secret = try {
        factory.generateSecret(spec)
    } finally {
        spec.clearPassword()
    }
    return secret.encoded ?: error("Failed to derive backup encryption key")
}

private inline fun <T> runCipherWithWipedKey(
    password: String,
    salt: ByteArray,
    iterations: Int,
    iv: ByteArray,
    mode: Int,
    aad: ByteArray?,
    block: (Cipher) -> T
): T {
    val passwordChars = password.toCharArray()
    var keyBytes: ByteArray? = null
    try {
        keyBytes = deriveKeyBytes(passwordChars, salt, iterations)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return block(cipher)
    } finally {
        Arrays.fill(passwordChars, '\u0000')
        keyBytes?.let { Arrays.fill(it, 0.toByte()) }
    }
}

private fun buildV2Header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
    val magic = BackupManager.MAGIC_HEADER
    val header = ByteArray(magic.size + ITERATIONS_FIELD_SIZE + SALT_SIZE + IV_SIZE)
    System.arraycopy(magic, 0, header, 0, magic.size)
    ByteBuffer.wrap(header, magic.size, ITERATIONS_FIELD_SIZE).putInt(iterations)
    System.arraycopy(salt, 0, header, magic.size + ITERATIONS_FIELD_SIZE, SALT_SIZE)
    System.arraycopy(iv, 0, header, magic.size + ITERATIONS_FIELD_SIZE + SALT_SIZE, IV_SIZE)
    return header
}

private fun encryptStream(
    input: InputStream,
    output: OutputStream,
    password: String,
    iterations: Int,
    aadOverride: ByteArray?
) {
    val random = SecureRandom()
    val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
    val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
    val header = buildV2Header(iterations, salt, iv)
    output.write(header)
    val effectiveAad = aadOverride ?: header
    runCipherWithWipedKey(
        password = password,
        salt = salt,
        iterations = iterations,
        iv = iv,
        mode = Cipher.ENCRYPT_MODE,
        aad = effectiveAad
    ) { cipher ->
        streamThroughCipher(input, output, cipher, maxBytes = MAX_BACKUP_BYTES, "Backup file is too large")
    }
}

private fun encryptTextStream(
    plainJson: String,
    output: OutputStream,
    password: String,
    iterations: Int
) {
    val random = SecureRandom()
    val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
    val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
    val header = buildV2Header(iterations, salt, iv)
    output.write(header)
    runCipherWithWipedKey(
        password = password,
        salt = salt,
        iterations = iterations,
        iv = iv,
        mode = Cipher.ENCRYPT_MODE,
        aad = header
    ) { cipher ->
        val cipherStream = CipherChunkOutputStream(output, cipher)
        val writer = OutputStreamWriter(cipherStream, Charsets.UTF_8)
        writer.write(plainJson)
        writer.flush()
        cipherStream.finish()
    }
}

private fun streamThroughCipher(
    input: InputStream,
    output: OutputStream,
    cipher: Cipher,
    maxBytes: Long,
    tooLargeMessage: String,
    initialTotalBytes: Long = 0L
): Long {
    val inBuffer = ByteArray(STREAM_BUFFER_SIZE)
    val outBuffer = ByteArray(STREAM_BUFFER_SIZE + GCM_TAG_BYTES * 2)
    var totalRead = initialTotalBytes
    var cipherBytesRead = 0L
    try {
        while (true) {
            val read = input.read(inBuffer)
            if (read < 0) break
            totalRead += read
            cipherBytesRead += read
            if (totalRead > maxBytes) {
                error(tooLargeMessage)
            }
            val produced = cipher.update(inBuffer, 0, read, outBuffer)
            if (produced > 0) {
                output.write(outBuffer, 0, produced)
            }
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) {
            output.write(finalBytes)
            Arrays.fill(finalBytes, 0.toByte())
        }
        return cipherBytesRead
    } finally {
        Arrays.fill(inBuffer, 0.toByte())
        Arrays.fill(outBuffer, 0.toByte())
    }
}

private fun readExactOrNull(input: InputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = input.read(buffer, offset, size - offset)
        if (read < 0) return null
        offset += read
    }
    return buffer
}

private fun readUpTo(input: InputStream, maxSize: Int): ByteArray {
    val buffer = ByteArray(maxSize)
    var offset = 0
    while (offset < maxSize) {
        val read = input.read(buffer, offset, maxSize - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == maxSize) buffer else buffer.copyOf(offset)
}

private fun decryptStream(
    input: InputStream,
    tooLargeMessage: String,
    password: String?,
    aadOverride: ByteArray?
): String {
    val magicLen = BackupManager.MAGIC_HEADER.size
    val prefix = readUpTo(input, magicLen)
    if (!BackupManager.isEncrypted(prefix)) {
        return readPlaintextRemaining(prefix, input, tooLargeMessage)
    }
    if (password.isNullOrBlank()) {
        error("Backup is encrypted; password is required")
    }
    if (prefix.size < magicLen) {
        error("Corrupted encrypted backup file")
    }
    return if (hasPrefix(prefix, BackupManager.LEGACY_MAGIC_HEADER)) {
        decryptLegacyV1Stream(prefix, input, tooLargeMessage, password, aadOverride)
    } else {
        decryptV2Stream(prefix, input, tooLargeMessage, password, aadOverride)
    }
}

private fun readPlaintextRemaining(
    prefix: ByteArray,
    input: InputStream,
    tooLargeMessage: String
): String {
    val out = ByteArrayOutputStream(prefix.size + STREAM_BUFFER_SIZE)
    var total = prefix.size.toLong()
    if (total > MAX_BACKUP_BYTES) error(tooLargeMessage)
    out.write(prefix)
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > MAX_BACKUP_BYTES) error(tooLargeMessage)
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun decryptLegacyV1Stream(
    prefix: ByteArray,
    input: InputStream,
    tooLargeMessage: String,
    password: String,
    aadOverride: ByteArray?
): String {
    val salt = readExactOrNull(input, SALT_SIZE) ?: error("Corrupted encrypted backup file")
    val iv = readExactOrNull(input, IV_SIZE) ?: error("Corrupted encrypted backup file")
    val headerBytes = prefix.size + SALT_SIZE + IV_SIZE
    val out = ByteArrayOutputStream(STREAM_BUFFER_SIZE)
    val cipherBytes = runBackupCatching {
        runCipherWithWipedKey(
            password = password,
            salt = salt,
            iterations = BackupManager.LEGACY_PBKDF2_ITERATIONS,
            iv = iv,
            mode = Cipher.DECRYPT_MODE,
            aad = aadOverride
        ) { cipher ->
            streamThroughCipher(
                input = input,
                output = out,
                cipher = cipher,
                maxBytes = MAX_BACKUP_BYTES,
                tooLargeMessage = tooLargeMessage,
                initialTotalBytes = headerBytes.toLong()
            )
        }
    }.getOrElse { ex ->
        if (ex.message == tooLargeMessage) throw ex
        error("Incorrect backup password or corrupted file")
    }
    if (cipherBytes < GCM_TAG_BYTES) {
        error("Corrupted encrypted backup file")
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun decryptV2Stream(
    prefix: ByteArray,
    input: InputStream,
    tooLargeMessage: String,
    password: String,
    aadOverride: ByteArray?
): String {
    if (!hasPrefix(prefix, BackupManager.MAGIC_HEADER)) {
        error("Corrupted encrypted backup file")
    }
    val iterBytes = readExactOrNull(input, ITERATIONS_FIELD_SIZE) ?: error("Corrupted encrypted backup file")
    val salt = readExactOrNull(input, SALT_SIZE) ?: error("Corrupted encrypted backup file")
    val iv = readExactOrNull(input, IV_SIZE) ?: error("Corrupted encrypted backup file")
    val iterations = ByteBuffer.wrap(iterBytes).int
    if (iterations !in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
        error("Corrupted encrypted backup file")
    }
    val header = ByteArray(prefix.size + ITERATIONS_FIELD_SIZE + SALT_SIZE + IV_SIZE)
    System.arraycopy(prefix, 0, header, 0, prefix.size)
    System.arraycopy(iterBytes, 0, header, prefix.size, ITERATIONS_FIELD_SIZE)
    System.arraycopy(salt, 0, header, prefix.size + ITERATIONS_FIELD_SIZE, SALT_SIZE)
    System.arraycopy(iv, 0, header, prefix.size + ITERATIONS_FIELD_SIZE + SALT_SIZE, IV_SIZE)

    val out = ByteArrayOutputStream(STREAM_BUFFER_SIZE)
    val cipherBytes = runBackupCatching {
        runCipherWithWipedKey(
            password = password,
            salt = salt,
            iterations = iterations,
            iv = iv,
            mode = Cipher.DECRYPT_MODE,
            aad = aadOverride ?: header
        ) { cipher ->
            streamThroughCipher(
                input = input,
                output = out,
                cipher = cipher,
                maxBytes = MAX_BACKUP_BYTES,
                tooLargeMessage = tooLargeMessage,
                initialTotalBytes = header.size.toLong()
            )
        }
    }.getOrElse { ex ->
        if (ex.message == tooLargeMessage) throw ex
        error("Incorrect backup password or corrupted file")
    }
    if (cipherBytes < GCM_TAG_BYTES) {
        error("Corrupted encrypted backup file")
    }
    return out.toString(Charsets.UTF_8.name())
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
    for ((group, node) in settings.getAllSelectedNodesForBackup()) {
        map["sel_node_$group"] = node
    }
    return map
}

/** Writes each present key back; absent or malformed values are skipped silently. */
private fun restoreSettings(settings: SettingsManager, map: Map<String, String>) {
    restoreTunnelAndLanSettings(settings, map)
    restoreDnsAndSecuritySettings(settings, map)
    restoreUiAndNodeSettings(settings, map)
}

private fun restoreTunnelAndLanSettings(settings: SettingsManager, map: Map<String, String>) {
    map["tunnelMode"]?.let { settings.tunnelMode = it }
    map["tunStack"]?.let { settings.tunStack = it }
    map["perAppProxyMode"]?.let {
        try {
            settings.perAppProxyMode = PerAppProxyMode.valueOf(it)
        } catch (_: IllegalArgumentException) {
            // Skip unknown enum value from backup
        }
    }
    map["selectedPackages"]?.let {
        settings.selectedPackages = it.split(',').filter { pkg -> pkg.isNotBlank() }.toSet()
    }
    map["bootOnStartup"]?.let { settings.bootOnStartup = it == "true" }
    val decision = BackupManager.decideLanRestore(
        wantsLan = map["allowLan"] == "true",
        backupLanPassword = map["allowLanPassword"],
        currentPassword = settings.allowLanPassword
    )
    if (decision.rejected) {
        Log.w(BACKUP_TAG, "Backup requested LAN sharing without a password; keeping LAN sharing disabled")
        LogRepository.addLog(
            "warning",
            "备份中启用了局域网共享但没有访问密码，已保持局域网共享关闭（否则混合端口会在局域网上完全无鉴权开放）"
        )
    }
    decision.password?.let { settings.allowLanPassword = it }
    settings.allowLan = decision.allowLan
}

private fun restoreDnsAndSecuritySettings(settings: SettingsManager, map: Map<String, String>) {
    map["fakeIpEnabled"]?.let { settings.fakeIpEnabled = it == "true" }
    map["ipv6Enabled"]?.let { settings.ipv6Enabled = it == "true" }
    map["dnsIpv6Enabled"]?.let { settings.dnsIpv6Enabled = it == "true" }
    map["dnsNameservers"]?.let {
        settings.dnsNameservers = ConfigSanitizer.sanitizeDnsServerListSetting(it)
    }
    map["dnsFallbackServers"]?.let {
        settings.dnsFallbackServers = ConfigSanitizer.sanitizeDnsServerListSetting(it)
    }
    map["mixedPort"]?.let { value -> value.toIntOrNull()?.let { settings.mixedPort = it } }
    map["tunMtu"]?.let { value -> value.toIntOrNull()?.let { settings.tunMtu = it } }
    map["killSwitchEnabled"]?.let { settings.killSwitchEnabled = it == "true" }
    map["allowBypassEnabled"]?.let { settings.allowBypassEnabled = it == "true" }
    map["allowPrivateSubscriptionHosts"]?.let {
        settings.allowPrivateSubscriptionHosts = it == "true"
    }
    map["blockThirdPartyDns"]?.let { settings.blockThirdPartyDns = it == "true" }
}

private fun restoreUiAndNodeSettings(settings: SettingsManager, map: Map<String, String>) {
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
    map["themeMode"]?.let {
        try {
            settings.themeMode = ThemeMode.valueOf(it)
        } catch (_: IllegalArgumentException) {
            // Skip unknown theme enum value
        }
    }
    map["appLanguage"]?.let { settings.appLanguage = it }
    map["appFont"]?.let {
        try {
            settings.appFont = AppFont.valueOf(it)
        } catch (_: IllegalArgumentException) {
            // Skip unknown font enum value
        }
    }
    for ((key, value) in map) {
        if (key.startsWith("sel_node_") && value.isNotBlank()) {
            settings.restoreLegacySelection(key.removePrefix("sel_node_"), value)
        }
    }
}
