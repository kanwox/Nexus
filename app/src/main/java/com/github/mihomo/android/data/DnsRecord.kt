package com.github.mihomo.android.data

data class DnsRecord(
    val id: Long,
    val domain: String,
    val ip: String,
    val isFakeIp: Boolean = false,
    val latencyMs: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "A",
    val server: String? = null
)
