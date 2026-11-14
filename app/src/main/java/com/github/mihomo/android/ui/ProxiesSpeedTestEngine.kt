@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.app.Application
import android.util.Log
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ParsedProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val ENGINE_TAG = "ProxiesSpeedTestEngine"
private const val AUTO_TEST_COOLDOWN_MS = 120_000L
private const val VALID_DELAY_MAX = 65534

internal interface SpeedTestHost {
    fun appContext(): Application
    fun currentScopeId(): String
    fun currentScopeEpoch(): Long
    fun proxyGroupsSnapshot(): List<String>
    fun parsedProfileSnapshot(): ParsedProfile
    fun readLiveGroup(groupName: String): ProxyGroup?
    fun fallbackGroupData(groupName: String): ProxyGroup?
    fun readDelayForScope(scopeId: String, key: String): Int?
    fun recordDelayForScope(scopeId: String, key: String, delay: Int)
    fun recordProbeResultForScope(scopeId: String, key: String, delay: Int)
    fun completeProbeForScope(scopeId: String, groupName: String, nodeName: String, delay: Int)
    fun failProbeForScope(scopeId: String, groupName: String, nodeName: String, reason: String? = null)
    fun completeGroupForScope(scopeId: String, groupName: String, groupDelay: Int, nodeNames: List<String>)
    fun failGroupForScope(scopeId: String, groupName: String, reason: String? = null)
    fun finishGroupTestingForScope(scopeId: String, groupName: String, nodeNames: List<String>)
    fun isTesting(key: String): Boolean
    fun addTesting(key: String)
    fun addTestingKeys(keys: Collection<String>)
    fun removeTestingForScope(scopeId: String, key: String)
    fun findGroupContainingNode(nodeName: String): String?
    fun updateLiveGroupNow(groupName: String, now: String, expectedScopeEpoch: Long)
    suspend fun publishDelayCacheOnMain()
}

internal class SpeedTestCoreOps(
    val isCoreLoaded: () -> Boolean = { ClashCore.isCoreLoaded },
    val ensureCoreLoaded: suspend (Application) -> Unit = { app -> ClashCore.ensureCoreLoaded(app) },
    val testProxyDelay: suspend (String, String) -> Int = { proxyName, groupName ->
        ClashCore.testProxyDelayNative(proxyName, groupName)
    },
    val healthCheckGroup: suspend (String, (String, Int) -> Unit) -> Map<String, Int> = { groupName, onProgress ->
        ClashCore.healthCheckGroupNative(groupName, onProgress)
    },
    val queryGroup: (String) -> ProxyGroup? = { groupName -> ClashCore.queryGroup(groupName) }
)

