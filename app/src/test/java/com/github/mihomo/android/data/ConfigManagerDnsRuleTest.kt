package com.github.mihomo.android.data

import com.github.mihomo.android.core.TunConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the plaintext-DNS reject rule.
 *
 * The rule is a Mihomo "logical rule" (AND/OR/NOT over sub-rules), and the core
 * REJECTS the whole config when it meets a rule type it does not implement. Two things
 * are therefore pinned down here:
 *  - the rule only uses rule types the core actually supports (`DST-IP` does NOT exist —
 *    Mihomo's rule parser fails with "unsupported rule type DST-IP", which made the
 *    entire config unloadable);
 *  - it covers UDP **and** TCP, so DNS over TCP/53 cannot bypass it.
 */
class ConfigManagerDnsRuleTest {

    /** The rule types Mihomo's `rules.ParseRule` implements (upstream `rules/parser.go`). */
    private val supportedRuleTypes = setOf(
        "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD",
        "GEOSITE", "GEOIP", "SRC-GEOIP", "IP-ASN", "SRC-IP-ASN", "IP-CIDR", "IP-CIDR6",
        "SRC-IP-CIDR", "IP-SUFFIX", "SRC-IP-SUFFIX", "SRC-PORT", "DST-PORT", "IN-PORT",
        "DSCP", "PROCESS-NAME", "PROCESS-PATH", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX",
        "PROCESS-NAME-WILDCARD", "PROCESS-PATH-WILDCARD", "NETWORK", "UID", "IN-TYPE",
        "IN-USER", "IN-NAME", "REMATCH-NAME", "SUB-RULE", "AND", "OR", "NOT", "RULE-SET", "MATCH"
    )

    private val rule = ConfigManager.buildDnsRejectRule()

    @Test
    fun `the rule does not use a rule type the core does not implement`() {
        // DST-IP never existed in Mihomo's rule parser. Using it made the whole config
        // fail to load with "unsupported rule type DST-IP".
        assertFalse("DST-IP is not a Mihomo rule type", rule.contains("DST-IP"))
    }

    @Test
    fun `every rule type used by the rule is supported by the core`() {
        val used = Regex("[A-Z][A-Z-]+(?=,)").findAll(rule).map { it.value }.toSet()
        for (type in used) {
            assertTrue(
                "rule type '$type' is not implemented by Mihomo (rule: $rule)",
                type in supportedRuleTypes
            )
        }
    }

    @Test
    fun `the rule covers both UDP and TCP`() {
        assertTrue("DNS over UDP must be blocked", rule.contains("NETWORK,UDP"))
        assertTrue("DNS over TCP must be blocked too", rule.contains("NETWORK,TCP"))
    }

    @Test
    fun `the rule targets the DNS port`() {
        assertTrue(rule.contains("DST-PORT,53"))
    }

    @Test
    fun `the rule exempts the tunnel portal addresses from TunConfig`() {
        assertTrue(
            "the IPv4 portal from TunConfig must be exempt",
            rule.contains("${TunConfig.IPV4_PORTAL}/32")
        )
        assertTrue(
            "the IPv6 portal from TunConfig must be exempt",
            rule.contains("${TunConfig.IPV6_PORTAL}/128")
        )
    }

    @Test
    fun `the rule ends with the REJECT action`() {
        assertTrue(rule.endsWith("REJECT"))
    }

    @Test
    fun `the reject rule is the first entry of the rule list`() {
        val out = ConfigManager.sanitizeUserConfig(
            """
            rules:
              - MATCH,DIRECT
            """.trimIndent(),
            blockThirdPartyDns = true
        )
        val rules = (YamlSupport.create().load<Map<String, Any?>>(out)["rules"] as? List<*>).orEmpty()
            .map { it.toString() }
        assertEquals(2, rules.size)
        assertEquals(rule, rules.first())
    }

    @Test
    fun `the textual fallback still injects the reject rule`() {
        // A structurally broken config falls back to the text filter. The DNS reject
        // rule is a security control, so it must NOT be silently dropped there.
        val broken = "external-controller: x\n  : : :\nproxies: []\nrules:\n  - MATCH,DIRECT"
        val out = ConfigManager.sanitizeUserConfig(broken, blockThirdPartyDns = true)

        assertTrue("the DNS reject rule was dropped by the text fallback", out.contains("DST-PORT,53"))
        assertTrue(out.contains("NETWORK,UDP"))
        assertTrue(out.contains("NETWORK,TCP"))
        // It must be the FIRST rule, so it keeps priority over the subscription's own.
        val ruleIndex = out.indexOf("DST-PORT,53")
        val matchIndex = out.indexOf("MATCH,DIRECT")
        assertTrue("the reject rule must come first", ruleIndex in 0 until matchIndex)
    }

    @Test
    fun `the textual fallback creates a rules section when there is none`() {
        val broken = "external-controller: x\n  : : :\nproxies: []"
        val out = ConfigManager.sanitizeUserConfig(broken, blockThirdPartyDns = true)

        assertTrue(out.contains("rules:"))
        assertTrue(out.contains("DST-PORT,53"))
    }

    @Test
    fun `without the switch the text fallback adds no DNS rule`() {
        val broken = "external-controller: x\n  : : :\nproxies: []\nrules:\n  - MATCH,DIRECT"
        val out = ConfigManager.sanitizeUserConfig(broken, blockThirdPartyDns = false)

        assertFalse(out.contains("DST-PORT,53"))
    }
}
