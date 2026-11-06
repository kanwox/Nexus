package com.github.mihomo.android.data

data class DnsRecord(
    val id: Long,
    val domain: String,
    val ip: String,
    val isFakeIp: Boolean = false,
    val latencyMs: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "A",
    val server: String? = null,
    val isCacheHit: Boolean = false
) {
    val isResolving: Boolean
        get() = ip.equals("Resolving...", ignoreCase = true)
}
