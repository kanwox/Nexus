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
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    internal const val DIAGNOSTIC_MAX_BYTES = 256 * 1024
    private const val DIAGNOSTIC_QUEUE_CAPACITY = 256

    // How often the diagnostic file is checked for the size cap. Rewriting the file is
    // O(size), so it must not happen on every single warning.
    internal const val TRIM_CHECK_INTERVAL = 64

    private val diagnosticTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Single-thread bounded background executor for on-disk diagnostic log persistence.
     * Decouples JNI / caller threads and in-memory [logLock] from file I/O and 256 KB trims.
     */
    private val diagnosticExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(DIAGNOSTIC_QUEUE_CAPACITY),
        ThreadFactory { runnable ->
            val thread = Thread(runnable, "mihomo-diagnostic-log")
            thread.isDaemon = true
            thread
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    /** The open append-only writer for the diagnostic file (accessed only on [diagnosticExecutor]). */
    private var diagnosticWriterInstance: Writer? = null

    /** Number of appends since the last size check (accessed only on [diagnosticExecutor]). */
    private var diagnosticAppends = 0

    @Volatile
    private var diagnosticFileOverride: File? = null

    private val logLock = Any()
    private val nextId = AtomicLong(1)
    private val logList = ArrayDeque<LogEntry>(MAX_LOGS)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _logsFlow.subscriptionCount) {
        val snapshot = synchronized(logLock) { logList.toList() }
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
        if (isScreenOff && !isWarningOrErrorPayload(payload)) {
            return
        }
        val obj: JsonObject? = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            null
        }
        if (obj == null) {
            addLog("info", payload)
            return
        }
        val level = (obj["level"] ?: obj["type"])?.jsonPrimitive?.content ?: "info"
        val msg = (obj["message"] ?: obj["payload"])?.jsonPrimitive?.content ?: payload
        addLog(level, msg)
    }

    private fun isWarningOrErrorPayload(payload: String): Boolean =
        payload.contains("warning", ignoreCase = true) || payload.contains("error", ignoreCase = true)

    fun addLog(level: String, message: String, forcePersist: Boolean = false) {
        val normalizedLevel = level.lowercase(Locale.ROOT)
        val shouldPersist = forcePersist || normalizedLevel == "error" || normalizedLevel == "warning"
        if (isScreenOff && !shouldPersist) return
        val nowMs = System.currentTimeMillis()
        val appendedNewEntry = synchronized(logLock) {
            appendOrMergeEntryLocked(normalizedLevel, message, nowMs)
        }
        publisher.request()
        if (!isScreenOff) {
            try {
                DnsRepository.parseFromLog(message)
            } catch (_: Exception) {
                // Ignore malformed log lines in DNS parser
            }
        }
        if (appendedNewEntry && shouldPersist) {
            enqueueDiagnostic(nowMs, normalizedLevel, message)
        }
    }

    private fun appendOrMergeEntryLocked(
        normalizedLevel: String,
        message: String,
        nowMs: Long
    ): Boolean {
        val lookbackFrom = (logList.size - DEDUP_LOOKBACK).coerceAtLeast(0)
        for (i in logList.indices.reversed()) {
            if (i < lookbackFrom) break
            val existing = logList[i]
            if (existing.level == normalizedLevel && existing.message == message) {
                logList[i] = existing.copy(count = existing.count + 1)
                return false
            }
        }
        if (logList.size >= MAX_LOGS) {
            logList.removeFirst()
        }
        logList.addLast(
            LogEntry(
                id = nextId.getAndIncrement(),
                timestamp = nowMs,
                level = normalizedLevel,
                message = message
            )
        )
        return true
    }

    fun clear() {
        synchronized(logLock) {
            logList.clear()
        }
        publisher.publishNow()
    }

    /**
     * Enqueues a diagnostic line to be asynchronously redacted and appended on [diagnosticExecutor];
     * never blocks the caller on disk I/O and never throws.
     */
    private fun enqueueDiagnostic(timestampMs: Long, level: String, message: String) {
        try {
            diagnosticExecutor.execute {
                writeDiagnosticOnWorker(timestampMs, level, message)
            }
        } catch (_: Exception) {
            // Bounded queue overflow or shutdown is non-fatal
        }
    }

    internal fun redactForDiagnostic(message: String): String =
        DiagnosticsExporter.redact(message)

    internal fun formatDiagnosticTimestamp(timestampMs: Long): String =
        diagnosticTimestampFormatter.format(
            Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
        )

    private fun writeDiagnosticOnWorker(timestampMs: Long, level: String, message: String) {
        try {
            val writer = diagnosticWriter()
            val stamp = formatDiagnosticTimestamp(timestampMs)
            val safeMessage = redactForDiagnostic(message)
            writer.write("[$stamp] [$level] $safeMessage\n")
            writer.flush()

            diagnosticAppends++
            if (diagnosticAppends >= TRIM_CHECK_INTERVAL) {
                diagnosticAppends = 0
                trimDiagnosticFileOnWorker()
            }
        } catch (_: Exception) {
            closeDiagnosticWriterQuietly()
        }
    }

    /** The lazily opened append-only writer for the diagnostic file (worker thread only). */
    private fun diagnosticWriter(): Writer {
        diagnosticWriterInstance?.let { return it }
        val file = resolveDiagnosticFile()
        file.parentFile?.mkdirs()
        val writer = OutputStreamWriter(
            FileOutputStream(file, true),
            Charsets.UTF_8
        )
        diagnosticWriterInstance = writer
        return writer
    }

    private fun resolveDiagnosticFile(): File =
        diagnosticFileOverride ?: File(Global.application.filesDir, DIAGNOSTIC_FILE)

    private fun closeDiagnosticWriterQuietly() {
        try {
            diagnosticWriterInstance?.flush()
        } catch (_: Exception) {
            // Ignore flush failure during close
        }
        try {
            diagnosticWriterInstance?.close()
        } catch (_: Exception) {
            // Ignore close failure
        }
        diagnosticWriterInstance = null
    }

    /**
     * Keeps only the newer half of the diagnostic file, starting at a line boundary.
     * Runs exclusively on [diagnosticExecutor].
     */
    private fun trimDiagnosticFileOnWorker() {
        try {
            closeDiagnosticWriterQuietly()
            val file = resolveDiagnosticFile()
            if (!file.exists() || file.length() <= DIAGNOSTIC_MAX_BYTES) return
            val text = file.readText(Charsets.UTF_8)
            val newlineIdx = text.indexOf('\n', text.length / 2)
            val keepFrom = if (newlineIdx == -1) 0 else newlineIdx + 1
            ConfigManager.writeTextAtomic(file, text.substring(keepFrom))
        } catch (_: Exception) {
            closeDiagnosticWriterQuietly()
        }
    }

    internal fun setDiagnosticFileForTest(file: File?) {
        try {
            diagnosticExecutor.submit {
                closeDiagnosticWriterQuietly()
                diagnosticAppends = 0
                diagnosticFileOverride = file
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            diagnosticFileOverride = file
        }
    }

    internal fun flushDiagnosticsForTest() {
        try {
            diagnosticExecutor.submit {}.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Ignore timeout in test flush
        }
    }

    internal fun submitDiagnosticTaskForTest(task: Runnable): java.util.concurrent.Future<*> =
        diagnosticExecutor.submit(task)
}
