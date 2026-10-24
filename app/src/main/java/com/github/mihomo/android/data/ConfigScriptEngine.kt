package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.yaml.snakeyaml.Yaml

object ConfigScriptEngine {
    private const val TAG = "ConfigScriptEngine"
    private const val RHINO_TIMEOUT_MS = 5_000L

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

    private val SAMPLE_PREVIEW_YAML = """
        mixed-port: 7890
        allow-lan: false
        mode: rule
        log-level: info
        proxies:
          - name: "🇭🇰 香港 IEPL 01"
            type: ss
            server: hk1.example.com
            port: 8388
            cipher: aes-256-gcm
            password: "password"
          - name: "🇯🇵 日本 BGP 01"
            type: vless
            server: jp1.example.com
            port: 443
            uuid: "00000000-0000-0000-0000-000000000000"
          - name: "🇸🇬 新加坡 高速 01"
            type: trojan
            server: sg1.example.com
            port: 443
            password: "password"
          - name: "🇺🇸 美国 专线 01"
            type: hysteria2
            server: us1.example.com
            port: 443
            password: "password"
        proxy-groups:
          - name: "🚀 节点选择"
            type: select
            proxies:
              - "♻️ 自动选择"
              - "🇭🇰 香港 IEPL 01"
              - "🇯🇵 日本 BGP 01"
              - "🇸🇬 新加坡 高速 01"
              - "🇺🇸 美国 专线 01"
              - DIRECT
          - name: "♻️ 自动选择"
            type: url-test
            url: "http://www.gstatic.com/generate_204"
            interval: 300
            proxies:
              - "🇭🇰 香港 IEPL 01"
              - "🇯🇵 日本 BGP 01"
              - "🇸🇬 新加坡 高速 01"
              - "🇺🇸 美国 专线 01"
        rules:
          - DOMAIN-SUFFIX,google.com,🚀 节点选择
          - DOMAIN-KEYWORD,github,🚀 节点选择
          - GEOIP,CN,DIRECT
          - MATCH,🚀 节点选择
    """.trimIndent()

    private fun countMapStats(map: Map<*, *>?): Triple<Int, Int, Int> {
        if (map == null) return Triple(0, 0, 0)
        val proxies = (map["proxies"] ?: map["Proxy"] ?: map["Proxies"]) as? List<*>
        val groups = (map["proxy-groups"] ?: map["Proxy Group"]) as? List<*>
        val rules = (map["rules"] ?: map["Rule"]) as? List<*>
        return Triple(proxies?.size ?: 0, groups?.size ?: 0, rules?.size ?: 0)
    }

