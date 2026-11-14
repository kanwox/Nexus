@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.app.Application
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.ParsedProfile
import com.github.mihomo.android.data.SpeedTestState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private class StubTestApplication : Application()

private data class ObservedUiFrame(
    val testing: Set<String>,
    val delays: Map<String, SpeedTestState>
)

private fun buildSampleProxyProfile(): ParsedProfile = ParsedProfile(
    groupNames = listOf("Proxy", "Auto"),
    groups = mapOf(
        "Proxy" to ProxyGroup(
            type = "Selector",
            now = "HK 01",
            proxies = listOf(Proxy("HK 01"), Proxy("JP 01"), Proxy("Auto"))
        ),
        "Auto" to ProxyGroup(
            type = "URLTest",
            now = "US 01",
            proxies = listOf(Proxy("US 01"))
        )
    )
)

private fun assertNoGapAfterTesting(
    frames: List<ObservedUiFrame>,
    testingKey: String,
    delayKey: String,
    expected: SpeedTestState
) {
    val startIdx = frames.indexOfFirst { frame -> frame.testing.contains(testingKey) }
    assertTrue("Expected at least one frame with $testingKey testing", startIdx >= 0)
    val postFrames = frames.subList(startIdx + 1, frames.size)
    assertFalse("Expected settled frame after $testingKey testing", postFrames.isEmpty())
    for (frame in postFrames) {
        if (!frame.testing.contains(testingKey)) {
            assertEquals(
                "Cleared $testingKey without publishing $expected",
                expected,
                frame.delays[delayKey]
            )
        }
    }
}

private class CoordinationHarness(
    initialScopeId: String,
    parsedProfile: ParsedProfile,
    coreOps: SpeedTestCoreOps
) : AutoCloseable {
    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "test-main-ui").apply { isDaemon = true }
    }
    val mainDispatcher: CoroutineDispatcher = mainExecutor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scopeEpoch = AtomicLong(1L)
    val frames = CopyOnWriteArrayList<ObservedUiFrame>()
    private val appRef = StubTestApplication()
    private val parsedRef = AtomicReference(parsedProfile)
    val cacheStore: ProxiesRuntimeCacheStore
    val engine: ProxiesSpeedTestEngine

    init {
        lateinit var createdStore: ProxiesRuntimeCacheStore
        createdStore = ProxiesRuntimeCacheStore(
            scope = scope,
            initialScopeId = initialScopeId,
            currentScopeEpoch = { scopeEpoch.get() },
            fallbackParsedGroup = { group -> parsedRef.get().groups[group] },
            fallbackCurrentGroup = { null },
            onTestingChanged = { testing ->
                frames.add(ObservedUiFrame(testing = testing, delays = createdStore.delayCache.toMap()))
            },
            onLiveMapChanged = { },
            mainDispatcher = mainDispatcher
        )
        cacheStore = createdStore
        val adapter = ProxiesSpeedTestHostAdapter(
            appProvider = { appRef },
            cacheStore = cacheStore,
            scopeEpochProvider = { scopeEpoch.get() },
            proxyGroupsProvider = { parsedRef.get().groupNames },
            parsedProfileProvider = { parsedRef.get() },
            fallbackGroupProvider = { null }
        )
        engine = ProxiesSpeedTestEngine(
            scope = scope,
            host = adapter,
            ioDispatcher = Dispatchers.IO,
            coreOps = coreOps
        )
    }

    fun drainMain() {
        runBlocking(mainDispatcher) { }
    }

    fun awaitFramesMatching(
        timeoutMs: Long = 3_000L,
        predicate: (List<ObservedUiFrame>) -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            drainMain()
            if (predicate(frames.toList())) return true
            Thread.sleep(10L)
        }
        drainMain()
        return predicate(frames.toList())
    }

    override fun close() {
        scope.cancel()
        mainExecutor.shutdownNow()
    }
}

class ProxiesSpeedTestCoordinationTest {

