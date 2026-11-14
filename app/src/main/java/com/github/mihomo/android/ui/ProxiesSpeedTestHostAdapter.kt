package com.github.mihomo.android.ui

import android.app.Application
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.ParsedProfile

/**
 * Bridges [ProxiesViewModel] and [ProxiesRuntimeCacheStore] to the [SpeedTestHost] interface,
 * keeping [ProxiesViewModel] lean and within Detekt's class-size and method-count budgets.
 */
internal class ProxiesSpeedTestHostAdapter(
    private val appProvider: () -> Application,
    private val cacheStore: ProxiesRuntimeCacheStore,
    private val scopeEpochProvider: () -> Long,
    private val proxyGroupsProvider: () -> List<String>,
    private val parsedProfileProvider: () -> ParsedProfile,
    private val fallbackGroupProvider: (String) -> ProxyGroup?
) : SpeedTestHost {
    override fun appContext(): Application = appProvider()
    override fun currentScopeId(): String = cacheStore.currentScopeId()
    override fun currentScopeEpoch(): Long = scopeEpochProvider()
    override fun proxyGroupsSnapshot(): List<String> = proxyGroupsProvider()
    override fun parsedProfileSnapshot(): ParsedProfile = parsedProfileProvider()
    override fun readLiveGroup(groupName: String): ProxyGroup? = cacheStore.readLiveGroupSnapshot(groupName)
    override fun fallbackGroupData(groupName: String): ProxyGroup? = fallbackGroupProvider(groupName)
    override fun readDelayForScope(scopeId: String, key: String): Int? =
        cacheStore.readDelayInScope(scopeId, key)
    override fun recordDelayForScope(scopeId: String, key: String, delay: Int) =
        cacheStore.recordDelayInScope(scopeId, key, delay)
    override fun recordProbeResultForScope(scopeId: String, key: String, delay: Int) =
        cacheStore.recordProbeInScope(scopeId, key, delay)
    override fun completeProbeForScope(scopeId: String, groupName: String, nodeName: String, delay: Int) =
        cacheStore.completeProbeInScope(scopeId, groupName, nodeName, delay)
    override fun failProbeForScope(scopeId: String, groupName: String, nodeName: String, reason: String?) =
        cacheStore.failProbeInScope(scopeId, groupName, nodeName, reason)
    override fun completeGroupForScope(
        scopeId: String,
        groupName: String,
        groupDelay: Int,
        nodeNames: List<String>
    ) = cacheStore.completeGroupInScope(scopeId, groupName, groupDelay, nodeNames)
    override fun failGroupForScope(scopeId: String, groupName: String, reason: String?) =
        cacheStore.failGroupInScope(scopeId, groupName, reason)
    override fun finishGroupTestingForScope(scopeId: String, groupName: String, nodeNames: List<String>) =
        cacheStore.finishGroupTestingInScope(scopeId, groupName, nodeNames)
    override fun isTesting(key: String): Boolean = cacheStore.isTesting(key)
    override fun addTesting(key: String) = cacheStore.addTestingKey(key)
    override fun addTestingKeys(keys: Collection<String>) = cacheStore.addTestingKeys(keys)
    override fun removeTestingForScope(scopeId: String, key: String) =
        cacheStore.removeTestingKeyInScope(scopeId, key)
    override fun findGroupContainingNode(nodeName: String): String? =
        cacheStore.resolveGroupForNode(nodeName, parsedProfileProvider().groups)
    override fun updateLiveGroupNow(groupName: String, now: String, expectedScopeEpoch: Long) =
        cacheStore.updateLiveGroupSelectedNode(groupName, now, expectedScopeEpoch)
    override suspend fun publishDelayCacheOnMain() = cacheStore.publishDelayCacheSnapshotOnMain()
}
