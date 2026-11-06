package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the WS-first transport: the reconnect backoff schedule and the
 * [SpeedTestState] to legacy-delay mapping. The supervisor loop itself needs a live
 * controller socket and is covered by manual/device testing instead (documented in the
 * change report), but its retry schedule — the part that must never accidentally grow
 * unbounded — is fully determined by [RequestRepository.wsBackoffMs].
 */
class RequestRepositoryTransportTest {

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
