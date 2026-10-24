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
    private val logList = ArrayDeque<LogEntry>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

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
        if (logList.size >= MAX_LOGS) {
            logList.removeFirst()
        }
        logList.addLast(entry)
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
}
