package com.github.mihomo.android.data

/**
 * Pure YAML template, DNS override, controller block, and profile override sanitization helpers.
 * Extracted from [ConfigManager] to keep single-file function counts compact and unit testing direct.
 */
internal object ConfigSanitizer {
    private const val UNICODE_LINE_SEP = '\u2028'
    private const val UNICODE_PARA_SEP = '\u2029'
    private const val MIN_LISTEN_PORT = 1
    private const val MAX_LISTEN_PORT = 65535
    private const val DNS_YAML_INDENT = "                - "

    val DEFAULT_NAMESERVERS = listOf(
        "https://223.5.5.5/dns-query",
        "https://doh.pub/dns-query",
        "223.5.5.5",
        "119.29.29.29"
    )
    val DEFAULT_FALLBACK_SERVERS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "1.1.1.1",
        "8.8.8.8"
    )

    private val VALID_CONTROLLER_LISTEN_REGEX = Regex("^[A-Za-z0-9.\\-:\\[\\]]+:\\d{1,5}$")
    private val BRACKETED_IPV6_DNS_REGEX = Regex("^\\[[0-9a-fA-F:]+](:\\d{1,5})?(#.+)?$")
    private val YAML_LEADING_SPECIALS = setOf('-', '#', '{', '&', '*', '!', '%', '@', '`', '|', '>', '"', '\'')

    private fun isLineBreakOrControl(ch: Char): Boolean =
        ch.isISOControl() || ch == UNICODE_LINE_SEP || ch == UNICODE_PARA_SEP

    fun stripControlAndLineBreaks(raw: String): String =
        raw.filterNot(::isLineBreakOrControl)

    fun sanitizeSingleLineScalar(raw: String?): String? {
        val candidate = raw?.trim() ?: return null
        val valid = candidate.isNotEmpty() && candidate.none(::isLineBreakOrControl)
        return if (valid) candidate else null
    }

    fun escapeYamlDoubleQuoted(raw: String): String =
        stripControlAndLineBreaks(raw).replace("\\", "\\\\").replace("\"", "\\\"")

    fun sanitizeDnsServerEntry(raw: String): String? {
        if (raw.any(::isLineBreakOrControl)) return null
        val cleaned = raw.trim().trim('"', '\'')
        val isDocMarker = cleaned == "---" || cleaned == "..."
        val invalid = cleaned.isEmpty() || isDocMarker || hasYamlInjectionToken(cleaned)
        return if (invalid) null else cleaned
    }

    private fun hasYamlInjectionToken(cleaned: String): Boolean {
        val colonOrComment = cleaned.contains(": ") || cleaned.endsWith(":") || cleaned.contains(" #")
        val quotesOrBackslash = cleaned.contains('"') || cleaned.contains('\\') || cleaned.contains('\'')
        val badChars = colonOrComment || quotesOrBackslash
        val spaceBeforeHash = cleaned.substringBefore('#').any { it.isWhitespace() }
        val first = cleaned.first()
        val bracketInvalid = first == '[' && !BRACKETED_IPV6_DNS_REGEX.matches(cleaned)
        val badPrefix = first in YAML_LEADING_SPECIALS || bracketInvalid
        return badChars || badPrefix || spaceBeforeHash
    }

    fun sanitizePrependRules(rules: List<String>): List<String> =
        rules.mapNotNull(::sanitizeSingleLineScalar)

    fun sanitizeHostsEntries(entries: List<String>): List<String> =
        entries.mapNotNull(::sanitizeSingleLineScalar).filter { entry ->
            val domain = entry.substringBefore(':').trim()
            val target = entry.substringAfter(':', "").trim()
            domain.isNotEmpty() && target.isNotEmpty()
        }

    fun sanitizeDnsServerEntries(servers: List<String>): List<String> =
        servers.mapNotNull(::sanitizeDnsServerEntry)

    fun sanitizeDnsServerListSetting(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace("\r\n", "\n")
            .split(',', '\n')
            .mapNotNull(::sanitizeDnsServerEntry)
            .joinToString("\n")
    }

    fun formatDnsServersYaml(servers: List<String>, fallback: List<String>): String {
        val valid = sanitizeDnsServerEntries(servers).ifEmpty { fallback }
        return valid.joinToString("\n") { "$DNS_YAML_INDENT\"${escapeYamlDoubleQuoted(it)}\"" }
    }

    fun formatDnsSettingYaml(raw: String, fallback: List<String>): String {
        val items = raw.replace("\r\n", "\n").split(',', '\n')
        return formatDnsServersYaml(items, fallback)
    }

    fun sanitizeDnsYamlBlock(rawBlock: String, fallback: List<String>): String {
        val normalized = rawBlock.replace("\r\n", "\n")
        val hasUnsafeControl = normalized.any { it != '\n' && it != '\t' && isLineBreakOrControl(it) }
        if (hasUnsafeControl) return formatDnsServersYaml(fallback, fallback)
        val extracted = normalized.lineSequence()
            .map { it.trimStart() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                if (line.startsWith("- ")) sanitizeDnsServerEntry(line.removePrefix("- ")) else null
            }
            .toList()
        return formatDnsServersYaml(extracted, fallback)
    }

    fun sanitizeControllerListen(listenAt: String?): String? {
        val singleLine = sanitizeSingleLineScalar(listenAt) ?: return null
        val port = if (VALID_CONTROLLER_LISTEN_REGEX.matches(singleLine)) {
            singleLine.substringAfterLast(':').toIntOrNull()
        } else {
            null
        }
        return if (port != null && port in MIN_LISTEN_PORT..MAX_LISTEN_PORT) singleLine else null
    }
}
