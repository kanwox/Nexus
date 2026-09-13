package com.github.mihomo.android.data

/**
 * Cleartext HTTP is disabled by the network security config, so reject remote
 * endpoints up front instead of surfacing OkHttp's opaque
 * `UnknownServiceException` to the user. Loopback stays exempt because the
 * security config explicitly allows it for local tooling.
 */
internal fun requireSecureRemoteUrl(url: String) {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme == "https") return
    if (scheme == "http") {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        if (host == "127.0.0.1" || host == "localhost") return
        error("出于安全考虑已禁用明文 HTTP 下载，请改用 https:// 地址")
    }
    error("不支持的地址协议：$url")
}
