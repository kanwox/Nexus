package com.github.mihomo.android.data

import okhttp3.Interceptor
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Interceptor that validates redirect targets against SSRF rules before OkHttp follows them.
 */
internal fun createSafeRedirectInterceptor(allowPrivateSupplier: () -> Boolean): Interceptor {
    return Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isRedirect) {
            val location = response.header("Location")
            if (!location.isNullOrBlank()) {
                val nextUrl = request.url.resolve(location)?.toString()
                if (nextUrl != null) {
                    requireSecureRemoteUrl(nextUrl, allowPrivateSupplier())
                }
            }
        }
        response
    }
}

/**
 * Remote subscription/script endpoints accept both https and http (cleartext is deliberately
 * allowed by the network security config), so everything else (ftp:, file:, data: …) is
 * rejected up front instead of surfacing OkHttp's opaque failures to the user.
 */
internal fun requireSecureRemoteUrl(url: String, allowPrivateHosts: Boolean = true) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "https" && scheme != "http") {
        error("不支持的地址协议：${redactUrl(url)}")
    }
    // SSRF guard: links are often pasted from untrusted sources. Link-local targets (e.g. the
    // 169.254.169.254 cloud metadata endpoint) are rejected regardless of the private-host
    // switch; loopback/LAN ranges stay allowed by default for self-hosted subscription
    // converters, and the settings switch narrows that down.
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
    if (host != null) {
        val literal = parseLiteralAddress(host)
        val isLinkLocal = literal != null &&
            (literal.isLinkLocalAddress || literal.isAnyLocalAddress)
        if (isLinkLocal || (!allowPrivateHosts && isPrivateNetworkHost(host))) {
            error("不允许的地址：${redactUrl(url)}")
        }
    }
}

/**
 * True for loopback, RFC1918 LAN ranges, IPv6 ULA, link-local literals and local hostnames.
 *
 * Only IP literals are inspected: hostnames are not resolved here, because doing DNS in the
 * validation path would block the caller and enable rebinding races. A public hostname that
 * resolves to a private address is therefore not detected — an accepted, documented limit of
 * the "reject private subscription hosts" switch.
 */
internal fun isPrivateNetworkHost(host: String): Boolean {
    val clean = host.trim().trim('[', ']').lowercase()
    if (clean.isEmpty()) return false
    if (clean == "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local")) {
        return true
    }
    val address = parseLiteralAddress(clean) ?: return false
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        isUniqueLocalIpv6(address)
}

/** Strips credentials, query string and fragment so subscription tokens never reach logcat. */
internal fun redactUrl(url: String): String {
    return runCatching {
        val uri = java.net.URI(url.trim())
        val userInfo = if (uri.rawUserInfo != null) "***@" else ""
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://$userInfo${uri.host}$port${uri.path ?: ""}"
    }.getOrDefault(
        url.substringBefore('?').substringBefore('#')
    )
}

/**
 * Parses an IP literal without ever triggering a DNS lookup. `InetAddress.getByName` is only
 * called for strings that are syntactically an IPv4 or IPv6 literal; a hostname such as
 * `fc2.com` or `backend.internal` is never handed to the resolver here.
 */
private fun parseLiteralAddress(raw: String): InetAddress? {
    // Drop an IPv6 zone id ("fe80::1%wlan0") before parsing so it is still recognised as a
    // link-local literal; getByName does not accept the zoned form on every JVM/Android version.
    val host = raw.trim().trim('[', ']').substringBefore('%')
    if (host.isEmpty()) return null
    return when {
        isIpv4Literal(host) -> runCatching { InetAddress.getByName(host) }.getOrNull()
        host.contains(':') -> runCatching { InetAddress.getByName(host) }.getOrNull()
        else -> null
    }
}

private fun isIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
    if (address !is Inet6Address) return false
    val bytes = address.address
    // fc00::/7 (unique local addresses)
    return bytes.isNotEmpty() && (bytes[0].toInt() and 0xFE) == 0xFC
}
