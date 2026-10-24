package com.github.mihomo.android.data

/**
 * Remote subscription endpoints accept both https and http (cleartext HTTP is
 * deliberately allowed — the network security config permits it globally), so
 * this rejects everything else (ftp:, file:, data: …) up front instead of
 * surfacing OkHttp's opaque failures to the user.
 */
internal fun requireSecureRemoteUrl(url: String) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme == "https" || scheme == "http") return
    error("不支持的地址协议：$url")
}
