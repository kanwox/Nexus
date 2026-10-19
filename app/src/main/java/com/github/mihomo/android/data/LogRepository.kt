package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // info, warning, error, debug
    val message: String
)

object LogRepository {
    private const val MAX_LOGS = 1000
    private val nextId = AtomicLong(1)
    private val logList = mutableListOf<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        seedDemoDataIfEmpty()
    }

    @Synchronized
    fun addRawLogcat(payload: String) {
        try {
            val element = json.parseToJsonElement(payload)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: "info"
            val msg = obj["payload"]?.jsonPrimitive?.content ?: payload
            addLog(type, msg)
        } catch (_: Exception) {
            addLog("info", payload)
        }
    }

    @Synchronized
    fun addLog(level: String, message: String) {
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            level = level.lowercase(),
            message = message
        )
        logList.add(entry)
        if (logList.size > MAX_LOGS) {
            logList.removeAt(0)
        }
        _logsFlow.value = logList.toList()
        runCatching {
            DnsRepository.parseFromLog(message)
            RequestRepository.parseFromLog(message)
        }
    }

    @Synchronized
    fun clear() {
        logList.clear()
        _logsFlow.value = emptyList()
    }

    private fun seedDemoDataIfEmpty() {
        if (logList.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val samples = listOf(
            LogEntry(nextId.getAndIncrement(), now - 2000, "error", "Failed to connect to endpoint 142.250.198.3:443: dial tcp timeout (3000ms)"),
            LogEntry(nextId.getAndIncrement(), now - 8000, "info", "Health check completed for group [🔰 节点选择], best node [🇭🇰 香港 01] (latency: 48ms)"),
            LogEntry(nextId.getAndIncrement(), now - 14000, "warning", "GeoIP database [Country.mmdb] cache will expire in 12 days"),
            LogEntry(nextId.getAndIncrement(), now - 21000, "info", "Mixed inbound proxy listening at 127.0.0.1:7890 (allow-lan: false)"),
            LogEntry(nextId.getAndIncrement(), now - 27000, "info", "DNS enhanced mode: Fake-IP pool (198.18.0.0/16) active"),
            LogEntry(nextId.getAndIncrement(), now - 35000, "info", "Mihomo (Clash.Meta) Core v1.18.7 initialized successfully")
        )
        logList.addAll(samples)
        _logsFlow.value = logList.toList()
    }
}
