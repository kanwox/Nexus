@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    internal const val MAX_RECORDS = 500
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

    private val recordsLock = Any()
    private val transportLock = Any()

    private val nextId = AtomicLong(1)

    // O(1) lookup map preserving insertion order. Guarded by [recordsLock].
    private val recordMap = LinkedHashMap<String, RequestRecord>(MAX_RECORDS)

    /**
     * Immutable snapshot atomically published whenever [recordMap] changes.
     * Read methods ([topHostsByTraffic], [closeConnection], [publisher]) read this
     * volatile reference without contending on [recordsLock].
     */
    @Volatile
    private var currentRecordsSnapshot: List<RequestRecord> = emptyList()

    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    /** Live connections from the last successful poll; drives the dashboard connections stat. */
    private val _activeCountFlow = MutableStateFlow(0)
    val activeCountFlow: StateFlow<Int> = _activeCountFlow.asStateFlow()

    /** Which transport currently feeds the snapshots ("websocket" or "polling"). */
    private val _transportModeFlow = MutableStateFlow(TRANSPORT_POLLING)
    val transportModeFlow: StateFlow<String> = _transportModeFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _recordsFlow.subscriptionCount) {
        _recordsFlow.value = currentRecordsSnapshot
    }

    @Volatile
    private var pollJob: Job? = null

    /** The coroutine running the WS supervisor loop; null when polling-only. */
    @Volatile
    private var streamJob: Job? = null

    /**
     * The live WebSocket handle. Guarded by [transportLock] for state transitions and
     * marked volatile for fast wait checks.
     */
    @Volatile
    private var activeSocket: okhttp3.WebSocket? = null

    @Volatile
    private var activeListener: ConnectionsSocketListener? = null

    /**
     * True from [stopPolling] until the next [startPolling]. This is the ONLY signal
     * that distinguishes "the user stopped the VPN" from "the socket died".
     */
    @Volatile
    private var streamStopped: Boolean = true

    /** Monotonic session generation to fence off stale supervisor/degrade callbacks. */
    @Volatile
    private var streamGeneration: Long = 0L

    /** Default failure counter used when [openWebSocket] is invoked directly in tests. */
    private val wsFailureCounter = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var uidResolver: ((Int, String, String) -> Int)? = null

    // Bounded LRU cache: uid -> (display label, package name); guarded by [uidCacheLock] outside [recordsLock].
    private val uidCacheLock = Any()
    private val uidLabelCache = object : LinkedHashMap<Int, Pair<String, String?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<String, String?>>?): Boolean =
            size > MAX_UID_CACHE_ENTRIES
    }

    internal var webSocketFactory: (okhttp3.WebSocketListener) -> okhttp3.WebSocket? = { listener ->
        MihomoApi.newConnectionsWebSocket(listener)
    }

    internal var connectionsFetcher: () -> MihomoApi.ConnectionsSnapshot? = {
        MihomoApi.connections()
    }

    internal var isUiInForegroundProvider: () -> Boolean = {
        com.github.mihomo.android.service.MihomoVpnService.isUiInForeground
    }

    internal var reconnectDelayMsOverride: Long? = null
    internal var pollIntervalOverrideMs: Long? = null

    /**
     * Starts connection tracking: WebSocket stream first, REST polling as the fallback.
     */
    fun startPolling(scope: CoroutineScope, context: Context, resolver: (Int, String, String) -> Int) {
        appContext = context.applicationContext
        uidResolver = resolver
        launchSupervisorLocked(scope)
    }

    internal fun startStreamingForTest(scope: CoroutineScope) {
        launchSupervisorLocked(scope)
    }

    private fun launchSupervisorLocked(scope: CoroutineScope) {
        synchronized(transportLock) {
            if (pollJob?.isActive == true || streamJob?.isActive == true) return
            streamStopped = false
            val generation = ++streamGeneration
            val sessionFailures = AtomicInteger(0)
            wsFailureCounter.set(0)
            streamJob = launchOnIo(scope) { runStreamSupervisor(scope, generation, sessionFailures) }
        }
    }

    fun stopPolling() {
        val streamToCancel: Job?
        val pollToCancel: Job?
        val socketToCancel: okhttp3.WebSocket?
        val listenerToClose: ConnectionsSocketListener?
        synchronized(transportLock) {
            streamStopped = true
            streamGeneration++
            streamToCancel = streamJob
            streamJob = null
            socketToCancel = activeSocket
            activeSocket = null
            listenerToClose = activeListener
            activeListener = null
            pollToCancel = pollJob
            pollJob = null
            _transportModeFlow.value = TRANSPORT_POLLING
            wsFailureCounter.set(0)
        }
        listenerToClose?.closedSignal?.complete(Unit)
        streamToCancel?.cancel()
        cancelSocketQuietly(socketToCancel)
        pollToCancel?.cancel()
    }

    private fun launchOnIo(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch(dispatcher, block = block)

    // ------------------------------------------------------------------
    // WebSocket-first transport with polling fallback
    // ------------------------------------------------------------------

    /**
     * Owns the WS lifecycle until it permanently degrades into the poll loop. Exposed
     * backoff computation stays pure via [wsBackoffMs] so retries are unit-testable.
     */
    private suspend fun runStreamSupervisor(
        scope: CoroutineScope,
        generation: Long,
        sessionFailures: AtomicInteger
    ) {
        var reconnectAttempt = 0
        while (currentCoroutineContext().isActive && isGenerationActive(generation)) {
            val opened = openWebSocket(generation, sessionFailures)
            if (!opened) {
                if (!isGenerationActive(generation)) return
                val failures = sessionFailures.incrementAndGet()
                if (failures >= MAX_WS_FAILURES || reconnectAttempt + 1 >= MAX_WS_RECONNECTS) {
                    degradeToPolling(scope, "websocket unavailable ($failures failures)", generation)
                    return
                }
                delay(reconnectDelayMsOverride ?: wsBackoffMs(reconnectAttempt))
                reconnectAttempt++
                continue
            }
            waitWhileSocketActive(generation)
            if (!currentCoroutineContext().isActive || !isGenerationActive(generation)) return
            val failures = sessionFailures.incrementAndGet()
            if (failures >= MAX_WS_FAILURES) {
                degradeToPolling(scope, "websocket failed $failures times", generation)
                return
            }
            delay(reconnectDelayMsOverride ?: wsBackoffMs(reconnectAttempt))
            reconnectAttempt++
        }
    }

    private fun isGenerationActive(generation: Long): Boolean =
        !streamStopped && streamGeneration == generation

    /**
     * Listener that tracks its own termination and socket identity so that:
     * 1) Callbacks firing BEFORE [webSocketFactory] returns cannot leave a dead socket in [activeSocket].
     * 2) Late callbacks from an old socket cannot null out or overwrite a newly opened [activeSocket].
     */
    private class ConnectionsSocketListener(
        val generation: Long,
        val failureCounter: AtomicInteger
    ) : okhttp3.WebSocketListener() {
        @Volatile
        var attachedSocket: okhttp3.WebSocket? = null

        @Volatile
        var terminated: Boolean = false

        val closedSignal: CompletableDeferred<Unit> = CompletableDeferred()

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            if (!isCurrentSocket(webSocket)) {
                cancelSocketQuietly(webSocket)
                return
            }
            val snapshot = parseSnapshotText(text)
            if (snapshot == null) {
                if (failureCounter.incrementAndGet() >= MAX_WS_FAILURES) {
                    markSocketTerminated(webSocket)
                    cancelSocketQuietly(webSocket)
                }
                return
            }
            failureCounter.set(0)
            applySnapshot(snapshot)
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            markSocketTerminated(webSocket)
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            markSocketTerminated(webSocket)
        }

        private fun isCurrentSocket(webSocket: okhttp3.WebSocket): Boolean =
            synchronized(transportLock) {
                if (!isGenerationActive(generation) || terminated) {
                    false
                } else {
                    attachedSocket == null || activeSocket === webSocket
                }
            }

        fun markSocketTerminated(webSocket: okhttp3.WebSocket?) {
            synchronized(transportLock) {
                terminated = true
                val current = activeSocket
                if (current != null && (current === webSocket || current === attachedSocket)) {
                    activeSocket = null
                }
                if (activeListener === this) {
                    activeListener = null
                }
            }
            closedSignal.complete(Unit)
        }
    }

    /** Opens one WS; returns false when the controller is not ready, stopped, or immediately failed. */
    internal fun openWebSocket(
        generation: Long = streamGeneration,
        failureCounter: AtomicInteger = wsFailureCounter
    ): Boolean {
        synchronized(transportLock) {
            if (!isGenerationActive(generation)) return false
        }
        val listener = ConnectionsSocketListener(generation, failureCounter)
        val socket = createSocketSafely(listener) ?: return false

        var socketToCancel: okhttp3.WebSocket? = null
        val activated = synchronized(transportLock) {
            listener.attachedSocket = socket
            if (!isGenerationActive(generation)) {
                socketToCancel = socket
                false
            } else if (listener.terminated) {
                if (activeSocket === socket) {
                    activeSocket = null
                }
                false
            } else {
                val previous = activeSocket
                val previousListener = activeListener
                if (previous != null && previous !== socket) {
                    socketToCancel = previous
                }
                if (previousListener != null && previousListener !== listener) {
                    previousListener.closedSignal.complete(Unit)
                }
                activeSocket = socket
                activeListener = listener
                _transportModeFlow.value = TRANSPORT_WEBSOCKET
                true
            }
        }
        cancelSocketQuietly(socketToCancel)
        return activated
    }

    private fun createSocketSafely(listener: okhttp3.WebSocketListener): okhttp3.WebSocket? =
        try {
            webSocketFactory(listener)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Suspends until the active socket is gone (failed/closed/cancelled) or streaming is stopped. */
    private suspend fun waitWhileSocketActive(generation: Long) {
        val signal = synchronized(transportLock) {
            if (activeSocket != null && isGenerationActive(generation)) {
                activeListener?.closedSignal
            } else {
                null
            }
        } ?: return
        signal.await()
    }

    internal fun currentGenerationForTest(): Long = streamGeneration

    internal fun degradeToPollingForTest(scope: CoroutineScope, reason: String, generation: Long) {
        degradeToPolling(scope, reason, generation)
    }

    /** Permanently switches to the REST poll loop (foreground/background/screen-off logic unchanged). */
    private fun degradeToPolling(scope: CoroutineScope, reason: String, generation: Long) {
        var oldSocket: okhttp3.WebSocket? = null
        var oldListener: ConnectionsSocketListener? = null
        var shouldLogDegrade = false
        synchronized(transportLock) {
            if (!isGenerationActive(generation)) return
            shouldLogDegrade = _transportModeFlow.value == TRANSPORT_WEBSOCKET
            _transportModeFlow.value = TRANSPORT_POLLING
            oldSocket = activeSocket
            activeSocket = null
            oldListener = activeListener
            activeListener = null
            startPollLoopLocked(scope, generation)
        }
        oldListener?.closedSignal?.complete(Unit)
        cancelSocketQuietly(oldSocket)
        if (shouldLogDegrade) {
            LogRepository.addLog("info", "连接流降级为轮询模式: $reason")
        }
    }

    /** Starts the historical poll loop under [transportLock], fenced by [generation]. */
    private fun startPollLoopLocked(scope: CoroutineScope, generation: Long) {
        if (!isGenerationActive(generation) || pollJob?.isActive == true) return
        pollJob = launchOnIo(scope) {
            runPollLoop(generation)
        }
    }

    private suspend fun runPollLoop(generation: Long) {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive && isGenerationActive(generation)) {
            val pollInterval = pollIntervalOverrideMs ?: resolvePollIntervalMs()
            delay(pollInterval)
            if (!isGenerationActive(generation) || LogRepository.isScreenOff) continue
            val snapshot = fetchConnectionsSafely(connectionsFetcher)
            if (!isGenerationActive(generation)) return
            if (snapshot == null) {
                consecutiveFailures = recordPollFailure(consecutiveFailures)
                continue
            }
            consecutiveFailures = 0
            applySnapshot(snapshot)
        }
    }

    private fun resolvePollIntervalMs(): Long =
        if (isUiInForegroundProvider()) POLL_INTERVAL_MS else BACKGROUND_POLL_INTERVAL_MS

    private fun recordPollFailure(currentFailures: Int): Int {
        val next = currentFailures + 1
        if (next == MAX_CONSECUTIVE_FAILURES) {
            // "warning" survives the screen-off gate, so a dead controller
            // stays visible even when the user has not opened the app.
            LogRepository.addLog(
                "warning",
                "连续 $MAX_CONSECUTIVE_FAILURES 次无法连接内核控制器，请求记录可能已停止更新"
            )
        }
        return next
    }

    /**
     * Exponential backoff for WS reconnects: 1s, 2s, 4s, ... capped at 30s. Kept as a
     * pure function of the attempt index for unit testing.
     */
    internal fun wsBackoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        return (BASE_WS_BACKOFF_MS shl shift).coerceAtMost(MAX_WS_BACKOFF_MS)
    }

    /**
     * Applies a connections snapshot from WebSocket or REST polling.
     *
     * App label / UID / PackageManager resolution is performed OUTSIDE [recordsLock] so that
     * Binder IPC or UID lookups never block [recordsLock] or UI readers ([topHostsByTraffic]).
     */
    internal fun applySnapshot(snapshot: MihomoApi.ConnectionsSnapshot) {
        val resolvedLabels = resolveMissingLabelsOutsideLock(snapshot.connections)
        val now = System.currentTimeMillis()
        var changed = false
        var activeCount = 0

        synchronized(recordsLock) {
            val liveIds = HashSet<String>(snapshot.connections.size)
            for (conn in snapshot.connections) {
                liveIds.add(conn.id)
            }
            // Mark disappeared connections as closed FIRST so that if MAX_RECORDS is full,
            // newly closed connections are immediately eligible for eviction before active ones.
            if (closeMissingRecordsLocked(recordMap, liveIds, now)) {
                changed = true
            }
            for (conn in snapshot.connections) {
                val insertedOrUpdated = upsertRecordLocked(
                    recordMap = recordMap,
                    nextId = nextId,
                    conn = conn,
                    resolvedLabels = resolvedLabels,
                    now = now
                )
                if (insertedOrUpdated) {
                    changed = true
                }
            }
            activeCount = recordMap.values.count { it.isActive }
            if (changed) {
                currentRecordsSnapshot = recordMap.values.reversed()
            }
        }

        if (_activeCountFlow.value != activeCount) {
            _activeCountFlow.value = activeCount
        }
        if (changed) {
            publisher.request()
        }
    }

    private fun resolveMissingLabelsOutsideLock(
        connections: List<MihomoApi.ApiConnection>
    ): Map<String, Pair<String, String?>> {
        if (connections.isEmpty()) return emptyMap()
        val unknownConns = synchronized(recordsLock) {
            connections.filter { !recordMap.containsKey(it.id) }
        }
        if (unknownConns.isEmpty()) return emptyMap()
        val resolved = HashMap<String, Pair<String, String?>>(unknownConns.size)
        for (conn in unknownConns) {
            resolved[conn.id] = resolveAppLabel(conn, uidResolver, appContext, uidCacheLock, uidLabelCache)
        }
        return resolved
    }

    /** Asks the core to drop the connection behind [id]; only marks closed when the core confirms. */
    suspend fun closeConnection(id: Long): Boolean = closeConnectionOnIo(id)

    private suspend fun closeConnectionOnIo(
        id: Long,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        val connectionId = currentRecordsSnapshot.firstOrNull { it.id == id }?.connectionId
        val requested = if (connectionId != null) MihomoApi.closeConnection(connectionId) else false
        if (requested) {
            markClosed(id)
        }
        requested
    }

    private fun markClosed(id: Long) {
        var changed = false
        var activeCount = 0
        synchronized(recordsLock) {
            val record = recordMap.values.firstOrNull { it.id == id }
            val connId = record?.connectionId
            if (record != null && connId != null && record.isActive) {
                val closedAt = record.lastSeenMs.takeIf { it > 0 } ?: System.currentTimeMillis()
                recordMap[connId] = record.copy(
                    isActive = false,
                    durationMs = (closedAt - record.timestamp).coerceAtLeast(0L)
                )
                currentRecordsSnapshot = recordMap.values.reversed()
                activeCount = recordMap.values.count { it.isActive }
                changed = true
            }
        }
        if (changed) {
            _activeCountFlow.value = activeCount
            publisher.request()
        }
    }

    fun clearClosed() {
        synchronized(recordsLock) {
            recordMap.entries.removeAll { !it.value.isActive }
            currentRecordsSnapshot = recordMap.values.reversed()
        }
        publisher.publishNow()
    }

    fun clearAll() {
        synchronized(recordsLock) {
            recordMap.clear()
            currentRecordsSnapshot = emptyList()
        }
        _activeCountFlow.value = 0
        publisher.publishNow()
    }

    /**
     * Aggregated per-host totals over the currently tracked records (for the ranking card).
     * Lock-free: reads the volatile immutable [currentRecordsSnapshot] so UI calls never block.
     */
    fun topHostsByTraffic(limit: Int = 5): List<Triple<String, Long, Long>> =
        computeTopHosts(currentRecordsSnapshot, limit)

    internal fun setUidResolverForTest(resolver: ((Int, String, String) -> Int)?) {
        uidResolver = resolver
        synchronized(uidCacheLock) { uidLabelCache.clear() }
    }

    internal fun resetTransportForTest() {
        stopPolling()
        webSocketFactory = { listener -> MihomoApi.newConnectionsWebSocket(listener) }
        connectionsFetcher = { MihomoApi.connections() }
        isUiInForegroundProvider = {
            com.github.mihomo.android.service.MihomoVpnService.isUiInForeground
        }
        reconnectDelayMsOverride = null
        pollIntervalOverrideMs = null
        uidResolver = null
        synchronized(uidCacheLock) { uidLabelCache.clear() }
    }
}

