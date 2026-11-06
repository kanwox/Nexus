package com.github.mihomo.android.data

import com.github.mihomo.android.ui.screens.formatHostPort
import com.github.mihomo.android.ui.screens.matchRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestAndRuleMatcherTest {

    @Test
    fun `SpeedTestState maps native sentinel values accurately`() {
        assertEquals(SpeedTestState.Untested, SpeedTestState.fromNativeDelay(0))
        assertEquals(SpeedTestState.Untested, SpeedTestState.fromNativeDelay(-1))
        assertEquals(SpeedTestState.Timeout, SpeedTestState.fromNativeDelay(-2))
        assertEquals(SpeedTestState.Timeout, SpeedTestState.fromNativeDelay(65535))
        assertEquals(SpeedTestState.Success(88), SpeedTestState.fromNativeDelay(88))
    }

    @Test
    fun `matchRule tracks skipped dynamic rules and matches ports and host-port inputs`() {
        val rules = listOf(
            ParsedRule("RULE-SET", "ads", "REJECT"),
            ParsedRule("GEOIP", "CN", "DIRECT"),
            ParsedRule("PROCESS-NAME", "chrome.exe", "Proxy"),
            ParsedRule("DST-PORT", "80,443,8000-8080", "PortGroup"),
            ParsedRule("DOMAIN-SUFFIX", "google.com", "GoogleGroup"),
            ParsedRule("IP-CIDR6", "2001:db8::/32", "Ipv6Group"),
            ParsedRule("MATCH", "", "FinalGroup")
        )

        val domainMatch = matchRule("www.google.com", rules)
        assertNotNull(domainMatch)
        assertEquals("GoogleGroup", domainMatch!!.rule.target)
        assertEquals(4, domainMatch.index)
        assertEquals(3, domainMatch.skippedDynamicRules)

        val hostPortMatch = matchRule("example.org:8042", rules)
        assertNotNull(hostPortMatch)
        assertEquals("PortGroup", hostPortMatch!!.rule.target)
        assertEquals(3, hostPortMatch.index)
        assertEquals(3, hostPortMatch.skippedDynamicRules)

        val ipv6PortMatch = matchRule("[2001:db8::1]:443", rules)
        assertNotNull(ipv6PortMatch)
        assertEquals("PortGroup", ipv6PortMatch!!.rule.target)

        val ipv6Match = matchRule("2001:db8::1", rules)
        assertNotNull(ipv6Match)
        assertEquals("Ipv6Group", ipv6Match!!.rule.target)

        val purePortMatch = matchRule("443", rules)
        assertNotNull(purePortMatch)
        assertEquals("PortGroup", purePortMatch!!.rule.target)

        assertNull(matchRule("   ", rules))
    }

    @Test
    fun `formatHostPort brackets IPv6 addresses`() {
        assertEquals("1.2.3.4:443", formatHostPort("1.2.3.4", 443))
        assertEquals("[2001:db8::1]:443", formatHostPort("2001:db8::1", 443))
        assertEquals("[2001:db8::1]:443", formatHostPort("[2001:db8::1]", 443))
        assertTrue(formatHostPort("", 443).isEmpty())
    }
}
