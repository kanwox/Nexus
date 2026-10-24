package com.github.mihomo.android.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DnsAndRequestRepositoryTest {

    @Before
    fun setUp() {
        ThrottledPublisher.isThrottlingEnabled = false
        DnsRepository.clear()
        RequestRepository.clearAll()
        LogRepository.clear()
    }

    @Test
    fun `test DnsRepository parses real Mihomo DNS formats`() {
        // 1. Direct resolution
        DnsRepository.parseFromLog("[DNS] www.google.com --> 142.250.190.46")
        assertEquals("142.250.190.46", DnsRepository.findIpForDomain("www.google.com"))

        // 2. Fake-IP resolution
        DnsRepository.parseFromLog("[DNS] api.github.com --> 198.18.0.25")
        val fakeIp = DnsRepository.findIpForDomain("api.github.com")
        assertEquals("198.18.0.25", fakeIp)

        // 3. Cache hit resolution
        DnsRepository.parseFromLog("[DNS] cache hit twitter.com --> 198.18.0.30, expire at 2026-10-24 16:00:00")
        assertEquals("198.18.0.30", DnsRepository.findIpForDomain("twitter.com"))

        // 4. Resolution with upstream server
        DnsRepository.parseFromLog("[DNS] dns.google --> [8.8.8.8 8.8.4.4] from 223.5.5.5")
        assertEquals("8.8.8.8, 8.8.4.4", DnsRepository.findIpForDomain("dns.google"))

        // 5. Mihomo format with comma before upstream protocol server
        DnsRepository.parseFromLog("[DNS] example.com --> [93.184.216.34], from udp://8.8.8.8:53")
        assertEquals("93.184.216.34", DnsRepository.findIpForDomain("example.com"))

        // 6. Pending resolve updated by result
        DnsRepository.parseFromLog("[DNS] resolve cloudflare.com A from udp://223.5.5.5:53")
        assertEquals("Resolving...", DnsRepository.findIpForDomain("cloudflare.com"))
        DnsRepository.parseFromLog("[DNS] cloudflare.com --> 104.16.132.229, 15ms")
        assertEquals("104.16.132.229", DnsRepository.findIpForDomain("cloudflare.com"))
    }

    @Test
    fun `test RequestRepository parses complex Mihomo routing with unicode group names`() {
        val log1 = "[TCP] 172.19.0.1:54321(com.android.chrome) --> www.google.com:443 match DomainSuffix(google.com) using 🔰 节点选择[🇭🇰 香港 01]"
        RequestRepository.parseFromLog(log1)

        val records = RequestRepository.recordsFlow.value
        assertEquals(1, records.size)
        val r1 = records.first()
        assertEquals("www.google.com", r1.host)
        assertEquals(443, r1.destinationPort)
        assertEquals("TCP", r1.network)
        assertEquals("com.android.chrome", r1.packageName)
        assertEquals("DomainSuffix", r1.rule)
        assertEquals("google.com", r1.rulePayload)
        assertEquals("🔰 节点选择[🇭🇰 香港 01]", r1.outbound)
        assertEquals(listOf("🔰 节点选择", "🇭🇰 香港 01"), r1.chain)
        assertTrue(r1.isActive)
    }

    @Test
    fun `test RequestRepository parses doesn't match any rule format`() {
        val log = "[TCP] 172.19.0.1:54321 --> api.github.com:443 doesn't match any rule using DIRECT"
        RequestRepository.parseFromLog(log)

        val records = RequestRepository.recordsFlow.value
        assertEquals(1, records.size)
        val r = records.first()
        assertEquals("api.github.com", r.host)
        assertEquals("DIRECT", r.outbound)
        assertEquals(listOf("DIRECT"), r.chain)
    }

    @Test
    fun `test LogRepository raw logcat json dispatches to DNS and Request repositories`() {
        // CFA JSON payload
        val rawJson = """{"level":"info","message":"[TCP] 172.19.0.1:54321 --> www.apple.com:443 match DomainSuffix(apple.com) using DIRECT","time":"2026-10-24T15:30:00Z"}"""
        LogRepository.addRawLogcat(rawJson)

        val requests = RequestRepository.recordsFlow.value
        assertEquals(1, requests.size)
        assertEquals("www.apple.com", requests.first().host)
        assertEquals("DIRECT", requests.first().outbound)
    }

    @Test
    fun `test RequestRepository handles connection closed`() {
        val logOpen = "[TCP] 172.19.0.1:54321 --> api.openai.com:443 match DomainSuffix(openai.com) using 节点选择[US 01]"
        RequestRepository.parseFromLog(logOpen)
        assertTrue(RequestRepository.recordsFlow.value.first().isActive)

        val logClose = "[TCP] connection to api.openai.com:443 closed"
        RequestRepository.parseFromLog(logClose)
        assertFalse(RequestRepository.recordsFlow.value.first().isActive)
    }
}
