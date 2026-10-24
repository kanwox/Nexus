package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object DnsRepository {
    private const val MAX_RECORDS = 500
    private val nextId = AtomicLong(1)
    private val recordList = mutableListOf<DnsRecord>()
    private val _recordsFlow = MutableStateFlow<List<DnsRecord>>(emptyList())
    val recordsFlow: StateFlow<List<DnsRecord>> = _recordsFlow.asStateFlow()

    private val dnsRegex1 = Regex("""(?:\[DNS\]\s+)?(?:resolve:?\s+|fake-ip:?\s+|query:?\s+|cache:?\s+)?([a-zA-Z0-9_.-]+\.[a-zA-Z]{2,})\s*(?:-->|->|to)\s*\[?([0-9a-fA-F.:]+)\]?""")
    private val dnsRegex2 = Regex("""\[DNS\]\s+([a-zA-Z0-9_.-]+\.[a-zA-Z]{2,})\s*(?:\[|\()([0-9a-fA-F.:]+)(?:\]|\))""")

    @Synchronized
    fun addRecord(domain: String, ip: String, isFakeIp: Boolean = false, latencyMs: Int = 0, type: String = "A") {
        val cleanDomain = domain.trim()
        val cleanIp = ip.trim().trim('[', ']')
        if (cleanDomain.isBlank() || cleanIp.isBlank()) return

        // Avoid spamming duplicate identical queries in consecutive succession
        val first = recordList.firstOrNull()
        if (first != null && first.domain.equals(cleanDomain, ignoreCase = true) && first.ip == cleanIp) {
            val now = System.currentTimeMillis()
            if (now - first.timestamp < 1500L) return
        }

        val record = DnsRecord(
            id = nextId.getAndIncrement(),
            domain = cleanDomain,
            ip = cleanIp,
            isFakeIp = isFakeIp || cleanIp.startsWith("198.18.") || cleanIp.startsWith("198.19."),
            latencyMs = latencyMs,
            timestamp = System.currentTimeMillis(),
            type = type
        )
        recordList.add(0, record)
        if (recordList.size > MAX_RECORDS) {
            recordList.removeAt(recordList.lastIndex)
        }
        _recordsFlow.value = recordList.toList()
    }

    @Synchronized
    fun clear() {
        recordList.clear()
        _recordsFlow.value = emptyList()
    }

    @Synchronized
    fun findIpForDomain(domain: String): String? {
        val clean = domain.trim()
        return recordList.firstOrNull { it.domain.equals(clean, ignoreCase = true) }?.ip
    }

    fun parseFromLog(message: String) {
        try {
            if (!message.contains("[DNS]", ignoreCase = true) && !message.contains("resolve", ignoreCase = true)) return

            // 1. Match: [DNS] (optional resolve/fake-ip/query/cache) domain -> ip or --> ip or to ip
            val match1 = dnsRegex1.find(message)
            if (match1 != null) {
                val domain = match1.groupValues[1]
                val ip = match1.groupValues[2]
                addRecord(domain, ip)
                return
            }

            // 2. Match: [DNS] domain [ip] or [DNS] domain (ip)
            val match2 = dnsRegex2.find(message)
            if (match2 != null) {
                val domain = match2.groupValues[1]
                val ip = match2.groupValues[2]
                addRecord(domain, ip)
            }
        } catch (_: Exception) {}
    }
}
