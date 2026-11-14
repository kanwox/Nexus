package com.github.mihomo.android.data

import androidx.work.ListenableWorker
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdateWorkerTest {

    private fun testProfile(
        id: String,
        url: String? = "https://example.com/$id.yaml",
        autoUpdate: Boolean = true,
        lastUpdated: Long = 0L,
        intervalMinutes: Int = 30
    ): ProfileItem = ProfileItem(
        id = id,
        name = "Profile-$id",
        url = url,
        file = File("$id.yaml"),
        lastUpdated = lastUpdated,
        autoUpdate = autoUpdate,
        autoUpdateInterval = intervalMinutes
    )

    @Test
    fun `classifyFailure distinguishes transient network and 5xx or 429 errors from permanent and SSRF failures`() {
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(IOException("connection reset"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(SocketTimeoutException("read timed out"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(TimeoutException("timeout"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(IllegalStateException("HTTP 错误: 503"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(IllegalStateException("HTTP 错误: 429"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(IllegalStateException("HTTP 错误: 408"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.TRANSIENT,
            SubscriptionUpdateWorker.classifyFailure(UnknownHostException("sub.example.com"))
        )

        assertEquals(
            SubscriptionUpdateWorker.FailureKind.PERMANENT,
            SubscriptionUpdateWorker.classifyFailure(IllegalStateException("HTTP 错误: 404"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.PERMANENT,
            SubscriptionUpdateWorker.classifyFailure(SsrfBlockedException("private IP blocked"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.PERMANENT,
            SubscriptionUpdateWorker.classifyFailure(UnknownHostException("不允许的主机名：localhost"))
        )
        assertEquals(
            SubscriptionUpdateWorker.FailureKind.PERMANENT,
            SubscriptionUpdateWorker.classifyFailure(UnknownHostException("解析到不允许的 IP 地址：127.0.0.1"))
        )

        assertEquals(
            SubscriptionUpdateWorker.FailureKind.CANCELLED,
            SubscriptionUpdateWorker.classifyFailure(CancellationException("cancelled"))
        )
    }

    @Test
    fun `runUpdatePass returns success and reloads or rolls back active running profile`() = runBlocking {
        val archived = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()
        val updatedIds = mutableListOf<String>()
        val now = 10_000_000L
        val profiles = listOf(
            testProfile(id = "p-active", lastUpdated = now - 60 * 60_000L),
            testProfile(id = "p-not-due", lastUpdated = now - 5 * 60_000L),
            testProfile(id = "p-disabled", autoUpdate = false, lastUpdated = 0L)
        )

        val depsSuccessReload = SubscriptionUpdateWorker.UpdateDependencies(
            loadProfiles = { profiles },
            selectedProfileId = { "p-active" },
            isVpnRunning = { true },
            updateSubscription = { p, _ ->
                updatedIds.add(p.id)
                Result.success(p)
            },
            reloadActiveConfig = { true },
            archiveKnownGoodVersion = { archived.add(it) },
            rollbackSubscriptionUpdate = { rolledBack.add(it); true },
            log = { _, _, _ -> },
            nowMs = { now }
        )

        val result = SubscriptionUpdateWorker.runUpdatePass(depsSuccessReload)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("p-active"), updatedIds)
        assertEquals(listOf("p-active"), archived)
        assertTrue(rolledBack.isEmpty())

        val rollbackDeps = depsSuccessReload.copy(
            reloadActiveConfig = { false },
            archiveKnownGoodVersion = { archived.clear() },
            rollbackSubscriptionUpdate = { rolledBack.add(it); true }
        )
        val rollbackResult = SubscriptionUpdateWorker.runUpdatePass(rollbackDeps)
        assertEquals(ListenableWorker.Result.success(), rollbackResult)
        assertEquals(listOf("p-active"), rolledBack)
    }

    @Test
    fun `runUpdatePass returns retry on transient failure and failure on permanent errors`() = runBlocking {
        val profiles = listOf(
            testProfile(id = "p-perm"),
            testProfile(id = "p-transient")
        )
        val mixedDeps = SubscriptionUpdateWorker.UpdateDependencies(
            loadProfiles = { profiles },
            selectedProfileId = { null },
            isVpnRunning = { false },
            updateSubscription = { p, _ ->
                if (p.id == "p-perm") {
                    Result.failure(IllegalStateException("HTTP 错误: 404"))
                } else {
                    Result.failure(IOException("network down"))
                }
            },
            reloadActiveConfig = { false },
            archiveKnownGoodVersion = {},
            rollbackSubscriptionUpdate = { false },
            log = { _, _, _ -> },
            nowMs = { 10_000_000L }
        )
        assertEquals(ListenableWorker.Result.retry(), SubscriptionUpdateWorker.runUpdatePass(mixedDeps))

        val permanentOnlyDeps = mixedDeps.copy(
            loadProfiles = { listOf(testProfile(id = "p-ssrf")) },
            updateSubscription = { _, _ -> Result.failure(SsrfBlockedException("blocked")) }
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            SubscriptionUpdateWorker.runUpdatePass(permanentOnlyDeps)
        )
    }

    @Test
    fun `runUpdatePass stops loop cleanly and propagates CancellationException`() {
        val calls = AtomicInteger(0)
        val profiles = listOf(testProfile(id = "p1"), testProfile(id = "p2"))
        val deps = SubscriptionUpdateWorker.UpdateDependencies(
            loadProfiles = { profiles },
            selectedProfileId = { null },
            isVpnRunning = { false },
            updateSubscription = { p, _ ->
                calls.incrementAndGet()
                currentCoroutineContext().cancel()
                Result.success(p)
            },
            reloadActiveConfig = { false },
            archiveKnownGoodVersion = {},
            rollbackSubscriptionUpdate = { false },
            log = { _, _, _ -> },
            nowMs = { 10_000_000L }
        )

        var caughtCancellation = false
        try {
            runBlocking {
                SubscriptionUpdateWorker.runUpdatePass(deps)
            }
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
        assertEquals(1, calls.get())

        val directCalls = AtomicInteger(0)
        val directCancelDeps = deps.copy(
            updateSubscription = { _, _ ->
                directCalls.incrementAndGet()
                Result.failure(CancellationException("explicit cancel"))
            }
        )
        var caughtDirectCancellation = false
        try {
            runBlocking {
                SubscriptionUpdateWorker.runUpdatePass(directCancelDeps)
            }
        } catch (_: CancellationException) {
            caughtDirectCancellation = true
        }
        assertTrue(caughtDirectCancellation)
        assertEquals(1, directCalls.get())
    }
}
