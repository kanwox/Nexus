package com.github.mihomo.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured connection records streamed from the core's external-controller (/connections).
 * This replaced the previous log-regex parsing: the API reports stable connection ids, real
 * byte counters, the full proxy chain and close events, so records can be updated in place,
 * per-connection close actually works, and a core log format change can no longer silently
 * empty the request view.
 *
 * Transport: the same /connections endpoint is FIRST consumed as a WebSocket stream (a
 * full snapshot pushed by the core about once per second — no polling request churn and
 * no 1s staleness). When the stream cannot be established or degrades repeatedly, the
 * repository falls back to the historical REST poll loop (foreground/background interval
 * plus screen-off gating preserved). [transportModeFlow] exposes which path is live for
 * diagnostics. The public API (recordsFlow, activeCountFlow, applySnapshot) is unchanged.
 */
object RequestRepository {
    private const val MAX_RECORDS = 500
    private const val PUBLISH_INTERVAL_MS = 400L
    private const val POLL_INTERVAL_MS = 1000L
    private const val BACKGROUND_POLL_INTERVAL_MS = 5000L

    /** Transport identifiers published through [transportModeFlow]. */
    const val TRANSPORT_WEBSOCKET = "websocket"
    const val TRANSPORT_POLLING = "polling"

    /** Consecutive controller failures before the poll loop reports the problem. */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    /** Consecutive WS-side failures (open failure / abnormal close / parse errors). */
    private const val MAX_WS_FAILURES = 3

    /** WS reconnect attempts before permanently falling back to REST polling. */
    private const val MAX_WS_RECONNECTS = 3

    /** First reconnect delay; doubles on every retry, capped at [MAX_WS_BACKOFF_MS]. */
    private const val BASE_WS_BACKOFF_MS = 1000L
    private const val MAX_WS_BACKOFF_MS = 30_000L

    private val nextId = AtomicLong(1)
    // O(1) lookup map preserving insertion order. Eldest entries are at the beginning.
    private val recordMap = LinkedHashMap<String, RequestRecord>(MAX_RECORDS)

    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    /** Live connections from the last successful poll; drives the dashboard connections stat. */
    private val _activeCountFlow = MutableStateFlow(0)
    val activeCountFlow: StateFlow<Int> = _activeCountFlow.asStateFlow()

