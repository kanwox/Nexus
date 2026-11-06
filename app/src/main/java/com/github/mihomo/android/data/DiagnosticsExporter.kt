package com.github.mihomo.android.data

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a shareable, REDACTED diagnostics bundle as a JSON file in `cacheDir/diagnostics/`.
 *
 * Privacy policy — the bundle may leave the device through any share channel, so it follows
 * a strict "better over-redact than miss a secret" rule (the opposite trade-off from what a
 * debugger would want): every dynamic string passes through [redact] before it enters the
 * JSON, and the fully serialized document is swept through [redact] a second time so a value
 * assembled from several fields cannot smuggle a credential past the per-field pass.
 *
 * [redact] itself is a PURE function (no Android types) so the whole rule set is unit
 * tested in DiagnosticsRedactTest.kt.
 */
object DiagnosticsExporter {

    /** How many recent in-memory core log lines are embedded in the bundle. */
    private const val LOG_TAIL_COUNT = 100

    /** Old export files beyond this count are pruned so the cache directory stays bounded. */
    private const val MAX_EXPORTS = 10

    // ---------------------------------------------------------------------------
    // Redaction rules (pure, ordered, applied top to bottom)
    // ---------------------------------------------------------------------------

    /** One ordered redaction pass: [regex] is replaced by [replacement] over the whole text. */
    private class Rule(val regex: Regex, val replacement: String)

