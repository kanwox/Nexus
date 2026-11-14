package com.github.mihomo.android.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.SpeedTestState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MAX_VALID_DELAY_MS = 65534

private fun probeStateFromDelay(delay: Int): SpeedTestState =
    if (delay in 1..MAX_VALID_DELAY_MS) {
        SpeedTestState.Success(delay)
    } else {
        SpeedTestState.Timeout
    }

private data class RuntimeSnapshotFrame(
    val version: Long,
    val delays: Map<String, SpeedTestState>,
    val testing: Set<String>
)

/**
 * Thread-safe backing store and coalesced main-thread snapshot publisher for [ProxiesViewModel]'s
 * delay cache, in-flight testing set, and live group map.
 */
internal class ProxiesRuntimeCacheStore(
    private val scope: CoroutineScope,
    initialScopeId: String,
    private val currentScopeEpoch: () -> Long,
    private val fallbackParsedGroup: (String) -> ProxyGroup?,
    private val fallbackCurrentGroup: (String) -> ProxyGroup?,
    private val onTestingChanged: (Set<String>) -> Unit,
    private val onLiveMapChanged: (Map<String, ProxyGroup>) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    val delayCache: SnapshotStateMap<String, SpeedTestState> = mutableStateMapOf()

    private val runtimeLock = Any()
    private val uiApplyLock = Any()
    private val delayCacheStore = ConcurrentHashMap<String, SpeedTestState>()
    private var activeScopeId: String = initialScopeId
    private val testingNodesStore = mutableSetOf<String>()
    private val runtimePublishVersion = AtomicLong(0L)
    private var lastAppliedRuntimeVersion = 0L
    private var unscheduledStateDirty = false

    private val liveMapLock = Any()
    private var liveGroupStore: Map<String, ProxyGroup> = emptyMap()
    private val liveMapPublishVersion = AtomicLong(0L)

    fun currentScopeId(): String = synchronized(runtimeLock) { activeScopeId }

    fun hasActiveTests(): Boolean = synchronized(runtimeLock) { testingNodesStore.isNotEmpty() }

    fun isTesting(key: String): Boolean = synchronized(runtimeLock) { testingNodesStore.contains(key) }

    fun invalidateDelayCache(newScope: String, force: Boolean = false) {
        val version = synchronized(runtimeLock) {
            if (newScope == activeScopeId && !force) return@synchronized null
            activeScopeId = newScope
            delayCacheStore.clear()
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun clearTestingNodes() {
        val version = synchronized(runtimeLock) {
            if (testingNodesStore.isEmpty()) return@synchronized null
            testingNodesStore.clear()
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun addTestingKey(key: String) {
        val version = synchronized(runtimeLock) {
            if (!testingNodesStore.add(key)) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun addTestingKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val version = synchronized(runtimeLock) {
            var changed = false
            for (key in keys) {
                if (testingNodesStore.add(key)) changed = true
            }
            if (!changed) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun removeTestingKey(key: String) {
        val version = synchronized(runtimeLock) {
            if (!testingNodesStore.remove(key)) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun removeTestingKeyInScope(scopeId: String, key: String) {
        val version = synchronized(runtimeLock) {
            if (activeScopeId != scopeId || !testingNodesStore.remove(key)) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun mutateTestingKeysLocked(transform: (Set<String>) -> Set<String>) {
        val version = synchronized(runtimeLock) {
            val next = transform(testingNodesStore.toSet())
            if (next == testingNodesStore) return@synchronized null
            testingNodesStore.clear()
            testingNodesStore.addAll(next)
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun recordDelayInScope(scopeId: String, key: String, delay: Int) {
        synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return
            delayCacheStore["p:$scopeId|$key"] = SpeedTestState.fromNativeDelay(delay)
            unscheduledStateDirty = true
        }
    }

    fun recordProbeInScope(scopeId: String, key: String, delay: Int) {
        synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return
            delayCacheStore["p:$scopeId|$key"] = probeStateFromDelay(delay)
            unscheduledStateDirty = true
        }
    }

    fun completeProbeInScope(scopeId: String, groupName: String, nodeName: String, delay: Int) {
        commitProbeStateLocked(scopeId, groupName, nodeName, probeStateFromDelay(delay))
    }

    fun failProbeInScope(scopeId: String, groupName: String, nodeName: String, reason: String? = null) {
        commitProbeStateLocked(scopeId, groupName, nodeName, SpeedTestState.Failed(reason))
    }

    fun completeGroupInScope(
        scopeId: String,
        groupName: String,
        groupDelay: Int,
        nodeNames: List<String>
    ) {
        val groupKey = "group:$groupName"
        val version = synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return@synchronized null
            delayCacheStore["p:$scopeId|$groupName"] = probeStateFromDelay(groupDelay)
            for (nodeName in nodeNames) {
                val scopedKey = "$groupName:$nodeName"
                if (testingNodesStore.remove(scopedKey)) {
                    delayCacheStore["p:$scopeId|$nodeName"] = SpeedTestState.Timeout
                    delayCacheStore["p:$scopeId|$scopedKey"] = SpeedTestState.Timeout
                }
            }
            testingNodesStore.remove(groupKey)
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun failGroupInScope(scopeId: String, groupName: String, reason: String? = null) {
        val groupKey = "group:$groupName"
        val groupPrefix = "$groupName:"
        val version = synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return@synchronized null
            val failedState = SpeedTestState.Failed(reason)
            val pendingKeys = testingNodesStore.filter { key -> key.startsWith(groupPrefix) }
            for (scopedKey in pendingKeys) {
                testingNodesStore.remove(scopedKey)
                val nodeName = scopedKey.removePrefix(groupPrefix)
                delayCacheStore["p:$scopeId|$nodeName"] = failedState
                delayCacheStore["p:$scopeId|$scopedKey"] = failedState
            }
            testingNodesStore.remove(groupKey)
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun finishGroupTestingInScope(scopeId: String, groupName: String, nodeNames: List<String>) {
        val groupKey = "group:$groupName"
        val groupPrefix = "$groupName:"
        val version = synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return@synchronized null
            var changed = testingNodesStore.remove(groupKey)
            for (nodeName in nodeNames) {
                if (testingNodesStore.remove("$groupName:$nodeName")) changed = true
            }
            val prefixedKeys = testingNodesStore.filter { key -> key.startsWith(groupPrefix) }
            for (scopedKey in prefixedKeys) {
                if (testingNodesStore.remove(scopedKey)) changed = true
            }
            if (!changed) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    fun readDelayInScope(scopeId: String, key: String): Int? =
        synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return@synchronized null
            (delayCacheStore["p:$scopeId|$key"] as? SpeedTestState.Success)?.delayMs
        }

    suspend fun publishDelayCacheSnapshotOnMain() {
        val version = synchronized(runtimeLock) {
            if (unscheduledStateDirty) {
                unscheduledStateDirty = false
                runtimePublishVersion.incrementAndGet()
            } else {
                runtimePublishVersion.get()
            }
        }
        if (version == 0L) return
        withContext(mainDispatcher) {
            applyRuntimeSnapshotOnMain(version, allowSkipIfSuperseded = false)
        }
    }

    fun readLiveGroupSnapshot(groupName: String): ProxyGroup? =
        synchronized(liveMapLock) { liveGroupStore[groupName] }

    fun clearLiveGroupMap() {
        synchronized(liveMapLock) { liveGroupStore = emptyMap() }
        val version = liveMapPublishVersion.incrementAndGet()
        scope.launch(mainDispatcher) {
            if (version < liveMapPublishVersion.get()) return@launch
            onLiveMapChanged(emptyMap())
        }
    }

    fun replaceLiveGroupMapOnMain(
        groupDataMap: Map<String, ProxyGroup>,
        startScopeEpoch: Long
    ): Map<String, ProxyGroup>? {
        val published = synchronized(liveMapLock) {
            if (currentScopeEpoch() != startScopeEpoch) return null
            liveGroupStore = groupDataMap
            groupDataMap
        }
        onLiveMapChanged(published)
        return published
    }

    fun applyLiveNodeSelection(group: String, nodeName: String, fallbackGroup: ProxyGroup?) {
        val nextLiveMap = synchronized(liveMapLock) {
            val currentLive = liveGroupStore[group] ?: fallbackGroup ?: return@synchronized null
            val updated = liveGroupStore + (group to currentLive.copy(now = nodeName))
            liveGroupStore = updated
            updated
        }
        if (nextLiveMap != null) onLiveMapChanged(nextLiveMap)
    }

    fun updateLiveGroupSelectedNode(groupName: String, now: String, expectedScopeEpoch: Long) {
        if (currentScopeEpoch() != expectedScopeEpoch) return
        val updated = synchronized(liveMapLock) {
            if (currentScopeEpoch() != expectedScopeEpoch) return
            val existing = liveGroupStore[groupName]
                ?: fallbackParsedGroup(groupName)
                ?: fallbackCurrentGroup(groupName)
            if (existing != null) {
                liveGroupStore = liveGroupStore + (groupName to existing.copy(now = now))
                true
            } else {
                false
            }
        }
        if (updated) schedulePublishLiveGroupMap(expectedScopeEpoch)
    }

    fun resolveGroupForNode(nodeName: String, parsedGroups: Map<String, ProxyGroup>): String? =
        synchronized(liveMapLock) {
            liveGroupStore.entries.firstOrNull { (_, grp) ->
                grp.proxies.any { proxy -> proxy.name == nodeName }
            }?.key
        } ?: parsedGroups.entries.firstOrNull { (_, grp) ->
            grp.proxies.any { proxy -> proxy.name == nodeName }
        }?.key

    private fun commitProbeStateLocked(
        scopeId: String,
        groupName: String,
        nodeName: String,
        state: SpeedTestState
    ) {
        val scopedKey = if (groupName.isNotBlank()) "$groupName:$nodeName" else nodeName
        val version = synchronized(runtimeLock) {
            if (activeScopeId != scopeId) return@synchronized null
            var changed = delayCacheStore.put("p:$scopeId|$nodeName", state) != state
            if (groupName.isNotBlank()) {
                if (delayCacheStore.put("p:$scopeId|$scopedKey", state) != state) changed = true
            }
            if (testingNodesStore.remove(scopedKey)) changed = true
            if (!changed) return@synchronized null
            unscheduledStateDirty = false
            runtimePublishVersion.incrementAndGet()
        }
        if (version != null) schedulePublishRuntimeState(version)
    }

    private fun schedulePublishRuntimeState(version: Long) {
        scope.launch(mainDispatcher) {
            applyRuntimeSnapshotOnMain(version, allowSkipIfSuperseded = true)
        }
    }

    private fun applyRuntimeSnapshotOnMain(
        targetVersion: Long,
        allowSkipIfSuperseded: Boolean
    ) {
        if (allowSkipIfSuperseded && targetVersion < runtimePublishVersion.get()) return
        synchronized(uiApplyLock) {
            if (allowSkipIfSuperseded && targetVersion < runtimePublishVersion.get()) return
            if (targetVersion <= lastAppliedRuntimeVersion) return
            val frame = captureRuntimeFrame(targetVersion, allowSkipIfSuperseded) ?: return
            lastAppliedRuntimeVersion = frame.version
            Snapshot.withMutableSnapshot {
                syncDelayCache(frame.delays)
                onTestingChanged(frame.testing)
            }
        }
    }

    private fun captureRuntimeFrame(
        targetVersion: Long,
        allowSkipIfSuperseded: Boolean
    ): RuntimeSnapshotFrame? = synchronized(runtimeLock) {
        val latest = runtimePublishVersion.get()
        if (allowSkipIfSuperseded && targetVersion < latest) return@synchronized null
        if (latest <= lastAppliedRuntimeVersion) return@synchronized null
        RuntimeSnapshotFrame(
            version = latest,
            delays = filterActiveDelaySnapshot(delayCacheStore, activeScopeId),
            testing = testingNodesStore.toSet()
        )
    }

    private fun syncDelayCache(snapshot: Map<String, SpeedTestState>) {
        if (snapshot.isEmpty()) {
            if (delayCache.isNotEmpty()) delayCache.clear()
            return
        }
        val staleKeys = delayCache.keys.filter { key -> !snapshot.containsKey(key) }
        for (key in staleKeys) {
            delayCache.remove(key)
        }
        for ((key, value) in snapshot) {
            if (delayCache[key] != value) {
                delayCache[key] = value
            }
        }
    }

    private fun schedulePublishLiveGroupMap(expectedScopeEpoch: Long) {
        val version = liveMapPublishVersion.incrementAndGet()
        scope.launch(mainDispatcher) {
            if (currentScopeEpoch() != expectedScopeEpoch) return@launch
            if (version < liveMapPublishVersion.get()) return@launch
            val latestSnapshot = synchronized(liveMapLock) {
                if (currentScopeEpoch() != expectedScopeEpoch) return@launch
                liveGroupStore
            }
            onLiveMapChanged(latestSnapshot)
        }
    }
}