    /**
     * Execute a single script in sandbox preview mode against [rawYaml] (or fallback sample config),
     * capturing console output, structural diffs (nodes/groups/rules), execution time, and output YAML.
     */
    fun previewScript(
        rawYaml: String,
        code: String,
        scriptName: String = "preview.js"
    ): ScriptPreviewResult {
        val startMs = System.currentTimeMillis()
        val effectiveInputYaml = rawYaml.takeIf { it.isNotBlank() } ?: SAMPLE_PREVIEW_YAML
        val dumperOptions = org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val yamlParser = Yaml(dumperOptions)
        val gson = GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .setPrettyPrinting()
            .create()

        val baseMap = runCatching { yamlParser.load<Any>(effectiveInputYaml) as? Map<*, *> }.getOrNull()
        val (beforeP, beforeG, beforeR) = countMapStats(baseMap)
        val logs = mutableListOf<String>()

        if (code.isBlank()) {
            return ScriptPreviewResult(
                success = false,
                engineName = "None",
                durationMs = 0L,
                outputYaml = effectiveInputYaml,
                consoleLogs = listOf("[WARN] 脚本内容为空"),
                errorMessage = "脚本代码不能为空",
                beforeProxies = beforeP,
                afterProxies = beforeP,
                beforeGroups = beforeG,
                afterGroups = beforeG,
                beforeRules = beforeR,
                afterRules = beforeR
            )
        }

        return try {
            val isJs = code.contains("main") ||
                code.contains("module.exports") ||
                code.contains("export default") ||
                code.contains("function") ||
                code.contains("=>")

            if (isJs) {
                if (baseMap == null) {
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    logs.add("[ERROR] 输入配置不是合法的 YAML 格式")
                    return ScriptPreviewResult(
                        success = false,
                        engineName = "Error",
                        durationMs = elapsed,
                        outputYaml = effectiveInputYaml,
                        consoleLogs = logs,
                        errorMessage = "输入配置不是合法的 YAML 格式",
                        beforeProxies = beforeP,
                        afterProxies = beforeP,
                        beforeGroups = beforeG,
                        afterGroups = beforeG,
                        beforeRules = beforeR,
                        afterRules = beforeR
                    )
                }
                val jsonStr = gson.toJson(baseMap)
                var engineUsed = "QuickJS (ES2020)"
                val resultJson: String? = try {
                    executeWithQuickJS(code, jsonStr, scriptName, gson, logs)
                } catch (qjsErr: Throwable) {
                    logs.add("[INFO] QuickJS 切换至 Rhino 兼容引擎: ${qjsErr.message}")
                    engineUsed = "Rhino (ES6)"
                    try {
                        executeWithRhino(code, jsonStr, scriptName, gson, logs)
                    } catch (rhinoErr: Throwable) {
                        val errMsg = rhinoErr.message ?: qjsErr.message ?: "语法或运行时错误"
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        logs.add("[ERROR] $errMsg")
                        return ScriptPreviewResult(
                            success = false,
                            engineName = "Rhino (ES6)",
                            durationMs = elapsed,
                            outputYaml = effectiveInputYaml,
                            consoleLogs = logs,
                            errorMessage = errMsg,
                            beforeProxies = beforeP,
                            afterProxies = beforeP,
                            beforeGroups = beforeG,
                            afterGroups = beforeG,
                            beforeRules = beforeR,
                            afterRules = beforeR
                        )
                    }
                }

                val finalMap = LinkedHashMap<String, Any?>()
                for ((k, v) in baseMap) {
                    if (k != null) finalMap[k.toString()] = v
                }
                if (!resultJson.isNullOrBlank() && resultJson != "undefined" && resultJson != "null") {
                    val modifiedMap = gson.fromJson(resultJson, Map::class.java) as? Map<*, *>
                    if (modifiedMap != null && modifiedMap.isNotEmpty()) {
                        for ((k, v) in modifiedMap) {
                            if (k != null) finalMap[k.toString()] = v
                        }
                    }
                }
                val normalized = normalizeYamlData(finalMap) as? Map<*, *>
                val (afterP, afterG, afterR) = countMapStats(normalized)
                val outYaml = yamlParser.dump(normalized ?: finalMap)
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                logs.add(0, "[OK] 使用 $engineUsed 执行完成 (${elapsed}ms)")
                ScriptPreviewResult(
                    success = true,
                    engineName = engineUsed,
                    durationMs = elapsed,
                    outputYaml = outYaml,
                    consoleLogs = logs,
                    beforeProxies = beforeP,
                    afterProxies = afterP,
                    beforeGroups = beforeG,
                    afterGroups = afterG,
                    beforeRules = beforeR,
                    afterRules = afterR
                )
            } else {
                val snippetMap = runCatching { yamlParser.load<Any>(code) as? Map<*, *> }.getOrNull()
                if (snippetMap == null) {
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    logs.add("[ERROR] 未检测到 main(config) 函数，且内容不是有效的 YAML 键值对片段")
                    return ScriptPreviewResult(
                        success = false,
                        engineName = "Error",
                        durationMs = elapsed,
                        outputYaml = effectiveInputYaml,
                        consoleLogs = logs,
                        errorMessage = "未检测到 main(config) 函数，且内容不是有效的 YAML 键值对片段",
                        beforeProxies = beforeP,
                        afterProxies = beforeP,
                        beforeGroups = beforeG,
                        afterGroups = beforeG,
                        beforeRules = beforeR,
                        afterRules = beforeR
                    )
                }
                val mergedMap = LinkedHashMap<String, Any?>()
                if (baseMap != null) {
                    for ((k, v) in baseMap) {
                        if (k != null) mergedMap[k.toString()] = v
                    }
                }
                for ((k, v) in snippetMap) {
                    val kStr = k?.toString() ?: continue
                    val baseVal = mergedMap[kStr]
                    if (baseVal is List<*> && v is List<*>) {
                        mergedMap[kStr] = (baseVal + v).distinct()
                    } else {
                        mergedMap[kStr] = v
                    }
                }
                val normalized = normalizeYamlData(mergedMap) as? Map<*, *>
                val (afterP, afterG, afterR) = countMapStats(normalized)
                val outYaml = yamlParser.dump(normalized ?: mergedMap)
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                logs.add("[OK] YAML 覆写片段合并完成 (${elapsed}ms)")
                ScriptPreviewResult(
                    success = true,
                    engineName = "YAML Merge",
                    durationMs = elapsed,
                    outputYaml = outYaml,
                    consoleLogs = logs,
                    beforeProxies = beforeP,
                    afterProxies = afterP,
                    beforeGroups = beforeG,
                    afterGroups = afterG,
                    beforeRules = beforeR,
                    afterRules = afterR
                )
            }
        } catch (e: Throwable) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            logs.add("[ERROR] ${e.message ?: e.javaClass.simpleName}")
            ScriptPreviewResult(
                success = false,
                engineName = "Error",
                durationMs = elapsed,
                outputYaml = effectiveInputYaml,
                consoleLogs = logs,
                errorMessage = e.message ?: "脚本运行异常",
                beforeProxies = beforeP,
                afterProxies = beforeP,
                beforeGroups = beforeG,
                afterGroups = beforeG,
                beforeRules = beforeR,
                afterRules = beforeR
            )
        }
    }

    fun executeScripts(
        context: Context,
        rawYaml: String,
        targetScriptIds: List<String>? = null,
        logToRepo: Boolean = true
    ): String {
        if (targetScriptIds.isNullOrEmpty()) return rawYaml
        val allScripts = ScriptManager.getScripts(context)
        val scripts = allScripts.filter { targetScriptIds.contains(it.id) }
        if (scripts.isEmpty()) return rawYaml

        var currentYaml = rawYaml
        val dumperOptions = org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val yamlParser = Yaml(dumperOptions)
        val gson = GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .setPrettyPrinting()
            .create()

        for (script in scripts) {
            val code = ScriptManager.readScriptCode(context, script)
            if (code.isBlank()) continue

            try {
                if (code.contains("main") || code.contains("module.exports") || code.contains("export default")) {
                    val configMap: Any? = runCatching { yamlParser.load<Any>(currentYaml) }.getOrNull()
                    if (configMap != null) {
                        val jsonStr = gson.toJson(configMap)
                        var resultJson: String? = null

                        // 1. Primary Engine: QuickJS (Full ES2020+ support for ?., ??, { ...obj }, for-of-const, arrow functions, etc.)
                        try {
                            resultJson = executeWithQuickJS(code, jsonStr, script.name, gson)
                            Log.i(TAG, "QuickJS executed script: ${script.name}")
                        } catch (qjsErr: Throwable) {
                            Log.w(TAG, "QuickJS execution failed, falling back to Rhino: ${qjsErr.message}")
                            // 2. Fallback Engine: Rhino with ES6 + syntax preprocessor
                            try {
                                resultJson = executeWithRhino(code, jsonStr, script.name, gson)
                                Log.i(TAG, "Rhino fallback executed script: ${script.name}")
                            } catch (rhinoErr: Throwable) {
                                throw Exception("QuickJS: ${qjsErr.message ?: qjsErr.javaClass.simpleName}; Rhino: ${rhinoErr.message ?: rhinoErr.javaClass.simpleName}")
                            }
                        }

                        if (!resultJson.isNullOrBlank() && resultJson != "undefined" && resultJson != "null") {
                            val modifiedMap = gson.fromJson(resultJson, Map::class.java) as? Map<*, *>
                            if (modifiedMap != null && modifiedMap.isNotEmpty()) {
                                // Merge defense: ensure proxies and proxy-groups are NEVER dropped
                                val finalMap = LinkedHashMap<String, Any?>()
                                if (configMap is Map<*, *>) {
                                    for ((k, v) in configMap) {
                                        if (k != null) finalMap[k.toString()] = v
                                    }
                                }
                                for ((k, v) in modifiedMap) {
                                    if (k != null) finalMap[k.toString()] = v
                                }
                                if (configMap is Map<*, *>) {
                                    val origProxies = configMap["proxies"] ?: configMap["Proxy"] ?: configMap["Proxies"]
                                    if (origProxies is List<*> && origProxies.isNotEmpty()) {
                                        val curProxies = finalMap["proxies"] ?: finalMap["Proxy"] ?: finalMap["Proxies"]
                                        if (curProxies !is List<*> || curProxies.isEmpty()) {
                                            finalMap["proxies"] = origProxies
                                        }
                                    }
                                    val origGroups = configMap["proxy-groups"] ?: configMap["proxy-providers"] ?: configMap["Proxy Group"]
                                    if (origGroups is List<*> && origGroups.isNotEmpty()) {
                                        val curGroups = finalMap["proxy-groups"] ?: finalMap["Proxy Group"]
                                        if (curGroups !is List<*> || curGroups.isEmpty()) {
                                            finalMap["proxy-groups"] = origGroups
                                        }
                                    }
                                }
                                val normalized = normalizeYamlData(finalMap)
                                currentYaml = yamlParser.dump(normalized)
                                if (logToRepo) {
                                    LogRepository.addLog("info", "JS 脚本 [${script.name}] 成功复写配置")
                                }
                                Log.i(TAG, "Successfully applied script: ${script.name}")
                            }
                        }
                    }
                } else {
                    val snippetMap: Any? = runCatching { yamlParser.load<Any>(code) }.getOrNull()
                    if (snippetMap is Map<*, *>) {
                        val baseMap = runCatching { yamlParser.load<Any>(currentYaml) as? Map<*, *> }.getOrNull()
                        if (baseMap != null) {
                            val mergedMap = LinkedHashMap<String, Any?>()
                            for ((k, v) in baseMap) {
                                if (k != null) mergedMap[k.toString()] = v
                            }
                            for ((k, v) in snippetMap) {
                                val kStr = k?.toString() ?: continue
                                val baseVal = mergedMap[kStr]
                                if (baseVal is List<*> && v is List<*>) {
                                    mergedMap[kStr] = (baseVal + v).distinct()
                                } else {
                                    mergedMap[kStr] = v
                                }
                            }
                            val normalized = normalizeYamlData(mergedMap)
                            currentYaml = yamlParser.dump(normalized)
                            if (logToRepo) {
                                LogRepository.addLog("info", "已安全合并脚本 [${script.name}] YAML 片段")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing script ${script.name}", e)
                if (logToRepo) {
                    LogRepository.addLog("warning", "执行脚本 [${script.name}] 失败: ${e.message}")
                }
            }
        }
        return currentYaml
    }

    private fun executeWithQuickJS(
        code: String,
        jsonStr: String,
        scriptName: String,
        gson: Gson,
        logSink: MutableList<String>? = null
    ): String? {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<String?> {
            val quickJS = com.quickjs.QuickJS.createRuntime()
            val jsContext = quickJS.createContext()
            try {
                val envPolyfill = """
                    var __logs = [];
                    function __ pushLog(level, args) {
                        if (__logs.length >= 100) return;
                        var parts = [];
                        for (var i = 0; i < args.length; i++) {
                            var a = args[i];
                            if (typeof a === 'object' && a !== null) {
                                try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
                            } else {
                                parts.push(String(a));
                            }
                        }
                        __logs.push('[' + level + '] ' + parts.join(' '));
                    }
                    var console = {
                        log: function() { __pushLog('LOG', arguments); },
                        info: function() { __pushLog('INFO', arguments); },
                        warn: function() { __pushLog('WARN', arguments); },
                        error: function() { __pushLog('ERROR', arguments); }
                    };
                    var module = { exports: {} };
                    var exports = module.exports;
                """.trimIndent().replace("function __ pushLog", "function __pushLog")
                jsContext.executeVoidScript(envPolyfill, "polyfill.js")
                jsContext.executeVoidScript(code, scriptName)

                val escapedJson = gson.toJson(jsonStr)
                val runnerCode = """
                    (function() {
                        var __input = JSON.parse($escapedJson);
                        var inProxies = __input.proxies ? JSON.parse(JSON.stringify(__input.proxies)) :
                                        (__input.Proxies ? JSON.parse(JSON.stringify(__input.Proxies)) :
                                        (__input.Proxy ? JSON.parse(JSON.stringify(__input.Proxy)) : null));
                        var inGroups = __input['proxy-groups'] ? JSON.parse(JSON.stringify(__input['proxy-groups'])) : null;
                        var __fn = (typeof main === 'function') ? main :
                                   (typeof module !== 'undefined' && typeof module.exports === 'function') ? module.exports :
                                   (typeof module !== 'undefined' && module.exports && typeof module.exports.main === 'function') ? module.exports.main :
                                   null;
                        var __res = __fn ? __fn(__input) : __input;
                        if (typeof __res === 'undefined' || __res === null) {
                            __res = __input;
                        }
                        if (__res && typeof __res.then === 'function') {
                            __res = __input;
                        }
                        if (Array.isArray(__res)) {
                            __input.proxies = __res;
                            __res = __input;
                        }
                        var outProxies = __res.proxies || __res.Proxies || __res.Proxy;
                        if (inProxies && inProxies.length > 0 && (!outProxies || !Array.isArray(outProxies) || outProxies.length === 0)) {
                            __res.proxies = inProxies;
                        }
                        if (inGroups && inGroups.length > 0 && (!__res['proxy-groups'] || !Array.isArray(__res['proxy-groups']) || __res['proxy-groups'].length === 0)) {
                            __res['proxy-groups'] = inGroups;
                        }
                        return JSON.stringify(__res);
                    })();
                """.trimIndent()
                val res = jsContext.executeStringScript(runnerCode, "runner.js")
                if (logSink != null) {
                    runCatching {
                        val logsJson = jsContext.executeStringScript("JSON.stringify(__logs);", "logs.js")
                        val parsedLogs = gson.fromJson(logsJson, Array<String>::class.java)
                        if (parsedLogs != null) {
                            logSink.addAll(parsedLogs)
                        }
                    }
                }
                res
            } finally {
                try { jsContext.close() } catch (_: Throwable) {}
                try { quickJS.close() } catch (_: Throwable) {}
            }
        }
        return try {
            future.get(RHINO_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw RuntimeException("脚本执行超时（超过 ${RHINO_TIMEOUT_MS / 1000}s）")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Rhino runs without any Java bridge: [org.mozilla.javascript.Context.initSafeStandardObjects]
     * removes the `Packages`/`JavaAdapter`/`getClass` reachability that `initStandardObjects`
     * exposes, and a denying ClassShutter is layered on top as defence in depth. Scripts are
     * pulled from remote subscriptions, so an unrestricted bridge here is remote code execution.
     * A ContextFactory-based instruction observer also bounds runaway scripts.
     */
    internal fun executeWithRhino(
        code: String,
        jsonStr: String,
        scriptName: String,
        gson: Gson,
        logSink: MutableList<String>? = null
    ): String? {
        val deadline = System.currentTimeMillis() + RHINO_TIMEOUT_MS
        val factory = object : org.mozilla.javascript.ContextFactory() {
            override fun observeInstructionCount(cx: org.mozilla.javascript.Context, instructionCount: Int) {
                if (System.currentTimeMillis() > deadline) {
                    throw org.mozilla.javascript.EvaluatorException("脚本执行超时（超过 ${RHINO_TIMEOUT_MS / 1000}s）")
                }
            }
        }

        return factory.call { rhinoCtx ->
            rhinoCtx.optimizationLevel = -1
            rhinoCtx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            rhinoCtx.instructionObserverThreshold = 10_000
            rhinoCtx.setClassShutter(org.mozilla.javascript.ClassShutter { false })

            val scope = rhinoCtx.initSafeStandardObjects()
            val envPolyfill = """
                var __logs = [];
                function __pushLog(level, args) {
                    if (__logs.length >= 100) return;
                    var parts = [];
                    for (var i = 0; i < args.length; i++) {
                        var a = args[i];
                        if (typeof a === 'object' && a !== null) {
                            try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
                        } else {
                            parts.push(String(a));
                        }
                    }
                    __logs.push('[' + level + '] ' + parts.join(' '));
                }
                var console = {
                    log: function() { __pushLog('LOG', arguments); },
                    info: function() { __pushLog('INFO', arguments); },
                    warn: function() { __pushLog('WARN', arguments); },
                    error: function() { __pushLog('ERROR', arguments); }
                };
                var module = { exports: {} };
                var exports = module.exports;
            """.trimIndent()
            rhinoCtx.evaluateString(scope, envPolyfill, "polyfill.js", 1, null)

            val preprocessedCode = code
                .replace(Regex("""for\s*\(\s*(const|let)\s+"""), "for (var ")

            rhinoCtx.evaluateString(scope, preprocessedCode, scriptName, 1, null)
            val escapedJson = gson.toJson(jsonStr)
            val runnerCode = """
                (function() {
                    var __input = JSON.parse($escapedJson);
                    var inProxies = __input.proxies ? JSON.parse(JSON.stringify(__input.proxies)) :
                                    (__input.Proxies ? JSON.parse(JSON.stringify(__input.Proxies)) :
                                    (__input.Proxy ? JSON.parse(JSON.stringify(__input.Proxy)) : null));
                    var inGroups = __input['proxy-groups'] ? JSON.parse(JSON.stringify(__input['proxy-groups'])) : null;
                    var __fn = (typeof main === 'function') ? main :
                               (typeof module !== 'undefined' && typeof module.exports === 'function') ? module.exports :
                               (typeof module !== 'undefined' && module.exports && typeof module.exports.main === 'function') ? module.exports.main :
                               null;
                    var __res = __fn ? __fn(__input) : __input;
                    if (typeof __res === 'undefined' || __res === null) {
                        __res = __input;
                    }
                    if (__res && typeof __res.then === 'function') {
                        __res = __input;
                    }
                    var outProxies = __res.proxies || __res.Proxies || __res.Proxy;
                    if (inProxies && inProxies.length > 0 && (!outProxies || !Array.isArray(outProxies) || outProxies.length === 0)) {
                        __res.proxies = inProxies;
                    }
                    if (inGroups && inGroups.length > 0 && (!__res['proxy-groups'] || !Array.isArray(__res['proxy-groups']) || __res['proxy-groups'].length === 0)) {
                        __res['proxy-groups'] = inGroups;
                    }
                    return JSON.stringify(__res);
                })();
            """.trimIndent()
            val resultObj = rhinoCtx.evaluateString(scope, runnerCode, "runner.js", 1, null)
            if (logSink != null) {
                runCatching {
                    val logsObj = rhinoCtx.evaluateString(scope, "JSON.stringify(__logs);", "logs.js", 1, null)
                    if (logsObj != null) {
                        val logsJson = org.mozilla.javascript.Context.toString(logsObj)
                        val parsedLogs = gson.fromJson(logsJson, Array<String>::class.java)
                        if (parsedLogs != null) {
                            logSink.addAll(parsedLogs)
                        }
                    }
                }
            }
            if (resultObj != null) org.mozilla.javascript.Context.toString(resultObj) else null
        }
    }

    internal fun normalizeYamlData(data: Any?): Any? {
        return when (data) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>()
                for ((key, value) in data) {
                    val kStr = key?.toString() ?: ""
                    var normVal = normalizeYamlData(value)
                    if (kStr == "expected-status") {
                        if (normVal is Number) {
                            normVal = normVal.toLong().toString()
                        }
                    }
                    result[kStr] = normVal
                }
                result
            }
            is List<*> -> data.map { normalizeYamlData(it) }
            is Double -> {
                if (data % 1.0 == 0.0 && data >= Long.MIN_VALUE && data <= Long.MAX_VALUE) {
                    data.toLong()
                } else {
                    data
                }
            }
            is Float -> {
                if (data % 1.0f == 0.0f) {
                    data.toLong()
                } else {
                    data
                }
            }
            else -> data
        }
    }
}


