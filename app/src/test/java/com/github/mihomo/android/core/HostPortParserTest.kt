package com.github.mihomo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Tests for the shared endpoint parser.
 *
 * The parser used to exist twice (ClashCore and MihomoVpnService) and split on the LAST
 * colon, which mangled bare IPv6 literals and accepted endpoints without a port. Both
 * copies were replaced by the single implementation in TunConfig.kt; these tests pin the
 * behaviour the core relies on for the connection-owner lookup.
 */
class HostPortParserTest {

    @Test
    fun `plain IPv4 endpoint parses`() {
        val parsed = parseHostPort("192.168.1.10:8080")
        assertNotNull(parsed)
        assertEquals(8080, parsed!!.port)
        assertEquals("192.168.1.10", parsed.address.hostAddress)
    }

    @Test
    fun `hostname endpoint parses`() {
        val parsed = parseHostPort("example.com:443")
        assertNotNull(parsed)
        assertEquals(443, parsed!!.port)
        // A hostname is resolved by InetSocketAddress, so the address is the resolved
        // one; the port is what matters here.
        assertNotNull(parsed.address)
    }

    @Test
    fun `bracketed IPv6 endpoint parses`() {
        val parsed = parseHostPort("[2001:db8::1]:443")
        assertNotNull(parsed)
        assertEquals(443, parsed!!.port)
        assertTrue("expected an IPv6 literal, got ${parsed.address}", parsed.address is Inet6Address)
        assertEquals("2001:db8:0:0:0:0:0:1", parsed.address.hostAddress)
    }

    @Test
    fun `bracketed IPv6 loopback parses`() {
        val parsed = parseHostPort("[::1]:9090")
        assertNotNull(parsed)
        assertEquals(9090, parsed!!.port)
        assertTrue(parsed.address is Inet6Address)
    }

    @Test
    fun `endpoint without a port is rejected`() {
        assertNull(parseHostPort("192.168.1.10"))
        assertNull(parseHostPort("example.com"))
        assertNull(parseHostPort("[2001:db8::1]"))
    }

    @Test
    fun `bare IPv6 literal without a port is rejected instead of being mangled`() {
        // The old parser read "2001:db8::1" as host "2001:db8:" with port 1.
        assertNull(parseHostPort("2001:db8::1"))
        assertNull(parseHostPort("::1"))
    }

    @Test
    fun `non numeric port is rejected`() {
        assertNull(parseHostPort("192.168.1.10:http"))
        assertNull(parseHostPort("192.168.1.10:"))
        assertNull(parseHostPort("192.168.1.10:-1"))
        assertNull(parseHostPort("192.168.1.10:+80"))
    }

    @Test
    fun `out of range port is rejected`() {
        assertNull(parseHostPort("192.168.1.10:0"))
        assertNull(parseHostPort("192.168.1.10:65536"))
        assertNull(parseHostPort("192.168.1.10:99999"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(parseHostPort(""))
        assertNull(parseHostPort("   "))
        assertNull(parseHostPort(":8080"))
        assertNull(parseHostPort("[]:8080"))
        assertNull(parseHostPort("[2001:db8::1"))
        assertNull(parseHostPort("garbage"))
        assertNull(parseHostPort(":::8080"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val parsed = parseHostPort(" 192.168.1.10:8080 ")
        assertNotNull(parsed)
        assertEquals(8080, parsed!!.port)
    }

    @Test
    fun `an unresolvable host still yields a usable address`() {
        // The core reports endpoints for hosts that may not resolve locally; the UID
        // lookup must still receive a well-formed address instead of dropping the entry.
        val parsed = parseHostPort("nonexistent.invalid:443")
        assertNotNull(parsed)
        assertEquals(443, parsed!!.port)
    }

    @Test
    fun `parsed address is a socket address`() {
        val parsed: InetSocketAddress? = parseHostPort("127.0.0.1:1080")
        assertNotNull(parsed)
        assertEquals(1080, parsed!!.port)
    }
}