    @Test
    fun `completeProbeInScope updates delayCache and clears testing atomically in one frame`() {
        val harness = CoordinationHarness("p1", ParsedProfile(), SpeedTestCoreOps())
        try {
            val store = harness.cacheStore
            store.completeProbeInScope("p1", "Proxy", "HK 01", 210)
            harness.drainMain()
            assertEquals(SpeedTestState.Success(210), store.delayCache["Proxy:HK 01"])

            val successStart = harness.frames.size
            store.addTestingKey("Proxy:HK 01")
            harness.drainMain()
            store.completeProbeInScope("p1", "Proxy", "HK 01", 48)
            harness.drainMain()

            val afterSuccess = harness.frames.subList(successStart, harness.frames.size)
            assertNoGapAfterTesting(afterSuccess, "Proxy:HK 01", "Proxy:HK 01", SpeedTestState.Success(48))
            assertNoGapAfterTesting(afterSuccess, "Proxy:HK 01", "HK 01", SpeedTestState.Success(48))

            val timeoutStart = harness.frames.size
            store.addTestingKey("Proxy:HK 01")
            harness.drainMain()
            store.completeProbeInScope("p1", "Proxy", "HK 01", -2)
            harness.drainMain()

            val afterTimeout = harness.frames.subList(timeoutStart, harness.frames.size)
            assertNoGapAfterTesting(afterTimeout, "Proxy:HK 01", "Proxy:HK 01", SpeedTestState.Timeout)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `runSpeedTestForSingleNode commits success and failure fallback without intermediate gap`() {
        val parsed = buildSampleProxyProfile()
        val probeGate = CompletableDeferred<Unit>()
        val coreOps = SpeedTestCoreOps(
            isCoreLoaded = { true },
            ensureCoreLoaded = { },
            testProxyDelay = { proxyName, _ ->
                probeGate.await()
                when (proxyName) {
                    "HK 01" -> 64
                    "JP 01" -> error("core boom")
                    else -> -1
                }
            },
            queryGroup = { groupName -> parsed.groups[groupName] }
        )
        val harness = CoordinationHarness("p1", parsed, coreOps)
        try {
            harness.engine.runSpeedTestForSingleNode("Proxy", "HK 01")
            harness.engine.runSpeedTestForSingleNode("Proxy", "JP 01")
            assertTrue(
                harness.awaitFramesMatching { list ->
                    list.any { frame ->
                        val bothTesting = frame.testing.contains("Proxy:HK 01") &&
                            frame.testing.contains("Proxy:JP 01")
                        bothTesting
                    }
                }
            )
            probeGate.complete(Unit)

            assertTrue(
                harness.awaitFramesMatching { list ->
                    val last = list.lastOrNull() ?: return@awaitFramesMatching false
                    val settled = !last.testing.contains("Proxy:HK 01") && !last.testing.contains("Proxy:JP 01")
                    val bothRecorded = last.delays["Proxy:HK 01"] == SpeedTestState.Success(64) &&
                        last.delays["Proxy:JP 01"] is SpeedTestState.Failed
                    settled && bothRecorded
                }
            )
            assertNoGapAfterTesting(harness.frames.toList(), "Proxy:HK 01", "Proxy:HK 01", SpeedTestState.Success(64))
            val jpIdx = harness.frames.indexOfFirst { frame -> frame.testing.contains("Proxy:JP 01") }
            assertTrue(jpIdx >= 0)
            for (frame in harness.frames.subList(jpIdx + 1, harness.frames.size)) {
                if (!frame.testing.contains("Proxy:JP 01")) {
                    assertTrue(frame.delays["Proxy:JP 01"] is SpeedTestState.Failed)
                }
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `runSpeedTestForSingleNode on subgroup settles parent entry without gap`() {
        val parsed = buildSampleProxyProfile()
        val autoGate = CompletableDeferred<Unit>()
        val coreOps = SpeedTestCoreOps(
            isCoreLoaded = { true },
            ensureCoreLoaded = { },
            healthCheckGroup = { groupName, onProgress ->
                autoGate.await()
                if (groupName == "Auto") {
                    onProgress("US 01", 92)
                    mapOf("US 01" to 92)
                } else {
                    emptyMap()
                }
            },
            queryGroup = { groupName -> parsed.groups[groupName] }
        )
        val harness = CoordinationHarness("p1", parsed, coreOps)
        try {
            harness.engine.runSpeedTestForSingleNode("Proxy", "Auto")
            assertTrue(
                harness.awaitFramesMatching { list ->
                    list.any { frame -> frame.testing.contains("Proxy:Auto") }
                }
            )
            autoGate.complete(Unit)

            assertTrue(
                harness.awaitFramesMatching { list ->
                    val last = list.lastOrNull() ?: return@awaitFramesMatching false
                    !last.testing.contains("Proxy:Auto") &&
                        last.delays["Proxy:Auto"] == SpeedTestState.Success(92)
                }
            )
            assertNoGapAfterTesting(harness.frames.toList(), "Proxy:Auto", "Proxy:Auto", SpeedTestState.Success(92))
        } finally {
            autoGate.complete(Unit)
            harness.close()
        }
    }

    @Test
    fun `runSpeedTestForGroup streams per-node completion with zero gap before sweep ends`() {
        val parsed = buildSampleProxyProfile()
        val firstLeafEmitted = CountDownLatch(1)
        val allowSecondLeaf = CompletableDeferred<Unit>()
        val coreOps = SpeedTestCoreOps(
            isCoreLoaded = { true },
            ensureCoreLoaded = { },
            healthCheckGroup = { groupName, onProgress ->
                when (groupName) {
                    "Proxy" -> {
                        onProgress("HK 01", 33)
                        firstLeafEmitted.countDown()
                        allowSecondLeaf.await()
                        onProgress("JP 01", -1)
                        mapOf("HK 01" to 33, "JP 01" to -1)
                    }
                    "Auto" -> {
                        onProgress("US 01", 75)
                        mapOf("US 01" to 75)
                    }
                    else -> emptyMap()
                }
            },
            queryGroup = { groupName -> parsed.groups[groupName] }
        )
        val harness = CoordinationHarness("p1", parsed, coreOps)
        try {
            val job = harness.engine.runSpeedTestForGroup("Proxy", isAutoTest = false)
            assertTrue(firstLeafEmitted.await(3, TimeUnit.SECONDS))

            assertTrue(
                harness.awaitFramesMatching { list ->
                    val latest = list.lastOrNull() ?: return@awaitFramesMatching false
                    val hkSettled = !latest.testing.contains("Proxy:HK 01") &&
                        latest.delays["Proxy:HK 01"] == SpeedTestState.Success(33)
                    val othersPending = latest.testing.contains("Proxy:JP 01") &&
                        latest.testing.contains("group:Proxy")
                    hkSettled && othersPending
                }
            )

            allowSecondLeaf.complete(Unit)
            runBlocking { job.join() }
            harness.drainMain()

            val finalFrame = harness.frames.last()
            assertTrue(finalFrame.testing.isEmpty())
            assertEquals(SpeedTestState.Success(33), finalFrame.delays["Proxy:HK 01"])
            assertEquals(SpeedTestState.Timeout, finalFrame.delays["Proxy:JP 01"])
            assertEquals(SpeedTestState.Success(75), finalFrame.delays["Proxy:Auto"])
            assertEquals(SpeedTestState.Success(33), finalFrame.delays["Proxy"])

            val snapshotFrames = harness.frames.toList()
            assertNoGapAfterTesting(snapshotFrames, "Proxy:HK 01", "Proxy:HK 01", SpeedTestState.Success(33))
            assertNoGapAfterTesting(snapshotFrames, "Proxy:JP 01", "Proxy:JP 01", SpeedTestState.Timeout)
        } finally {
            allowSecondLeaf.complete(Unit)
            harness.close()
        }
    }

    @Test
    fun `group failure preserves already completed leaves and marks pending leaves Failed`() {
        val parsed = ParsedProfile(
            groupNames = listOf("Proxy"),
            groups = mapOf(
                "Proxy" to ProxyGroup(
                    type = "Selector",
                    proxies = listOf(Proxy("HK 01"), Proxy("JP 01"))
                )
            )
        )
        val coreOps = SpeedTestCoreOps(
            isCoreLoaded = { true },
            ensureCoreLoaded = { },
            healthCheckGroup = { _, onProgress ->
                onProgress("HK 01", 51)
                error("native disconnect")
            },
            queryGroup = { groupName -> parsed.groups[groupName] }
        )
        val harness = CoordinationHarness("p1", parsed, coreOps)
        try {
            val job = harness.engine.runSpeedTestForGroup("Proxy", isAutoTest = false)
            runBlocking { job.join() }
            harness.drainMain()

            val last = harness.frames.last()
            assertTrue("Expected all testing keys cleared after failure", last.testing.isEmpty())
            assertEquals(SpeedTestState.Success(51), last.delays["Proxy:HK 01"])
            assertTrue(last.delays["Proxy:JP 01"] is SpeedTestState.Failed)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `stale scope completion and job cancellation cannot pollute new profile testing state`() {
        val parsed = ParsedProfile(
            groupNames = listOf("Proxy"),
            groups = mapOf(
                "Proxy" to ProxyGroup(
                    type = "Selector",
                    proxies = listOf(Proxy("HK 01"))
                )
            )
        )
        val enteredCore = CountDownLatch(1)
        val releaseCore = CompletableDeferred<Unit>()
        val coreOps = SpeedTestCoreOps(
            isCoreLoaded = { true },
            ensureCoreLoaded = { },
            healthCheckGroup = { _, onProgress ->
                enteredCore.countDown()
                releaseCore.await()
                onProgress("HK 01", 15)
                mapOf("HK 01" to 15)
            },
            queryGroup = { groupName -> parsed.groups[groupName] }
        )
        val harness = CoordinationHarness("scopeA", parsed, coreOps)
        try {
            val scopeAJob = harness.engine.runSpeedTestForGroup("Proxy", isAutoTest = false)
            assertTrue(enteredCore.await(3, TimeUnit.SECONDS))

            harness.scopeEpoch.incrementAndGet()
            harness.cacheStore.invalidateDelayCache("scopeB")
            harness.cacheStore.clearTestingNodes()
            harness.cacheStore.addTestingKey("Proxy:HK 01")
            harness.drainMain()

            releaseCore.complete(Unit)
            runBlocking { scopeAJob.join() }
            harness.drainMain()

            assertTrue(harness.cacheStore.isTesting("Proxy:HK 01"))
            assertFalse(harness.cacheStore.delayCache.containsKey("Proxy:HK 01"))

            val cancelEntered = CountDownLatch(1)
            val cancelGate = CompletableDeferred<Unit>()
            val cancelCoreOps = SpeedTestCoreOps(
                isCoreLoaded = { true },
                ensureCoreLoaded = { },
                healthCheckGroup = { _, _ ->
                    cancelEntered.countDown()
                    cancelGate.await()
                    emptyMap()
                },
                queryGroup = { groupName -> parsed.groups[groupName] }
            )
            val cancelHarness = CoordinationHarness("scopeC", parsed, cancelCoreOps)
            try {
                val job = cancelHarness.engine.runSpeedTestForGroup("Proxy", isAutoTest = false)
                assertTrue(cancelEntered.await(3, TimeUnit.SECONDS))
                job.cancel()
                runBlocking { job.join() }
                cancelHarness.drainMain()

                assertFalse(cancelHarness.cacheStore.hasActiveTests())
                assertFalse(cancelHarness.cacheStore.delayCache.containsKey("Proxy:HK 01"))
            } finally {
                cancelGate.complete(Unit)
                cancelHarness.close()
            }
        } finally {
            releaseCore.complete(Unit)
            harness.close()
        }
    }

    @Test
    fun `concurrent probe completions coalesce safely and preserve testing-delay invariant`() {
        val harness = CoordinationHarness("p1", ParsedProfile(), SpeedTestCoreOps())
        try {
            val store = harness.cacheStore
            val nodeNames = (0 until 30).map { index -> "N$index" }
            val scopedKeys = nodeNames.map { node -> "G:$node" }
            store.addTestingKeys(scopedKeys + "group:G")
            harness.drainMain()

            val workers = Executors.newFixedThreadPool(8)
            try {
                val done = CountDownLatch(nodeNames.size)
                nodeNames.forEachIndexed { idx, node ->
                    workers.submit {
                        try {
                            if (idx % 5 == 0) {
                                store.failProbeInScope("p1", "G", node, "err-$idx")
                            } else {
                                store.completeProbeInScope("p1", "G", node, 20 + idx)
                            }
                        } finally {
                            done.countDown()
                        }
                    }
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                store.completeGroupInScope("p1", "G", 21, nodeNames)
                harness.drainMain()
            } finally {
                workers.shutdownNow()
            }

            assertFalse(store.hasActiveTests())
            for ((idx, node) in nodeNames.withIndex()) {
                val expected = if (idx % 5 == 0) {
                    SpeedTestState.Failed("err-$idx")
                } else {
                    SpeedTestState.Success(20 + idx)
                }
                assertEquals(expected, store.delayCache["G:$node"])
                for (frame in harness.frames) {
                    if (frame.testing.contains("group:G") && !frame.testing.contains("G:$node")) {
                        assertEquals(expected, frame.delays["G:$node"])
                    }
                }
            }
            assertEquals(SpeedTestState.Success(21), store.delayCache["G"])
        } finally {
            harness.close()
        }
    }
}
