package com.github.mihomo.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private const val PUBLISH_INTERVAL_MS = 400L
    private val nextId = AtomicLong(1)
    private val logList = ArrayDeque<LogEntry>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _logsFlow.subscriptionCount) {
        val snapshot = synchronized(this) { logList.toList() }
        _logsFlow.value = snapshot
    }

    /**
     * When the device screen is off (Doze / standby), skip non-essential JNI logcat
     * JSON/Regex parsing and list allocations to eliminate background CPU/GC battery drain.
     */
    @Volatile
    var isScreenOff: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The CFA native bridge emits `{"level":"info","message":"...","time":...}`. Go's JSON encoder
     * escapes `>` as `\u003e`, so the raw payload must be decoded before parsing: reading the wrong
     * keys previously left the raw JSON as the message, so `-->` never matched and both the request
     * and DNS records stayed empty.
     */
    fun addRawLogcat(payload: String) {
        if (isScreenOff) return
        val obj: JsonObject? = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        if (obj == null) {
            addLog("info", payload)
            return
        }
        val level = (obj["level"] ?: obj["type"])?.jsonPrimitive?.content ?: "info"
        val msg = (obj["message"] ?: obj["payload"])?.jsonPrimitive?.content ?: payload
        addLog(level, msg)
    }

    fun addLog(level: String, message: String) {
        val normalizedLevel = level.lowercase()
        if (isScreenOff && normalizedLevel != "error") return
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            level = normalizedLevel,
            message = message
        )
        synchronized(this) {
            if (logList.size >= MAX_LOGS) {
                logList.removeFirst()
            }
            logList.addLast(entry)
        }
        publisher.request()
        if (!isScreenOff) {
            runCatching {
                DnsRepository.parseFromLog(message)
                RequestRepository.parseFromLog(message)
            }
        }
    }

    fun clear() {
        synchronized(this) { logList.clear() }
        publisher.publishNow()
    }
}
