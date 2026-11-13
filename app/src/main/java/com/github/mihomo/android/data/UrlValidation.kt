package com.github.mihomo.android.data

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException

private const val MAX_REDIRECT_HOPS = 20
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_PART_MAX_LEN = 3
private const val IPV6_BYTE_LENGTH = 16
private const val BYTE_MASK = 0xFF
private const val ULA_PREFIX_MASK = 0xFE
private const val ULA_PREFIX_VALUE = 0xFC
private const val CGNAT_FIRST_OCTET = 100
private const val CGNAT_SECOND_MIN = 64
private const val CGNAT_SECOND_MAX = 127
private const val HTTP_MOVED_TEMP = 307
private const val HTTP_MOVED_PERM = 308
private const val IPV6_MIN_TRANSLATED_BYTES = 8
private const val IPV6_MAPPED_BYTE_START = 10
private const val IPV6_EMBEDDED_OFFSET = 12
private const val SIX_TO_FOUR_OFFSET = 2
private const val NAT64_PREFIX_LEN = 4
private const val MAX_HOST_LOG_LENGTH = 64
private const val HEX_PREFIX = "0x"

private val NAT64_PREFIX = intArrayOf(0x00, 0x64, 0xFF, 0x9B)
private val SIX_TO_FOUR_PREFIX = intArrayOf(0x20, 0x02)

/**
 * Thrown when a URL, redirect target, or resolved IP violates the SSRF guard.
 * Subclasses [IllegalStateException] for compatibility with callers and tests, while allowing
 * fallback orchestrators to recognize SSRF blocks and avoid trying fallback paths.
 */
internal class SsrfBlockedException(message: String) : IllegalStateException(message)

/**
 * Configures SSRF protection on an [OkHttpClient.Builder]:
 * - Disables OkHttp's internal redirect following so every 3xx hop is validated before connection.
 * - Optionally enforces resolved IP checks via [Dns] (keep `false` on proxy clients).
 */
internal fun OkHttpClient.Builder.withSsrfProtection(
    allowPrivateSupplier: () -> Boolean,
    validateDns: Boolean = true
): OkHttpClient.Builder {
    followRedirects(false)
    followSslRedirects(false)
    addInterceptor(createSafeRedirectInterceptor(allowPrivateSupplier))
    if (validateDns) {
        dns(createSafeDns(allowPrivateSupplier))
    }
    return this
}

/**
 * Interceptor that validates the initial request and every HTTP redirect hop against SSRF rules
 * before the next network call is made.
 */
internal fun createSafeRedirectInterceptor(allowPrivateSupplier: () -> Boolean): Interceptor {
    return Interceptor { chain ->
        var currentRequest = chain.request()
        requireSecureRemoteUrl(currentRequest.url.toString(), allowPrivateSupplier())
        var response = chain.proceed(currentRequest)
        var redirectCount = 0
        while (response.isRedirect) {
            val location = response.header("Location")?.trim()
            if (location.isNullOrEmpty()) return@Interceptor response
            val statusCode = response.code
            val allowPrivate = allowPrivateSupplier()
            val nextUrl = resolveValidatedRedirectUrl(
                baseUrl = currentRequest.url,
                location = location,
                allowPrivate = allowPrivate,
                responseToClose = response
            )
            redirectCount++
            if (redirectCount > MAX_REDIRECT_HOPS) {
                throw ProtocolException("Too many follow-up requests: $redirectCount")
            }
            currentRequest = buildRedirectRequest(currentRequest, statusCode, nextUrl)
            response = chain.proceed(currentRequest)
        }
        response
    }
}

private fun resolveValidatedRedirectUrl(
    baseUrl: HttpUrl,
    location: String,
    allowPrivate: Boolean,
    responseToClose: Response
): HttpUrl {
    responseToClose.close()
    if (location.contains("://")) {
        requireSecureRemoteUrl(location, allowPrivate)
    }
    val resolved = baseUrl.resolve(location)
        ?: blockSsrf("不支持或无效的重定向地址：${redactUrl(location)}")
    requireSecureRemoteUrl(resolved.toString(), allowPrivate)
    return resolved
}

private fun buildRedirectRequest(
    previousRequest: Request,
    statusCode: Int,
    targetUrl: HttpUrl
): Request {
    val builder = previousRequest.newBuilder().url(targetUrl)
    if (!sameOrigin(previousRequest.url, targetUrl)) {
        builder.removeHeader("Authorization")
        builder.removeHeader("Cookie")
        builder.removeHeader("Proxy-Authorization")
    }
    val preserveMethod = statusCode == HTTP_MOVED_TEMP || statusCode == HTTP_MOVED_PERM
    val method = previousRequest.method
    if (!preserveMethod && method != "GET" && method != "HEAD") {
        builder.get()
        builder.removeHeader("Transfer-Encoding")
        builder.removeHeader("Content-Length")
        builder.removeHeader("Content-Type")
    }
    return builder.build()
}

