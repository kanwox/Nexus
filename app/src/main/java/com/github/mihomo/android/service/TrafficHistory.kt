package com.github.mihomo.android.service

/**
 * Rolling buffer of raw per-second throughput samples for the dashboard chart. Appended by
 * the VPN traffic monitor (once per poll while the screen is interactive), read by the UI.
 */
object TrafficHistory {
    data class Sample(val timeMs: Long, val upBps: Long, val downBps: Long)

    private const val MAX_SAMPLES = 120

    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)

    @Volatile
    var lastUpBps: Long = 0L
        private set

    @Volatile
    var lastDownBps: Long = 0L
        private set

    @Synchronized
    fun append(upBps: Long, downBps: Long) {
        lastUpBps = upBps
        lastDownBps = downBps
        if (samples.size >= MAX_SAMPLES) {
            samples.removeFirst()
        }
        samples.addLast(Sample(System.currentTimeMillis(), upBps, downBps))
    }

    @Synchronized
    fun snapshot(): List<Sample> = samples.toList()

    @Synchronized
    fun clear() {
        samples.clear()
        lastUpBps = 0L
        lastDownBps = 0L
    }
}
