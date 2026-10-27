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
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured connection records polled from the core's external-controller (/connections).
 * This replaced the previous log-regex parsing: the API reports stable connection ids, real
 * byte counters, the full proxy chain and close events, so records can be updated in place,
 * per-connection close actually works, and a core log format change can no longer silently
 * empty the request view.
 */
object RequestRepository {
    private const val MAX_RECORDS = 500
    private const val PUBLISH_INTERVAL_MS = 400L
    private const val POLL_INTERVAL_MS = 1000L
    private const val BACKGROUND_POLL_INTERVAL_MS = 5000L

    /** Consecutive controller failures before the poll loop reports the problem. */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    private val nextId = AtomicLong(1)
    // O(1) lookup map preserving insertion order. Eldest entries are at the beginning.
    private val recordMap = LinkedHashMap<String, RequestRecord>(MAX_RECORDS)

    private val _recordsFlow = MutableStateFlow<List<RequestRecord>>(emptyList())
    val recordsFlow: StateFlow<List<RequestRecord>> = _recordsFlow.asStateFlow()

    /** Live connections from the last successful poll; drives the dashboard connections stat. */
    private val _activeCountFlow = MutableStateFlow(0)
    val activeCountFlow: StateFlow<Int> = _activeCountFlow.asStateFlow()

    private val publisher = ThrottledPublisher(PUBLISH_INTERVAL_MS, _recordsFlow.subscriptionCount) {
        val snapshot = synchronized(this) { recordMap.values.reversed() }
        _recordsFlow.value = snapshot
    }

    @Volatile
    private var pollJob: Job? = null

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
     * Starts the poll loop. [resolver] performs the Android connection-owner lookup
     * (getConnectionOwnerUid) for a connection's source/dest endpoints; it is invoked on IO.
     */
    fun startPolling(scope: CoroutineScope, context: Context, resolver: (Int, String, String) -> Int) {
        appContext = context.applicationContext
        uidResolver = resolver
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

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
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

        if (_activeCountFlow.value != liveIds.size) {
            _activeCountFlow.value = liveIds.size
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

    /** Asks the core to drop the connection behind [id]; the record is closed either way. */
    suspend fun closeConnection(id: Long): Boolean = withContext(Dispatchers.IO) {
        val connectionId = synchronized(this@RequestRepository) {
            recordMap.values.firstOrNull { it.id == id }?.connectionId
        }
        val requested = if (connectionId != null) MihomoApi.closeConnection(connectionId) else false
        markClosed(id)
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
