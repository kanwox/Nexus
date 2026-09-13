package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
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
    val updatedAt: Long = System.currentTimeMillis()
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

object ScriptManager {
    private const val TAG = "ScriptManager"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        getScriptsPrefs(context).edit().putString("script_items", raw).apply()
        // Script content changes the parsed profile, whose cache key does not include it.
        ProfileParser.clearCache()
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
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP 错误: ${response.code} ${response.message}")
                }

                val body = response.body?.string() ?: error("脚本内容为空")
                val id = java.util.UUID.randomUUID().toString()
                val fileName = "$id.js"
                val file = getScriptsDir(context).resolve(fileName)
                file.writeText(body)

                val item = ScriptItem(
                    id = id,
                    name = name.ifBlank { url.substringAfterLast("/").substringBefore("?") },
                    url = url,
                    pattern = pattern,
                    type = type,
                    scriptFile = fileName,
                    enabled = true,
                    updatedAt = System.currentTimeMillis()
                )

                val current = getScripts(context).toMutableList()
                current.add(0, item)
                saveScripts(context, current)
                Log.i(TAG, "Successfully downloaded script: ${item.name} ($fileName)")
                item
            }
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
        file.writeText(code)

        val item = ScriptItem(
            id = id,
            name = name,
            url = "",
            pattern = pattern,
            type = type,
            scriptFile = fileName,
            enabled = true,
            updatedAt = System.currentTimeMillis()
        )

        val current = getScripts(context).toMutableList()
        current.add(0, item)
        saveScripts(context, current)
        return item
    }

    fun readScriptCode(context: Context, script: ScriptItem): String {
        val file = getScriptsDir(context).resolve(script.scriptFile)
        return if (file.exists()) file.readText() else ""
    }

    fun updateScriptCode(context: Context, script: ScriptItem, newCode: String) {
        val file = getScriptsDir(context).resolve(script.scriptFile)
        file.writeText(newCode)
        val current = getScripts(context).toMutableList()
        val idx = current.indexOfFirst { it.id == script.id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(updatedAt = System.currentTimeMillis())
            saveScripts(context, current)
        }
    }

    suspend fun updateScriptFromRemote(context: Context, script: ScriptItem): Result<ScriptItem> = withContext(Dispatchers.IO) {
        runCatching {
            if (script.url.isBlank()) error("无远程更新链接")
            requireSecureRemoteUrl(script.url)
            val request = Request.Builder()
                .url(script.url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("更新失败 HTTP ${response.code}")
                val body = response.body?.string() ?: error("响应内容为空")

                val file = getScriptsDir(context).resolve(script.scriptFile)
                file.writeText(body)

                val updated = script.copy(updatedAt = System.currentTimeMillis())
                val current = getScripts(context).toMutableList()
                val idx = current.indexOfFirst { it.id == script.id }
                if (idx >= 0) {
                    current[idx] = updated
                    saveScripts(context, current)
                }
                updated
            }
        }
    }

    fun toggleScript(context: Context, id: String, enabled: Boolean) {
        val current = getScripts(context).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            saveScripts(context, current)
        }
    }

    fun deleteScript(context: Context, id: String) {
        val current = getScripts(context).toMutableList()
        val item = current.firstOrNull { it.id == id }
        if (item != null) {
            val file = getScriptsDir(context).resolve(item.scriptFile)
            if (file.exists()) file.delete()
            current.removeAll { it.id == id }
            saveScripts(context, current)
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
        getScriptsPrefs(context).edit().putString("rewrite_rules", raw).apply()
        ProfileParser.clearCache()
    }

    fun saveRewrite(context: Context, rule: RewriteRule) {
        val current = getRewrites(context).toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
        } else {
            current.add(0, rule)
        }
        saveRewrites(context, current)
    }

    fun toggleRewrite(context: Context, id: String, enabled: Boolean) {
        val current = getRewrites(context).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            saveRewrites(context, current)
        }
    }

    fun deleteRewrite(context: Context, id: String) {
        val current = getRewrites(context).toMutableList()
        current.removeAll { it.id == id }
        saveRewrites(context, current)
    }
}
