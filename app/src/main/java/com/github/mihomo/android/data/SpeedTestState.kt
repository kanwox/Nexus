package com.github.mihomo.android.data

/**
 * Structured speed-test result replacing the Int-only delay cache.
 *
 * The legacy cache is `Map<String, Int>`, which cannot distinguish "never tested"
 * from "tested but failed" from "still testing": the UI renders all of them as `--`.
 * This sealed hierarchy makes each outcome explicit so the proxies screen can show a
 * spinner while a probe is in flight and a failure marker instead of a misleading dash.
 */
sealed class SpeedTestState {
    /** No probe has run for this node since the process started. */
    object Untested : SpeedTestState()

    /** A probe is currently in flight (drives a per-node progress indicator). */
    object Testing : SpeedTestState()

    /** The probe finished and the core reported a usable RTT in milliseconds. */
    data class Success(val delayMs: Int) : SpeedTestState()

    /** The probe exceeded the health-check timeout without an HTTP-level answer. */
    object Timeout : SpeedTestState()

    /** The probe failed for a non-timeout reason (DNS, TLS, proxy refused, ...). */
    data class Failed(val reason: String?) : SpeedTestState()

    /** The probe was cancelled (user navigated away, profile switched, core reset). */
    object Cancelled : SpeedTestState()

    /**
     * Maps to the legacy Int cache semantics used across the UI today:
     * null = "no result" (the UI falls back to the core-reported delay / `--`),
     * -1 = "tested but unusable" (the UI shows the failure marker).
     *
     * This lets [com.github.mihomo.android.ui.ProxiesViewModel] keep the existing
     * `Map<String, Int>` plumbing while switching the authoritative store to
     * `Map<String, SpeedTestState>` — the migration does not need to touch the screens.
     */
    fun toLegacyDelay(): Int? = when (this) {
        is Success -> delayMs.takeIf { it in 1..65534 }
        Timeout, is Failed, Cancelled -> -1
        Untested, Testing -> null
    }

    companion object {
        /**
         * Classifies a native health-check delay value using the same ranges the rest of
         * the app treats as valid (1..65534). Values outside that range are core sentinels
         * (-1 = pending / unknown, -2 = REJECT or a group without a usable member).
         */
        fun fromNativeDelay(delay: Int): SpeedTestState = when {
            delay in 1..65534 -> Success(delay)
            delay == 0 || delay == -1 -> Untested
            delay == -2 || delay >= 65535 -> Timeout
            else -> Failed("core reported $delay")
        }
    }
}
