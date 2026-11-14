package com.github.mihomo.android.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Picks and owns the loopback address pair for the core's external-controller. The values
 * are generated once per process and injected into the runtime config header, so every
 * config load (initial or reload) brings the REST API up with the same credentials.
 *
 * Because the Go core binds the port inside `Bridge.nativeLoad` after config synthesis,
 * a narrow TOCTOU window exists between probing a port and Go listening on it. [acquire]
 * therefore avoids false-positive `TIME_WAIT` probes (`reuseAddress = false`), verifies an
 * already-selected port against the running controller on subsequent calls, rotates to a
 * fresh port if another process claimed it, and exposes [reacquire] for bind-failure retry.
 */
object ApiController {
    private const val TAG = "ApiController"
    private const val PORT_RANGE_START = 9090
    private const val PORT_RANGE_END = 9199
    private const val PROBE_TIMEOUT_MS = 300L
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var secret: String = ""
        private set

    val isReady: Boolean
        get() = port > 0 && secret.isNotEmpty()

    @Volatile
    internal var portProbeOverride: ((Int) -> Boolean)? = null

    @Volatile
    internal var ownControllerVerifierOverride: ((Int, String) -> Boolean)? = null

    @Volatile
    internal var ephemeralPortOverride: (() -> Int?)? = null

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Probes a free loopback port once; later calls verify the active port is still either free
     * or served by our own Mihomo controller, rotating automatically if another process stole it.
     */
    @Synchronized
    fun acquire(): Boolean {
        if (isReady) {
            val stillAvailable = isPortFree(port) || isOwnControllerListening(port, secret)
            if (stillAvailable) return true
            Log.w(TAG, "Controller port $port is occupied by another listener; acquiring a replacement")
            invalidateLocked()
        }
        return acquireFreshPort(excludedPort = -1)
    }

    /** Forces probing a fresh loopback port (e.g., when core startup failed to bind [port]). */
    @Synchronized
    fun reacquire(): Boolean {
        val previousPort = port
        invalidateLocked()
        return acquireFreshPort(excludedPort = previousPort)
    }

    /** Sets test controller credentials, or resets all test state/overrides when [testPort] <= 0. */
    @Synchronized
    internal fun configureForTest(testPort: Int = -1, testSecret: String = "") {
        if (testPort <= 0) {
            invalidateLocked()
            portProbeOverride = null
            ownControllerVerifierOverride = null
            ephemeralPortOverride = null
        } else {
            port = testPort
            secret = testSecret
        }
    }

    private fun invalidateLocked() {
        port = -1
        secret = ""
    }

    private fun acquireFreshPort(excludedPort: Int): Boolean {
        val candidate = findAvailablePort(excludedPort) ?: return false
        port = candidate
        secret = UUID.randomUUID().toString().replace("-", "")
        return true
    }

    private fun findAvailablePort(excludedPort: Int): Int? {
        for (candidate in PORT_RANGE_START..PORT_RANGE_END) {
            if (candidate != excludedPort && isPortFree(candidate)) {
                return candidate
            }
        }
        return probeEphemeralLoopbackPort()
    }

