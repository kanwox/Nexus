package com.github.mihomo.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
    private val publish: () -> Unit
) {
    private val scheduled = AtomicBoolean(false)
    private val stale = AtomicBoolean(false)

    init {
        scope.launch {
            subscriptionCount.collect { count ->
                if (count > 0 && stale.compareAndSet(true, false)) {
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
        if (subscriptionCount.value == 0) {
            stale.set(true)
            return
        }
        if (scheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(intervalMs.milliseconds)
                scheduled.set(false)
                publish()
            }
        }
    }

    /** Publishes synchronously, used for user-initiated actions such as "clear". */
    fun publishNow() {
        stale.set(false)
        publish()
    }

    companion object {
        private val isRunningTests: Boolean by lazy {
            try {
                (System.getProperty("java.class.path") ?: "").contains("junit") ||
                    Thread.currentThread().stackTrace.any {
                        it.className.startsWith("org.junit.") || it.className.startsWith("org.testng.")
                    }
            } catch (_: Throwable) {
                false
            }
        }

        @Volatile
        var isThrottlingEnabled: Boolean = !isRunningTests

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
