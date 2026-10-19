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

    init {
        seedDemoDataIfEmpty()
    }

    @Synchronized
    fun addRecord(domain: String, ip: String, isFakeIp: Boolean = false, latencyMs: Int = 0, type: String = "A") {
        if (domain.isBlank() || ip.isBlank()) return
        val record = DnsRecord(
            id = nextId.getAndIncrement(),
            domain = domain.trim(),
            ip = ip.trim(),
            isFakeIp = isFakeIp || ip.trim().startsWith("198.18."),
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

    private fun seedDemoDataIfEmpty() {
        if (recordList.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val samples = listOf(
            DnsRecord(nextId.getAndIncrement(), "api.github.com", "198.18.0.24", isFakeIp = true, latencyMs = 28, timestamp = now - 4000),
            DnsRecord(nextId.getAndIncrement(), "connectivitycheck.gstatic.com", "142.250.198.3", isFakeIp = false, latencyMs = 12, timestamp = now - 18000),
            DnsRecord(nextId.getAndIncrement(), "gateway.icloud.com", "198.18.0.42", isFakeIp = true, latencyMs = 0, timestamp = now - 45000),
            DnsRecord(nextId.getAndIncrement(), "doh.pub", "119.29.29.29", isFakeIp = false, latencyMs = 8, timestamp = now - 72000),
            DnsRecord(nextId.getAndIncrement(), "v2ex.com", "198.18.0.88", isFakeIp = true, latencyMs = 45, timestamp = now - 130000),
            DnsRecord(nextId.getAndIncrement(), "www.google.com", "198.18.0.103", isFakeIp = true, latencyMs = 32, timestamp = now - 210000),
            DnsRecord(nextId.getAndIncrement(), "weixin.qq.com", "183.3.226.35", isFakeIp = false, latencyMs = 15, timestamp = now - 350000)
        )
        recordList.addAll(samples)
        _recordsFlow.value = recordList.toList()
    }

    fun parseFromLog(message: String) {
        try {
            if (!message.contains("[DNS]", ignoreCase = true) && !message.contains("resolve", ignoreCase = true)) return
            val dnsRegex = Regex("""(?:\[DNS\]\s+)?(?:resolve\s+)?([a-zA-Z0-9.-]+)\s+(?:to|-->)\s+([0-9a-fA-F.:]+)""")
            val match = dnsRegex.find(message)
            if (match != null) {
                val domain = match.groupValues[1]
                val ip = match.groupValues[2]
                addRecord(domain, ip)
            }
        } catch (_: Exception) {}
    }
}
