package com.github.mihomo.android.data

/**
 * Model representing a network connection / request record.
 * Designed with pure iOS/Loon linear metadata structure.
 */
data class RequestRecord(
    val id: Long,
    /** Stable id from the core's /connections API; null for legacy log-parsed records. */
    val connectionId: String? = null,
    val host: String,
    val destinationIp: String,
    val destinationPort: Int = 443,
    val network: String = "TCP",
    /** Resolved app label; blank means "system / unknown" and the UI localizes the fallback. */
    val sourceApp: String = "",
    val packageName: String? = null,
    val rule: String = "Match",
    val rulePayload: String = "",
    val outbound: String = "DIRECT",
    val chain: List<String> = emptyList(),
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val isActive: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    /** Last time the poller confirmed this connection alive (drives duration on close). */
    val lastSeenMs: Long = 0L
)
