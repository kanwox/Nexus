package com.github.mihomo.android.data

import com.github.kr328.clash.common.Global
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // info, warning, error, debug
    val message: String,
    /** How many times this identical message repeated since its first occurrence. */
    val count: Int = 1
)

object LogRepository {
    private const val MAX_LOGS = 1000
    private const val PUBLISH_INTERVAL_MS = 400L
    // Repeats collapse into the recent identical entry within this window, so a health-check
    // failure storm cannot churn through the whole buffer and evict the rare one-line
    // warning you actually needed. Interleaved repeats (ERROR/WARNING pairs) are covered.
    private const val DEDUP_LOOKBACK = 50
    // Errors and warnings are also appended to a bounded on-disk file so a post-mortem is
    // still possible after the process dies (the in-memory buffer does not survive that).
    private const val DIAGNOSTIC_FILE = "diagnostic.log"
    private const val DIAGNOSTIC_MAX_BYTES = 256 * 1024

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
     * Errors and warnings are the exception: they are rare, high-value for diagnosis, and
     * lost forever if dropped (e.g. a boot-time VPN start with the screen still off).
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
        // Cheap substring gate instead of a full JSON parse while the screen is off: only
        // payloads that can possibly be a warning/error are decoded.
        if (isScreenOff &&
            !payload.contains("warning", ignoreCase = true) &&
            !payload.contains("error", ignoreCase = true)
        ) {
            return
        }
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
        if (isScreenOff && normalizedLevel != "error" && normalizedLevel != "warning") return
        var appendedNewEntry = false
        synchronized(this) {
            val lookbackFrom = (logList.size - DEDUP_LOOKBACK).coerceAtLeast(0)
            var merged = false
            for (i in logList.indices.reversed()) {
                if (i < lookbackFrom) break
                val existing = logList[i]
                if (existing.level == normalizedLevel && existing.message == message) {
                    logList[i] = existing.copy(count = existing.count + 1)
                    merged = true
                    break
                }
            }
            if (!merged) {
                if (logList.size >= MAX_LOGS) {
                    logList.removeFirst()
                }
                logList.addLast(
                    LogEntry(
                        id = nextId.getAndIncrement(),
                        level = normalizedLevel,
                        message = message
                    )
                )
                appendedNewEntry = true
            }
        }
        publisher.request()
        if (!isScreenOff) {
            runCatching {
                DnsRepository.parseFromLog(message)
                RequestRepository.parseFromLog(message)
            }
        }
        if (appendedNewEntry && (normalizedLevel == "error" || normalizedLevel == "warning")) {
            persistDiagnostic(normalizedLevel, message)
        }
    }

    fun clear() {
        synchronized(this) { logList.clear() }
        publisher.publishNow()
    }

    /** Appends a diagnostic line to the bounded on-disk log; never throws. */
    @Synchronized
    private fun persistDiagnostic(level: String, message: String) {
        runCatching {
            val file = File(Global.application.filesDir, DIAGNOSTIC_FILE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(Date())
            file.appendText("[$stamp] [$level] $message\n")
            if (file.length() > DIAGNOSTIC_MAX_BYTES) {
                // Keep only the newer half, starting at a line boundary.
                val text = file.readText()
                val keepFrom = text.indexOf('\n', text.length / 2).let { if (it == -1) 0 else it + 1 }
                file.writeText(text.substring(keepFrom))
            }
        }
    }
}
