package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object DnsRepository {
    private const val MAX_RECORDS = 500
    private const val PUBLISH_INTERVAL_MS = 400L
    private val nextId = AtomicLong(1)
    private val recordList = ArrayDeque<DnsRecord>(MAX_RECORDS)
    private val domainToIpMap = ConcurrentHashMap<String, String>()

    private val _recordsFlow = MutableStateFlow<List<DnsRecord>>(emptyList())
    val recordsFlow: StateFlow<List<DnsRecord>> = _recordsFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _recordsFlow.subscriptionCount) {
        val snapshot = synchronized(this) { recordList.toList() }
        _recordsFlow.value = snapshot
    }

    // Handles:
    // [DNS] www.google.com --> 142.250.190.46
    // [DNS] www.google.com --> 198.18.0.2
    // [DNS] cache hit www.google.com --> 198.18.0.2, expire at 2026-10-24 15:30:00
    // [DNS] www.google.com --> 142.250.190.46 from 223.5.5.5
    // [DNS] dns.google --> [8.8.8.8 8.8.4.4] from 223.5.5.5
    private val arrowRegex = Regex(
        """\[DNS]\s+(?:cache\s+hit\s+)?([^\s:]+)(?:\s+[A-Za-z0-9]+)?\s+-->\s+(\[[^]]+]|[0-9a-fA-F.:]+)(?:[,\s]+(?:from\s+([^\s,]+)|expire\s+at\s+.*|([0-9]+ms)))?""",
        RegexOption.IGNORE_CASE
    )

    private val resolveRegex = Regex(
        """\[DNS]\s+resolve\s+(\S+)\s+([A-Za-z0-9]+)\s+from\s+(\S+)""",
        RegexOption.IGNORE_CASE,
    )

    @Synchronized
    fun addRecord(
        domain: String,
        ip: String,
        isFakeIp: Boolean = false,
        latencyMs: Int = 0,
        type: String = "A",
        server: String? = null
    ) {
        val cleanDomain = domain.trim()
        val cleanIp = ip.trim().trim('[', ']').split(Regex("""\s+""")).joinToString(", ")
        if (cleanDomain.isBlank() || cleanIp.isBlank()) return

        val cleanServer = server?.trim()
            ?.removePrefix("udp://")
            ?.removePrefix("tcp://")
            ?.removeSuffix(":53")

        val effectiveFakeIp = isFakeIp || cleanIp.startsWith("198.18.") || cleanIp.startsWith("198.19.")

        domainToIpMap[cleanDomain.lowercase()] = cleanIp

        // If there was an in-progress "Resolving..." entry for this domain, update it in place
        val pendingIndex = recordList.indexOfFirst {
            it.domain.equals(cleanDomain, ignoreCase = true) && (it.ip == "Resolving...")
        }
        if ((pendingIndex != -1) && (cleanIp != "Resolving...")) {
            val old = recordList[pendingIndex]
            recordList[pendingIndex] = old.copy(
                ip = cleanIp,
                isFakeIp = effectiveFakeIp,
                latencyMs = latencyMs,
                server = cleanServer ?: old.server,
                type = type
            )
            publisher.request()
            return
        }

        // Avoid duplicate identical queries spamming in consecutive 1.5 seconds
        val first = recordList.firstOrNull()
        if (first != null && first.domain.equals(cleanDomain, ignoreCase = true) && first.ip == cleanIp) {
            val now = System.currentTimeMillis()
            if (now - first.timestamp < 1500L) return
        }

        val record = DnsRecord(
            id = nextId.getAndIncrement(),
            domain = cleanDomain,
            ip = cleanIp,
            isFakeIp = effectiveFakeIp,
            latencyMs = latencyMs,
            timestamp = System.currentTimeMillis(),
            type = type,
            server = cleanServer
        )
        if (recordList.size >= MAX_RECORDS) {
            recordList.removeLast()
        }
        recordList.addFirst(record)
        publisher.request()
    }

    @Synchronized
    fun clear() {
        recordList.clear()
        domainToIpMap.clear()
        publisher.publishNow()
    }

    fun findIpForDomain(domain: String): String? {
        val clean = domain.trim().lowercase()
        return domainToIpMap[clean]
    }

    fun parseFromLog(message: String) {
        try {
            if (!message.contains("[DNS]", ignoreCase = true)) return

            val arrowMatch = arrowRegex.find(message)
            if (arrowMatch != null) {
                val domain = arrowMatch.groupValues[1]
                val ipRaw = arrowMatch.groupValues[2]
                val server = arrowMatch.groupValues[3].takeIf { it.isNotBlank() }
                addRecord(domain = domain, ip = ipRaw, server = server)
                return
            }

            val resolveMatch = resolveRegex.find(message)
            if (resolveMatch != null) {
                val domain = resolveMatch.groupValues[1]
                val recordType = resolveMatch.groupValues[2]
                val server = resolveMatch.groupValues[3]
                // Recorded as in-progress or pending resolution if not yet resolved
                if (!domainToIpMap.containsKey(domain.lowercase())) {
                    addRecord(domain = domain, ip = "Resolving...", type = recordType, server = server)
                }
            }
        } catch (_: Exception) {}
    }
}
