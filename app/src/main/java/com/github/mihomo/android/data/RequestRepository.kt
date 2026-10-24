package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object RequestRepository {
    private const val MAX_RECORDS = 500
    private const val PUBLISH_INTERVAL_MS = 400L
    private const val ACTIVE_CONNECTION_TTL_MS = 120_000L
    private const val TTL_SWEEP_INTERVAL_MS = 15_000L
    private val nextId = AtomicLong(1)
    private val recordList = ArrayDeque<RequestRecord>(MAX_RECORDS)
    private var lastSweepTimestamp = 0L

    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _recordsFlow.subscriptionCount) {
        val snapshot = synchronized(this) { recordList.toList() }
        _recordsFlow.value = snapshot
    }

    private val closeRegex = Regex(
        """\[(TCP|UDP|ICMP)]\s+(?:connection\s+to\s+)?(?:\[([0-9a-fA-F:]+)]|([^\s:]+))(?::\d+)?\s+closed""",
        RegexOption.IGNORE_CASE
    )

    // Handles:
    // [TCP] 172.19.0.1:54321(com.android.chrome) --> www.google.com:443 match DomainSuffix(google.com) using 🔰 节点选择[🇭🇰 香港 01]
    // [TCP] 172.19.0.1:54321 --> api.github.com:443 doesn't match any rule using DIRECT
    // [UDP] 172.19.0.1:12345 --> 8.8.8.8:53 using DIRECT
    // [TCP] 172.19.0.1:54321 --> [2607:f8b0:4005:805::200e]:443 match GeoIP(US) using Proxy[US 01]
    private val arrowRegex = Regex(
        """\[(TCP|UDP|ICMP)]\s+(?:\[?[0-9a-fA-F.:]+]?:\d+)?(?:\(([^)]+)\))?\s+(?:-->|->)\s+(?:\[([0-9a-fA-F:]+)]|([^\s:]+)):?(\d+)?(?:\s+(?:match\s+([a-zA-Z0-9_.-]+)(?:\(([^)]*)\))?|doesn't match any rule))?\s+using\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Handles:
    // [TCP] dial Proxy[HK 01] (match DomainSuffix/github.com) to api.github.com:443
    // [TCP] dial DIRECT to 127.0.0.1:53
    private val dialRegex = Regex(
        """\[(TCP|UDP|ICMP)]\s+dial\s+(.+?)(?:\s+\(match\s+([a-zA-Z0-9_.-]+)(?:[/(]([^/)]+)[)/]?)?\))?\s+to\s+(?:\[([0-9a-fA-F:]+)]|([^\s:]+)):?(\d+)?$""",
        RegexOption.IGNORE_CASE
    )

    @Synchronized
    fun addRecord(record: RequestRecord) {
        val now = record.timestamp
        if ((now - lastSweepTimestamp) >= TTL_SWEEP_INTERVAL_MS) {
            lastSweepTimestamp = now
            for (i in recordList.indices) {
                val existing = recordList[i]
                if (existing.isActive && now - existing.timestamp > ACTIVE_CONNECTION_TTL_MS) {
                    recordList[i] = existing.copy(
                        isActive = false,
                        durationMs = now - existing.timestamp
                    )
                }
            }
        }
        if (recordList.size >= MAX_RECORDS) {
            recordList.removeLast()
        }
        recordList.addFirst(record)
        publisher.request()
    }

    @Synchronized
    fun closeConnection(id: Long) {
        val index = recordList.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = recordList[index]
            recordList[index] = old.copy(
                isActive = false,
                durationMs = if (old.durationMs > 0) old.durationMs else (System.currentTimeMillis() - old.timestamp)
            )
            publisher.request()
        }
    }

    @Synchronized
    fun markHostClosed(host: String) {
        val cleanHost = host.trim().lowercase()
        val index = recordList.indexOfFirst { it.isActive && it.host.lowercase() == cleanHost }
        if (index != -1) {
            val old = recordList[index]
            recordList[index] = old.copy(
                isActive = false,
                durationMs = if (old.durationMs > 0) old.durationMs else (System.currentTimeMillis() - old.timestamp)
            )
            publisher.request()
        }
    }

    @Synchronized
    fun clearClosed() {
        recordList.removeAll { !it.isActive }
        publisher.publishNow()
    }

    @Synchronized
    fun clearAll() {
        recordList.clear()
        publisher.publishNow()
    }

    private fun parseChain(rawOutbound: String): List<String> {
        val trimmed = rawOutbound.trim()
        if (trimmed.equals("DIRECT", ignoreCase = true) || trimmed.equals("REJECT", ignoreCase = true)) {
            return listOf(trimmed.uppercase())
        }
        // Check for Proxy[name] or Group[Node] format: e.g. 🔰 节点选择[🇭🇰 香港 01]
        val bracketMatch = Regex("""^(.*?)\s*\[(.*?)]$""").find(trimmed)
        if (bracketMatch != null) {
            val group = bracketMatch.groupValues[1].trim()
            val node = bracketMatch.groupValues[2].trim()
            return if (group.isNotBlank() && !group.equals("Proxy", ignoreCase = true)) {
                listOf(group, node)
            } else {
                listOf(node)
            }
        }
        return listOf("🔰 节点选择", trimmed)
    }

    fun parseFromLog(message: String) {
        try {
            val trimmed = message.trim()

            // 1. Check for connection closed events: e.g. [TCP] connection to api.github.com:443 closed
            if (trimmed.contains("closed", ignoreCase = true)) {
                val closeMatch = closeRegex.find(trimmed)
                if (closeMatch != null) {
                    val host = (closeMatch.groupValues[2].takeIf { it.isNotBlank() }
                        ?: closeMatch.groupValues[3]).trim()
                    markHostClosed(host)
                    return
                }
            }

            // Quick pre-filtering
            if (!trimmed.contains("-->") && !trimmed.contains("->") && !trimmed.contains("dial", ignoreCase = true)) {
                return
            }

            // 2. Format: [TCP] 172.19.0.1:54321(com.android.chrome) --> www.google.com:443 match DomainSuffix(google.com) using 🔰 节点选择[🇭🇰 香港 01]
            val arrowMatch = arrowRegex.find(trimmed)
            if (arrowMatch != null) {
                val net = arrowMatch.groupValues[1].uppercase()
                val pkgName = arrowMatch.groupValues[2].takeIf { it.isNotBlank() }
                val host = (arrowMatch.groupValues[3].takeIf { it.isNotBlank() }
                    ?: arrowMatch.groupValues[4]).trim()
                val port = arrowMatch.groupValues[5].toIntOrNull() ?: 443
                val rawRule = arrowMatch.groupValues[6].takeIf { it.isNotBlank() }
                val ruleType = when {
                    rawRule != null -> rawRule
                    trimmed.contains("doesn't match any rule", ignoreCase = true) -> "Direct/Default"
                    else -> "Match"
                }
                val rulePayload = arrowMatch.groupValues[7].trim()
                val rawOutbound = arrowMatch.groupValues[8].trim()
                val outbound = if (rawOutbound.startsWith("Proxy[") && rawOutbound.endsWith("]")) {
                    rawOutbound.removePrefix("Proxy[").removeSuffix("]").trim()
                } else {
                    rawOutbound
                }

                val resolvedIp = DnsRepository.findIpForDomain(host) ?: host
                val isReject = outbound.equals("REJECT", ignoreCase = true)

                // Only mirror genuinely resolved domains into the DNS view. Synthesizing a
                // fake "198.18.0.x (Proxy)" entry polluted the DNS list with non-DNS data
                // and poisoned the domain→IP map used to fill destinationIp elsewhere.
                if (host.contains('.') && !host.matches(Regex("""^[0-9.:]+$""")) && resolvedIp != host) {
                    DnsRepository.addRecord(
                        domain = host,
                        ip = resolvedIp,
                        isFakeIp = resolvedIp.startsWith("198.18.") || resolvedIp.startsWith("198.19."),
                        server = outbound
                    )
                }

                addRecord(
                    RequestRecord(
                        id = nextId.getAndIncrement(),
                        host = host,
                        destinationIp = resolvedIp,
                        destinationPort = port,
                        network = net,
                        sourceApp = pkgName ?: "系统",
                        packageName = pkgName,
                        rule = ruleType,
                        rulePayload = rulePayload,
                        outbound = outbound,
                        chain = parseChain(rawOutbound),
                        isActive = !isReject,
                        timestamp = System.currentTimeMillis()
                    )
                )
                return
            }

            // 3. Format: [TCP] dial Proxy[HK 01] (match DomainSuffix/github.com) to api.github.com:443
            val dialMatch = dialRegex.find(trimmed)
            if (dialMatch != null) {
                val net = dialMatch.groupValues[1].uppercase()
                val rawOutbound = dialMatch.groupValues[2].trim()
                val outbound = if (rawOutbound.startsWith("Proxy[") && rawOutbound.endsWith("]")) {
                    rawOutbound.removePrefix("Proxy[").removeSuffix("]").trim()
                } else {
                    rawOutbound
                }
                val ruleType = dialMatch.groupValues[3].ifBlank { "Match" }
                val rulePayload = dialMatch.groupValues[4].trim()
                val host = (dialMatch.groupValues[5].takeIf { it.isNotBlank() }
                    ?: dialMatch.groupValues[6]).trim()
                val port = dialMatch.groupValues[7].toIntOrNull() ?: 443

                val resolvedIp = DnsRepository.findIpForDomain(host) ?: host
                val isReject = outbound.equals("REJECT", ignoreCase = true)

                // See the arrow-log branch above: only record real resolutions, never
                // synthetic placeholder IPs.
                if (host.contains('.') && !host.matches(Regex("""^[0-9.:]+$""")) && resolvedIp != host) {
                    DnsRepository.addRecord(
                        domain = host,
                        ip = resolvedIp,
                        isFakeIp = resolvedIp.startsWith("198.18.") || resolvedIp.startsWith("198.19."),
                        server = outbound
                    )
                }

                addRecord(
                    RequestRecord(
                        id = nextId.getAndIncrement(),
                        host = host,
                        destinationIp = resolvedIp,
                        destinationPort = port,
                        network = net,
                        sourceApp = "系统",
                        packageName = null,
                        rule = ruleType,
                        rulePayload = rulePayload,
                        outbound = outbound,
                        chain = parseChain(rawOutbound),
                        isActive = !isReject,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.d("RequestRepository", "parseFromLog failed: ${e.message}")
        }
    }
}