    private val rules: List<Rule> = listOf(
        // 1. URL userinfo — "user:pass@host" and the password-only form "pass@host"
        //    (trojan/ss URIs) both carry the credential before the '@'. The repetition
        //    `(?:...@)+` deliberately consumes EVERY '@'-terminated segment so a raw,
        //    non-URI-encoded '@' inside a password cannot leave a suffix behind.
        Rule(
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://)(?:[^\s/@]+@)+"""),
            "\$1***"
        ),
        // 2. Sensitive-named query params (anywhere in the text, scheme or not): the name
        //    is kept (it preserves diagnostic structure) and only the value dies. Long
        //    alternations are listed first so "signature" is fully matched before "sign"
        //    could win and leave "ature=x" behind. Core-log fragments like
        //    "delay?timeout=5000&url=..." carry no scheme, so this is their only anchor.
        Rule(
            Regex(
                """(?i)([?&](?:access_token|api_key|apikey|signature|refresh_token|password|passwd|secret|token|auth|sign|sig|key|uuid)=)([^&\s"']+)"""
            ),
            "\$1***"
        ),
        // 3. The ENTIRE query of any scheme URL is wiped: subscription/API links hide
        //    tokens in params whose names are not in rule 2's list, and the host+path
        //    (which is what a diagnosis actually needs) survives. Extending this beyond
        //    http(s) covers hysteria2/tuic/clash deep links which also carry secrets.
        Rule(
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://[^\s?]*)\?[^\s"']*"""),
            "\$1?***"
        ),
        // 4. Percent-encoded http(s) URLs inside query values / logs ("%3A%2F%2F…"):
        //    rule 3 cannot see them (no literal "://"), yet they are the most common way
        //    a subscription URL with its token appears in core logs.
        Rule(
            Regex("""(?i)https?%3a%2f%2f[^\s&"']+"""),
            "***"
        ),
        // 5. Bearer credentials. The value must be ≥8 token-ish characters so prose like
        //    "bearer token required" survives; "***" itself is not in the charset, so the
        //    rule is idempotent.
        Rule(
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
            "Bearer ***"
        ),
        // 6. JSON credential fields ("secret": …, "token": …, "uuid": …). The regex stops
        //    at the first unescaped quote; a JSON *object* value (no opening quote) does
        //    not match, so structured sub-objects are not clobbered.
        Rule(
            Regex("""(?i)("(?:secret|password|passwd|token|authorization|auth|uuid)"\s*:\s*")[^"]*(")"""),
            "\$1***\$2"
        ),
        // 7. Bare credential assignments in log text ("secret: x", "password=x"). Only
        //    values of ≥8 credential-charset characters die — that keeps words like
        //    "none" or "failed" readable while still catching the controller secret,
        //    which is a 32-char hex string with no dashes and no other anchor.
        Rule(
            Regex(
                """(?i)\b(secret|password|passwd|token|auth|authorization|apikey|api_key)\b(\s*[=:]\s*)[A-Za-z0-9+/=_-]{8,}"""
            ),
            "\$1\$2***"
        ),
        // 8. "sk-" style API keys. The charset is widened with "_" and "-" because real
        //    provider keys contain them; ≥16 chars avoids eating ordinary node names
        //    that merely start with "sk-".
        Rule(
            Regex("""(?i)\bsk-[A-Za-z0-9_-]{16,}"""),
            "***"
        ),
        // 9. Standard 8-4-4-4-12 UUIDs (VMess/VLess "uuid" credentials, connection ids).
        //    Lookarounds anchored on the hex/dash charset prevent matching a UUID-shaped
        //    prefix of a longer identifier. Trade-off (accepted): core connection ids are
        //    UUIDs and stop being correlatable across exports — the bundle carries no
        //    per-connection records anyway.
        Rule(
            Regex(
                """(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f-])"""
            ),
            "***"
        ),
        // 10. Base64-style secrets: ≥32 chars of the base64 charset containing upper AND
        //     lower AND digits. Lowercase-only hex hashes (geo file checksums, sha256) do
        //     NOT match and stay readable — that exception is intentional and documented:
        //     hashes verify artifact integrity and are not credentials.
        Rule(
            Regex(
                """(?<![A-Za-z0-9+/=_-])(?=[A-Za-z0-9+/=_-]*[A-Z])(?=[A-Za-z0-9+/=_-]*[a-z])(?=[A-Za-z0-9+/=_-]*[0-9])[A-Za-z0-9+/=_-]{32,}"""
            ),
            "***"
        ),
        // 11. RFC1918 private ranges → <lan-ip>. The lookbehind/lookahead anchored on
        //     digits and dots stop "10.0.0.1" from matching inside "110.0.0.1" and stop a
        //     dotted quad from being matched partially inside a longer dotted run.
        Rule(
            Regex(
                """(?<![\d.])(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?![\d.])"""
            ),
            "<lan-ip>"
        ),
        // 12. Every other public IPv4 → <ip> (ports survive because only the dotted quad
        //     is consumed). The whole loopback /8 is exempt: 127.0.0.1:9090 is exactly the
        //     "is the controller reachable" fact a diagnosis needs and it is not sensitive.
        Rule(
            Regex("""(?<![\d.])(?!127\.)(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"""),
            "<ip>"
        )
    )

    /**
     * Redacts credentials and location hints from [text]. Pure and idempotent — applying
     * it twice yields the same output as applying it once (every replacement string is
     * either outside the rule's own value charset or normalized to itself).
     *
     * Known, deliberate limits (kept because a wrong regex here would destroy the
     * diagnostic value of timestamps and checksums): compressed IPv6 is not covered and
     * lowercase-only hex hashes are preserved by design — see rule 10.
     */
    fun redact(text: String): String {
        var current = text
        for (rule in rules) {
            current = rule.regex.replace(current, rule.replacement)
        }
        return current
    }

    // ---------------------------------------------------------------------------
    // Bundle assembly
    // ---------------------------------------------------------------------------

    /** Result of [exportDiagnostics]. */
    sealed class DiagnosticsExportResult {
        /** The bundle was written; [file] is what the UI layer should hand to a share sheet. */
        data class Success(val file: File, val sizeBytes: Long) : DiagnosticsExportResult()