    private fun isPortFree(candidate: Int): Boolean {
        portProbeOverride?.let { return it(candidate) }
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 1)
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun probeEphemeralLoopbackPort(): Int? {
        ephemeralPortOverride?.let { return it() }
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
                socket.localPort.takeIf { it > 0 }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun isOwnControllerListening(targetPort: Int, targetSecret: String): Boolean {
        ownControllerVerifierOverride?.let { return it(targetPort, targetSecret) }
        if (targetPort !in MIN_PORT..MAX_PORT || targetSecret.isEmpty()) return false
        val url = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(targetPort)
            .addPathSegment("version")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $targetSecret")
            .build()
        return try {
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val element = Json.parseToJsonElement(response.body.string()) as? JsonObject
                element?.containsKey("version") == true
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Structured access to the core's REST API (external-controller on loopback, Bearer secret). */
object MihomoApi {
    private const val TAG = "MihomoApi"
    private const val MAX_ERROR_BODY_CHARS = 256
    private const val PROVIDER_READ_TIMEOUT_SECONDS = 30L
    private const val PROVIDER_CALL_TIMEOUT_SECONDS = 35L
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    private val json = Json { ignoreUnknownKeys = true }

    internal val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Dedicated client for provider updates (`PUT /providers/...`), sharing [client]'s dispatcher
     * and connection pool while relaxing read/call timeouts to 30s/35s because the core fetches
     * remote provider payloads synchronously during the HTTP request.
     */
    internal val providerUpdateClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(PROVIDER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROVIDER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A SECOND client dedicated to WebSocket connections. The REST client above keeps
     * fast timeouts, while a WebSocket is a long-lived read (`readTimeout(0)` + `pingInterval(20)`).
     */
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    /**
     * Builds an endpoint URL by adding each REST segment separately via [HttpUrl.Builder.addPathSegment],
     * guaranteeing spaces become `%20` (never `+`) and embedded `/` or `?`/`#` stay inside one segment.
     */
    internal fun buildEndpointUrl(vararg segments: String): HttpUrl {
        if (!ApiController.isReady) error("external-controller is not configured")
        val urlBuilder = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(ApiController.port)
        segments.forEach { urlBuilder.addPathSegment(it) }
        return urlBuilder.build()
    }

    private fun requestBuilder(vararg segments: String): Request.Builder =
        Request.Builder()
            .url(buildEndpointUrl(*segments))
            .header("Authorization", "Bearer ${ApiController.secret}")

    /**
     * Safe GET: returns null whenever the controller is not configured (VPN never started)
     * or the call fails for any reason.
     */
    private fun getJson(vararg segments: String): JsonObject? {
        if (!ApiController.isReady) return null
        return try {
            client.newCall(requestBuilder(*segments).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body.string()
                json.parseToJsonElement(text) as? JsonObject
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    // ---- Parsed models ---------------------------------------------------------------

    data class ApiProxy(
        val name: String,
        val type: String,
        val udp: Boolean,
        val alive: Boolean,
        val now: String,
        /** (timestampMs, delayMs) samples, oldest first; delay 0 means the probe failed. */
        val history: List<Pair<Long, Int>>
    )

    data class ApiConnection(
        val id: String,
        val host: String,
        val destinationIp: String,
        val destinationPort: Int,
        val network: String,
        val sourceIp: String,
        val sourcePort: Int,
        val rule: String,
        val rulePayload: String,
        val chains: List<String>,
        val uploadBytes: Long,
        val downloadBytes: Long,
        val startMs: Long
    )

    data class ConnectionsSnapshot(
        val connections: List<ApiConnection>,
        val uploadTotal: Long,
        val downloadTotal: Long
    )

    data class ApiProvider(
        val name: String,
        val vehicleType: String,
        val updatedAtMs: Long,
        val itemCount: Int
    )

    // ---- Endpoints -------------------------------------------------------------------

    fun version(): String? {
        val obj = getJson("version") ?: return null
        return obj.str("version")
    }

    /** Full proxy snapshot: groups and proxies keyed by name. */
    fun proxies(): JsonObject? = getJson("proxies")

    fun proxyDetail(name: String): ApiProxy? {
        val obj = getJson("proxies", name) ?: return null
        return parseProxy(obj)
    }

    fun selectProxyResult(group: String, name: String): Result<Unit> {
        val body = buildJsonObject { put("name", name) }.toString().toRequestBody(jsonBody)
        return executeWrite("selectProxy($group)") {
            requestBuilder("proxies", group).put(body).build()
        }
    }

    fun selectProxy(group: String, name: String): Boolean = selectProxyResult(group, name).isSuccess

    fun connections(): ConnectionsSnapshot? {
        val obj = getJson("connections") ?: return null
        return parseConnections(obj)
    }

    /**
     * Opens a WebSocket to `ws://127.0.0.1:<port>/connections`.
     * Returns null when the controller is not configured so callers stay on REST polling.
     */
    fun newConnectionsWebSocket(listener: WebSocketListener): WebSocket? {
        if (!ApiController.isReady) return null
        return try {
            val request = requestBuilder("connections").build()
            wsClient.newWebSocket(request, listener)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open connections WebSocket", e)
            null
        }
    }

    fun closeConnectionResult(id: String): Result<Unit> =
        executeWrite("closeConnection($id)") {
            requestBuilder("connections", id).delete().build()
        }

    fun closeConnection(id: String): Boolean = closeConnectionResult(id).isSuccess

    fun closeAllConnectionsResult(): Result<Unit> =
        executeWrite("closeAllConnections") {
            requestBuilder("connections").delete().build()
        }

    fun closeAllConnections(): Boolean = closeAllConnectionsResult().isSuccess

    fun patchConfigsResult(body: JsonObject): Result<Unit> =
        executeWrite("patchConfigs") {
            requestBuilder("configs").patch(body.toString().toRequestBody(jsonBody)).build()
        }

    fun patchConfigs(body: JsonObject): Boolean = patchConfigsResult(body).isSuccess

    fun patchModeResult(mode: String): Result<Unit> =
        patchConfigsResult(buildJsonObject { put("mode", mode) })

    fun patchMode(mode: String): Boolean = patchModeResult(mode).isSuccess

    fun memoryInuse(): Long? {
        val obj = getJson("memory") ?: return null
        return obj.long("inuse")
    }

    fun proxyProviders(): List<ApiProvider> {
        val obj = getJson("providers", "proxies") ?: return emptyList()
        return parseProviders(obj) { provider -> provider.arr("proxies")?.size ?: 0 }
    }

    fun updateProxyProviderResult(name: String): Result<Unit> =
        updateProviderResult("proxies", name)

    fun updateProxyProvider(name: String): Boolean = updateProxyProviderResult(name).isSuccess

    fun ruleProviders(): List<ApiProvider> {
        val obj = getJson("providers", "rules") ?: return emptyList()
        return parseProviders(obj) { provider -> provider.long("ruleCount")?.toInt() ?: 0 }
    }

    fun updateRuleProviderResult(name: String): Result<Unit> =
        updateProviderResult("rules", name)

    fun updateRuleProvider(name: String): Boolean = updateRuleProviderResult(name).isSuccess

    private fun updateProviderResult(kind: String, name: String): Result<Unit> =
        executeWrite("updateProvider($kind/$name)", providerUpdateClient) {
            requestBuilder("providers", kind, name)
                .put(byteArrayOf().toRequestBody(jsonBody))
                .build()
        }

    private fun executeWrite(
        action: String,
        httpClient: OkHttpClient = client,
        buildRequest: () -> Request
    ): Result<Unit> {
        if (!ApiController.isReady) {
            val err = IllegalStateException("external-controller is not configured")
            Log.w(TAG, "$action failed: external-controller is not configured")
            return Result.failure(err)
        }
        return try {
            httpClient.newCall(buildRequest()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val snippet = runCatching { response.body.string().trim().take(MAX_ERROR_BODY_CHARS) }
                        .getOrDefault("")
                    val detail = if (snippet.isNotEmpty()) "HTTP ${response.code}: $snippet" else "HTTP ${response.code}"
                    Log.w(TAG, "$action failed ($detail)")
                    LogRepository.addLog("warning", "API 操作 $action 失败 ($detail)")
                    Result.failure(IOException("$action failed ($detail)"))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "$action failed: ${e.message}", e)
            LogRepository.addLog("warning", "API 操作 $action 异常: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    // ---- Parsing helpers -------------------------------------------------------------

    fun parseProxy(obj: JsonObject): ApiProxy? {
        return try {
            val name = obj.str("name") ?: return null
            val history = obj.arr("history").orEmpty().mapNotNull { entry ->
                parseHistoryEntry(entry)
            }
            ApiProxy(
                name = name,
                type = obj.str("type").orEmpty(),
                udp = obj.bool("udp") ?: false,
                alive = obj.bool("alive") ?: false,
                now = obj.str("now").orEmpty(),
                history = history
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHistoryEntry(entry: JsonElement): Pair<Long, Int>? {
        val entryObj = entry as? JsonObject ?: return null
        val delay = entryObj.long("delay") ?: return null
        val time = entryObj.str("time")?.let(::parseIsoTime) ?: 0L
        return time to delay.toInt()
    }

    fun parseConnections(obj: JsonObject): ConnectionsSnapshot? {
        val list = obj.arr("connections").orEmpty().mapNotNull { element ->
            parseConnectionElement(element)
        }
        return ConnectionsSnapshot(
            connections = list,
            uploadTotal = obj.long("uploadTotal") ?: 0L,
            downloadTotal = obj.long("downloadTotal") ?: 0L
        )
    }

    private fun parseConnectionElement(element: JsonElement): ApiConnection? {
        val item = element as? JsonObject ?: return null
        val id = item.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val meta = item.obj("metadata")
        val host = meta?.str("host").orEmpty()
        val destIp = meta?.str("destinationIP").orEmpty()
        val displayHost = host.ifBlank { destIp }
        val chains = item.arr("chains").orEmpty().mapNotNull { it.asNonNullString() }
        val network = (meta?.str("network") ?: "tcp").ifBlank { "tcp" }.uppercase(Locale.ROOT)
        return ApiConnection(
            id = id,
            host = displayHost,
            destinationIp = destIp,
            destinationPort = meta?.port("destinationPort") ?: 0,
            network = network,
            sourceIp = meta?.str("sourceIP").orEmpty(),
            sourcePort = meta?.port("sourcePort") ?: 0,
            rule = item.str("rule").orEmpty(),
            rulePayload = item.str("rulePayload").orEmpty(),
            chains = chains,
            uploadBytes = item.long("upload") ?: 0L,
            downloadBytes = item.long("download") ?: 0L,
            startMs = item.str("start")?.let(::parseIsoTime) ?: 0L
        )
    }

    private fun parseProviders(obj: JsonObject, countOf: (JsonObject) -> Int): List<ApiProvider> {
        val providers = obj.obj("providers") ?: return emptyList()
        return providers.entries.mapNotNull { (_, value) ->
            parseProviderEntry(value, countOf)
        }
    }

    private fun parseProviderEntry(
        value: JsonElement,
        countOf: (JsonObject) -> Int
    ): ApiProvider? {
        val provider = value as? JsonObject ?: return null
        val name = provider.str("name") ?: return null
        // "Compatible" is the synthetic provider the core creates for inline proxies.
        val vehicle = provider.str("vehicleType").orEmpty()
        if (vehicle == "Compatible") return null
        return ApiProvider(
            name = name,
            vehicleType = vehicle,
            updatedAtMs = provider.str("updatedAt")?.let(::parseIsoTime) ?: 0L,
            itemCount = countOf(provider)
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.str(key: String): String? = this[key]?.asNonNullString()

    private fun JsonObject.long(key: String): Long? {
        val prim = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        return prim.longOrNull ?: prim.content.toLongOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val prim = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        return prim.booleanOrNull
    }

    private fun JsonObject.port(key: String): Int {
        val parsed = long(key) ?: return 0
        return if (parsed in MIN_PORT..MAX_PORT) parsed.toInt() else 0
    }

    private fun JsonElement.asNonNullString(): String? {
        val prim = this as? JsonPrimitive ?: return null
        return if (prim is JsonNull) null else prim.content
    }

    /** Parses the core's ISO-8601 timestamps ("2026-10-25T12:34:56.789+08:00") to epoch ms. */
    private fun parseIsoTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(raw.substringBefore('.'))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}
