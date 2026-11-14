package com.github.mihomo.android.core

import com.github.mihomo.android.MihomoApplication
import com.github.mihomo.android.service.hasValidWhitelistTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests covering pure logic in [ClashCore], [MihomoApplication], and
 * `MihomoVpnService` helpers introduced/hardened for Phase 3.
 */
class CoreLogicTest {

    @Test
    fun `isTunnelAddress detects both compressed and expanded TUN portal and gateway addresses`() {
        assertTrue(isTunnelAddress(TunConfig.IPV4_PORTAL))
        assertTrue(isTunnelAddress(TunConfig.IPV4_ADDRESS))
        assertTrue(isTunnelAddress(TunConfig.IPV6_PORTAL))
        assertTrue(isTunnelAddress(TunConfig.IPV6_ADDRESS))
        assertTrue(isTunnelAddress("fdfe:dcba:9876:0:0:0:0:2"))
        assertTrue(isTunnelAddress("FDFE:DCBA:9876:0000:0000:0000:0000:0001"))
        assertTrue(isTunnelAddress("fdfe:dcba:9876::2%tun0"))
        assertTrue(isTunnelAddress("  "))

        assertFalse(isTunnelAddress("8.8.8.8"))
        assertFalse(isTunnelAddress("2001:4860:4860::8888"))
        assertFalse(isTunnelAddress("192.168.1.1"))
    }

    @Test
    fun `selectDnsServerString excludes tunnel portal addresses and preserves previous DNS on transient loss`() {
        val selected = selectDnsServerString(
            rawAddresses = listOf(
                " ${TunConfig.IPV4_PORTAL} ",
                "8.8.8.8",
                "fdfe:dcba:9876:0:0:0:0:2",
                "8.8.8.8",
                "2001:4860:4860::8888"
            ),
            previousDns = "1.1.1.1"
        )
        assertEquals("8.8.8.8,2001:4860:4860::8888", selected)

        val preservedOnTransientEmpty = selectDnsServerString(
            rawAddresses = emptyList(),
            previousDns = "9.9.9.9,149.112.112.112"
        )
        assertEquals("9.9.9.9,149.112.112.112", preservedOnTransientEmpty)

        val preservedWhenOnlyPortalReported = selectDnsServerString(
            rawAddresses = listOf(TunConfig.IPV4_PORTAL, TunConfig.IPV6_PORTAL),
            previousDns = "1.1.1.1"
        )
        assertEquals("1.1.1.1", preservedWhenOnlyPortalReported)

        val fallbackWhenNoHistory = selectDnsServerString(
            rawAddresses = listOf(TunConfig.IPV4_PORTAL),
            previousDns = null
        )
        assertEquals(DEFAULT_FALLBACK_DNS, fallbackWhenNoHistory)
    }

    @Test
    fun `isHealthCheckReusable rejects completed stale or clock-skewed entries`() {
        val started = 10_000L
        assertTrue(isHealthCheckReusable(isCompleted = false, startedAtMs = started, nowMs = started))
        assertTrue(isHealthCheckReusable(isCompleted = false, startedAtMs = started, nowMs = started + 5_000L))
        assertFalse(isHealthCheckReusable(isCompleted = true, startedAtMs = started, nowMs = started + 1_000L))
        assertFalse(
            isHealthCheckReusable(
                isCompleted = false,
                startedAtMs = started,
                nowMs = started + HEALTH_CHECK_STALE_MS
            )
        )
        assertFalse(isHealthCheckReusable(isCompleted = false, startedAtMs = started, nowMs = started - 1L))
    }

    @Test
    fun `hasValidWhitelistTargets fails fast when external whitelist packages are uninstalled`() {
        val hostPkg = "com.github.mihomo.android"
        assertFalse(hasValidWhitelistTargets(emptySet(), hostPkg, appliedExternalCount = 0))
        assertFalse(hasValidWhitelistTargets(setOf(hostPkg), hostPkg, appliedExternalCount = 0))
        assertFalse(
            hasValidWhitelistTargets(
                setOf(hostPkg, "com.example.removed"),
                hostPkg,
                appliedExternalCount = 0
            )
        )
        assertTrue(
            hasValidWhitelistTargets(
                setOf("com.example.browser", "com.example.removed"),
                hostPkg,
                appliedExternalCount = 1
            )
        )
    }

    @Test
    fun `MihomoApplication redactCrashReport strips URLs tokens and credentials while retaining trace info`() {
        val rawCrash = """
            time: 2026-11-13 22:00:00
            thread: main

            java.lang.IllegalStateException: Fetch failed for https://alice:secretPass@sub.example.com/link/AbCdEfGh12345678?token=mySecretToken99
                at trojan://p@ss!word@node.example.com:443?sni=node.example.com#HK
                Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.signatureToken123
        """.trimIndent()

        val redacted = MihomoApplication.redactCrashReport(rawCrash)
        assertTrue(redacted.contains("java.lang.IllegalStateException"))
        assertFalse(redacted.contains("secretPass"))
        assertFalse(redacted.contains("mySecretToken99"))
        assertFalse(redacted.contains("AbCdEfGh12345678"))
        assertFalse(redacted.contains("p@ss!word"))
        assertFalse(redacted.contains("signatureToken123"))
    }

    @Test
    fun `HostPortParser accepts port boundaries and ipv4 mapped ipv6 literals`() {
        val minPort = parseHostPort("10.0.0.1:1")
        assertNotNull(minPort)
        assertEquals(1, minPort!!.port)

        val maxPort = parseHostPort("10.0.0.1:65535")
        assertNotNull(maxPort)
        assertEquals(65535, maxPort!!.port)

        val v4MappedV6 = parseHostPort("[::ffff:192.0.2.1]:443")
        assertNotNull(v4MappedV6)
        assertEquals(443, v4MappedV6!!.port)
    }

    @Test
    fun `ClashCore uninitialized guards return safe defaults without loading native library`() {
        ClashCore.reset()
        ClashCore.setSuspended(false)
        ClashCore.stopTun()
        assertEquals(0L, ClashCore.queryTrafficNow())
        assertEquals(0L, ClashCore.queryTrafficTotal())
        assertEquals("Unknown (uninitialized)", ClashCore.getCoreVersion())
        assertEquals("rule", ClashCore.queryTunnelState().mode)
        assertTrue(ClashCore.queryGroupNames().isEmpty())
        assertEquals(null, ClashCore.queryGroup("Proxy"))
        assertFalse(ClashCore.patchSelector("Proxy", "DIRECT"))
        ClashCore.healthCheckAll()
    }
}
