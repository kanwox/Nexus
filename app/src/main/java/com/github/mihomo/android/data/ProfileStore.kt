package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal object ProfileStore {
    private const val TAG = "ProfileStore"
    private const val SUBSCRIPTION_HISTORY_KEEP = 3
    private const val MIGRATIONS_PREFS = "mihomo_migrations"
    private const val KEY_LEGACY_SCOPES_MIGRATED = "legacy_script_scopes_migrated"

    @Serializable
    internal data class ProfileMeta(
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

    @Serializable
    internal data class ProfilesStoreData(
        val profiles: Map<String, ProfileMeta> = emptyMap(),
        val order: List<String> = emptyList()
    )

    internal val storeLock = Any()
    private val storeJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var storeCache: ProfilesStoreData? = null

    @Volatile
    private var cachedProfiles: List<ProfileItem>? = null

    fun getConfigFile(context: Context): File = context.filesDir.resolve("config.yaml")

    fun getProfilesDir(context: Context): File = context.filesDir.resolve("profiles").apply { mkdirs() }

    fun profileFile(context: Context, id: String): File? =
        SafeFileName.resolveChild(getProfilesDir(context), "$id.yaml")

    fun profileBackupFile(context: Context, id: String): File? =
        SafeFileName.resolveChild(getProfilesDir(context), "$id.yaml.bak")

    fun profileStagedFile(context: Context, id: String): File? =
        SafeFileName.resolveChild(getProfilesDir(context), "$id.yaml.staged")

    fun profileHistoryFiles(context: Context, id: String): List<File> =
        (1..SUBSCRIPTION_HISTORY_KEEP).mapNotNull { version ->
            SafeFileName.resolveChild(getProfilesDir(context), "$id.yaml.v$version")
        }

    /** Newest history slot of a profile (.v1 is newest, .v3 is oldest). */
    fun newestProfileHistoryFile(context: Context, id: String): File? =
        profileHistoryFiles(context, id).firstOrNull { it.exists() && it.length() > 0 }

    fun archiveKnownGoodVersion(context: Context, id: String) {
        synchronized(storeLock) {
            val backup = profileBackupFile(context, id)
                ?.takeIf { it.exists() && it.length() > 0 }
                ?: return
            val content = runCatching { backup.readText() }.getOrDefault("")
            if (!ConfigValidation.isValidSubscriptionPayload(content)) return
            val ring = profileHistoryFiles(context, id)
            ring.lastOrNull()?.delete()
            for (i in ring.size - 1 downTo 1) {
                runCatching {
                    java.nio.file.Files.move(
                        ring[i - 1].toPath(),
                        ring[i].toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
            ring.firstOrNull()?.let { target ->
                runCatching { ConfigManager.writeTextAtomic(target, content) }
            }
            Log.i(TAG, "Archived known-good version of profile $id into the history ring")
        }
    }

    fun restoreProfileFromHistory(context: Context, id: String, source: File): Result<Unit> {
        return runCatching {
            synchronized(storeLock) {
                val target = profileFile(context, id) ?: error("无法为订阅创建安全的文件路径")
                if (!source.exists() || source.length() == 0L) error("历史版本文件为空或不存在")
                val content = source.readText()
                val resolved = ConfigValidation.resolveSubscriptionYaml(content) ?: error("历史版本内容无效")
                ConfigManager.writeTextAtomic(target, resolved)
                touchProfileTimestamp(context, id)
            }
            invalidateProfilesCache()
            Log.i(TAG, "Restored profile $id from history file ${source.name}")
        }
    }

    fun rollbackSubscriptionUpdate(context: Context, id: String): Boolean {
        val backup = profileBackupFile(context, id)
            ?.takeIf { it.exists() && it.length() > 0 }
            ?: newestProfileHistoryFile(context, id)
            ?: return false
        val restored = restoreProfileFromHistory(context, id, backup).isSuccess
        if (restored) {
            LogRepository.addLog("warning", "订阅配置热重载失败，已自动回滚到上一个可用版本")
        }
        return restored
    }

    fun invalidateProfilesCache() {
        synchronized(storeLock) { cachedProfiles = null }
        ProfileParser.clearCache()
    }

    internal fun resetForTest() {
        synchronized(storeLock) {
            storeCache = null
            cachedProfiles = null
        }
        ProfileParser.clearCache()
    }

    private fun storeFile(context: Context): File = context.filesDir.resolve("profiles_meta.json")

    private fun storeBackupFile(context: Context): File = context.filesDir.resolve("profiles_meta.json.bak")

    internal fun loadStoreLocked(context: Context): ProfilesStoreData {
        storeCache?.let { return it }
        val loaded = readStoreFromDisk(context)
        storeCache = loaded
        return loaded
    }

    private fun readStoreFromDisk(context: Context): ProfilesStoreData {
        val file = storeFile(context)
        if (!file.exists()) return migrateLegacyPrefsStore(context)
        val parsed = runCatching { storeJson.decodeFromString<ProfilesStoreData>(file.readText()) }
        if (parsed.isSuccess) return parsed.getOrThrow()
        Log.w(TAG, "Failed to read profiles metadata store, trying backup", parsed.exceptionOrNull())
        val backup = runCatching { storeJson.decodeFromString<ProfilesStoreData>(storeBackupFile(context).readText()) }
        if (backup.isSuccess) {
            Log.w(TAG, "Recovered profiles metadata from backup")
            return backup.getOrThrow()
        }
        Log.w(TAG, "Profiles metadata unrecoverable; quarantining corrupt files and rebuilding from profiles directory")
        val stamp = System.currentTimeMillis()
        runCatching {
            if (file.exists()) file.renameTo(context.filesDir.resolve("profiles_meta.json.corrupt.$stamp"))
            val bakFile = storeBackupFile(context)
            if (bakFile.exists()) bakFile.renameTo(context.filesDir.resolve("profiles_meta.json.bak.corrupt.$stamp"))
        }
        val rebuilt = rebuildStoreFromProfileFiles(context)
        return rebuilt
    }

    private fun rebuildStoreFromProfileFiles(context: Context): ProfilesStoreData {
        val files = getProfilesDir(context).listFiles { f -> f.extension == "yaml" } ?: emptyArray()
        if (files.isEmpty()) return ProfilesStoreData()
        val metas = LinkedHashMap<String, ProfileMeta>()
        for (yamlFile in files.sortedByDescending { it.lastModified() }) {
            val id = yamlFile.nameWithoutExtension
            metas[id] = ProfileMeta(name = id, updated = yamlFile.lastModified())
        }
        return ProfilesStoreData(profiles = metas, order = metas.keys.toList())
    }

    private fun migrateLegacyPrefsStore(context: Context): ProfilesStoreData {
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
        val store = ProfilesStoreData(profiles = metas, order = order)
        val written = runCatching {
            ConfigManager.writeTextAtomic(storeFile(context), storeJson.encodeToString(store))
        }.onFailure { err ->
            Log.w(TAG, "Failed to persist migrated profiles metadata", err)
        }.isSuccess
        if (written) {
            prefs.edit { clear() }
        }
        return store
    }

    internal fun persistStoreLocked(context: Context, store: ProfilesStoreData) {
        val json = storeJson.encodeToString(store)
        try {
            ConfigManager.writeTextAtomic(storeFile(context), json)
            runCatching { ConfigManager.writeTextAtomic(storeBackupFile(context), json) }
            storeCache = store
            cachedProfiles = null
        } catch (err: Throwable) {
            Log.w(TAG, "Failed to persist profiles metadata", err)
            throw err
        }
    }

    fun getProfiles(context: Context, forceReload: Boolean = false): List<ProfileItem> {
        if (!forceReload) {
            cachedProfiles?.let { return it }
        }
        synchronized(storeLock) {
            if (!forceReload) {
                cachedProfiles?.let { return it }
            }
            val dir = getProfilesDir(context)
            val files = dir.listFiles { f -> f.extension == "yaml" } ?: emptyArray()
            val store = loadStoreLocked(context)
            val items = files.map { file -> buildProfileItem(file, store.profiles[file.nameWithoutExtension]) }
            val result = sortProfileItems(items, store.order)
            cachedProfiles = result
            return result
        }
    }

    private fun buildProfileItem(file: File, storedMeta: ProfileMeta?): ProfileItem {
        val id = file.nameWithoutExtension
        val meta = storedMeta ?: ProfileMeta(name = id, updated = file.lastModified())
        return ProfileItem(
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

    private fun sortProfileItems(items: List<ProfileItem>, order: List<String>): List<ProfileItem> {
        if (order.isEmpty()) return items.sortedByDescending { it.lastUpdated }
        val orderIndex = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { index, id -> orderIndex[id] = index }
        return items.sortedWith(compareBy { orderIndex[it.id] ?: Int.MAX_VALUE })
    }

    fun saveProfileOrder(context: Context, orderIds: List<String>) {
        synchronized(storeLock) {
            val store = loadStoreLocked(context)
            persistStoreLocked(context, store.copy(order = orderIds))
        }
        invalidateProfilesCache()
    }

    fun saveProfileMetadata(context: Context, item: ProfileItem) {
        synchronized(storeLock) {
            val store = loadStoreLocked(context)
            persistStoreLocked(context, store.copy(profiles = store.profiles + (item.id to item.toMeta())))
        }
    }

    fun touchProfileTimestamp(context: Context, id: String) {
        synchronized(storeLock) {
            val store = loadStoreLocked(context)
            store.profiles[id]?.let { meta ->
                persistStoreLocked(
                    context,
                    store.copy(profiles = store.profiles + (id to meta.copy(updated = System.currentTimeMillis())))
                )
            }
        }
    }

    fun commitSubscriptionUpdate(
        context: Context,
        id: String,
        fallbackName: String,
        url: String,
        file: File,
        stagedFile: File,
        backupFile: File? = null,
        userInfo: SubscriptionUserInfo? = null
    ): ProfileItem {
        synchronized(storeLock) {
            val store = loadStoreLocked(context)
            val existing = store.profiles[id]
            if (existing == null || !file.exists()) {
                stagedFile.delete()
                error("订阅配置已被删除，已取消本次更新")
            }
            if (file.length() > 0L && backupFile != null) {
                runCatching { file.copyTo(backupFile, overwrite = true) }
            }
            moveReplacingFile(stagedFile, file)
            val resolvedName = existing.name.ifBlank { fallbackName }
            val updatedItem = ProfileItem(
                id = id,
                name = resolvedName,
                url = url,
                file = file,
                lastUpdated = System.currentTimeMillis(),
                scriptEnabled = existing.scriptEnabled,
                scriptIds = existing.scriptIds,
                autoUpdate = existing.autoUpdate,
                autoUpdateInterval = existing.autoUpdateInterval,
                uploadBytes = userInfo?.upload ?: existing.uploadBytes,
                downloadBytes = userInfo?.download ?: existing.downloadBytes,
                totalBytes = userInfo?.total ?: existing.totalBytes,
                expireTimestamp = userInfo?.expire ?: existing.expireTimestamp,
                prependRules = existing.prependRules,
                hostsEntries = existing.hostsEntries,
                dnsServersOverride = existing.dnsServersOverride
            )
            persistStoreLocked(context, store.copy(profiles = store.profiles + (id to updatedItem.toMeta())))
            return updatedItem
        }
    }

    fun deleteProfile(context: Context, id: String) {
        synchronized(storeLock) {
            profileFile(context, id)?.delete()
            profileBackupFile(context, id)?.delete()
            profileStagedFile(context, id)?.delete()
            profileHistoryFiles(context, id).forEach { it.delete() }
            cleanupTempFilesForProfile(context, id)
            val store = loadStoreLocked(context)
            persistStoreLocked(
                context,
                store.copy(profiles = store.profiles - id, order = store.order.filter { it != id })
            )
        }
        runCatching { SettingsManager(context).clearSelectedNodesForProfile(id) }
        invalidateProfilesCache()
    }

    internal fun moveReplacingFile(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: Exception) {
            // Fall back to plain replace when filesystem refuses ATOMIC_MOVE.
        }
        java.nio.file.Files.move(
            source.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun cleanupTempFilesForProfile(context: Context, id: String) {
        val prefix = "$id.yaml."
        getProfilesDir(context)
            .listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    fun migrateLegacyScriptScopes(context: Context) {
        val prefs = context.getSharedPreferences(MIGRATIONS_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_SCOPES_MIGRATED, false)) return
        val enabledIds = ScriptManager.getScripts(context).filter { it.enabled }.map { it.id }
        synchronized(storeLock) {
            if (prefs.getBoolean(KEY_LEGACY_SCOPES_MIGRATED, false)) return
            if (enabledIds.isNotEmpty()) {
                val store = loadStoreLocked(context)
                var changed = false
                val migrated = store.profiles.mapValues { (_, meta) ->
                    if (meta.scriptEnabled && meta.scriptIds.isEmpty()) {
                        changed = true
                        meta.copy(scriptIds = enabledIds)
                    } else {
                        meta
                    }
                }
                if (changed) {
                    persistStoreLocked(context, store.copy(profiles = migrated))
                }
            }
            prefs.edit { putBoolean(KEY_LEGACY_SCOPES_MIGRATED, true) }
        }
        invalidateProfilesCache()
    }

    private fun ProfileItem.toMeta(): ProfileMeta = ProfileMeta(
        name = name,
        url = url,
        updated = lastUpdated,
        scriptEnabled = scriptEnabled,
        scriptIds = scriptIds,
        autoUpdate = autoUpdate,
        autoUpdateInterval = autoUpdateInterval,
        uploadBytes = uploadBytes,
        downloadBytes = downloadBytes,
        totalBytes = totalBytes,
        expireTimestamp = expireTimestamp,
        prependRules = prependRules,
        hostsEntries = hostsEntries,
        dnsServersOverride = dnsServersOverride
    )
}
