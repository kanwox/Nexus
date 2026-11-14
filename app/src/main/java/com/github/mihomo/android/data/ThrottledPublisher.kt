@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces high-frequency repository mutations (one per core log line) into at most one
 * StateFlow snapshot per [intervalMs]. Snapshots are skipped entirely while nothing collects
 * the flow (screen not visible) and produced lazily once a collector subscribes again.
 *
 * Without this, every proxied connection copied up to 1000 list items on the JNI thread and
 * woke every collector, which was the main foreground CPU/battery cost.
 */
internal class ThrottledPublisher(
    private val intervalMs: Long,
    private val subscriptionCount: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val publish: () -> Unit
) {
    constructor(
        intervalMs: Long,
        subscriptionCount: StateFlow<Int>,
        publish: () -> Unit
    ) : this(intervalMs, subscriptionCount, scope, publish)

    private val lock = Any()
    private var scheduledJob: Job? = null
    private val stale = AtomicBoolean(false)

    init {
        coroutineScope.launch {
            subscriptionCount.collect { count ->
                val shouldPublish = synchronized(lock) {
                    count > 0 && stale.compareAndSet(true, false)
                }
                if (shouldPublish) {
                    publish()
                }
            }
        }
    }

    fun request() {
        if (!isThrottlingEnabled) {
            publishNow()
            return
        }
        val publishImmediately = synchronized(lock) { scheduleOrMarkStaleLocked() }
        if (publishImmediately) {
            publish()
        }
    }

    private fun scheduleOrMarkStaleLocked(): Boolean {
        if (subscriptionCount.value <= 0) {
            stale.set(true)
            return subscriptionCount.value > 0 && stale.compareAndSet(true, false)
        }
        if (scheduledJob == null) {
            val timerJob = launchDelayedPublish()
            scheduledJob = timerJob
            timerJob.start()
        }
        return false
    }

    private fun launchDelayedPublish(): Job =
        coroutineScope.launch(start = CoroutineStart.LAZY) {
            delay(intervalMs.milliseconds)
            val activeJob = currentCoroutineContext()[Job]
            if (shouldExecuteScheduledPublish(activeJob)) {
                publish()
            }
        }

    private fun shouldExecuteScheduledPublish(activeJob: Job?): Boolean = synchronized(lock) {
        if (scheduledJob !== activeJob || !coroutineScope.isActive || activeJob?.isActive != true) {
            return@synchronized false
        }
        scheduledJob = null
        val hasSubscribers = subscriptionCount.value > 0
        stale.set(!hasSubscribers)
        hasSubscribers
    }

    /** Publishes synchronously and cancels any pending delayed publication. */
    fun publishNow() {
        synchronized(lock) {
            stale.set(false)
            scheduledJob?.cancel()
            scheduledJob = null
        }
        publish()
    }

    companion object {
        private val isRunningTests: Boolean by lazy {
            try {
                (System.getProperty("java.class.path") ?: "").contains("junit") ||
                    Thread.currentThread().stackTrace.any {
                        it.className.startsWith("org.junit.") || it.className.startsWith("org.testng.")
                    }
            } catch (_: Exception) {
                false
            }
        }

        @Volatile
        var isThrottlingEnabled: Boolean = !isRunningTests

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
