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
        RequestRepository.resetTransportForTest()
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
        val twitterRecord = DnsRepository.recordsFlow.value.first { it.domain == "twitter.com" }
        assertTrue(twitterRecord.isCacheHit)

        // 4. Resolution with upstream server
        DnsRepository.parseFromLog("[DNS] dns.google --> [8.8.8.8 8.8.4.4] from 223.5.5.5")
        assertEquals("8.8.8.8, 8.8.4.4", DnsRepository.findIpForDomain("dns.google"))
        val googleDnsRecord = DnsRepository.recordsFlow.value.first { it.domain == "dns.google" }
        assertFalse(googleDnsRecord.isCacheHit)
        assertEquals("223.5.5.5", googleDnsRecord.server)

        // 5. Mihomo format with comma before upstream protocol server
        DnsRepository.parseFromLog("[DNS] example.com --> [93.184.216.34], from udp://8.8.8.8:53")
        assertEquals("93.184.216.34", DnsRepository.findIpForDomain("example.com"))

        // 6. Pending resolve updated by result (does not pollute domainToIpMap with "Resolving...")
        DnsRepository.parseFromLog("[DNS] resolve cloudflare.com AAAA from udp://223.5.5.5:53")
        assertNull(DnsRepository.findIpForDomain("cloudflare.com"))
        val pendingCf = DnsRepository.recordsFlow.value.first { it.domain == "cloudflare.com" }
        assertTrue(pendingCf.isResolving)
        assertEquals("AAAA", pendingCf.type)
        DnsRepository.parseFromLog("[DNS] cloudflare.com --> 104.16.132.229, 15ms")
        assertEquals("104.16.132.229", DnsRepository.findIpForDomain("cloudflare.com"))
        val resolvedCf = DnsRepository.recordsFlow.value.first { it.domain == "cloudflare.com" }
        assertFalse(resolvedCf.isResolving)
        assertFalse(resolvedCf.isCacheHit)
        assertEquals(15, resolvedCf.latencyMs)
        assertEquals("AAAA", resolvedCf.type)
    }

    @Test
    fun `test DnsRepository collapses multi-whitespace bracketed IPs`() {
        DnsRepository.parseFromLog("[DNS] multi.google --> [8.8.8.8   8.8.4.4\t1.1.1.1] from 223.5.5.5")
        assertEquals("8.8.8.8, 8.8.4.4, 1.1.1.1", DnsRepository.findIpForDomain("multi.google"))
        val record = DnsRepository.recordsFlow.value.first { it.domain == "multi.google" }
        assertEquals("223.5.5.5", record.server)
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

    @Test
    fun `test RequestRepository evicts closed connections before active long-lived connections`() {
        // Fill 500 connections: c0 is a long-lived connection, c1..c499 are short-lived.
        val initialConns = (0 until RequestRepository.MAX_RECORDS).map { i ->
            conn("c$i", "host$i.example.com", upload = 100L + i)
        }
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = initialConns,
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertEquals(RequestRepository.MAX_RECORDS, RequestRepository.recordsFlow.value.size)

        // Next snapshot: c1..c499 have closed (disappeared), c0 is still active, and c500 arrives.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(
                    conn("c0", "host0.example.com", upload = 5000L),
                    conn("c500", "new.example.com", upload = 50L)
                ),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )

        val records = RequestRepository.recordsFlow.value
        assertEquals(RequestRepository.MAX_RECORDS, records.size)
        val c0Record = records.firstOrNull { it.connectionId == "c0" }
        val nonNullC0 = requireNotNull(c0Record) { "Active long-lived connection c0 must not be evicted" }
        assertTrue(nonNullC0.isActive)
        assertEquals(5000L, nonNullC0.uploadBytes)
        assertNotNull("New connection c500 must be inserted", records.firstOrNull { it.connectionId == "c500" })
        assertNull(
            "Oldest closed connection c1 should be evicted first",
            records.firstOrNull { it.connectionId == "c1" }
        )
    }

    @Test
    fun `test RequestRepository resolves UID outside lock and caches by UID without blocking topHostsByTraffic`() {
        val resolverEntered = java.util.concurrent.CountDownLatch(1)
        val allowResolverReturn = java.util.concurrent.CountDownLatch(1)
        val resolverCalls = java.util.concurrent.atomic.AtomicInteger(0)

        // Seed an existing record so topHostsByTraffic has data to aggregate.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("seed", "seed.example.com", upload = 100L, download = 200L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )

        RequestRepository.setUidResolverForTest { _, _, _ ->
            if (resolverCalls.incrementAndGet() == 1) {
                resolverEntered.countDown()
                allowResolverReturn.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            10042
        }

        val writerThread = Thread {
            RequestRepository.applySnapshot(
                MihomoApi.ConnectionsSnapshot(
                    connections = listOf(
                        conn("c-uid-1", "app1.example.com"),
                        conn("c-uid-2", "app2.example.com")
                    ),
                    uploadTotal = 0L,
                    downloadTotal = 0L
                )
            )
        }
        writerThread.start()

        assertTrue("UID resolver should be entered", resolverEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // While applySnapshot is blocked inside uidResolver, topHostsByTraffic must return lock-free!
        val top = RequestRepository.topHostsByTraffic(5)
        assertEquals(1, top.size)
        assertEquals("seed.example.com", top.first().first)

        allowResolverReturn.countDown()
        writerThread.join(5000)

        val records = RequestRepository.recordsFlow.value
        val r1 = records.first { it.connectionId == "c-uid-1" }
        val r2 = records.first { it.connectionId == "c-uid-2" }
        assertEquals("UID 10042", r1.sourceApp)
        assertEquals("UID 10042", r2.sourceApp)
    }
}