internal class ProxiesSpeedTestEngine(
    private val scope: CoroutineScope,
    private val host: SpeedTestHost,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coreOps: SpeedTestCoreOps = SpeedTestCoreOps()
) {
    private val lastAutoTestTimes = ConcurrentHashMap<String, Long>()
    private var autoTestJob: Job? = null

    fun cancelAll() {
        autoTestJob?.cancel()
        autoTestJob = null
    }

    fun runSpeedTestForGroup(groupName: String, isAutoTest: Boolean): Job {
        val groupTestingKey = "group:$groupName"
        if (groupName.isNotBlank() && host.isTesting(groupTestingKey)) {
            return Job().apply { complete() }
        }
        val runScopeId = host.currentScopeId()
        val runScopeEpoch = host.currentScopeEpoch()
        if (!isAutoTest && groupName.isNotBlank()) {
            markInitialGroupTesting(groupName, groupTestingKey)
        }
        return scope.launch(ioDispatcher) {
            executeGroupSweep(groupName, isAutoTest, runScopeId, runScopeEpoch)
        }
    }

    fun runSpeedTestForSingleNode(groupName: String?, nodeName: String) {
        val scopedKey = if (!groupName.isNullOrBlank()) "$groupName:$nodeName" else nodeName
        if (host.isTesting(scopedKey)) return
        val runScopeId = host.currentScopeId()
        val isSubGroup = host.parsedProfileSnapshot().groups.containsKey(nodeName) ||
            host.readLiveGroup(nodeName) != null
        if (isSubGroup) {
            runSubGroupSingleTest(groupName, nodeName, scopedKey, runScopeId)
            return
        }
        val targetGroup = groupName?.takeIf { name -> name.isNotBlank() }
            ?: host.findGroupContainingNode(nodeName)
        if (targetGroup.isNullOrBlank()) {
            Log.w(ENGINE_TAG, "Cannot resolve group for node $nodeName")
            return
        }
        val targetScopedKey = "$targetGroup:$nodeName"
        if (host.isTesting(targetScopedKey)) return
        host.addTesting(targetScopedKey)
        scope.launch(ioDispatcher) {
            try {
                coreOps.ensureCoreLoaded(host.appContext())
                val singleDelay = coreOps.testProxyDelay(nodeName, targetGroup)
                host.completeProbeForScope(runScopeId, targetGroup, nodeName, singleDelay)
                host.publishDelayCacheOnMain()
            } catch (e: CancellationException) {
                host.removeTestingForScope(runScopeId, targetScopedKey)
                throw e
            } catch (e: Exception) {
                Log.e(ENGINE_TAG, "Speed test failed for node $nodeName: ${e.message}", e)
                host.failProbeForScope(runScopeId, targetGroup, nodeName, e.message)
                host.publishDelayCacheOnMain()
            }
        }
    }

    fun triggerAutoSpeedTest() {
        if (autoTestJob?.isActive == true) return
        autoTestJob = scope.launch(ioDispatcher) {
            try {
                if (!coreOps.isCoreLoaded()) {
                    coreOps.ensureCoreLoaded(host.appContext())
                }
                val now = System.currentTimeMillis()
                val groupsToTest = selectAutoGroupsDueForTest(now)
                for (groupName in groupsToTest) {
                    lastAutoTestTimes[groupName] = System.currentTimeMillis()
                    // Await each group so the sweep cannot overlap itself or finish early.
                    runSpeedTestForGroup(groupName, isAutoTest = true).join()
                    delay(200L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(ENGINE_TAG, "Auto speed test failed", e)
            }
        }
    }

    private fun runSubGroupSingleTest(
        parentGroup: String?,
        subGroupName: String,
        scopedKey: String,
        runScopeId: String
    ) {
        host.addTesting(scopedKey)
        // join() avoids invokeOnCompletion's @InternalCoroutinesApi 3-arg overload ambiguity.
        scope.launch(ioDispatcher) {
            try {
                runSpeedTestForGroup(subGroupName, isAutoTest = false).join()
                val resolved = host.readDelayForScope(runScopeId, subGroupName) ?: -1
                host.completeProbeForScope(runScopeId, parentGroup.orEmpty(), subGroupName, resolved)
                host.publishDelayCacheOnMain()
            } catch (e: CancellationException) {
                host.removeTestingForScope(runScopeId, scopedKey)
                throw e
            } catch (e: Exception) {
                Log.w(ENGINE_TAG, "Sub-group single test failed for $subGroupName", e)
                host.failProbeForScope(runScopeId, parentGroup.orEmpty(), subGroupName, e.message)
                host.publishDelayCacheOnMain()
            }
        }
    }

    private fun markInitialGroupTesting(groupName: String, groupTestingKey: String) {
        val initialGroup = host.readLiveGroup(groupName)
            ?: host.parsedProfileSnapshot().groups[groupName]
            ?: host.fallbackGroupData(groupName)
        val keys = buildList {
            initialGroup?.proxies
                ?.filter { proxy -> !isVirtualProxyName(proxy.name) }
                ?.forEach { proxy -> add("$groupName:${proxy.name}") }
            add(groupTestingKey)
        }
        host.addTestingKeys(keys)
    }

    private suspend fun executeGroupSweep(
        groupName: String,
        isAutoTest: Boolean,
        runScopeId: String,
        runScopeEpoch: Long
    ) {
        try {
            coreOps.ensureCoreLoaded(host.appContext())
            val targetGroups = if (groupName.isNotBlank()) listOf(groupName) else host.proxyGroupsSnapshot()
            val semaphore = Semaphore(3)
            val subGroupBatchCache = ConcurrentHashMap<String, Deferred<Int>>()
            coroutineScope {
                targetGroups.map { targetGroup ->
                    async(ioDispatcher) {
                        semaphore.withPermit {
                            probeSingleGroup(
                                groupName = targetGroup,
                                isAutoTest = isAutoTest,
                                runScopeId = runScopeId,
                                runScopeEpoch = runScopeEpoch,
                                subGroupBatchCache = subGroupBatchCache
                            )
                        }
                    }
                }.awaitAll()
                host.publishDelayCacheOnMain()
            }
        } catch (e: CancellationException) {
            if (groupName.isNotBlank()) {
                host.finishGroupTestingForScope(runScopeId, groupName, emptyList())
            }
            throw e
        } catch (e: Exception) {
            Log.w(ENGINE_TAG, "Group speed test failed for $groupName", e)
            if (groupName.isNotBlank()) {
                host.failGroupForScope(runScopeId, groupName, e.message)
                host.publishDelayCacheOnMain()
            }
        }
    }

    private suspend fun probeSingleGroup(
        groupName: String,
        isAutoTest: Boolean,
        runScopeId: String,
        runScopeEpoch: Long,
        subGroupBatchCache: ConcurrentHashMap<String, Deferred<Int>>
    ) {
        val groupKey = "group:$groupName"
        val group = resolveGroupSnapshot(groupName)
        if (isAutoTest && !isAutoStrategyGroupType(group?.type.orEmpty())) return

        val allNodeNames = group?.proxies
            ?.filter { proxy -> !isVirtualProxyName(proxy.name) }
            ?.map { proxy -> proxy.name }
            .orEmpty()
        if (allNodeNames.isEmpty()) {
            host.removeTestingForScope(runScopeId, groupKey)
            return
        }

        val memberSubGroups = allNodeNames.filter { name -> isKnownGroup(name) }.toSet()
        val leafNodeNames = allNodeNames.filter { name -> name !in memberSubGroups }
        val scopedKeys = allNodeNames.map { name -> "$groupName:$name" }
        markGroupNodesTesting(groupKey, scopedKeys, isAutoTest)

        var settled = false
        try {
            coroutineScope {
                val parentJob = async(ioDispatcher) {
                    probeLeafNodes(groupName, leafNodeNames, memberSubGroups, runScopeId)
                }
                val subJobs = memberSubGroups.map { subGroup ->
                    async(ioDispatcher) {
                        probeMemberSubGroup(groupName, subGroup, runScopeId, runScopeEpoch, subGroupBatchCache)
                    }
                }
                val groupDelays = parentJob.await()
                subJobs.awaitAll()
                finalizeGroupProbeResults(
                    groupName = groupName,
                    fallbackGroup = group,
                    allNodeNames = allNodeNames,
                    memberSubGroups = memberSubGroups,
                    groupDelays = groupDelays,
                    runScopeId = runScopeId,
                    runScopeEpoch = runScopeEpoch
                )
                settled = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ENGINE_TAG, "Group probe failed for $groupName", e)
            host.failGroupForScope(runScopeId, groupName, e.message)
            settled = true
        } finally {
            if (!settled) {
                host.finishGroupTestingForScope(runScopeId, groupName, allNodeNames)
            }
        }
    }

    private suspend fun probeMemberSubGroup(
        groupName: String,
        subGroup: String,
        runScopeId: String,
        runScopeEpoch: Long,
        batchCache: ConcurrentHashMap<String, Deferred<Int>>
    ) {
        try {
            val finalSubDelay = resolveSubGroupDelay(
                subGroup = subGroup,
                visited = setOf(groupName),
                runScopeId = runScopeId,
                runScopeEpoch = runScopeEpoch,
                batchCache = batchCache
            )
            host.completeProbeForScope(runScopeId, groupName, subGroup, finalSubDelay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ENGINE_TAG, "Sub-group probe failed for $subGroup in $groupName", e)
            host.failProbeForScope(runScopeId, groupName, subGroup, e.message)
        }
    }

    private suspend fun probeLeafNodes(
        groupName: String,
        leafNodeNames: List<String>,
        memberSubGroups: Set<String>,
        runScopeId: String
    ): Map<String, Int> {
        if (leafNodeNames.isEmpty()) return emptyMap()
        return coreOps.healthCheckGroup(groupName) { proxyName, delayMs ->
            if (!memberSubGroups.contains(proxyName)) {
                host.completeProbeForScope(runScopeId, groupName, proxyName, delayMs)
            } else if (delayMs in 1..VALID_DELAY_MAX) {
                host.recordDelayForScope(runScopeId, proxyName, delayMs)
                host.recordDelayForScope(runScopeId, "$groupName:$proxyName", delayMs)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun finalizeGroupProbeResults(
        groupName: String,
        fallbackGroup: ProxyGroup?,
        allNodeNames: List<String>,
        memberSubGroups: Set<String>,
        groupDelays: Map<String, Int>,
        runScopeId: String,
        runScopeEpoch: Long
    ) {
        groupDelays.forEach { (name, delayMs) ->
            recordGroupDelayEntry(groupName, name, delayMs, memberSubGroups.contains(name), runScopeId)
        }
        val finalGroup = coreOps.queryGroup(groupName)
        val bestNodeName = finalGroup?.now ?: fallbackGroup?.now.orEmpty()
        val rawBest = host.readDelayForScope(runScopeId, "$groupName:$bestNodeName")
            ?: host.readDelayForScope(runScopeId, bestNodeName)
            ?: finalGroup?.proxies?.firstOrNull { proxy -> proxy.name == bestNodeName }?.delay
            ?: -1
        val minValid = allNodeNames.mapNotNull { name ->
            (host.readDelayForScope(runScopeId, "$groupName:$name")
                ?: host.readDelayForScope(runScopeId, name)
                ?: groupDelays[name])?.takeIf { delayVal -> delayVal in 1..VALID_DELAY_MAX }
        }.minOrNull()
        val finalGroupDelay = rawBest.takeIf { delayVal -> delayVal in 1..VALID_DELAY_MAX }
            ?: minValid
            ?: -1
        host.completeGroupForScope(runScopeId, groupName, finalGroupDelay, allNodeNames)
        if (finalGroup != null && finalGroup.now.isNotBlank()) {
            host.updateLiveGroupNow(groupName, finalGroup.now, runScopeEpoch)
        }
    }

    private fun recordGroupDelayEntry(
        groupName: String,
        nodeName: String,
        delayMs: Int,
        isSubGroup: Boolean,
        runScopeId: String
    ) {
        if (!isSubGroup) {
            host.completeProbeForScope(runScopeId, groupName, nodeName, delayMs)
        } else if (delayMs in 1..VALID_DELAY_MAX) {
            val cached = host.readDelayForScope(runScopeId, nodeName) ?: -1
            if (cached !in 1..VALID_DELAY_MAX) {
                host.recordDelayForScope(runScopeId, nodeName, delayMs)
                host.recordDelayForScope(runScopeId, "$groupName:$nodeName", delayMs)
            }
        }
    }

    private suspend fun resolveSubGroupDelay(
        subGroup: String,
        visited: Set<String>,
        runScopeId: String,
        runScopeEpoch: Long,
        batchCache: ConcurrentHashMap<String, Deferred<Int>>
    ): Int = coroutineScope {
        if (subGroup in visited || visited.size > 4) {
            return@coroutineScope host.readDelayForScope(runScopeId, subGroup) ?: -1
        }
        val deferred = batchCache.getOrPut(subGroup) {
            async(ioDispatcher) {
                computeSubGroupDelay(subGroup, visited, runScopeId, runScopeEpoch, batchCache)
            }
        }
        deferred.await()
    }

    private suspend fun computeSubGroupDelay(
        subGroup: String,
        visited: Set<String>,
        runScopeId: String,
        runScopeEpoch: Long,
        batchCache: ConcurrentHashMap<String, Deferred<Int>>
    ): Int {
        val snapshot = host.readLiveGroup(subGroup)
            ?: host.parsedProfileSnapshot().groups[subGroup]
            ?: coreOps.queryGroup(subGroup)
        val nonVirtualNames = snapshot?.proxies
            ?.filter { proxy -> !isVirtualProxyName(proxy.name) }
            ?.map { proxy -> proxy.name }
            .orEmpty()
        val nestedSubGroups = nonVirtualNames.filter { name -> isKnownGroup(name) }.toSet()
        val leafMembers = nonVirtualNames.filter { name -> name !in nestedSubGroups }
        val subDelays = probeSubGroupLeafMembers(subGroup, leafMembers, nestedSubGroups, runScopeId)
        val subGroupData = coreOps.queryGroup(subGroup) ?: snapshot
        val subBestName = subGroupData?.now.orEmpty()
        val nestedTarget = when {
            subBestName.isNotBlank() && subBestName in nestedSubGroups -> subBestName
            leafMembers.isEmpty() && nestedSubGroups.isNotEmpty() -> nestedSubGroups.first()
            else -> null
        }
        val nestedBestDelay = if (nestedTarget != null) {
            resolveSubGroupDelay(nestedTarget, visited + subGroup, runScopeId, runScopeEpoch, batchCache)
        } else {
            -1
        }
        val finalSubDelay = pickBestSubGroupDelay(
            nestedBestDelay = nestedBestDelay,
            subDelays = subDelays,
            subBestName = subBestName,
            subGroupData = subGroupData,
            runScopeId = runScopeId
        )
        subDelays.forEach { (subNodeName, subNodeDelay) ->
            if (subNodeName !in nestedSubGroups) {
                host.completeProbeForScope(runScopeId, subGroup, subNodeName, subNodeDelay)
            }
        }
        host.recordProbeResultForScope(runScopeId, subGroup, finalSubDelay)
        if (subGroupData != null && subGroupData.now.isNotBlank()) {
            host.updateLiveGroupNow(subGroup, subGroupData.now, runScopeEpoch)
        }
        return finalSubDelay
    }

    private suspend fun probeSubGroupLeafMembers(
        subGroup: String,
        leafMembers: List<String>,
        nestedSubGroups: Set<String>,
        runScopeId: String
    ): Map<String, Int> {
        if (leafMembers.isEmpty()) return emptyMap()
        return coreOps.healthCheckGroup(subGroup) { subNodeName, subNodeDelay ->
            if (subNodeName !in nestedSubGroups) {
                host.completeProbeForScope(runScopeId, subGroup, subNodeName, subNodeDelay)
            }
        }
    }

    private fun pickBestSubGroupDelay(
        nestedBestDelay: Int,
        subDelays: Map<String, Int>,
        subBestName: String,
        subGroupData: ProxyGroup?,
        runScopeId: String
    ): Int {
        val rawBest = nestedBestDelay.takeIf { d -> d in 1..VALID_DELAY_MAX }
            ?: subDelays[subBestName]?.takeIf { d -> d in 1..VALID_DELAY_MAX }
            ?: host.readDelayForScope(runScopeId, subBestName)?.takeIf { d -> d in 1..VALID_DELAY_MAX }
            ?: subGroupData?.proxies?.firstOrNull { proxy -> proxy.name == subBestName }?.delay
                ?.takeIf { d -> d in 1..VALID_DELAY_MAX }
            ?: -1
        val minValid = subDelays.values.filter { d -> d in 1..VALID_DELAY_MAX }.minOrNull()
        return rawBest.takeIf { d -> d in 1..VALID_DELAY_MAX } ?: minValid ?: -1
    }

    private fun resolveGroupSnapshot(groupName: String): ProxyGroup? =
        host.readLiveGroup(groupName)
            ?: host.parsedProfileSnapshot().groups[groupName]
            ?: host.fallbackGroupData(groupName)
            ?: coreOps.queryGroup(groupName)

    private fun isKnownGroup(name: String): Boolean =
        host.parsedProfileSnapshot().groups.containsKey(name) || host.readLiveGroup(name) != null

    private fun markGroupNodesTesting(
        groupKey: String,
        scopedKeys: List<String>,
        isAutoTest: Boolean
    ) {
        val keys = if (isAutoTest) listOf(groupKey) else scopedKeys + groupKey
        host.addTestingKeys(keys)
    }

    private fun selectAutoGroupsDueForTest(now: Long): List<String> =
        host.parsedProfileSnapshot().groups
            .filter { (gName, grp) ->
                isAutoStrategyGroupType(grp.type) && !isRootStrategyGroup(gName)
            }
            .keys
            .filter { gName ->
                val lastTime = lastAutoTestTimes[gName] ?: 0L
                now - lastTime > AUTO_TEST_COOLDOWN_MS
            }
}

private fun isVirtualProxyName(name: String): Boolean =
    name.equals("DIRECT", ignoreCase = true) ||
        name.equals("REJECT", ignoreCase = true) ||
        name.equals("COMPATIBLE", ignoreCase = true)

private fun isAutoStrategyGroupType(type: String): Boolean {
    val norm = type.lowercase(Locale.ROOT).replace("-", "").replace("_", "").trim()
    return norm == "urltest" || norm == "fallback" || norm == "loadbalance"
}

private fun isRootStrategyGroup(groupName: String): Boolean =
    groupName.equals("GLOBAL", ignoreCase = true) ||
        groupName.equals("节点选择", ignoreCase = true) ||
        groupName.equals("PROXIES", ignoreCase = true) ||
        groupName.equals("Proxy", ignoreCase = true) ||
        groupName.equals("全部节点", ignoreCase = true)
