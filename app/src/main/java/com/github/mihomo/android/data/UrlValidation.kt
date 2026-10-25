package com.github.mihomo.android.data

/**
 * Remote subscription endpoints accept both https and http (cleartext HTTP is
 * deliberately allowed — the network security config permits it globally), so
 * this rejects everything else (ftp:, file:, data: …) up front instead of
 * surfacing OkHttp's opaque failures to the user.
 */
internal fun requireSecureRemoteUrl(url: String, allowPrivateHosts: Boolean = true) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "https" && scheme != "http") {
        error("不支持的地址协议：${redactUrl(url)}")
    }
    // SSRF guard: subscription/script links are often pasted from untrusted sources, so the
    // app must never fetch from link-local addresses (e.g. the 169.254.169.254 cloud metadata
    // endpoint) regardless of the private-hosts switch below. Loopback and LAN ranges stay
    // allowed by default for self-hosted subscription converters; the settings switch narrows
    // this down for users who only ever add public subscriptions.
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
    if (host != null) {
        val isLinkLocal = host.startsWith("169.254.") || host.startsWith("fe80:")
        val isPrivate = isPrivateNetworkHost(host)
        if (isLinkLocal || (!allowPrivateHosts && isPrivate)) {
            error("不允许的地址：${redactUrl(url)}")
        }
    }
}

/** True for loopback, RFC1918 LAN ranges, IPv6 ULA and the localhost hostname. */
internal fun isPrivateNetworkHost(host: String): Boolean {
    val clean = host.trim().trim('[', ']').lowercase()
    if (clean == "localhost" || clean.endsWith(".localhost") || clean == "::1") return true
    if (clean.startsWith("127.")) return true
    if (clean.startsWith("10.")) return true
    if (clean.startsWith("192.168.")) return true
    if (clean.startsWith("172.")) {
        val second = clean.substringAfter("172.").substringBefore('.').toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    // IPv6 unique-local (fc00::/7) and link-local literals
    if (clean.startsWith("fc") || clean.startsWith("fd") || clean.startsWith("fe80")) return true
    return false
}

/**
 * Strips credentials, query string and fragment so subscription tokens embedded in URLs
 * never reach logcat, crash files or user-visible toasts.
 */
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
