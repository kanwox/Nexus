package com.github.mihomo.android.data

/**
 * Model representing a network connection / request record.
 * Designed with pure iOS/Loon linear metadata structure.
 */
data class RequestRecord(
    val id: Long,
    val host: String,
    val destinationIp: String,
    val destinationPort: Int = 443,
    val network: String = "TCP",
    val sourceApp: String = "系统",
    val packageName: String? = null,
    val rule: String = "Match",
    val rulePayload: String = "",
    val outbound: String = "DIRECT",
    val chain: List<String> = emptyList(),
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val isActive: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L
)