private const val MAX_UID_CACHE_ENTRIES = 500
private const val LAST_SEEN_REFRESH_MS = 5000L
private const val FALLBACK_IPPROTO_TCP = 6
private const val FALLBACK_IPPROTO_UDP = 17

private fun cancelSocketQuietly(socket: okhttp3.WebSocket?) {
    if (socket == null) return
    try {
        socket.cancel()
    } catch (_: Exception) {
        // Ignore cancellation errors on closing socket
    }
}

private fun parseSnapshotText(text: String): MihomoApi.ConnectionsSnapshot? =
    try {
        MihomoApi.parseConnections(Json.parseToJsonElement(text).jsonObject)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private fun fetchConnectionsSafely(
    fetcher: () -> MihomoApi.ConnectionsSnapshot?
): MihomoApi.ConnectionsSnapshot? =
    try {
        fetcher()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private fun resolveProtocol(network: String): Int =
    if (network.equals("UDP", ignoreCase = true)) {
        android.system.OsConstants.IPPROTO_UDP.takeIf { it != 0 } ?: FALLBACK_IPPROTO_UDP
    } else {
        android.system.OsConstants.IPPROTO_TCP.takeIf { it != 0 } ?: FALLBACK_IPPROTO_TCP
    }

private fun queryPackageLabelForUid(context: Context?, uid: Int): Pair<String, String?> {
    if (context == null) return "UID $uid" to null
    val pm = context.packageManager
    val pkg = try {
        pm.getPackagesForUid(uid)?.firstOrNull()
    } catch (_: Exception) {
        null
    }
    val label = pkg?.let { name ->
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
        } catch (_: Exception) {
            null
        }
    } ?: "UID $uid"
    return label to pkg
}

private fun resolveAppLabel(
    conn: MihomoApi.ApiConnection,
    uidResolver: ((Int, String, String) -> Int)?,
    appContext: Context?,
    uidCacheLock: Any,
    uidLabelCache: LinkedHashMap<Int, Pair<String, String?>>
): Pair<String, String?> {
    val resolver = uidResolver ?: return "" to null
    val proto = resolveProtocol(conn.network)
    val uid = try {
        resolver(
            proto,
            "${conn.sourceIp}:${conn.sourcePort}",
            "${conn.destinationIp.ifBlank { conn.host }}:${conn.destinationPort}"
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        0
    }
    if (uid <= 0) return "" to null
    synchronized(uidCacheLock) {
        uidLabelCache[uid]?.let { return it }
    }
    val pair = queryPackageLabelForUid(appContext, uid)
    synchronized(uidCacheLock) {
        uidLabelCache[uid] = pair
    }
    return pair
}

private fun upsertRecordLocked(
    recordMap: LinkedHashMap<String, RequestRecord>,
    nextId: AtomicLong,
    conn: MihomoApi.ApiConnection,
    resolvedLabels: Map<String, Pair<String, String?>>,
    now: Long
): Boolean {
    val existing = recordMap[conn.id]
    if (existing == null) {
        if (recordMap.size >= RequestRepository.MAX_RECORDS) {
            evictOneRecordLocked(recordMap)
        }
        val labelPkg = resolvedLabels[conn.id] ?: ("" to null)
        recordMap[conn.id] = buildNewRequestRecord(nextId.getAndIncrement(), conn, labelPkg, now)
        return true
    }
    val active = isActiveConnection(conn)
    if (hasRecordChanged(existing, conn, active)) {
        recordMap[conn.id] = existing.copy(
            uploadBytes = conn.uploadBytes,
            downloadBytes = conn.downloadBytes,
            isActive = active,
            lastSeenMs = now
        )
        return true
    }
    if (now - existing.lastSeenMs > LAST_SEEN_REFRESH_MS) {
        recordMap[conn.id] = existing.copy(lastSeenMs = now)
    }
    return false
}

private fun closeMissingRecordsLocked(
    recordMap: LinkedHashMap<String, RequestRecord>,
    liveIds: Set<String>,
    now: Long
): Boolean {
    var changed = false
    for ((connId, record) in recordMap) {
        if (record.isActive && connId !in liveIds) {
            val closedAt = record.lastSeenMs.takeIf { it > 0 } ?: now
            recordMap[connId] = record.copy(
                isActive = false,
                durationMs = (closedAt - record.timestamp).coerceAtLeast(0L),
                lastSeenMs = closedAt
            )
            changed = true
        }
    }
    return changed
}

/**
 * Evicts one entry when [recordMap] reaches [RequestRepository.MAX_RECORDS]:
 * 1. Prefer closed/inactive records (`!isActive`), evicting the one least recently active (`minBy(lastSeenMs)`).
 * 2. Only when all entries are active, evict the least recently active one (`minBy(lastSeenMs)`).
 */
private fun evictOneRecordLocked(recordMap: LinkedHashMap<String, RequestRecord>) {
    val victimKey = recordMap.entries
        .asSequence()
        .filter { !it.value.isActive }
        .minByOrNull { it.value.lastSeenMs }
        ?.key
        ?: recordMap.entries.minByOrNull { it.value.lastSeenMs }?.key
        ?: recordMap.keys.firstOrNull()
    if (victimKey != null) {
        recordMap.remove(victimKey)
    }
}

private fun buildNewRequestRecord(
    id: Long,
    conn: MihomoApi.ApiConnection,
    labelPkg: Pair<String, String?>,
    now: Long
): RequestRecord = RequestRecord(
    id = id,
    connectionId = conn.id,
    host = conn.host,
    destinationIp = conn.destinationIp.ifBlank { conn.host },
    destinationPort = conn.destinationPort,
    network = conn.network,
    sourceApp = labelPkg.first,
    packageName = labelPkg.second,
    rule = conn.rule.ifBlank { "Match" },
    rulePayload = conn.rulePayload,
    outbound = conn.chains.firstOrNull()?.uppercase(Locale.ROOT).orEmpty().ifBlank { "DIRECT" },
    chain = conn.chains.asReversed(),
    uploadBytes = conn.uploadBytes,
    downloadBytes = conn.downloadBytes,
    isActive = isActiveConnection(conn),
    timestamp = if (conn.startMs > 0) conn.startMs else now,
    lastSeenMs = now
)

private fun hasRecordChanged(
    existing: RequestRecord,
    conn: MihomoApi.ApiConnection,
    active: Boolean
): Boolean =
    existing.uploadBytes != conn.uploadBytes ||
        existing.downloadBytes != conn.downloadBytes ||
        existing.isActive != active

private fun MihomoApi.ApiConnection.outboundEqualsReject(): Boolean =
    chains.firstOrNull()?.equals("REJECT", ignoreCase = true) == true

private fun isActiveConnection(conn: MihomoApi.ApiConnection): Boolean =
    !conn.rule.equals("Reject", ignoreCase = true) && !conn.outboundEqualsReject()

private fun computeTopHosts(
    records: List<RequestRecord>,
    limit: Int
): List<Triple<String, Long, Long>> =
    records.asSequence()
        .filter { it.host.isNotBlank() }
        .groupBy { it.host.lowercase(Locale.ROOT) }
        .map { (host, grouped) ->
            Triple(
                host,
                grouped.sumOf { it.uploadBytes },
                grouped.sumOf { it.downloadBytes }
            )
        }
        .sortedByDescending { it.second + it.third }
        .take(limit)
        .toList()
