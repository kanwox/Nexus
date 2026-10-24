package com.github.mihomo.android.data

/**
 * Remote subscription endpoints accept both https and http (cleartext HTTP is
 * deliberately allowed — the network security config permits it globally), so
 * this rejects everything else (ftp:, file:, data: …) up front instead of
 * surfacing OkHttp's opaque failures to the user.
 */
internal fun requireSecureRemoteUrl(url: String) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "https" && scheme != "http") {
        error("不支持的地址协议：${redactUrl(url)}")
    }
    // SSRF guard: subscription/script links are often pasted from untrusted sources, so the
    // app must not fetch from link-local addresses (e.g. the 169.254.169.254 cloud metadata
    // endpoint). Loopback stays allowed by design for local tooling (subconverter etc.), and
    // private LAN ranges stay allowed for self-hosted subscription converters.
    val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
    if (host != null) {
        val isLinkLocal = host.startsWith("169.254.") || host.startsWith("fe80:")
        if (isLinkLocal) {
            error("不允许的地址：${redactUrl(url)}")
        }
    }
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
