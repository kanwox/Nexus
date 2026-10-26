package com.github.mihomo.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Picks and owns the loopback address pair for the core's external-controller. The values
 * are generated once per process and injected into the runtime config header, so every
 * config load (initial or reload) brings the REST API up with the same credentials.
 */
object ApiController {
    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var secret: String = ""
        private set

    val isReady: Boolean
        get() = port > 0 && secret.isNotEmpty()

    /** Probes a free loopback port once; later calls are no-ops so reloads keep the same API. */
    @Synchronized
    fun acquire(): Boolean {
        if (isReady) return true
        // A wider window makes a silent "controller disabled" state unlikely when other
        // local tools already hold one of the usual Clash ports.
        for (candidate in 9090..9199) {
            runCatching {
                ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).use { }
            }.onSuccess {
                port = candidate
                secret = UUID.randomUUID().toString().replace("-", "")
                return true
            }
        }
        return false
    }
}

/**
 * Percent-encodes a single REST path segment. [java.net.URLEncoder] writes `+` for spaces,
 * but `+` is only decoded as a space inside query strings; the controller's router treats
 * it literally, so any node/provider whose name contains a space (most real subscriptions)
 * failed to resolve. `%20` is unambiguous in both paths and queries.
 */
internal fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Structured access to the core's REST API (external-controller on loopback, Bearer secret). */
object MihomoApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    private fun requestBuilder(path: String): Request.Builder {
        if (!ApiController.isReady) error("external-controller is not configured")
        return Request.Builder()
            .url("http://127.0.0.1:${ApiController.port}$path")
            .header("Authorization", "Bearer ${ApiController.secret}")
    }

    /**
     * Safe GET: returns null whenever the controller is not configured (VPN never started)
     * or the call fails for any reason. Callers run inside coroutines with no exception
     * handler, so an endpoint must never throw — an unguarded error() here crashed the app
     * when the core status screen was opened before the first VPN start.
     */
    private fun getJson(path: String): JsonObject? {
        if (!ApiController.isReady) return null
        return runCatching {
            client.newCall(requestBuilder(path).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text) as? JsonObject
            }
        }.getOrNull()
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
        val obj = getJson("/version") ?: return null
        return runCatching { obj["version"]?.jsonPrimitive?.content }.getOrNull()
    }

    /** Full proxy snapshot: groups and proxies keyed by name. */
    fun proxies(): JsonObject? = getJson("/proxies")

    fun proxyDetail(name: String): ApiProxy? {
        val encoded = encodePathSegment(name)
        val obj = getJson("/proxies/$encoded") ?: return null
        return parseProxy(obj)
    }

    fun selectProxy(group: String, name: String): Boolean {
        val encoded = encodePathSegment(group)
        val body = buildJsonObject { put("name", name) }.toString().toRequestBody(jsonBody)
        return runCatching {
            client.newCall(requestBuilder("/proxies/$encoded").put(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun connections(): ConnectionsSnapshot? {
        val obj = getJson("/connections") ?: return null
        return parseConnections(obj)
    }

    fun closeConnection(id: String): Boolean {
        val encoded = encodePathSegment(id)
        return runCatching {
            client.newCall(requestBuilder("/connections/$encoded").delete().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun closeAllConnections(): Boolean {
        return runCatching {
            client.newCall(requestBuilder("/connections").delete().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun patchConfigs(body: JsonObject): Boolean {
        return runCatching {
            client.newCall(
                requestBuilder("/configs").patch(body.toString().toRequestBody(jsonBody)).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun patchMode(mode: String): Boolean = patchConfigs(buildJsonObject { put("mode", mode) })

    fun memoryInuse(): Long? {
        val obj = getJson("/memory") ?: return null
        return runCatching { obj["inuse"]?.jsonPrimitive?.longOrNull }.getOrNull()
    }

    fun proxyProviders(): List<ApiProvider> {
        val obj = getJson("/providers/proxies") ?: return emptyList()
        return parseProviders(obj) { provider -> provider["proxies"]?.jsonArray?.size ?: 0 }
    }

    fun updateProxyProvider(name: String): Boolean = updateProvider("proxies", name)

    fun ruleProviders(): List<ApiProvider> {
        val obj = getJson("/providers/rules") ?: return emptyList()
        return parseProviders(obj) { provider -> provider["ruleCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0 }
    }

    fun updateRuleProvider(name: String): Boolean = updateProvider("rules", name)

    private fun updateProvider(kind: String, name: String): Boolean {
        val encoded = encodePathSegment(name)
        return runCatching {
            client.newCall(requestBuilder("/providers/$kind/$encoded").put(byteArrayOf().toRequestBody(jsonBody)).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // ---- Parsing helpers -------------------------------------------------------------

    fun parseProxy(obj: JsonObject): ApiProxy? {
        return runCatching {
            val name = obj.str("name") ?: return null
            val history = obj["history"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val entryObj = entry as? JsonObject ?: return@mapNotNull null
                val delay = entryObj["delay"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val time = entryObj.str("time")?.let { parseIsoTime(it) } ?: 0L
                time to delay.toInt()
            }
            ApiProxy(
                name = name,
                type = obj.str("type") ?: "",
                udp = obj["udp"]?.jsonPrimitive?.booleanOrNull ?: false,
                alive = obj["alive"]?.jsonPrimitive?.booleanOrNull ?: false,
                now = obj.str("now") ?: "",
                history = history
            )
        }.getOrNull()
    }

    fun parseConnections(obj: JsonObject): ConnectionsSnapshot? {
        return runCatching {
            val list = obj["connections"]?.jsonArray.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val meta = item["metadata"] as? JsonObject ?: return@mapNotNull null
                val id = item.str("id") ?: return@mapNotNull null
                val host = meta.str("host").orEmpty()
                val destIp = meta.str("destinationIP").orEmpty()
                val displayHost = host.ifBlank { destIp }
                val chains = item["chains"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.content }
                ApiConnection(
                    id = id,
                    host = displayHost,
                    destinationIp = destIp,
                    destinationPort = meta.str("destinationPort")?.toIntOrNull() ?: 0,
                    network = (meta.str("network") ?: "tcp").uppercase(),
                    sourceIp = meta.str("sourceIP").orEmpty(),
                    sourcePort = meta.str("sourcePort")?.toIntOrNull() ?: 0,
                    rule = item.str("rule").orEmpty(),
                    rulePayload = item.str("rulePayload").orEmpty(),
                    chains = chains,
                    uploadBytes = item["upload"]?.jsonPrimitive?.longOrNull ?: 0L,
                    downloadBytes = item["download"]?.jsonPrimitive?.longOrNull ?: 0L,
                    startMs = item.str("start")?.let { parseIsoTime(it) } ?: 0L
                )
            }
            ConnectionsSnapshot(
                connections = list,
                uploadTotal = obj["uploadTotal"]?.jsonPrimitive?.longOrNull ?: 0L,
                downloadTotal = obj["downloadTotal"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }.getOrNull()
    }

    private fun parseProviders(obj: JsonObject, countOf: (JsonObject) -> Int): List<ApiProvider> {
        return runCatching {
            val providers = obj["providers"] as? JsonObject ?: return emptyList()
            providers.entries.mapNotNull { (_, value) ->
                val provider = value as? JsonObject ?: return@mapNotNull null
                val name = provider.str("name") ?: return@mapNotNull null
                // "Compatible" is the synthetic provider the core creates for inline proxies.
                val vehicle = provider.str("vehicleType") ?: ""
                if (vehicle == "Compatible") return@mapNotNull null
                ApiProvider(
                    name = name,
                    vehicleType = vehicle,
                    updatedAtMs = provider.str("updatedAt")?.let { parseIsoTime(it) } ?: 0L,
                    itemCount = countOf(provider)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] ?: return null
        if (value is kotlinx.serialization.json.JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }

    /** Parses the core's ISO-8601 timestamps ("2026-10-25T12:34:56.789+08:00") to epoch ms. */
    private fun parseIsoTime(raw: String): Long {
        return runCatching {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.LocalDateTime.parse(raw.substringBefore('.'))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }
}