        data class Failure(val message: String, val cause: Throwable? = null) : DiagnosticsExportResult()
    }

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Assembles the diagnostics document as pretty-printed JSON text. Every dynamic value
     * is redacted individually and the serialized document is swept once more — see the
     * class KDoc for why the second pass exists.
     *
     * Reads controller REST endpoints (rule/provider counts) and SharedPreferences, so it
     * performs I/O: call it off the main thread.
     */
    fun buildDiagnosticsJson(context: Context): String {
        val appContext = context.applicationContext
        val vpnState = runCatching {
            com.github.mihomo.android.service.MihomoVpnService.vpnState.value
        }.getOrNull()
        val root = buildJsonObject {
            put("generatedAt", isoNow())
            put("app", buildJsonObject {
                put("packageName", appContext.packageName)
                put("versionName", installedVersionName(appContext))
                put("versionCode", installedVersionCode(appContext))
            })
            put("device", buildJsonObject {
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            })
            put("vpn", buildJsonObject {
                put(
                    "status",
                    vpnState?.status?.name ?: "UNKNOWN"
                )
                put("coreVersion", redact(vpnState?.coreVersion.orEmpty()))
            })
            put("core", buildJsonObject {
                put("apiVersion", redact(runCatching { MihomoApi.version() }.getOrNull() ?: "unavailable"))
                put("nativeVersion", redact(com.github.mihomo.android.core.ClashCore.getCoreVersion()))
            })
            put("runtime", buildJsonObject {
                put("tunnelMode", redact(SettingsManager(appContext).tunnelMode))
                put("activeConnections", runCatching { RequestRepository.activeCountFlow.value }.getOrDefault(0))
                put(
                    "proxyProviderCount",
                    runCatching { MihomoApi.proxyProviders().size }.getOrDefault(-1)
                )
                put(
                    "ruleProviderCount",
                    runCatching { MihomoApi.ruleProviders().size }.getOrDefault(-1)
                )
            })
            put("recentLogs", buildJsonArray {
                runCatching { LogRepository.logsFlow.value.takeLast(LOG_TAIL_COUNT) }
                    .getOrDefault(emptyList())
                    .forEach { entry ->
                        add(buildJsonObject {
                            put("time", isoOf(entry.timestamp))
                            put("level", entry.level)
                            put("count", entry.count)
                            put("message", redact(entry.message))
                        })
                    }
            })
        }
        return redact(prettyJson.encodeToString(JsonObject.serializer(), root))
    }

    /**
     * Writes the redacted bundle to `cacheDir/diagnostics/diag-<yyyyMMdd-HHmmss>.json`.
     *
     * The file is staged as `.tmp` and renamed, so a crash mid-write can never leave a
     * truncated JSON that a share sheet would publish as complete. Must be called off the
     * main thread (it does disk and possibly controller I/O).
     */
    suspend fun exportDiagnostics(context: Context): DiagnosticsExportResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val appContext = context.applicationContext
            runCatching {
                val dir = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val target = File(dir, "diag-$stamp.json")
                val staged = File(dir, "diag-$stamp.json.tmp")
                staged.writeText(buildDiagnosticsJson(appContext), Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    // Same-second collision with an earlier export; losing the old file is
                    // fine, but renameTo would silently fail, so say so.
                    staged.delete()
                    error("could not replace an existing diagnostics export with the same timestamp")
                }
                check(staged.renameTo(target)) { "rename of the staged diagnostics file failed" }
                pruneOldExports(dir)
                DiagnosticsExportResult.Success(target, target.length())
            }.getOrElse {
                DiagnosticsExportResult.Failure(redact(it.message ?: "diagnostics export failed"), it)
            }
        }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // versionCode as a plain Int field is deprecated since API 28; the deprecation
        // warning on the fallback branch is expected — it only executes below P.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(-1L)

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun isoOf(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    /** Keeps only the newest [MAX_EXPORTS] bundles; never throws. */
    private fun pruneOldExports(dir: File) {
        runCatching {
            dir.listFiles { file -> file.isFile && file.name.startsWith("diag-") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_EXPORTS)
                ?.forEach { it.delete() }
        }
    }
}
