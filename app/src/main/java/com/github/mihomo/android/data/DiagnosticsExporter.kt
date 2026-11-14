@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private const val SENSITIVE_KEYS_PATTERN =
        "access_token|refresh_token|private_key|private-key|passphrase|authorization|" +
            "auth_token|secret_key|client_secret|api_key|apikey|password|passwd|secret|token|auth|psk|uuid"

    private val httpUrlRegex = Regex("""(?i)\bhttps?://[^\s"'?#]+""")
    private val mixedPathTokenRegex =
        Regex("""^(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{16,}$""")
    private const val BASE64_SECRET_PATTERN =
        """(?<![A-Za-z0-9+/=_-])(?=[A-Za-z0-9+/=_-]*[A-Z])(?=[A-Za-z0-9+/=_-]*[a-z])(?=[A-Za-z0-9+/=_-]*[0-9])[A-Za-z0-9+/=_-]{32,}"""
    private val base64SecretRegex = Regex("^$BASE64_SECRET_PATTERN$")
    private val base64OutsideHttpUrlRegex = Regex("""\b((?i:https?)://[^\s"'?#]+)|($BASE64_SECRET_PATTERN)""")

    private val prePathRules: List<Rule> = listOf(
        // 1. URL userinfo — "user:pass@host" and the password-only form "pass@host"
        //    (trojan/ss URIs) both carry the credential before the '@'. The repetition
        //    `(?:...@)+` deliberately consumes EVERY '@'-terminated segment before the
        //    authority ends so a raw, non-URI-encoded '@' or '!' inside a password cannot
        //    leave a suffix behind.
        Rule(
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://)(?:[^\s/?#@"']+@)+"""),
            "\$1***"
        ),
        // 2. Proxy deep links without '@host' (e.g. opaque base64 vmess://eyJ..., ss://YWVz...,
        //    ssr://..., trojan://..., vless://..., hysteria2://..., tuic://...). When '@' was
        //    present, rule 1 already rewrote the prefix to `scheme://***` and preserved the
        //    host:port structure; `(?!\*\*\*)` keeps that host:port intact while wiping opaque
        //    base64 payloads completely.
        Rule(
            Regex(
                """(?i)\b((?:vmess|vless|ss|ssr|trojan|hysteria2|hy2|tuic)://)(?!\*\*\*)[^\s"'?#]+(?:\?[^\s"'#]*)?(?:#[^\s"']*)?"""
            ),
            "\$1***"
        ),
        // 3a. Subscription path tokens under common panel routes (`/link/<token>`, `/sub/<token>`,
        //     `/subscribe/<token>`, `/s/<token>`, `/token/<token>`, `/rss/<token>`).
        Rule(
            Regex("""(?i)(/(?:link|sub|subscribe|s|token|rss)/)[A-Za-z0-9._~-]{6,}"""),
            "\$1***"
        )
    )

    private val postPathRules: List<Rule> = listOf(
        // 4. Sensitive-named query params (anywhere in the text, scheme or not): the name
        //    is kept (it preserves diagnostic structure) and only the value dies. Long
        //    alternations are listed first so "signature" is fully matched before "sign"
        //    could win and leave "ature=x" behind.
        Rule(
            Regex(
                """(?i)([?&](?:access_token|refresh_token|private_key|private-key|passphrase|signature|auth_token|secret_key|client_secret|api_key|apikey|password|passwd|secret|token|auth|sign|sig|psk|key|uuid)=)([^&\s"']+)"""
            ),
            "\$1***"
        ),
        // 5. The ENTIRE query of any scheme URL is wiped: subscription/API links hide
        //    tokens in params whose names are not in rule 4's list, and the host+path
        //    (which is what a diagnosis actually needs) survives.
        Rule(
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://[^\s?"']*)\?[^\s"']*"""),
            "\$1?***"
        ),
        // 6. Percent-encoded http(s) URLs inside query values / logs ("%3A%2F%2F…"):
        //    rule 5 cannot see them (no literal "://"), yet they are a common way
        //    a subscription URL with its token appears in core logs.
        Rule(
            Regex("""(?i)https?%3a%2f%2f[^\s&"']+"""),
            "***"
        ),
        // 7. Bearer credentials. The value must be >=8 token-ish characters so prose like
        //    "bearer token required" survives; "***" itself is not in the charset, so the
        //    rule is idempotent.
        Rule(
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=!@-]{8,}"""),
            "Bearer ***"
        ),
        // 8. HTTP Basic credentials (`Basic <base64>`). Uses case-sensitive upper/lower
        //    checks on the token so base64 payloads (which contain digits/+/=/ or internal
        //    uppercase letters) match while English prose like "Basic auth required" stays.
        Rule(
            Regex(
                """\b(?i:basic)\s+(?:(?=[A-Za-z0-9+/=]*[0-9+/=])[A-Za-z0-9+/=]{4,}|(?=[A-Za-z0-9+/]+[A-Z])(?=[A-Za-z0-9+/]*[a-z])[A-Za-z0-9+/=]{4,})"""
            ),
            "Basic ***"
        ),
        // 9. Short & standard JWTs (three dot-separated base64url segments starting with
        //    `eyJ`, the base64url encoding of `{"`). Runs after rule 7 so `Bearer <jwt>`
        //    becomes `Bearer ***`, while standalone JWTs become `***`.
        Rule(
            Regex("""\beyJ[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}"""),
            "***"
        ),
        // 10. JSON credential fields ("secret": "…", "password": "…", "api_key": "…", etc.).
        //     Handles escaped quotes (`\"`) as well as `!` and `@` inside the string value.
        Rule(
            Regex("""(?i)("(?:$SENSITIVE_KEYS_PATTERN)"\s*:\s*")(?:\\.|[^"\\])+(")"""),
            "\$1***\$2"
        ),
        // 11. Quoted YAML / log credential assignments (`password: "p@ss!\"word"` or
        //     `psk: 'secret!@#'`).
        Rule(
            Regex("""(?i)\b($SENSITIVE_KEYS_PATTERN)\b(\s*[=:]\s*")(?:\\.|[^"\\])+(")"""),
            "\$1\$2***\$3"
        ),
        Rule(
            Regex("""(?i)\b($SENSITIVE_KEYS_PATTERN)\b(\s*[=:]\s*')(?:\\.|[^'\\])+(')"""),
            "\$1\$2***\$3"
        ),
        // 12. Bare (unquoted) credential assignments in YAML/log text ("secret: x",
        //     "password=x", "psk: x", "api_key=x"). Values of >=8 credential characters
        //     or >=4 characters containing `!`, `@`, or an embedded `"` / `\"` are masked,
        //     keeping short prose words like "password: none" readable and never consuming
        //     a closing JSON quote.
        Rule(
            Regex(
                """(?i)\b($SENSITIVE_KEYS_PATTERN)\b(\s*[=:]\s*)(?!\*\*\*)(?:(?=(?:\\\\|\\["']|[A-Za-z0-9+/=_!@#$%^&~.-]|["'](?=[A-Za-z0-9+/=_!@#$%^&~.-]))*[!@"'])(?:\\\\|\\["']|[A-Za-z0-9+/=_!@#$%^&~.-]|["'](?=[A-Za-z0-9+/=_!@#$%^&~.-])){4,}|(?:\\\\|\\["']|[A-Za-z0-9+/=_!@#$%^&~.-]|["'](?=[A-Za-z0-9+/=_!@#$%^&~.-])){8,})"""
            ),
            "\$1\$2***"
        ),
        // 13. "sk-" style API keys.
        Rule(
            Regex("""(?i)\bsk-[A-Za-z0-9_-]{16,}"""),
            "***"
        ),
        // 14. Standard 8-4-4-4-12 UUIDs (VMess/VLess "uuid" credentials, connection ids).
        Rule(
            Regex(
                """(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f-])"""
            ),
            "***"
        )
    )

    // Rules 16-18 run after Base64 secret redaction so IP hosts inside URLs still get masked.
    private val ipAddressRules: List<Rule> = listOf(
        // 16. IPv6 addresses (full 8-hextet, compressed `::`, and IPv4-mapped/embedded IPv6,
        //     with optional `%zone`). Loopback `::1` is exempt (like `127.0.0.1`), and
        //     alpha/colon lookarounds preserve timestamps (`22:15:00+08:00`) and ports.
        Rule(
            Regex(
                """(?<![0-9A-Za-z:])(?!::1(?![0-9A-Fa-f:]))(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(?:(?:[0-9A-Fa-f]{1,4}:){6}|(?:(?:[0-9A-Fa-f]{1,4}:){1,5}|:):(?:[0-9A-Fa-f]{1,4}:)?)(?:\d{1,3}\.){3}\d{1,3}|(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,5})?|::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6}))(?:%[0-9A-Za-z._-]+)?(?![0-9A-Za-z:])"""
            ),
            "<ipv6>"
        ),
        // 17. RFC1918 private ranges -> <lan-ip>.
        Rule(
            Regex(
                """(?<![\d.])(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?![\d.])"""
            ),
            "<lan-ip>"
        ),
        // 18. Every other public IPv4 -> <ip> (ports survive because only the dotted quad
        //     is consumed). The whole loopback /8 is exempt.
        Rule(
            Regex("""(?<![\d.])(?!127\.)(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"""),
            "<ip>"
        )
    )

    /**
     * Redacts credentials and location hints from [text]. Pure and idempotent — applying
     * it twice yields the same output as applying it once.
     */
    fun redact(text: String): String {
        var current = text
        for (rule in prePathRules) {
            current = rule.regex.replace(current, rule.replacement)
        }
        current = redactUrlPathTokens(current)
        for (rule in postPathRules) {
            current = rule.regex.replace(current, rule.replacement)
        }
        current = redactBase64SecretsOutsideUrls(current)
        for (rule in ipAddressRules) {
            current = rule.regex.replace(current, rule.replacement)
        }
        return current
    }

    /**
     * Rule 3b: replaces >=16-char mixed alphanumeric subscription token path segments inside
     * http(s) URLs in a single idempotent pass.
     */
    private fun redactUrlPathTokens(text: String): String =
        httpUrlRegex.replace(text) { match ->
            val url = match.value
            val schemeEnd = url.indexOf("://")
            val pathStart = url.indexOf('/', schemeEnd + 3)
            if (pathStart < 0 || pathStart >= url.length - 1) {
                url
            } else {
                val authority = url.substring(0, pathStart)
                val path = url.substring(pathStart + 1)
                val redactedPath = path.split('/').joinToString("/") { segment ->
                    if (mixedPathTokenRegex.matches(segment) || base64SecretRegex.matches(segment)) {
                        "***"
                    } else {
                        segment
                    }
                }
                "$authority/$redactedPath"
            }
        }

    /**
     * Rule 15: masks >=32-char mixed-case Base64 secrets while leaving multi-segment http(s)
     * URLs (already filtered segment-by-segment in [redactUrlPathTokens]) untouched.
     */
    private fun redactBase64SecretsOutsideUrls(text: String): String =
        base64OutsideHttpUrlRegex.replace(text) { match ->
            match.groups[1]?.value ?: "***"
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
        val vpnState = safeOrNull {
            com.github.mihomo.android.service.MihomoVpnService.vpnState.value
        }
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
                put("status", vpnState?.status?.name ?: "UNKNOWN")
                put("coreVersion", redact(vpnState?.coreVersion.orEmpty()))
            })
            put("core", buildJsonObject {
                put("apiVersion", redact(safeOrNull { MihomoApi.version() } ?: "unavailable"))
                put("nativeVersion", redact(com.github.mihomo.android.core.ClashCore.getCoreVersion()))
            })
            put("runtime", buildJsonObject {
                put("tunnelMode", redact(SettingsManager(appContext).tunnelMode))
                put("activeConnections", safeOrNull { RequestRepository.activeCountFlow.value } ?: 0)
                put("proxyProviderCount", safeOrNull { MihomoApi.proxyProviders().size } ?: -1)
                put("ruleProviderCount", safeOrNull { MihomoApi.ruleProviders().size } ?: -1)
            })
            put("recentLogs", buildJsonArray {
                val tailLogs = safeOrNull { LogRepository.logsFlow.value.takeLast(LOG_TAIL_COUNT) }
                    ?: emptyList()
                tailLogs.forEach { entry ->
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
        withContext(Dispatchers.IO) {
            exportDiagnosticsSync(context.applicationContext)
        }

    private fun exportDiagnosticsSync(appContext: Context): DiagnosticsExportResult =
        try {
            val dir = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(dir, "diag-$stamp.json")
            val staged = File(dir, "diag-$stamp.json.tmp")
            staged.writeText(buildDiagnosticsJson(appContext), Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                staged.delete()
                error("could not replace an existing diagnostics export with the same timestamp")
            }
            check(staged.renameTo(target)) { "rename of the staged diagnostics file failed" }
            pruneOldExports(dir)
            DiagnosticsExportResult.Success(target, target.length())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsExportResult.Failure(redact(e.message ?: "diagnostics export failed"), e)
        }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private inline fun <T> safeOrNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun installedVersionName(context: Context): String = safeOrNull {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } ?: "unknown"

    private fun installedVersionCode(context: Context): Long = safeOrNull {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } ?: -1L

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun isoOf(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    /** Keeps only the newest [MAX_EXPORTS] bundles; never throws. */
    private fun pruneOldExports(dir: File) {
        safeOrNull {
            dir.listFiles { file -> file.isFile && file.name.startsWith("diag-") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_EXPORTS)
                ?.forEach { it.delete() }
        }
    }
}
