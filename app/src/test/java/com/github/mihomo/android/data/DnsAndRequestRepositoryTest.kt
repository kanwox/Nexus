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

    private fun conn(
        id: String,
        host: String,
        chains: List<String> = listOf("🇭🇰 香港 01", "🔰 节点选择"),
        upload: Long = 100L,
        download: Long = 200L,
        rule: String = "DomainSuffix",
        network: String = "TCP"
    ) = MihomoApi.ApiConnection(
        id = id,
        host = host,
        destinationIp = "1.2.3.4",
        destinationPort = 443,
        network = network,
        sourceIp = "172.19.0.1",
        sourcePort = 54321,
        rule = rule,
        rulePayload = "google.com",
        chains = chains,
        uploadBytes = upload,
        downloadBytes = download,
        startMs = System.currentTimeMillis()
    )

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
    fun `test RequestRepository maps structured connections with reversed chains`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", "www.google.com")),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )

        val records = RequestRepository.recordsFlow.value
        assertEquals(1, records.size)
        val r = records.first()
        assertEquals("www.google.com", r.host)
        assertEquals(443, r.destinationPort)
        assertEquals("TCP", r.network)
        assertEquals("c1", r.connectionId)
        assertEquals("DomainSuffix", r.rule)
        assertEquals("google.com", r.rulePayload)
        // API chains run [exitNode, ..., entryGroup]: the direct outbound is the exit node.
        assertEquals("🇭🇰 香港 01", r.outbound)
        assertEquals(listOf("🔰 节点选择", "🇭🇰 香港 01"), r.chain)
        assertEquals(100L, r.uploadBytes)
        assertEquals(200L, r.downloadBytes)
        assertTrue(r.isActive)
    }

    @Test
    fun `test RequestRepository closes connections missing from the snapshot`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", "api.github.com")),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertTrue(RequestRepository.recordsFlow.value.first().isActive)

        // Empty snapshot: the connection disappeared, so the record is closed.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(connections = emptyList(), uploadTotal = 0L, downloadTotal = 0L)
        )
        val closed = RequestRepository.recordsFlow.value.first()
        assertFalse(closed.isActive)
        assertTrue(closed.durationMs >= 0L)
    }

    @Test
    fun `test RequestRepository updates byte counters in place`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", "www.google.com", upload = 10L, download = 20L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", "www.google.com", upload = 30L, download = 40L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )

        val records = RequestRepository.recordsFlow.value
        assertEquals(1, records.size)
        assertEquals(30L, records.first().uploadBytes)
        assertEquals(40L, records.first().downloadBytes)
    }

    @Test
    fun `test RequestRepository ranks hosts by traffic`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(
                    conn("c1", "a.example.com", upload = 100L, download = 900L),
                    conn("c2", "a.example.com", upload = 500L, download = 500L),
                    conn("c3", "b.example.com", upload = 10L, download = 10L)
                ),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )

        val ranking = RequestRepository.topHostsByTraffic(2)
        assertEquals(2, ranking.size)
        assertEquals("a.example.com", ranking.first().first)
        assertEquals(600L, ranking.first().second)
        assertEquals(1400L, ranking.first().third)
    }

    @Test
    fun `test LogRepository raw logcat still dispatches DNS lines`() {
        val rawJson = """{"level":"info","message":"[DNS] www.apple.com --> 17.253.144.10","time":"2026-10-26T10:00:00Z"}"""
        LogRepository.addRawLogcat(rawJson)
        assertEquals("17.253.144.10", DnsRepository.findIpForDomain("www.apple.com"))
    }
}