private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
    a.scheme == b.scheme && a.host == b.host && a.port == b.port

/**
 * [Dns] implementation that verifies every resolved IP address before OkHttp connects.
 */
internal fun createSafeDns(allowPrivateSupplier: () -> Boolean): Dns {
    return Dns { hostname ->
        val allowPrivate = allowPrivateSupplier()
        if (!allowPrivate && isPrivateNetworkHost(hostname)) {
            throw UnknownHostException("不允许的主机名：${redactHost(hostname)}")
        }
        val resolved = Dns.SYSTEM.lookup(hostname)
        validateResolvedAddresses(hostname, resolved, allowPrivate)
        resolved
    }
}

internal fun validateResolvedAddresses(
    hostname: String,
    addresses: List<InetAddress>,
    allowPrivateHosts: Boolean
) {
    if (addresses.isEmpty()) {
        throw UnknownHostException("无法解析主机：${redactHost(hostname)}")
    }
    val hasBlocked = addresses.any { address ->
        val embedded = extractEmbeddedIpv4(address)
        isDisallowedAddress(address, allowPrivateHosts) ||
            (embedded != null && isDisallowedEmbeddedIpv4(embedded))
    }
    if (hasBlocked) {
        throw UnknownHostException("解析到不允许的 IP 地址：${redactHost(hostname)}")
    }
}

private fun isDisallowedAddress(address: InetAddress, allowPrivateHosts: Boolean): Boolean =
    isAlwaysBlockedAddress(address) || (!allowPrivateHosts && isPrivateAddress(address))

private fun isDisallowedEmbeddedIpv4(embedded: Inet4Address): Boolean =
    isAlwaysBlockedAddress(embedded) || isPrivateAddress(embedded)

/**
 * Validates that [url] is an `http`/`https` URL and does not target a link-local, unspecified,
 * obfuscated, or (when [allowPrivateHosts] is `false`) private/loopback host. Fails closed when
 * the URL cannot be parsed.
 */
internal fun requireSecureRemoteUrl(url: String, allowPrivateHosts: Boolean = true) {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore("://", "").lowercase()
    if (scheme != "https" && scheme != "http") {
        blockSsrf("不支持的地址协议：${redactUrl(trimmed)}")
    }
    if (trimmed.any { it.isISOControl() }) {
        blockSsrf("不允许的地址：${redactUrl(trimmed)}")
    }
    val rawAuthority = extractRawAuthority(trimmed)
    if (rawAuthority.isEmpty() || rawAuthority.contains('\\')) {
        blockSsrf("不允许的地址：${redactUrl(trimmed)}")
    }
    val rawHost = extractRawHost(rawAuthority)
    if (rawHost.isEmpty() || isNonStandardNumericIpv4(rawHost)) {
        blockSsrf("不允许的地址：${redactUrl(trimmed)}")
    }
    val httpUrl = trimmed.toHttpUrlOrNull()
        ?: blockSsrf("不允许的地址：${redactUrl(trimmed)}")
    validateParsedHost(rawHost, httpUrl.host, allowPrivateHosts, trimmed)
}

private fun validateParsedHost(
    rawHost: String,
    parsedHost: String,
    allowPrivateHosts: Boolean,
    originalUrl: String
) {
    val cleanHost = parsedHost.trimEnd('.').lowercase()
    if (cleanHost.isEmpty() || isNonStandardNumericIpv4(cleanHost)) {
        blockSsrf("不允许的地址：${redactUrl(originalUrl)}")
    }
    val literal = parseLiteralAddress(cleanHost)
    val embedded = literal?.let { extractEmbeddedIpv4(it) }
    val mappedIpv4FromIpv6 = rawHost.contains(':') && isStandardIpv4Literal(cleanHost)
    val blockedDirect = literal != null && isDisallowedAddress(literal, allowPrivateHosts)
    val blockedEmbedded = embedded != null && isDisallowedEmbeddedIpv4(embedded)
    val blockedMapped = mappedIpv4FromIpv6 && literal != null &&
        (isAlwaysBlockedAddress(literal) || isPrivateAddress(literal))
    val blockedNamedPrivate = !allowPrivateHosts && isPrivateNetworkHost(cleanHost)
    val blockedLiteral = blockedDirect || blockedEmbedded
    val blockedTransition = blockedMapped || blockedNamedPrivate
    if (blockedLiteral || blockedTransition) {
        blockSsrf("不允许的地址：${redactUrl(originalUrl)}")
    }
}

