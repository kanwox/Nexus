package com.github.mihomo.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the `isActive` regression in [RequestRepository].
 *
 * The insert path excluded REJECT rules and REJECT outbounds, but the update path set
 * `isActive = true` unconditionally — so a rejected connection flipped back to "active"
 * on the next poll, and the `!existing.isActive` trigger re-opened records that had just
 * been closed.
 */
class RequestRepositoryIsActiveTest {

    @Before
    fun setUp() {
        ThrottledPublisher.isThrottlingEnabled = false
        RequestRepository.clearAll()
    }

    private fun conn(
        id: String,
        rule: String = "DomainSuffix",
        chains: List<String> = listOf("🇭🇰 香港 01", "🔰 节点选择"),
        upload: Long = 100L,
        download: Long = 200L
    ) = MihomoApi.ApiConnection(
        id = id,
        host = "www.example.com",
        destinationIp = "1.2.3.4",
        destinationPort = 443,
        network = "TCP",
        sourceIp = "172.19.0.1",
        sourcePort = 54321,
        rule = rule,
        rulePayload = "example.com",
        chains = chains,
        uploadBytes = upload,
        downloadBytes = download,
        startMs = System.currentTimeMillis()
    )

    @Test
    fun `a rejected connection is not active on insert`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", rule = "Reject")),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertFalse(RequestRepository.recordsFlow.value.first().isActive)
    }

    @Test
    fun `a REJECT outbound is not active on insert`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", chains = listOf("REJECT"))),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertFalse(RequestRepository.recordsFlow.value.first().isActive)
    }

    @Test
    fun `a rejected connection stays inactive on update`() {
        // Insert a rejected connection.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", rule = "Reject")),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertFalse(RequestRepository.recordsFlow.value.first().isActive)

        // The very same connection comes back with new byte counters. The update path
        // used to set isActive = true here, which resurrected the rejected record.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", rule = "Reject", upload = 150L, download = 250L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertFalse(
            "a rejected connection must not be reactivated by the update path",
            RequestRepository.recordsFlow.value.first().isActive
        )
    }

    @Test
    fun `a connection that turns rejected is deactivated on update`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1")),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertTrue(RequestRepository.recordsFlow.value.first().isActive)

        // The rule changed to Reject: the record must not stay active.
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", rule = "Reject", upload = 150L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        assertFalse(RequestRepository.recordsFlow.value.first().isActive)
    }

    @Test
    fun `a normal connection stays active when its counters change`() {
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", upload = 10L, download = 20L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        RequestRepository.applySnapshot(
            MihomoApi.ConnectionsSnapshot(
                connections = listOf(conn("c1", upload = 30L, download = 40L)),
                uploadTotal = 0L,
                downloadTotal = 0L
            )
        )
        val record = RequestRepository.recordsFlow.value.first()
        assertTrue(record.isActive)
        assertTrue(record.uploadBytes == 30L)
        assertTrue(record.downloadBytes == 40L)
    }
}
