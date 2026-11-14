@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import org.yaml.snakeyaml.Yaml

/**
 * Outcome of running a chain of config-rewrite scripts.
 *
 * [failed] lists the scripts that did not run to completion. The caller must not ship a
 * half-rewritten config: the scripts are applied in order and each one sees the output of
 * the previous one, so a failure in the middle leaves a config that is neither the original
 * nor the intended result. [yaml] therefore only carries a fully applied chain; on failure
 * the caller is expected to fall back to the original YAML.
 */
data class ScriptExecutionResult(
    val yaml: String,
    val applied: Int,
    val failed: List<String>,
) {
    val hasFailures: Boolean get() = failed.isNotEmpty()
    val total: Int get() = applied + failed.size
}

object ConfigScriptEngine {
    private const val TAG = "ConfigScriptEngine"
    private const val SCRIPT_TIMEOUT_MS = 5_000L

    private val JS_ENTRY_REGEX = Regex(
        """\b(function\s+main\s*\(|\bmain\s*[=(]|module\.exports|export\s+default\b)"""
    )

    /** Overridable in tests so the infinite-loop interruption test stays fast. */
    internal var scriptTimeoutMs: Long = SCRIPT_TIMEOUT_MS

    internal fun looksLikeJavaScript(code: String): Boolean =
        JS_ENTRY_REGEX.containsMatchIn(code) || code.contains("=>")

    internal enum class ScriptKind { JS, YAML }

    /**
     * Decides how a script is executed. Shared by both [previewScript] and [executeScripts]
     * so preview and runtime routing never diverge.
     */
    internal fun routeScriptKind(code: String, yamlParser: Yaml): ScriptKind? {
        if (looksLikeJavaScript(code)) return ScriptKind.JS
        if (code.isNotBlank() && runCatching { yamlParser.load<Any>(code) is Map<*, *> }.getOrDefault(false)) {
            return ScriptKind.YAML
        }
        val hasJsFallbackKeyword = code.contains("function") || code.contains("=>") || code.contains("module")
        return if (hasJsFallbackKeyword) ScriptKind.JS else null
    }

    data class ScriptPreviewResult(
        val success: Boolean,
        val engineName: String,
        val durationMs: Long,
        val outputYaml: String,
        val consoleLogs: List<String>,
        val errorMessage: String? = null,
        val beforeProxies: Int = 0,
        val afterProxies: Int = 0,
        val beforeGroups: Int = 0,
        val afterGroups: Int = 0,
        val beforeRules: Int = 0,
        val afterRules: Int = 0
    )

    @JvmOverloads
    internal fun anyToJsonElement(
        value: Any?,
        maxDepth: Int = ConfigScriptSupport.MAX_YAML_TRAVERSAL_DEPTH,
        maxNodes: Int = ConfigScriptSupport.MAX_YAML_TRAVERSAL_NODES
    ): JsonElement = ConfigScriptSupport.anyToJsonElement(value, maxDepth, maxNodes)

    @JvmOverloads
    internal fun normalizeYamlData(
        data: Any?,
        maxDepth: Int = ConfigScriptSupport.MAX_YAML_TRAVERSAL_DEPTH,
        maxNodes: Int = ConfigScriptSupport.MAX_YAML_TRAVERSAL_NODES
    ): Any? = ConfigScriptSupport.normalizeYamlData(data, maxDepth, maxNodes)

    @Suppress("unused")
    suspend fun previewScript(
        rawYaml: String,
        code: String,
        scriptName: String = "preview.js"
    ): ScriptPreviewResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val effectiveInputYaml = rawYaml.takeIf { it.isNotBlank() } ?: ConfigScriptSupport.SAMPLE_PREVIEW_YAML
        val yamlParser = YamlSupport.create()
        val baseMap = loadYamlMapOrNull(yamlParser, effectiveInputYaml)
        val beforeStats = ConfigScriptSupport.countMapStats(baseMap)
        val logs = mutableListOf<String>()

        if (code.isBlank()) {
            return@withContext buildFailedPreview(
                engineName = "None",
                durationMs = 0L,
                outputYaml = effectiveInputYaml,
                logs = listOf("[WARN] 脚本内容为空"),
                errorMessage = "脚本代码不能为空",
                stats = beforeStats
            )
        }