private fun blockSsrf(message: String): Nothing {
    throw SsrfBlockedException(message)
}

private fun extractRawAuthority(url: String): String {
    val afterScheme = url.substringAfter("://", "")
    return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
}

private fun extractRawHost(authority: String): String {
    val hostPort = authority.substringAfterLast('@')
    return if (hostPort.startsWith('[')) {
        hostPort.substringAfter('[').substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }.trimEnd('.').lowercase()
}

/**
 * True for loopback, RFC1918 LAN ranges, RFC6598 CGNAT, IPv6 ULA, link-local literals,
 * obfuscated numeric IP literals, embedded-IPv4 private mappings, and `.localhost`/`.local` names.
 *
 * Hostnames are NOT resolved via DNS here; DNS resolution checks happen in [createSafeDns].
 */
internal fun isPrivateNetworkHost(host: String): Boolean {
    val clean = host.trim().trim('[', ']').trimEnd('.').lowercase()
    val localName = clean == "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local")
    return when {
        clean.isEmpty() -> false
        localName || isNonStandardNumericIpv4(clean) -> true
        else -> isLiteralPrivate(clean)
    }
}

private fun isLiteralPrivate(cleanHost: String): Boolean {
    val address = parseLiteralAddress(cleanHost) ?: return false
    val embedded = extractEmbeddedIpv4(address)
    return isAlwaysBlockedAddress(address) ||
        isPrivateAddress(address) ||
        (embedded != null && isDisallowedEmbeddedIpv4(embedded))
}

/** Strips credentials, query string and fragment so subscription tokens never reach logcat. */
internal fun redactUrl(url: String): String {
    val trimmed = url.trim()
    val parsed = trimmed.toHttpUrlOrNull()
    return when {
        parsed != null -> formatRedactedHttpUrl(parsed)
        trimmed.contains("://") -> redactOpaqueSchemeUrl(trimmed)
        else -> {
            val scheme = trimmed.substringBefore(':', "")
            if (scheme.isNotEmpty()) "$scheme:<redacted>" else "<invalid-url>"
        }
    }
}

private fun formatRedactedHttpUrl(parsed: HttpUrl): String {
    val hasCredentials = parsed.username.isNotEmpty() || parsed.password.isNotEmpty()
    val userInfo = if (hasCredentials) "***@" else ""
    val port = if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
    return "${parsed.scheme}://$userInfo${parsed.host}$port${parsed.encodedPath}"
}

private fun redactOpaqueSchemeUrl(trimmed: String): String {
    val scheme = trimmed.substringBefore("://").lowercase()
    val afterScheme = trimmed.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val cleanAuthority = authority.substringAfterLast('@')
    val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val cleanPath = if (path.isEmpty()) "" else "/$path"
    return "$scheme://$cleanAuthority$cleanPath"
}

private fun redactHost(host: String): String {
    val clean = host.trim()
    return if (clean.length <= MAX_HOST_LOG_LENGTH) clean else clean.take(MAX_HOST_LOG_LENGTH) + "..."
}

private fun isAlwaysBlockedAddress(address: InetAddress): Boolean {
    val basic = address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress
    if (basic) return true
    val v4 = address as? Inet4Address ?: return false
    val bytes = v4.address
    val first = bytes[0].toInt() and BYTE_MASK
    // Block 0.0.0.0/8 ("this host on this network") and 255.255.255.255 (broadcast).
    return first == 0 || bytes.all { (it.toInt() and BYTE_MASK) == BYTE_MASK }
}

private fun isPrivateAddress(address: InetAddress): Boolean {
    val corePrivate = address.isLoopbackAddress || address.isSiteLocalAddress || isUniqueLocalIpv6(address)
    if (corePrivate) return true
    val v4 = address as? Inet4Address ?: return false
    val bytes = v4.address
    val first = bytes[0].toInt() and BYTE_MASK
    val second = bytes[1].toInt() and BYTE_MASK
    // RFC 6598 shared address space (100.64.0.0/10)
    return first == CGNAT_FIRST_OCTET && second in CGNAT_SECOND_MIN..CGNAT_SECOND_MAX
}

/**
 * Extracts an embedded IPv4 address from IPv6 transition mechanisms (`::ffff:0:0/96`,
 * IPv4-compatible `::/96`, IPv4-translated `::ffff:0:0:0/96`, NAT64 `64:ff9b::/96`,
 * and 6to4 `2002::/16`) without performing DNS lookups.
 */
private fun extractEmbeddedIpv4(address: InetAddress): Inet4Address? {
    val v6 = address as? Inet6Address ?: return null
    val bytes = v6.address
    val ipv4Bytes = if (bytes.size == IPV6_BYTE_LENGTH) extractEmbeddedIpv4Bytes(bytes) else null
    return ipv4Bytes?.let { raw ->
        runCatching { InetAddress.getByAddress(raw) as? Inet4Address }.getOrNull()
    }
}

