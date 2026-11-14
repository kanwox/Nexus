package com.github.mihomo.android.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MihomoApiTest {

    private val servers = mutableListOf<HttpServer>()

    @Before
    fun setUp() {
        ApiController.configureForTest()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(0) } }
        servers.clear()
        ApiController.configureForTest()
    }

    private data class CapturedRequest(
        val method: String,
        val rawPath: String,
        val auth: String?,
        val body: String
    )

    private fun startServer(
        statusCode: Int,
        responseBody: String = ""
    ): Pair<HttpServer, MutableList<CapturedRequest>> {
        val captured = CopyOnWriteArrayList<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            val reqBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            captured.add(
                CapturedRequest(
                    method = exchange.requestMethod,
                    rawPath = exchange.requestURI.rawPath,
                    auth = exchange.requestHeaders.getFirst("Authorization"),
                    body = reqBody
                )
            )
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            if (statusCode == 204) {
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        servers.add(server)
        return server to captured
    }

    // ---- ApiController TOCTOU & rotation tests ---------------------------------------

    @Test
    fun `ApiController skips occupied ports, preserves own controller, and rotates on hijack`() {
        val occupiedPorts = mutableSetOf(9090, 9091)
        var ownControllerAlive = false
        ApiController.portProbeOverride = { port -> port !in occupiedPorts }
        ApiController.ownControllerVerifierOverride = { _, _ -> ownControllerAlive }

        assertTrue(ApiController.acquire())
        assertEquals(9092, ApiController.port)
        val firstSecret = ApiController.secret
        assertTrue(firstSecret.isNotEmpty())

        // Port 9092 is now bound by our own core: subsequent acquire keeps same port & secret.
        occupiedPorts.add(9092)
        ownControllerAlive = true
        assertTrue(ApiController.acquire())
        assertEquals(9092, ApiController.port)
        assertEquals(firstSecret, ApiController.secret)

        // Simulate another process hijacking 9092 while our controller is not responding there.
        ownControllerAlive = false
        assertTrue(ApiController.acquire())
        assertEquals(9093, ApiController.port)
        assertNotEquals(firstSecret, ApiController.secret)
    }

    @Test
    fun `ApiController reacquire excludes previous port and falls back to ephemeral port`() {
        ApiController.portProbeOverride = { port -> port == 9090 || port == 9095 }
        assertTrue(ApiController.acquire())
        assertEquals(9090, ApiController.port)

        assertTrue(ApiController.reacquire())
        assertEquals(9095, ApiController.port)

        // Exhaust entire fixed range and verify ephemeral fallback.
        ApiController.portProbeOverride = { false }
        ApiController.ephemeralPortOverride = { 41234 }
        assertTrue(ApiController.reacquire())
        assertEquals(41234, ApiController.port)

        // If ephemeral probe also returns null, acquire fails cleanly.
        ApiController.ephemeralPortOverride = { null }
        assertFalse(ApiController.reacquire())
        assertFalse(ApiController.isReady)
    }

    // ---- Timeouts & Write operations -------------------------------------------------

    @Test
    fun `providerUpdateClient relaxes read and call timeouts beyond default client`() {
        assertEquals(3_000, MihomoApi.client.readTimeoutMillis)
        assertEquals(5_000, MihomoApi.client.callTimeoutMillis)
        assertEquals(30_000, MihomoApi.providerUpdateClient.readTimeoutMillis)
        assertEquals(35_000, MihomoApi.providerUpdateClient.callTimeoutMillis)
    }

    @Test
    fun `write operations return Result success on HTTP 2xx and preserve paths and auth`() {
        val (server, captured) = startServer(statusCode = 204)
        ApiController.configureForTest(server.address.port, "secret-xyz")

        assertTrue(MihomoApi.selectProxyResult("Auto Group", "HK Node 1").isSuccess)
        assertTrue(MihomoApi.patchConfigsResult(buildJsonObject { put("mode", "rule") }).isSuccess)
        assertTrue(MihomoApi.updateProxyProviderResult("sub/provider").isSuccess)
        assertTrue(MihomoApi.updateRuleProviderResult("ads").isSuccess)
        assertTrue(MihomoApi.closeConnectionResult("conn-42").isSuccess)
        assertTrue(MihomoApi.closeAllConnectionsResult().isSuccess)

        assertEquals(6, captured.size)
        assertEquals("PUT", captured[0].method)
        assertEquals("/proxies/Auto%20Group", captured[0].rawPath)
        assertEquals("Bearer secret-xyz", captured[0].auth)
        assertTrue(captured[0].body.contains("HK Node 1"))

        assertEquals("PATCH", captured[1].method)
        assertEquals("/configs", captured[1].rawPath)

        assertEquals("PUT", captured[2].method)
        assertEquals("/providers/proxies/sub%2Fprovider", captured[2].rawPath)

        assertEquals("PUT", captured[3].method)
        assertEquals("/providers/rules/ads", captured[3].rawPath)

        assertEquals("DELETE", captured[4].method)
        assertEquals("/connections/conn-42", captured[4].rawPath)

        assertEquals("DELETE", captured[5].method)
        assertEquals("/connections", captured[5].rawPath)
    }

    @Test
    fun `write operations surface HTTP errors and missing controller instead of swallowing them`() {
        assertFalse(MihomoApi.patchMode("rule"))
        val notReadyResult = MihomoApi.patchModeResult("rule")
        assertTrue(notReadyResult.isFailure)
        assertTrue(notReadyResult.exceptionOrNull() is IllegalStateException)

        val (server, _) = startServer(statusCode = 502, responseBody = """{"message":"bad gateway"}""")
        ApiController.configureForTest(server.address.port, "secret-xyz")

        val providerFailure = MihomoApi.updateRuleProviderResult("reject")
        assertTrue(providerFailure.isFailure)
        assertTrue(providerFailure.exceptionOrNull()?.message?.contains("502") == true)
        assertFalse(MihomoApi.updateRuleProvider("reject"))
    }

    // ---- Defensive JSON parsing ------------------------------------------------------

    @Test
    fun `parseConnections handles missing fields, JsonNull, and mismatched JSON types`() {
        val raw = """
            {
              "uploadTotal": "1024",
              "downloadTotal": null,
              "connections": [
                null,
                "invalid-string-entry",
                {"id": "", "upload": 10},
                {
                  "id": "c-1",
                  "upload": "250",
                  "download": null,
                  "chains": ["DIRECT", null, 42, {"nested": true}],
                  "metadata": {
                    "host": null,
                    "destinationIP": "1.1.1.1",
                    "destinationPort": "443",
                    "sourceIP": "10.0.0.2",
                    "sourcePort": 999999,
                    "network": ""
                  },
                  "rule": null,
                  "rulePayload": 123,
                  "start": "not-an-iso-timestamp"
                },
                {
                  "id": "c-2",
                  "metadata": ["wrong-type-array"]
                }
              ]
            }
        """.trimIndent()

        val obj = Json.parseToJsonElement(raw) as JsonObject
        val snapshot = requireNotNull(MihomoApi.parseConnections(obj))
        assertEquals(1024L, snapshot.uploadTotal)
        assertEquals(0L, snapshot.downloadTotal)
        assertEquals(2, snapshot.connections.size)

        val first = snapshot.connections[0]
        assertEquals("c-1", first.id)
        assertEquals("1.1.1.1", first.host)
        assertEquals(443, first.destinationPort)
        assertEquals(0, first.sourcePort)
        assertEquals("TCP", first.network)
        assertEquals(250L, first.uploadBytes)
        assertEquals(0L, first.downloadBytes)
        assertEquals(0L, first.startMs)
        assertEquals(listOf("DIRECT", "42"), first.chains)

        val second = snapshot.connections[1]
        assertEquals("c-2", second.id)
        assertEquals("", second.host)
        assertEquals("TCP", second.network)
    }

    @Test
    fun `parseProxy ignores malformed history elements and handles wrong field types`() {
        val raw = """
            {
              "name": "ProxyA",
              "type": null,
              "udp": "not-a-boolean",
              "alive": true,
              "now": "HK-01",
              "history": [
                null,
                "bad",
                {"time": "invalid", "delay": "120"},
                {"time": "2026-10-25T12:34:56+08:00"}
              ]
            }
        """.trimIndent()
        val obj = Json.parseToJsonElement(raw) as JsonObject
        val proxy = requireNotNull(MihomoApi.parseProxy(obj))
        assertEquals("ProxyA", proxy.name)
        assertEquals("", proxy.type)
        assertFalse(proxy.udp)
        assertTrue(proxy.alive)
        assertEquals("HK-01", proxy.now)
        assertEquals(1, proxy.history.size)
        assertEquals(0L to 120, proxy.history[0])

        val noName = Json.parseToJsonElement("""{"type":"Selector"}""") as JsonObject
        assertNull(MihomoApi.parseProxy(noName))
    }
}
