package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object RequestRepository {
    private const val MAX_RECORDS = 500
    private val nextId = AtomicLong(1)
    private val recordList = mutableListOf<RequestRecord>()
    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    init {
        seedDemoDataIfEmpty()
    }

    @Synchronized
    fun addRecord(record: RequestRecord) {
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
    fun clearClosed() {
        recordList.removeAll { !it.isActive }
        _recordsFlow.value = recordList.toList()
    }

    @Synchronized
    fun clearAll() {
        recordList.clear()
        _recordsFlow.value = emptyList()
    }

    private fun seedDemoDataIfEmpty() {
        if (recordList.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val samples = listOf(
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "www.youtube.com",
                destinationIp = "198.18.0.12",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "YouTube",
                packageName = "com.google.android.youtube",
                rule = "Match",
                rulePayload = "Google",
                outbound = "🇭🇰 香港 01",
                chain = listOf("🔰 节点选择", "🇭🇰 香港 01"),
                uploadBytes = 127_400L,
                downloadBytes = 8_840_000L,
                isActive = true,
                timestamp = now - 5_000L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "api.github.com",
                destinationIp = "198.18.0.24",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "Chrome",
                packageName = "com.android.chrome",
                rule = "DomainSuffix",
                rulePayload = "github.com",
                outbound = "🇭🇰 香港 01",
                chain = listOf("🔰 节点选择", "🇭🇰 香港 01"),
                uploadBytes = 18_600L,
                downloadBytes = 96_800L,
                isActive = true,
                timestamp = now - 14_000L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "149.154.167.92",
                destinationIp = "149.154.167.92",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "Telegram",
                packageName = "org.telegram.messenger",
                rule = "GeoIP",
                rulePayload = "Telegram",
                outbound = "🇯🇵 日本 03",
                chain = listOf("🔰 节点选择", "🇯🇵 日本 03"),
                uploadBytes = 43_100L,
                downloadBytes = 320_000L,
                isActive = true,
                timestamp = now - 35_000L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "gateway.icloud.com",
                destinationIp = "198.18.0.42",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "系统",
                rule = "DomainSuffix",
                rulePayload = "icloud.com",
                outbound = "🇺🇸 美国 02",
                chain = listOf("🔰 节点选择", "🇺🇸 美国 02"),
                uploadBytes = 3_280L,
                downloadBytes = 9_120L,
                isActive = true,
                timestamp = now - 62_000L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "weixin.qq.com",
                destinationIp = "183.3.226.35",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "微信",
                packageName = "com.tencent.mm",
                rule = "GeoIP",
                rulePayload = "CN",
                outbound = "DIRECT",
                chain = listOf("DIRECT"),
                uploadBytes = 4_200L,
                downloadBytes = 13_100L,
                isActive = false,
                timestamp = now - 85_000L,
                durationMs = 8_400L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "pagead2.googlesyndication.com",
                destinationIp = "142.250.198.66",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "Chrome",
                packageName = "com.android.chrome",
                rule = "RuleSet",
                rulePayload = "Advertising",
                outbound = "REJECT",
                chain = listOf("REJECT"),
                uploadBytes = 0L,
                downloadBytes = 0L,
                isActive = false,
                timestamp = now - 110_000L,
                durationMs = 12L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "dns.google",
                destinationIp = "8.8.8.8",
                destinationPort = 853,
                network = "UDP",
                sourceApp = "系统",
                rule = "DomainSuffix",
                rulePayload = "google",
                outbound = "🇸🇬 新加坡 01",
                chain = listOf("🔰 节点选择", "🇸🇬 新加坡 01"),
                uploadBytes = 1_230L,
                downloadBytes = 2_450L,
                isActive = false,
                timestamp = now - 145_000L,
                durationMs = 88L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "api.bilibili.com",
                destinationIp = "119.29.29.98",
                destinationPort = 443,
                network = "TCP",
                sourceApp = "哔哩哔哩",
                packageName = "tv.danmaku.bili",
                rule = "DomainSuffix",
                rulePayload = "bilibili.com",
                outbound = "DIRECT",
                chain = listOf("DIRECT"),
                uploadBytes = 25_200L,
                downloadBytes = 1_890_000L,
                isActive = false,
                timestamp = now - 220_000L,
                durationMs = 32_100L
            ),
            RequestRecord(
                id = nextId.getAndIncrement(),
                host = "connectivitycheck.gstatic.com",
                destinationIp = "142.250.198.3",
                destinationPort = 80,
                network = "TCP",
                sourceApp = "系统",
                rule = "DIRECT",
                rulePayload = "",
                outbound = "DIRECT",
                chain = listOf("DIRECT"),
                uploadBytes = 850L,
                downloadBytes = 1_220L,
                isActive = false,
                timestamp = now - 310_000L,
                durationMs = 1_100L
            )
        )
        recordList.addAll(samples)
        _recordsFlow.value = recordList.toList()
    }

    fun parseFromLog(message: String) {
        try {
            // Match formats like:
            // [TCP] 198.18.0.24:54321 --> api.github.com:443 match DomainSuffix(github.com) using Proxy[🇭🇰 HK-01]
            // or [TCP] dial DIRECT (match GeoIP/CN) to weixin.qq.com:443
            if (!message.contains("-->") && !message.contains("dial", ignoreCase = true) && !message.contains("match", ignoreCase = true)) {
                return
            }

            val regex1 = Regex("""\[(TCP|UDP)\]\s+(?:[0-9a-fA-F.:]+)\s+-->\s+([a-zA-Z0-9.-]+):(\d+)\s+match\s+([a-zA-Z0-9_.-]+)(?:\(([^)]+)\))?\s+using\s+(?:Proxy\[([^\]]+)\]|([^\s\n\r]+))""")
            val match1 = regex1.find(message)
            if (match1 != null) {
                val net = match1.groupValues[1]
                val host = match1.groupValues[2]
                val port = match1.groupValues[3].toIntOrNull() ?: 443
                val rule = match1.groupValues[4]
                val rulePayload = match1.groupValues[5]
                val outbound = match1.groupValues[6].ifBlank { match1.groupValues[7] }.ifBlank { "DIRECT" }

                addRecord(
                    RequestRecord(
                        id = nextId.getAndIncrement(),
                        host = host,
                        destinationIp = host,
                        destinationPort = port,
                        network = net,
                        sourceApp = "系统",
                        rule = rule,
                        rulePayload = rulePayload,
                        outbound = outbound,
                        chain = listOf(outbound),
                        isActive = !outbound.equals("REJECT", ignoreCase = true),
                        timestamp = System.currentTimeMillis()
                    )
                )
                return
            }

            val regex2 = Regex("""\[(TCP|UDP)\]\s+dial\s+([^\s]+)\s+\(match\s+([^\)]+)\)\s+to\s+([a-zA-Z0-9.-]+):(\d+)""")
            val match2 = regex2.find(message)
            if (match2 != null) {
                val net = match2.groupValues[1]
                val outbound = match2.groupValues[2]
                val rule = match2.groupValues[3]
                val host = match2.groupValues[4]
                val port = match2.groupValues[5].toIntOrNull() ?: 443

                addRecord(
                    RequestRecord(
                        id = nextId.getAndIncrement(),
                        host = host,
                        destinationIp = host,
                        destinationPort = port,
                        network = net,
                        sourceApp = "系统",
                        rule = rule,
                        rulePayload = "",
                        outbound = outbound,
                        chain = listOf(outbound),
                        isActive = !outbound.equals("REJECT", ignoreCase = true),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (_: Exception) {}
    }
}