private fun extractEmbeddedIpv4Bytes(bytes: ByteArray): ByteArray? {
    val isMapped = isAllZero(bytes, 0, IPV6_MAPPED_BYTE_START) &&
        isAllFf(bytes, IPV6_MAPPED_BYTE_START, IPV6_EMBEDDED_OFFSET)
    val isTranslated = isAllZero(bytes, 0, IPV6_MIN_TRANSLATED_BYTES) &&
        isAllFf(bytes, IPV6_MIN_TRANSLATED_BYTES, IPV6_MAPPED_BYTE_START) &&
        isAllZero(bytes, IPV6_MAPPED_BYTE_START, IPV6_EMBEDDED_OFFSET)
    val isCompatible = isAllZero(bytes, 0, IPV6_EMBEDDED_OFFSET) && !isLoopbackOrUnspecifiedTail(bytes)
    val isNat64 = hasPrefixBytes(bytes, NAT64_PREFIX) &&
        isAllZero(bytes, NAT64_PREFIX_LEN, IPV6_EMBEDDED_OFFSET)
    val is6to4 = hasPrefixBytes(bytes, SIX_TO_FOUR_PREFIX)
    return when {
        isMapped || isTranslated || isCompatible || isNat64 ->
            bytes.copyOfRange(IPV6_EMBEDDED_OFFSET, IPV6_BYTE_LENGTH)
        is6to4 -> bytes.copyOfRange(SIX_TO_FOUR_OFFSET, SIX_TO_FOUR_OFFSET + IPV4_OCTET_COUNT)
        else -> null
    }
}

private fun isAllZero(bytes: ByteArray, start: Int, endExclusive: Int): Boolean =
    (start until endExclusive).all { bytes[it] == 0.toByte() }

private fun isAllFf(bytes: ByteArray, start: Int, endExclusive: Int): Boolean =
    (start until endExclusive).all { (bytes[it].toInt() and BYTE_MASK) == BYTE_MASK }

private fun hasPrefixBytes(bytes: ByteArray, expected: IntArray): Boolean =
    expected.indices.all { idx -> (bytes[idx].toInt() and BYTE_MASK) == expected[idx] }

private fun isLoopbackOrUnspecifiedTail(bytes: ByteArray): Boolean =
    isAllZero(bytes, IPV6_EMBEDDED_OFFSET, IPV6_BYTE_LENGTH - 1) &&
        (bytes[IPV6_BYTE_LENGTH - 1].toInt() and BYTE_MASK) <= 1

/**
 * Parses an IP literal without ever triggering a DNS lookup.
 */
private fun parseLiteralAddress(raw: String): InetAddress? {
    val host = raw.trim().trim('[', ']').trimEnd('.').substringBefore('%')
    if (host.isEmpty()) return null
    return when {
        isStandardIpv4Literal(host) -> runCatching { InetAddress.getByName(host) }.getOrNull()
        host.contains(':') -> runCatching { InetAddress.getByName(host) }.getOrNull()
        else -> null
    }
}

private fun isStandardIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != IPV4_OCTET_COUNT) return false
    return parts.all { part ->
        val validChars = part.isNotEmpty() && part.length <= IPV4_PART_MAX_LEN && part.all { it in '0'..'9' }
        val noLeadingZero = part.length == 1 || !part.startsWith('0')
        validChars && noLeadingZero && part.toIntOrNull()?.let { it in 0..BYTE_MASK } == true
    }
}

private fun isNonStandardNumericIpv4(rawHost: String): Boolean {
    val clean = rawHost.trim().trimEnd('.')
    if (clean.isEmpty() || clean.contains(':') || isStandardIpv4Literal(clean)) {
        return false
    }
    val parts = clean.split('.')
    return parts.size in 1..IPV4_OCTET_COUNT && parts.all(::isNumericIpv4Component)
}

private fun isNumericIpv4Component(part: String): Boolean {
    if (part.isEmpty()) return false
    val lower = part.lowercase()
    return if (lower.startsWith(HEX_PREFIX)) {
        lower.length > HEX_PREFIX.length && lower.drop(HEX_PREFIX.length).all {
            it in '0'..'9' || it in 'a'..'f'
        }
    } else {
        lower.all { it in '0'..'9' }
    }
}

private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
    val v6 = address as? Inet6Address ?: return false
    val bytes = v6.address
    return bytes.isNotEmpty() && (bytes[0].toInt() and ULA_PREFIX_MASK) == ULA_PREFIX_VALUE
}