    /** Which transport currently feeds the snapshots ("websocket" or "polling"). */
    private val _transportModeFlow = MutableStateFlow(TRANSPORT_POLLING)
    val transportModeFlow: StateFlow<String> = _transportModeFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _recordsFlow.subscriptionCount) {
        val snapshot = synchronized(this) { recordMap.values.reversed() }
        _recordsFlow.value = snapshot
    }

    @Volatile
    private var pollJob: Job? = null

    /** The coroutine running the WS supervisor loop; null when polling-only. */
    @Volatile
    private var streamJob: Job? = null

    /**
     * The live WebSocket handle. `newConnectionsWebSocket` runs on the stream-loop
     * coroutine, and okhttp delivers callbacks on its own dispatcher threads, so the
     * reference is volatile; `stopPolling` may cancel it from any thread.
     */
    @Volatile
    private var activeSocket: okhttp3.WebSocket? = null

    /**
     * True from [stopPolling] until the next [startPolling]. This is the ONLY signal
     * that distinguishes "the user stopped the VPN" from "the socket died": the socket
     * death itself is always observable as activeSocket becoming null (the listener
     * callbacks null it), so the supervisor must not infer a stop from that.
     */
    @Volatile
    private var streamStopped: Boolean = true

    /** Counts consecutive WS failures (open errors, abnormal closes, parse errors). */
    private val wsFailureCounter = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var uidResolver: ((Int, String, String) -> Int)? = null

    /**
     * Consecutive controller failures. `MihomoApi.connections()` returns null on ANY
     * failure, and a plain `?: continue` would swallow a dead controller forever without
     * a trace. After [MAX_CONSECUTIVE_FAILURES] the poll loop says so.
     */
    private var consecutiveFailures = 0

    // uid -> (display label, package name); a long session with many apps must not hammer
    // PackageManager on every new connection.
    private val uidLabelCache = HashMap<Int, Pair<String, String?>>()

    /**
     * Starts connection tracking: WebSocket stream first, REST polling as the fallback.
     *
     * The supervisor loop launches WS attempts with exponential backoff; after
     * [MAX_WS_RECONNECTS] failed attempts it gives up on the stream, starts the poll
     * loop, and stays there — the controller port/secret never change within a process,
     * so a transport that failed 3 times in a row is very unlikely to succeed by
     * hammering it, while the poll loop still delivers correct (only coarser-grained)
     * data. `startPolling` remains as the entry point name so the VPN-service call site
     * does not change; it now means "start the streaming supervisor".
     */
    fun startPolling(scope: CoroutineScope, context: Context, resolver: (Int, String, String) -> Int) {
        appContext = context.applicationContext
        uidResolver = resolver
        if (pollJob?.isActive == true || streamJob?.isActive == true) return
        streamStopped = false
        wsFailureCounter.set(0)
        streamJob = scope.launch(Dispatchers.IO) { runStreamSupervisor(scope) }
        // The poll loop is created LAZILY by the supervisor on permanent degradation, so
        // both transports never run at once.
    }

    fun stopPolling() {
        streamStopped = true
        streamJob?.cancel()
        streamJob = null
        runCatching { activeSocket?.cancel() }
        activeSocket = null
        pollJob?.cancel()
        pollJob = null
        _transportModeFlow.value = TRANSPORT_POLLING
        wsFailureCounter.set(0)
    }

    // ------------------------------------------------------------------
    // WebSocket-first transport with polling fallback
    // ------------------------------------------------------------------

    /**
     * Owns the WS lifecycle until it permanently degrades into the poll loop. Exposed
     * backoff computation stays pure via [wsBackoffMs] so retries are unit-testable.
     */
    private suspend fun runStreamSupervisor(scope: CoroutineScope) {
        var reconnectAttempt = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !streamStopped) {
            val opened = openWebSocket()
            if (!opened) {
                wsFailureCounter.incrementAndGet()
                if (wsFailureCounter.get() >= MAX_WS_FAILURES || reconnectAttempt + 1 >= MAX_WS_RECONNECTS) {
                    degradeToPolling(scope, "websocket unavailable (${wsFailureCounter.get()} failures)")
                    return
                }
                delay(wsBackoffMs(reconnectAttempt))
                reconnectAttempt++
                continue
            }
            // The socket is up; callbacks drive everything now. Wait until the socket
            // dies (cancel/failure/close) before deciding whether to retry.
            waitWhileSocketActive()
            if (!kotlinx.coroutines.currentCoroutineContext().isActive || streamStopped) return
            // Socket death: the listener nulled activeSocket. Retry with backoff unless
            // the failure budget is exhausted.
            wsFailureCounter.incrementAndGet()
            if (wsFailureCounter.get() >= MAX_WS_FAILURES) {
                degradeToPolling(scope, "websocket failed ${wsFailureCounter.get()} times")
                return
            }
            delay(wsBackoffMs(reconnectAttempt))
            reconnectAttempt++
        }
    }

    /** Opens one WS; returns false when the controller is not ready or the call threw. */
    private fun openWebSocket(): Boolean {
        val listener = object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val snapshot = runCatching {
                    MihomoApi.parseConnections(
                        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                    )
                }.getOrNull()
                if (snapshot == null) {
                    // A malformed frame is a transport-quality problem too: after
                    // MAX_WS_FAILURES of them the stream is treated as broken.
                    if (wsFailureCounter.incrementAndGet() >= MAX_WS_FAILURES) {
                        runCatching { webSocket.cancel() }
                        runCatching { activeSocket = null }
                    }
                    return
                }
                wsFailureCounter.set(0)
                applySnapshot(snapshot)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                // A clean server-side close still ends the stream: nulling the handle
                // wakes the supervisor, which retries or degrades. stopPolling() sets
                // streamStopped first, so a clean shutdown short-circuits there.
                runCatching { activeSocket = null }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                runCatching { activeSocket = null }
            }
        }
        val socket = MihomoApi.newConnectionsWebSocket(listener) ?: return false
        activeSocket = socket
        _transportModeFlow.value = TRANSPORT_WEBSOCKET
        return true
    }

    /** Suspends until the active socket is gone (failed/closed/cancelled) or streaming is stopped. */
    private suspend fun waitWhileSocketActive() {
        while (activeSocket != null &&
            !streamStopped &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            delay(200)
        }
    }

    /** Permanently switches to the REST poll loop (foreground/background/screen-off logic unchanged). */
    private fun degradeToPolling(scope: CoroutineScope, reason: String) {
        if (_transportModeFlow.value == TRANSPORT_WEBSOCKET) {
            LogRepository.addLog("info", "连接流降级为轮询模式: $reason")
        }
        _transportModeFlow.value = TRANSPORT_POLLING
        runCatching { activeSocket?.cancel() }
        activeSocket = null
        startPollLoop(scope)
    }

    /** The historical poll loop, byte-for-byte the old behavior. */
    private fun startPollLoop(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pollInterval = if (com.github.mihomo.android.service.MihomoVpnService.isUiInForeground) {
                    POLL_INTERVAL_MS
                } else {
                    BACKGROUND_POLL_INTERVAL_MS
                }
                delay(pollInterval)
                // Screen-off pauses everything non-essential (same gate as LogRepository).
                if (LogRepository.isScreenOff) continue
                val snapshot = MihomoApi.connections()
                if (snapshot == null) {
                    consecutiveFailures++
                    if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
                        // "warning" survives the screen-off gate, so a dead controller
                        // stays visible even when the user has not opened the app.
                        LogRepository.addLog(
                            "warning",
                            "连续 $MAX_CONSECUTIVE_FAILURES 次无法连接内核控制器，请求记录可能已停止更新"
                        )
                    }
                    continue
                }
                consecutiveFailures = 0
                applySnapshot(snapshot)
            }
        }
    }

    /**
     * Exponential backoff for WS reconnects: 1s, 2s, 4s, ... capped at 30s. Kept as a
     * pure function of the attempt index for unit testing.
     */
    internal fun wsBackoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        return (BASE_WS_BACKOFF_MS shl shift).coerceAtMost(MAX_WS_BACKOFF_MS)
    }

    @Synchronized
    internal fun applySnapshot(snapshot: MihomoApi.ConnectionsSnapshot) {
        val now = System.currentTimeMillis()
        val liveIds = HashSet<String>(snapshot.connections.size)
        var changed = false

        for (conn in snapshot.connections) {
            liveIds.add(conn.id)
            val existing = recordMap[conn.id]
            if (existing == null) {
                if (recordMap.size >= MAX_RECORDS) {
                    val eldestKey = recordMap.keys.first()
                    recordMap.remove(eldestKey)
                }
                val (label, pkg) = resolveAppLabel(conn)
                recordMap[conn.id] = RequestRecord(
                    id = nextId.getAndIncrement(),
                    connectionId = conn.id,
                    host = conn.host,
                    destinationIp = conn.destinationIp.ifBlank { conn.host },
                    destinationPort = conn.destinationPort,
                    network = conn.network,
                    sourceApp = label,
                    packageName = pkg,
                    rule = conn.rule.ifBlank { "Match" },
                    rulePayload = conn.rulePayload,
                    outbound = conn.chains.firstOrNull()?.uppercase().orEmpty().ifBlank { "DIRECT" },
                    chain = conn.chains.asReversed(),
                    uploadBytes = conn.uploadBytes,
                    downloadBytes = conn.downloadBytes,
                    isActive = isActiveConnection(conn),
                    timestamp = if (conn.startMs > 0) conn.startMs else now,
                    lastSeenMs = now
                )
                changed = true
            } else {
                val active = isActiveConnection(conn)
                if (existing.uploadBytes != conn.uploadBytes || existing.downloadBytes != conn.downloadBytes || existing.isActive != active) {
                    recordMap[conn.id] = existing.copy(
                        uploadBytes = conn.uploadBytes,
                        downloadBytes = conn.downloadBytes,
                        // The SAME rule as on the insert path: a rejected connection must
                        // not flip back to "active". The old code set it to true
                        // unconditionally, so a REJECT rule re-activated the record on
                        // the next poll, and the `!existing.isActive` trigger re-opened
                        // records that had just been closed.
                        isActive = active,
                        lastSeenMs = now
                    )
                    changed = true
                } else if (now - existing.lastSeenMs > 5000L) {
                    // Keep lastSeen fresh without triggering a publish; a stale lastSeen would
                    // inflate durations when the poll loop resumes after a long pause.
                    recordMap[conn.id] = existing.copy(lastSeenMs = now)
                }
            }
        }

        for ((connId, record) in recordMap) {
            if (record.isActive && connId !in liveIds) {
                val closedAt = record.lastSeenMs.takeIf { it > 0 } ?: now
                recordMap[connId] = record.copy(
                    isActive = false,
                    durationMs = (closedAt - record.timestamp).coerceAtLeast(0L),
                    lastSeenMs = now
                )
                changed = true
            }
        }

        val activeCount = recordMap.values.count { it.isActive }
        if (_activeCountFlow.value != activeCount) {
            _activeCountFlow.value = activeCount
        }
        if (changed) {
            publisher.request()
        }
    }

    private fun MihomoApi.ApiConnection.outboundEqualsReject(): Boolean =
        chains.firstOrNull()?.equals("REJECT", ignoreCase = true) == true

    /**
     * Whether a connection counts as "active" in the request list.
     *
     * A connection that the core rejected (a REJECT rule, or a REJECT outbound) is never
     * active — it is blocked, not routed. This single definition is used by BOTH the
     * insert and the update path: the update path used to force `isActive = true`, so a
     * rejected connection flipped back to "active" on the next poll.
     */
    private fun isActiveConnection(conn: MihomoApi.ApiConnection): Boolean =
        !conn.rule.equals("Reject", ignoreCase = true) && !conn.outboundEqualsReject()

    private fun resolveAppLabel(conn: MihomoApi.ApiConnection): Pair<String, String?> {
        val resolver = uidResolver ?: return "" to null
        val uid = runCatching {
            resolver(
                if (conn.network == "UDP") android.system.OsConstants.IPPROTO_UDP
                else android.system.OsConstants.IPPROTO_TCP,
                "${conn.sourceIp}:${conn.sourcePort}",
                "${conn.destinationIp.ifBlank { conn.host }}:${conn.destinationPort}"
            )
        }.getOrDefault(0)
        if (uid <= 0) return "" to null
        synchronized(uidLabelCache) {
            uidLabelCache[uid]?.let { return it }
        }
        val context = appContext
        val pkg = context?.packageManager?.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let { name ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(name, 0)
                ).toString()
            }.getOrNull()
        } ?: "UID $uid"
        val pair = label to pkg
        synchronized(uidLabelCache) {
            if (uidLabelCache.size > 500) uidLabelCache.clear()
            uidLabelCache[uid] = pair
        }
        return pair
    }

    /** Asks the core to drop the connection behind [id]; only marks closed when the core confirms. */
    suspend fun closeConnection(id: Long): Boolean = withContext(Dispatchers.IO) {
        val connectionId = synchronized(this@RequestRepository) {
            recordMap.values.firstOrNull { it.id == id }?.connectionId
        }
        val requested = if (connectionId != null) MihomoApi.closeConnection(connectionId) else false
        if (requested) {
            markClosed(id)
        }
        requested
    }

    @Synchronized
    private fun markClosed(id: Long) {
        val record = recordMap.values.firstOrNull { it.id == id }
        if (record != null && record.connectionId != null && record.isActive) {
            val closedAt = record.lastSeenMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            recordMap[record.connectionId] = record.copy(
                isActive = false,
                durationMs = (closedAt - record.timestamp).coerceAtLeast(0L)
            )
            _activeCountFlow.value = recordMap.values.count { it.isActive }
            publisher.request()
        }
    }

    @Synchronized
    fun clearClosed() {
        recordMap.entries.removeAll { !it.value.isActive }
        publisher.publishNow()
    }

    @Synchronized
    fun clearAll() {
        recordMap.clear()
        _activeCountFlow.value = 0
        publisher.publishNow()
    }

    /** Aggregated per-host totals over the currently tracked records (for the ranking card). */
    @Synchronized
    fun topHostsByTraffic(limit: Int = 5): List<Triple<String, Long, Long>> {
        return recordMap.values.asSequence()
            .filter { it.host.isNotBlank() }
            .groupBy { it.host.lowercase() }
            .map { (host, records) ->
                Triple(
                    host,
                    records.sumOf { it.uploadBytes },
                    records.sumOf { it.downloadBytes }
                )
            }
            .sortedByDescending { it.second + it.third }
            .take(limit)
    }
}
