package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object RequestRepository {
    private const val MAX_RECORDS = 500
    private const val ACTIVE_CONNECTION_TTL_MS = 120_000L
    private val nextId = AtomicLong(1)
    private val recordList = mutableListOf<RequestRecord>()
    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    private val closeRegex = Regex(
        """\[(TCP|UDP)\]\s+(?:connection\s+to\s+)?([a-zA-Z0-9_.-]+)(?::\d+)?\s+closed""",
        RegexOption.IGNORE_CASE
    )
    private val arrowRegex = Regex(
        """\[(TCP|UDP)\]\s+(?:\[?[0-9a-fA-F.:]+\]?:\d+)(?:\(([^)]+)\))?\s+(?:-->|->)\s+\[?([a-zA-Z0-9_.-]+)\]?:(\d+)(?:\s+match\s+([a-zA-Z0-9_.-]+)(?:[/(]([^/)]+)[)/]?)?)?\s+using\s+(?:Proxy\[([^\]]+)\]|([^\s\r\n]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val dialRegex = Regex(
        """\[(TCP|UDP)\]\s+dial\s+(?:Proxy\[([^\]]+)\]|([^\s(]+))(?:\s+\(match\s+([a-zA-Z0-9_.-]+)(?:[/(]([^/)]+)[)/]?)?\))?\s+to\s+\[?([a-zA-Z0-9_.-]+)\]?:(\d+)""",
        RegexOption.IGNORE_CASE
    )

    @Synchronized
    fun addRecord(record: RequestRecord) {
        val now = record.timestamp
        for (i in recordList.indices) {
            val existing = recordList[i]
            if (existing.isActive && now - existing.timestamp > ACTIVE_CONNECTION_TTL_MS) {
                recordList[i] = existing.copy(
                    isActive = false,
                    durationMs = now - existing.timestamp
                )
            }
        }
        recordList.add(0, record)
        if (recordList.size > MAX_RECORDS) {
            recordList.removeAt(recordList.lastIndex)
        }
        _recordsFlow.value = recordList.toList()
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
            _recordsFlow.value = recordList.toList()
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
            _recordsFlow.value = recordList.toList()
        }
    }

    @Synchronized
    fun clearClosed() {
        recordList.removeAll { !it.isActive }
        _recordsFlow.value = recordList.toList()
    }

    @Synchronized
    fun clearAll() {
        recordList.clear()
        _recordsFlow.value = emptyList()
    }

    fun parseFromLog(message: String) {
        try {
            val trimmed = message.trim()

            // 1. Check for connection closed events: e.g. [TCP] connection to api.github.com:443 closed
            if (trimmed.contains("closed", ignoreCase = true)) {
                val closeMatch = closeRegex.find(trimmed)
                if (closeMatch != null) {
                    val host = closeMatch.groupValues[2]
                    markHostClosed(host)
                    return
                }
            }

            // Quick pre-filtering
            if (!trimmed.contains("-->") && !trimmed.contains("->") && !trimmed.contains("dial", ignoreCase = true)) {
                return
            }

            // 2. Format: [TCP] 198.18.0.1:54321 --> api.github.com:443 match DomainSuffix(github.com) using Proxy[HK 01]
            // or: [TCP] 198.18.0.1:54321 --> 1.1.1.1:853 using DIRECT
            val arrowMatch = arrowRegex.find(trimmed)
            if (arrowMatch != null) {
                val net = arrowMatch.groupValues[1].uppercase()
                val pkgName = arrowMatch.groupValues[2].takeIf { it.isNotBlank() }
                val host = arrowMatch.groupValues[3]
                val port = arrowMatch.groupValues[4].toIntOrNull() ?: 443
                val ruleType = arrowMatch.groupValues[5].ifBlank { "Match" }
                val rulePayload = arrowMatch.groupValues[6]
                val rawOutbound = arrowMatch.groupValues[7].ifBlank { arrowMatch.groupValues[8] }.ifBlank { "DIRECT" }.trim()
                val outbound = rawOutbound.removePrefix("Proxy[").removeSuffix("]")

                val resolvedIp = DnsRepository.findIpForDomain(host) ?: host
                val isReject = outbound.equals("REJECT", ignoreCase = true)

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
                        chain = if (outbound == "DIRECT" || outbound == "REJECT") listOf(outbound) else listOf("🔰 节点选择", outbound),
                        isActive = !isReject,
                        timestamp = System.currentTimeMillis()
                    )
                )
                return
            }

            // 3. Format: [TCP] dial Proxy[HK 01] (match DomainSuffix/github.com) to api.github.com:443
            // or: [TCP] dial DIRECT to 127.0.0.1:53
            val dialMatch = dialRegex.find(trimmed)
            if (dialMatch != null) {
                val net = dialMatch.groupValues[1].uppercase()
                val rawOutbound = dialMatch.groupValues[2].ifBlank { dialMatch.groupValues[3] }.ifBlank { "DIRECT" }.trim()
                val outbound = rawOutbound.removePrefix("Proxy[").removeSuffix("]")
                val ruleType = dialMatch.groupValues[4].ifBlank { "Match" }
                val rulePayload = dialMatch.groupValues[5]
                val host = dialMatch.groupValues[6]
                val port = dialMatch.groupValues[7].toIntOrNull() ?: 443

                val resolvedIp = DnsRepository.findIpForDomain(host) ?: host
                val isReject = outbound.equals("REJECT", ignoreCase = true)

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
                        chain = if (outbound == "DIRECT" || outbound == "REJECT") listOf(outbound) else listOf("🔰 节点选择", outbound),
                        isActive = !isReject,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (_: Exception) {}
    }
}