        try {
            when (routeScriptKind(code, yamlParser)) {
                ScriptKind.JS -> previewJsScript(
                    code = code,
                    scriptName = scriptName,
                    effectiveInputYaml = effectiveInputYaml,
                    baseMap = baseMap,
                    yamlParser = yamlParser,
                    beforeStats = beforeStats,
                    logs = logs,
                    startMs = startMs
                )
                ScriptKind.YAML -> previewYamlSnippet(
                    code = code,
                    effectiveInputYaml = effectiveInputYaml,
                    baseMap = baseMap,
                    yamlParser = yamlParser,
                    beforeStats = beforeStats,
                    logs = logs,
                    startMs = startMs
                )
                null -> {
                    val elapsed = elapsedSince(startMs)
                    val msg = "未检测到 main(config) 函数，且内容不是有效的 YAML 键值对片段"
                    logs.add("[ERROR] $msg")
                    buildFailedPreview("Error", elapsed, effectiveInputYaml, logs, msg, beforeStats)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val elapsed = elapsedSince(startMs)
            val msg = e.message ?: e.javaClass.simpleName
            logs.add("[ERROR] $msg")
            buildFailedPreview("Error", elapsed, effectiveInputYaml, logs, e.message ?: "脚本运行异常", beforeStats)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun previewJsScript(
        code: String,
        scriptName: String,
        effectiveInputYaml: String,
        baseMap: Map<*, *>?,
        yamlParser: Yaml,
        beforeStats: Triple<Int, Int, Int>,
        logs: MutableList<String>,
        startMs: Long
    ): ScriptPreviewResult {
        if (baseMap == null) {
            val msg = "输入配置不是合法的 YAML 格式"
            logs.add("[ERROR] $msg")
            return buildFailedPreview("Error", elapsedSince(startMs), effectiveInputYaml, logs, msg, beforeStats)
        }
        val jsonStr = ConfigScriptSupport.scriptJson.encodeToString(anyToJsonElement(baseMap))
        val resultJson: String? = try {
            ConfigScriptSupport.executeWithQuickJS(code, jsonStr, scriptName, scriptTimeoutMs, logs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val errMsg = e.message ?: "语法或运行时错误"
            logs.add("[ERROR] $errMsg")
            return buildFailedPreview(
                "QuickJS (ES2020)",
                elapsedSince(startMs),
                effectiveInputYaml,
                logs,
                errMsg,
                beforeStats
            )
        }
        val modifiedMap = ConfigScriptSupport.parseResultMap(resultJson)
        val finalMap = if (modifiedMap != null) {
            ConfigScriptSupport.mergeJsResultPreservingCoreLists(baseMap, modifiedMap)
        } else {
            ConfigScriptSupport.mergeYamlMaps(baseMap, emptyMap<Any?, Any?>())
        }
        val normalized = normalizeYamlData(finalMap) as? Map<*, *>
        val (afterP, afterG, afterR) = ConfigScriptSupport.countMapStats(normalized)
        val outYaml = yamlParser.dump(normalized ?: finalMap)
        val elapsed = elapsedSince(startMs)
        logs.add(0, "[OK] 使用 QuickJS (ES2020) 执行完成 (${elapsed}ms)")
        return ScriptPreviewResult(
            success = true,
            engineName = "QuickJS (ES2020)",
            durationMs = elapsed,
            outputYaml = outYaml,
            consoleLogs = logs,
            beforeProxies = beforeStats.first,
            afterProxies = afterP,
            beforeGroups = beforeStats.second,
            afterGroups = afterG,
            beforeRules = beforeStats.third,
            afterRules = afterR
        )
    }

    private fun previewYamlSnippet(
        code: String,
        effectiveInputYaml: String,
        baseMap: Map<*, *>?,
        yamlParser: Yaml,
        beforeStats: Triple<Int, Int, Int>,
        logs: MutableList<String>,
        startMs: Long
    ): ScriptPreviewResult {
        val snippetMap = runCatching { yamlParser.load<Any>(code) as? Map<*, *> }.getOrNull()
        if (snippetMap == null) {
            val msg = "未检测到 main(config) 函数，且内容不是有效的 YAML 键值对片段"
            logs.add("[ERROR] $msg")
            return buildFailedPreview("Error", elapsedSince(startMs), effectiveInputYaml, logs, msg, beforeStats)
        }
        val mergedMap = ConfigScriptSupport.mergeYamlMaps(baseMap, snippetMap)
        val normalized = normalizeYamlData(mergedMap) as? Map<*, *>
        val (afterP, afterG, afterR) = ConfigScriptSupport.countMapStats(normalized)
        val outYaml = yamlParser.dump(normalized ?: mergedMap)
        val elapsed = elapsedSince(startMs)
        logs.add("[OK] YAML 覆写片段合并完成 (${elapsed}ms)")
        return ScriptPreviewResult(
            success = true,
            engineName = "YAML Merge",
            durationMs = elapsed,
            outputYaml = outYaml,
            consoleLogs = logs,
            beforeProxies = beforeStats.first,
            afterProxies = afterP,
            beforeGroups = beforeStats.second,
            afterGroups = afterG,
            beforeRules = beforeStats.third,
            afterRules = afterR
        )
    }

    private fun buildFailedPreview(
        engineName: String,
        durationMs: Long,
        outputYaml: String,
        logs: List<String>,
        errorMessage: String,
        stats: Triple<Int, Int, Int>
    ): ScriptPreviewResult = ScriptPreviewResult(
        success = false,
        engineName = engineName,
        durationMs = durationMs,
        outputYaml = outputYaml,
        consoleLogs = logs,
        errorMessage = errorMessage,
        beforeProxies = stats.first,
        afterProxies = stats.first,
        beforeGroups = stats.second,
        afterGroups = stats.second,
        beforeRules = stats.third,
        afterRules = stats.third
    )

    private fun elapsedSince(startMs: Long): Long =
        (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

    suspend fun executeScripts(
        context: Context,
        rawYaml: String,
        targetScriptIds: List<String>? = null,
        logToRepo: Boolean = true
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        if (targetScriptIds.isNullOrEmpty()) return@withContext ScriptExecutionResult(rawYaml, 0, emptyList())
        val scripts = ScriptManager.getScripts(context).filter { targetScriptIds.contains(it.id) && it.enabled }
        if (scripts.isEmpty()) return@withContext ScriptExecutionResult(rawYaml, 0, emptyList())

        var currentYaml = rawYaml
        var applied = 0
        val failed = mutableListOf<String>()
        val yamlParser = YamlSupport.create()

        for (script in scripts) {
            if (!ScriptManager.verifyScriptIntegrity(context, script)) {
                Log.e(TAG, "Script ${script.name} content does not match its pinned fingerprint; skipping")
                if (logToRepo) {
                    LogRepository.addLog("warning", "脚本 [${script.name}] 内容与指纹不符，已跳过执行")
                }
                failed += script.name
                continue
            }
            val code = ScriptManager.readScriptCode(context, script)
            if (code.isBlank()) continue

            val stepOutcome = executeSingleScriptStep(script, code, currentYaml, yamlParser, logToRepo)
            when (stepOutcome) {
                is SingleScriptOutcome.Applied -> {
                    currentYaml = stepOutcome.newYaml
                    applied++
                }
                is SingleScriptOutcome.NoOp -> Unit
                is SingleScriptOutcome.Failed -> failed += script.name
            }
        }

        if (failed.isNotEmpty() && logToRepo) {
            LogRepository.addLog(
                "warning",
                "配置脚本未全部成功：${failed.size}/${scripts.size} 个脚本失败（${failed.joinToString("、")}），本次将使用未改写的原始配置"
            )
        }

        ScriptExecutionResult(
            yaml = if (failed.isEmpty()) currentYaml else rawYaml,
            applied = applied,
            failed = failed
        )
    }

    private sealed class SingleScriptOutcome {
        data class Applied(val newYaml: String) : SingleScriptOutcome()
        data object NoOp : SingleScriptOutcome()
        data object Failed : SingleScriptOutcome()
    }

    private suspend fun executeSingleScriptStep(
        script: ScriptItem,
        code: String,
        currentYaml: String,
        yamlParser: Yaml,
        logToRepo: Boolean
    ): SingleScriptOutcome {
        return try {
            when (routeScriptKind(code, yamlParser)) {
                ScriptKind.JS -> applyJsScriptStep(script, code, currentYaml, yamlParser, logToRepo)
                ScriptKind.YAML -> applyYamlSnippetStep(script, code, currentYaml, yamlParser, logToRepo)
                null -> {
                    Log.w(TAG, "Script ${script.name} content is not JS or YAML; ignored")
                    if (logToRepo) {
                        LogRepository.addLog("warning", "脚本 [${script.name}] 内容无法识别，已忽略")
                    }
                    SingleScriptOutcome.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing script ${script.name}", e)
            if (logToRepo) {
                LogRepository.addLog("warning", "执行脚本 [${script.name}] 失败: ${e.message ?: e.javaClass.simpleName}")
            }
            SingleScriptOutcome.Failed
        }
    }

    private fun loadYamlOrNull(yamlParser: Yaml, text: String): Any? =
        runCatching { yamlParser.load<Any>(text) }.getOrNull()

    private fun loadYamlMapOrNull(yamlParser: Yaml, text: String): Map<*, *>? =
        loadYamlOrNull(yamlParser, text) as? Map<*, *>

    private suspend fun applyJsScriptStep(
        script: ScriptItem,
        code: String,
        currentYaml: String,
        yamlParser: Yaml,
        logToRepo: Boolean
    ): SingleScriptOutcome {
        val configMap: Any = loadYamlOrNull(yamlParser, currentYaml)
            ?: return SingleScriptOutcome.NoOp
        val jsonStr = ConfigScriptSupport.scriptJson.encodeToString(anyToJsonElement(configMap))
        val scriptLogs = mutableListOf<String>()
        val resultJson: String? = try {
            ConfigScriptSupport.executeWithQuickJS(code, jsonStr, script.name, scriptTimeoutMs, scriptLogs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("QuickJS: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val modifiedMap = ConfigScriptSupport.parseResultMap(resultJson)
        if (modifiedMap.isNullOrEmpty()) {
            if (logToRepo) {
                val warn = scriptLogs.firstOrNull { it.startsWith("[WARN]") }
                    ?: "未返回有效配置对象，本次改写未生效"
                LogRepository.addLog("warning", "JS 脚本 [${script.name}] $warn")
            }
            return SingleScriptOutcome.NoOp
        }
        val finalMap = ConfigScriptSupport.mergeJsResultPreservingCoreLists(configMap, modifiedMap)
        val normalized = normalizeYamlData(finalMap)
        val dumped = yamlParser.dump(normalized)
        if (dumped.isBlank()) return SingleScriptOutcome.NoOp
        if (logToRepo) {
            LogRepository.addLog("info", "JS 脚本 [${script.name}] 成功复写配置")
        }
        Log.i(TAG, "Successfully applied script: ${script.name}")
        return SingleScriptOutcome.Applied(dumped)
    }

    private fun applyYamlSnippetStep(
        script: ScriptItem,
        code: String,
        currentYaml: String,
        yamlParser: Yaml,
        logToRepo: Boolean
    ): SingleScriptOutcome {
        val snippetMap = runCatching { yamlParser.load<Any>(code) }.getOrNull()
        if (snippetMap !is Map<*, *> || snippetMap.isEmpty()) {
            Log.w(TAG, "Script ${script.name} is neither JS nor a valid YAML snippet; ignored")
            if (logToRepo) {
                LogRepository.addLog("warning", "脚本 [${script.name}] 既不是 JS 也不是有效的 YAML 片段，已忽略")
            }
            return SingleScriptOutcome.Failed
        }
        val baseMap = runCatching { yamlParser.load<Any>(currentYaml) as? Map<*, *> }.getOrNull()
            ?: return SingleScriptOutcome.NoOp
        val mergedMap = ConfigScriptSupport.mergeYamlMaps(baseMap, snippetMap)
        val normalized = normalizeYamlData(mergedMap)
        val dumped = yamlParser.dump(normalized)
        if (dumped.isBlank()) return SingleScriptOutcome.NoOp
        if (logToRepo) {
            LogRepository.addLog("info", "已安全合并脚本 [${script.name}] YAML 片段")
        }
        return SingleScriptOutcome.Applied(dumped)
    }
}
