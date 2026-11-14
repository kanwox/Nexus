package com.github.mihomo.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-logic and lifecycle synchronization tests for the WS-first transport and
 * [SpeedTestState] to legacy-delay mapping.
 */
class RequestRepositoryTransportTest {

    private class FakeWebSocket : WebSocket {
        @Volatile
        var cancelled: Boolean = false
        private val dummyRequest = Request.Builder().url("http://127.0.0.1:9090/connections").build()

        override fun request(): Request = dummyRequest
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            cancelled = true
        }
    }

    @Before
    fun setUp() {
        RequestRepository.resetTransportForTest()
    }

    @After
    fun tearDown() {
        RequestRepository.resetTransportForTest()
    }

    @Test
    fun `backoff doubles per attempt`() {
        assertEquals(1000L, RequestRepository.wsBackoffMs(0))
        assertEquals(2000L, RequestRepository.wsBackoffMs(1))
        assertEquals(4000L, RequestRepository.wsBackoffMs(2))
        assertEquals(8000L, RequestRepository.wsBackoffMs(3))
    }

    @Test
    fun `backoff is capped at thirty seconds`() {
        assertEquals(16_000L, RequestRepository.wsBackoffMs(4))
        assertEquals(30_000L, RequestRepository.wsBackoffMs(5))
        assertEquals(30_000L, RequestRepository.wsBackoffMs(20))
    }

    @Test
    fun `backoff tolerates out of range attempts`() {
        assertEquals(1000L, RequestRepository.wsBackoffMs(-3))
        assertEquals(30_000L, RequestRepository.wsBackoffMs(5000))
    }

    @Test
    fun `transport constants keep their diagnostic values`() {
        assertEquals("websocket", RequestRepository.TRANSPORT_WEBSOCKET)
        assertEquals("polling", RequestRepository.TRANSPORT_POLLING)
    }

    @Test
    fun `openWebSocket returns false when onFailure fires before factory returns`() {
        val fakeSocket = FakeWebSocket()
        enableStreamingStateForTest()
        RequestRepository.webSocketFactory = { listener ->
            listener.onFailure(fakeSocket, IOException("immediate failure"), null)
            fakeSocket
        }

        val opened = RequestRepository.openWebSocket()
        assertFalse("openWebSocket must return false when callback already failed", opened)
        assertEquals(RequestRepository.TRANSPORT_POLLING, RequestRepository.transportModeFlow.value)
        assertNull(getActiveSocketViaReflection())
    }

    @Test
    fun `openWebSocket cancels socket and avoids leak when stopPolling runs during factory`() {
        val fakeSocket = FakeWebSocket()
        enableStreamingStateForTest()
        RequestRepository.webSocketFactory = { _ ->
            RequestRepository.stopPolling()
            fakeSocket
        }

        val opened = RequestRepository.openWebSocket()
        assertFalse(opened)
        assertTrue("Socket created concurrently with stopPolling must be cancelled", fakeSocket.cancelled)
        assertNull(getActiveSocketViaReflection())
    }

    @Test
    fun `stale socket onClosed does not clear newer activeSocket`() {
        val socket1 = FakeWebSocket()
        val socket2 = FakeWebSocket()
        var listener1: WebSocketListener? = null
        enableStreamingStateForTest()

        RequestRepository.webSocketFactory = { listener ->
            listener1 = listener
            socket1
        }
        assertTrue(RequestRepository.openWebSocket())
        assertEquals(socket1, getActiveSocketViaReflection())

        RequestRepository.webSocketFactory = { _ -> socket2 }
        assertTrue(RequestRepository.openWebSocket())
        assertEquals(socket2, getActiveSocketViaReflection())

        // Late onClosed from socket1 must NOT clobber socket2.
        checkNotNull(listener1).onClosed(socket1, 1000, "closed late")
        assertEquals(socket2, getActiveSocketViaReflection())
    }

    @Test
    fun `degradeToPolling is a no-op after stopPolling even with stale generation`() {
        val scope = CoroutineScope(SupervisorJob())
        val fetchCalls = AtomicInteger(0)
        try {
            RequestRepository.isUiInForegroundProvider = { true }
            RequestRepository.reconnectDelayMsOverride = 60_000L
            RequestRepository.pollIntervalOverrideMs = 5L
            RequestRepository.webSocketFactory = { null }
            RequestRepository.connectionsFetcher = {
                fetchCalls.incrementAndGet()
                MihomoApi.ConnectionsSnapshot(emptyList(), 0L, 0L)
            }

            RequestRepository.startStreamingForTest(scope)
            val staleGeneration = RequestRepository.currentGenerationForTest()
            RequestRepository.stopPolling()
            RequestRepository.degradeToPollingForTest(scope, "stale ws failure", staleGeneration)

            assertNull("degradeToPolling with stale generation must not launch pollJob", getPollJobViaReflection())
            Thread.sleep(30)
            assertEquals("Stale degradeToPolling must not trigger any poll fetch", 0, fetchCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `supervisor degrades to polling after repeated WS failures and stopPolling cleanly halts polling`() {
        val scope = CoroutineScope(SupervisorJob())
        val pollLatch = CountDownLatch(2)
        val fetchCount = AtomicInteger(0)
        try {
            RequestRepository.isUiInForegroundProvider = { true }
            RequestRepository.reconnectDelayMsOverride = 10L
            RequestRepository.pollIntervalOverrideMs = 10L
            RequestRepository.webSocketFactory = { null }
            RequestRepository.connectionsFetcher = {
                fetchCount.incrementAndGet()
                pollLatch.countDown()
                MihomoApi.ConnectionsSnapshot(emptyList(), 0L, 0L)
            }

            RequestRepository.startStreamingForTest(scope)
            assertTrue("Polling fallback should start and fetch snapshots", pollLatch.await(5, TimeUnit.SECONDS))
            assertEquals(RequestRepository.TRANSPORT_POLLING, RequestRepository.transportModeFlow.value)

            val activePollJob = getPollJobViaReflection()
            assertNotNull("Poll loop job should be active before stopPolling", activePollJob)

            RequestRepository.stopPolling()
            assertNull("stopPolling must clear pollJob", getPollJobViaReflection())
            runBlocking {
                activePollJob?.join()
            }

            val countAfterStop = fetchCount.get()
            Thread.sleep(40)
            assertEquals("stopPolling must halt subsequent poll fetches", countAfterStop, fetchCount.get())
        } finally {
            scope.cancel()
        }
    }

    private fun enableStreamingStateForTest() {
        val stoppedField = RequestRepository::class.java.getDeclaredField("streamStopped")
        stoppedField.isAccessible = true
        stoppedField.setBoolean(RequestRepository, false)
    }

    private fun getActiveSocketViaReflection(): WebSocket? {
        val field = RequestRepository::class.java.getDeclaredField("activeSocket")
        field.isAccessible = true
        return field.get(RequestRepository) as? WebSocket
    }

    private fun getPollJobViaReflection(): Job? {
        val field = RequestRepository::class.java.getDeclaredField("pollJob")
        field.isAccessible = true
        return field.get(RequestRepository) as? Job
    }
}

/** Mapping between the structured speed-test states and the legacy Int cache. */
class SpeedTestStateMappingTest {

    @Test
    fun `success maps to its delay inside the valid range`() {
        assertEquals(250, SpeedTestState.Success(250).toLegacyDelay())
    }

    @Test
    fun `success with an out of range delay maps to no result`() {
        assertNull(SpeedTestState.Success(0).toLegacyDelay())
        assertNull(SpeedTestState.Success(-1).toLegacyDelay())
        assertNull(SpeedTestState.Success(65535).toLegacyDelay())
    }

    @Test
    fun `terminal failures map to the tested-but-failed marker`() {
        assertEquals(-1, SpeedTestState.Timeout.toLegacyDelay())
        assertEquals(-1, SpeedTestState.Failed("dns").toLegacyDelay())
        assertEquals(-1, SpeedTestState.Cancelled.toLegacyDelay())
    }

    @Test
    fun `pending states map to no result`() {
        assertNull(SpeedTestState.Untested.toLegacyDelay())
        assertNull(SpeedTestState.Testing.toLegacyDelay())
    }

    @Test
    fun `native delay sentinels are classified accurately`() {
        assertTrue(SpeedTestState.fromNativeDelay(120) is SpeedTestState.Success)
        assertTrue(SpeedTestState.fromNativeDelay(0) is SpeedTestState.Untested)
        assertTrue(SpeedTestState.fromNativeDelay(-1) is SpeedTestState.Untested)
        assertTrue(SpeedTestState.fromNativeDelay(-2) is SpeedTestState.Timeout)
        assertTrue(SpeedTestState.fromNativeDelay(70000) is SpeedTestState.Timeout)
        assertTrue(SpeedTestState.fromNativeDelay(-3) is SpeedTestState.Failed)
    }
}
