package com.github.mihomo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the tunnel's address plan.
 *
 * The same addresses are used in three places that must agree: the VpnService.Builder
 * (interface addresses), the native core (gateway / portal / DNS) and the plaintext-DNS
 * reject rule. These tests pin the individual constants and check that the composed
 * lists still contain exactly the addresses they are supposed to.
 */
class TunConfigTest {

    @Test
    fun `gateway list carries both address families with their prefixes`() {
        assertEquals("172.19.0.1/30,fdfe:dcba:9876::1/126", TunConfig.GATEWAY)
        assertTrue(TunConfig.GATEWAY.contains("${TunConfig.IPV4_ADDRESS}/${TunConfig.IPV4_PREFIX}"))
        assertTrue(TunConfig.GATEWAY.contains("${TunConfig.IPV6_ADDRESS}/${TunConfig.IPV6_PREFIX}"))
    }

    @Test
    fun `portal list carries both address families`() {
        assertEquals("172.19.0.2,fdfe:dcba:9876::2", TunConfig.PORTAL)
        assertTrue(TunConfig.PORTAL.contains(TunConfig.IPV4_PORTAL))
        assertTrue(TunConfig.PORTAL.contains(TunConfig.IPV6_PORTAL))
    }

    @Test
    fun `dns list points at the portal addresses`() {
        assertEquals("172.19.0.2,fdfe:dcba:9876::2", TunConfig.DNS)
        assertEquals(TunConfig.PORTAL, TunConfig.DNS)
    }

    @Test
    fun `ipv4 only variants drop the ipv6 addresses`() {
        assertEquals("172.19.0.1/30", TunConfig.GATEWAY_IPV4)
        assertEquals("172.19.0.2", TunConfig.PORTAL_IPV4)
        assertEquals("172.19.0.2", TunConfig.DNS_IPV4)
        assertFalse(TunConfig.GATEWAY_IPV4.contains(":"))
        assertFalse(TunConfig.PORTAL_IPV4.contains(":"))
        assertFalse(TunConfig.DNS_IPV4.contains(":"))
    }

    @Test
    fun `the portal address is the dns address of the tunnel`() {
        // The DNS reject rule in ConfigManager exempts exactly this address; if the two
        // ever drift apart the core's own DNS lookups get rejected.
        assertEquals(TunConfig.IPV4_PORTAL, TunConfig.DNS_IPV4)
        assertEquals(TunConfig.IPV6_PORTAL, TunConfig.DNS.substringAfter(','))
    }

    @Test
    fun `prefixes match the address families`() {
        assertEquals(30, TunConfig.IPV4_PREFIX)
        assertEquals(126, TunConfig.IPV6_PREFIX)
    }

    @Test
    fun `the local and portal addresses differ`() {
        assertFalse(TunConfig.IPV4_ADDRESS == TunConfig.IPV4_PORTAL)
        assertFalse(TunConfig.IPV6_ADDRESS == TunConfig.IPV6_PORTAL)
    }

    @Test
    fun `the ipv4 address stays inside the tunnel subnet`() {
        // 172.19.0.0/30 is the /30 assigned to the TUN interface: the local side is
        // .1 and the portal side is .2, so the address must be one of those two.
        val octets = TunConfig.IPV4_ADDRESS.split('.')
        assertEquals(4, octets.size)
        assertEquals("172", octets[0])
        assertEquals("19", octets[1])
        assertEquals("0", octets[2])
        assertTrue(
            TunConfig.IPV4_ADDRESS == "172.19.0.1" || TunConfig.IPV4_ADDRESS == "172.19.0.2"
        )
    }
}
