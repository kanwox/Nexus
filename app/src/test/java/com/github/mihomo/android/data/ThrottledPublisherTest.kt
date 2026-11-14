package com.github.mihomo.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ThrottledPublisherTest {

    private var previousThrottling: Boolean = false

    @Before
    fun setUp() {
        previousThrottling = ThrottledPublisher.isThrottlingEnabled
        ThrottledPublisher.isThrottlingEnabled = true
    }

    @After
    fun tearDown() {
        ThrottledPublisher.isThrottlingEnabled = previousThrottling
    }

    @Test
    fun `publishNow cancels pending scheduled timer so it does not double-publish`() {
        val subCount = MutableStateFlow(1)
        val published = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val publisher = ThrottledPublisher(
                intervalMs = 80L,
                subscriptionCount = subCount,
                coroutineScope = scope
            ) {
                published.incrementAndGet()
            }

            publisher.request()
            // Immediately force synchronous publish; pending timer must be cancelled.
            publisher.publishNow()
            assertEquals(1, published.get())

            Thread.sleep(160L)
            assertEquals("cancelled timer must not fire after publishNow()", 1, published.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale request while unsubscribed flushes when subscriber attaches`() {
        val subCount = MutableStateFlow(0)
        val latch = CountDownLatch(1)
        val published = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val publisher = ThrottledPublisher(
                intervalMs = 50L,
                subscriptionCount = subCount,
                coroutineScope = scope
            ) {
                published.incrementAndGet()
                latch.countDown()
            }

            publisher.request()
            assertEquals(0, published.get())

            subCount.value = 1
            assertTrue("expected flush upon subscriber attach", latch.await(1, TimeUnit.SECONDS))
            assertEquals(1, published.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scheduled timer marks stale instead of publishing if subscribers drop to zero`() {
        val subCount = MutableStateFlow(1)
        val published = AtomicInteger(0)
        val reopenLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val publisher = ThrottledPublisher(
                intervalMs = 80L,
                subscriptionCount = subCount,
                coroutineScope = scope
            ) {
                if (published.incrementAndGet() == 1) {
                    reopenLatch.countDown()
                }
            }

            publisher.request()
            subCount.value = 0
            Thread.sleep(150L)
            assertEquals("must not publish while subscriptionCount == 0", 0, published.get())

            subCount.value = 1
            assertTrue("expected deferred stale flush on re-subscribe", reopenLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, published.get())
        } finally {
            scope.cancel()
        }
    }
}
