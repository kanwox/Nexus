package com.github.mihomo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Tests for the shared literal-IP endpoint parser in [TunConfig.kt].
 *
 * The parser must strictly accept only literal IPv4/IPv6 endpoints and reject domain names
 * immediately without triggering blocking DNS resolution on hot paths.
 */
class HostPortParserTest {

    @Test
    fun `plain IPv4 endpoint parses without unresolved state`() {
        val parsed = parseHostPort("192.168.1.10:8080")
        assertNotNull(parsed)
        assertFalse(parsed!!.isUnresolved)
        assertTrue(parsed.address is Inet4Address)
        assertEquals(8080, parsed.port)
        assertEquals("192.168.1.10", parsed.address.hostAddress)
    }

    @Test
    fun `hostname endpoints are rejected without DNS resolution`() {
        assertNull(parseHostPort("example.com:443"))
        assertNull(parseHostPort("nonexistent.invalid:443"))
        assertNull(parseHostPort("localhost:8080"))
        assertNull(parseHostPort("[example.com]:443"))
    }

    @Test
    fun `non canonical IPv4 forms are rejected`() {
        assertNull(parseHostPort("0177.0.0.1:80"))
        assertNull(parseHostPort("0x7f.0.0.1:80"))
        assertNull(parseHostPort("2130706433:80"))
        assertNull(parseHostPort("127.1:80"))
        assertNull(parseHostPort("256.0.0.1:80"))
        assertNull(parseHostPort("1.2.3.4.5:80"))
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
    fun `bracketed IPv6 loopback and unspecified parse`() {
        val loopback = parseHostPort("[::1]:9090")
        assertNotNull(loopback)
        assertEquals(9090, loopback!!.port)
        assertTrue(loopback.address is Inet6Address)

        val unspecified = parseHostPort("[::]:53")
        assertNotNull(unspecified)
        assertEquals(53, unspecified!!.port)
        assertEquals("0:0:0:0:0:0:0:0", unspecified.address.hostAddress)
    }

    @Test
    fun `bracketed IPv6 with zone id or embedded IPv4 parses`() {
        val zoned = parseHostPort("[fe80::1%wlan0]:5353")
        assertNotNull(zoned)
        assertEquals(5353, zoned!!.port)
        assertTrue(zoned.address is Inet6Address)

        val mapped = parseHostPort("[2001:db8::192.168.1.10]:8443")
        assertNotNull(mapped)
        assertEquals(8443, mapped!!.port)
        assertTrue(mapped.address is Inet6Address)
    }

    @Test
    fun `malformed IPv6 literals are rejected`() {
        assertNull(parseHostPort("[2001::db8::1]:443"))
        assertNull(parseHostPort("[2001:db8:::1]:443"))
        assertNull(parseHostPort("[2001:db8:1:2:3:4:5:6:7]:443"))
        assertNull(parseHostPort("[2001:db8:1:2:3:4:5]:443"))
        assertNull(parseHostPort("[fe80::1%]:443"))
        assertNull(parseHostPort("[%wlan0]:443"))
        assertNull(parseHostPort("[2001:db8::1ffff]:443"))
        assertNull(parseHostPort("[2001:db8::gggg]:443"))
    }

    @Test
    fun `endpoint without a port is rejected`() {
        assertNull(parseHostPort("192.168.1.10"))
        assertNull(parseHostPort("example.com"))
        assertNull(parseHostPort("[2001:db8::1]"))
    }

    @Test
    fun `bare IPv6 literal without a port is rejected instead of being mangled`() {
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
    fun `parsed address is a socket address and resolveConnectionOwnerUid handles null manager`() {
        val parsed: InetSocketAddress? = parseHostPort("127.0.0.1:1080")
        assertNotNull(parsed)
        assertEquals(1080, parsed!!.port)
        assertEquals(0, resolveConnectionOwnerUid(null, 6, "127.0.0.1:1080", "1.1.1.1:443"))
    }
}
