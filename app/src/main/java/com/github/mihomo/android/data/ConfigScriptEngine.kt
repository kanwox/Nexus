package com.github.mihomo.android.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.yaml.snakeyaml.Yaml

object ConfigScriptEngine {
    private const val TAG = "ConfigScriptEngine"
    private const val RHINO_TIMEOUT_MS = 5_000L

    fun executeScripts(
        context: Context,
        rawYaml: String,
        targetScriptIds: List<String>? = null,
        logToRepo: Boolean = true
    ): String {
        if (targetScriptIds.isNullOrEmpty()) return rawYaml
        val allScripts = ScriptManager.getScripts(context)
        val scripts = allScripts.filter { it.enabled && targetScriptIds.contains(it.id) }
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

    private fun executeWithQuickJS(code: String, jsonStr: String, scriptName: String, gson: Gson): String? {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<String?> {
            val quickJS = com.quickjs.QuickJS.createRuntime()
            val jsContext = quickJS.createContext()
            try {
                val envPolyfill = """
                    var console = {
                        log: function() {},
                        info: function() {},
                        warn: function() {},
                        error: function() {}
                    };
                    var module = { exports: {} };
                    var exports = module.exports;
                """.trimIndent()
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
                jsContext.executeStringScript(runnerCode, "runner.js")
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
    internal fun executeWithRhino(code: String, jsonStr: String, scriptName: String, gson: Gson): String? {
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
                var console = {
                    log: function() {},
                    info: function() {},
                    warn: function() {},
                    error: function() {}
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


