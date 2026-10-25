package com.github.mihomo.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.LogcatInterface
import com.github.kr328.clash.core.bridge.TunInterface
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object ClashCore {
    private const val TAG = "ClashCore"
    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val HEALTH_CHECK_TIMEOUT_MS = 8_000L

    private val activeGroupChecks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var loadedProfileId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var initialized = false

    @Volatile
    var isCoreLoaded = false
        private set

    fun notifyNetworkChanged(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(activeNet)
            // Both A and AAAA records are reported: on IPv6-only networks dropping the v6
            // servers used to force a fallback to hardcoded resolvers that may be unreachable.
            val dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            val dnsStr = if (dnsServers.isNotEmpty()) dnsServers.joinToString(",") else "223.5.5.5,119.29.29.29"
            Bridge.nativeNotifyDnsChanged(dnsStr)
            Bridge.nativeNotifyTimeZoneChanged(
                java.util.TimeZone.getDefault().id,
                java.util.TimeZone.getDefault().rawOffset / 1000
            )
            Log.i(TAG, "Notified native core DNS: $dnsStr")
        }.onFailure {
            Log.w(TAG, "Failed to notify DNS to native core", it)
        }
    }

    /** Idempotent; safe to call from any thread. Native loading and asset extraction are heavy. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        Bridge.init(context)
        initialized = true
        runCatching {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    com.github.mihomo.android.data.LogRepository.addRawLogcat(jsonPayload)
                }
            })
        }
        notifyNetworkChanged(context)
        Log.i(TAG, "Mihomo Core initialized. Version: ${getCoreVersion()}")
    }

    @Suppress("unused")
    suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
        init(context)
    }

    suspend fun reloadActiveConfig(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            init(context)
            val settings = com.github.mihomo.android.data.SettingsManager(context)
            val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(context)
            val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
            val configFile = com.github.mihomo.android.data.ConfigManager.prepareConfig(context, activeProfile?.file)
            val res = load(configFile, activeProfile?.id)
            if (res.isSuccess) {
                runCatching {
                    val allSaved = settings.getAllSelectedNodes()
                    allSaved.forEach { (gName, sNode) ->
                        patchSelector(gName, sNode)
                    }
                }
                notifyNetworkChanged(context)
                return@withContext true
            }
            false
        }.getOrDefault(false)
    }

    suspend fun ensureCoreLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = com.github.mihomo.android.data.SettingsManager(context)
        val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(context)
        val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
        if (isCoreLoaded && activeProfile?.id == loadedProfileId) {
            notifyNetworkChanged(context)
            return@withContext true
        }
        reloadActiveConfig(context)
    }

    fun getCoreVersion(): String {
        return try {
            Bridge.nativeCoreVersion()
        } catch (e: Throwable) {
            "Unknown (${e.message})"
        }
    }

    suspend fun load(configFile: File, profileId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // The geo databases are extracted off the main thread at init; the core reads them
            // while parsing the config, so wait for the extraction to finish first.
            Bridge.assetsReady()
            val deferred = CompletableDeferred<Unit>()
            val configDir = if (configFile.isDirectory) configFile.absolutePath else (configFile.parentFile?.absolutePath ?: configFile.absolutePath)
            Log.i(TAG, "Loading core config from directory: $configDir (target file: ${configFile.name})")
            Bridge.nativeLoad(deferred, configDir)
            withTimeout(LOAD_TIMEOUT_MS.milliseconds) { deferred.await() }
            isCoreLoaded = true
            if (profileId != null) {
                loadedProfileId = profileId
            }
            Unit
        }
    }

    fun setSuspended(suspended: Boolean) {
        if (!initialized) return
        runCatching {
            Bridge.nativeSuspend(suspended)
        }
    }

    fun startTun(
        fd: Int,
        stack: String = "mixed",
        gateway: String = "172.19.0.1/30,fdfe:dcba:9876::1/126",
        portal: String = "172.19.0.2,fdfe:dcba:9876::2",
        dns: String = "172.19.0.2,fdfe:dcba:9876::2",
        vpnService: VpnService
    ) {
        Log.i(TAG, "Starting TUN with fd=$fd, stack=$stack")
        val cm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } else null

        Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                vpnService.protect(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
                    try {
                        val srcAddr = parseSocketAddress(source)
                        val dstAddr = parseSocketAddress(target)
                        if (srcAddr != null && dstAddr != null) {
                            return cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
                        }
                    } catch (_: Throwable) {
                    }
                }
                return 0
            }
        })
    }

    private fun parseSocketAddress(endpoint: String): InetSocketAddress? {
        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == endpoint.lastIndex) return null
        val hostPart = endpoint.substring(0, lastColon).removeSurrounding("[", "]")
        val port = endpoint.substring(lastColon + 1).toIntOrNull() ?: return null
        return InetSocketAddress(hostPart, port)
    }

    fun stopTun() {
        Log.i(TAG, "Stopping TUN")
        runCatching { Bridge.nativeStopTun() }
    }

    fun queryTrafficNow(): Traffic {
        return runCatching { Bridge.nativeQueryTrafficNow() }.getOrDefault(0L)
    }

    fun queryTrafficTotal(): Traffic {
        return runCatching { Bridge.nativeQueryTrafficTotal() }.getOrDefault(0L)
    }

    @Suppress("unused")
    fun queryTunnelState(): TunnelState {
        return runCatching {
            val res = Bridge.nativeQueryTunnelState()
            json.decodeFromString<TunnelState>(res)
        }.getOrDefault(TunnelState("rule"))
    }

    fun queryGroupNames(excludeNotSelectable: Boolean = false): List<String> {
        return runCatching {
            val raw = Bridge.nativeQueryGroupNames(excludeNotSelectable)
            val array = json.decodeFromString<JsonArray>(raw)
            array.mapNotNull { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    fun queryGroup(name: String, sort: ProxySort = ProxySort.Default): ProxyGroup? {
        return runCatching {
            val raw = Bridge.nativeQueryGroup(name, sort.name)
            if (!raw.isNullOrEmpty()) {
                json.decodeFromString<ProxyGroup>(raw)
            } else null
        }.getOrNull()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return runCatching {
            Bridge.nativePatchSelector(selector, name)
        }.getOrDefault(false)
    }

    private fun getOrStartGroupHealthCheck(groupName: String): CompletableDeferred<Unit> {
        return activeGroupChecks.compute(groupName) { _, existing ->
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                CompletableDeferred<Unit>().also { deferred ->
                    runCatching {
                        Bridge.nativeHealthCheck(deferred, groupName)
                    }.onFailure {
                        deferred.complete(Unit)
                    }
                }
            }
        }!!
    }

    suspend fun healthCheckGroupNative(
        name: String,
        onProgress: ((String, Int) -> Unit)? = null
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val initialGroup = queryGroup(name)
        val initialDelays = initialGroup?.proxies?.associate { it.name to it.delay } ?: emptyMap()
        val deferred = getOrStartGroupHealthCheck(name)
        var pollJob: Job? = null
        try {
            if (onProgress != null) {
                val reported = mutableSetOf<String>()
                pollJob = launch {
                    while (isActive && !deferred.isCompleted) {
                        delay(120L.milliseconds)
                        val currentGroup = queryGroup(name)
                        val proxies = currentGroup?.proxies ?: emptyList()
                        proxies.forEach { p ->
                            if (!reported.contains(p.name)) {
                                val oldDelay = initialDelays[p.name]
                                val newDelay = p.delay
                                if (oldDelay != newDelay && newDelay in 1..65534) {
                                    reported.add(p.name)
                                    onProgress(p.name, newDelay)
                                }
                            }
                        }
                    }
                }
            }
            withTimeout(HEALTH_CHECK_TIMEOUT_MS.milliseconds) {
                deferred.await()
            }
        } catch (_: Throwable) {
        } finally {
            pollJob?.cancel()
            activeGroupChecks.remove(name, deferred)
            // The deferred is normally completed by the native callback; if the health check
            // timed out instead, drop the map entry here so a later check can start fresh
            // instead of waiting on a check nobody will ever complete.
            if (!deferred.isCompleted) {
                deferred.cancel()
                activeGroupChecks.remove(name, deferred)
            }
        }
        val group = queryGroup(name)
        val result = mutableMapOf<String, Int>()
        group?.proxies?.forEach { p ->
            val d = when {
                p.delay in 1..65534 -> p.delay
                p.name.equals("DIRECT", ignoreCase = true) -> 0
                else -> -2
            }
            result[p.name] = d
            onProgress?.invoke(p.name, d)
        }
        result
    }

    suspend fun healthCheck(name: String, onProgress: ((String, Int) -> Unit)? = null): Map<String, Int> =
        healthCheckGroupNative(name, onProgress)

    suspend fun testProxyDelayNative(proxyName: String, groupName: String? = null): Int = withContext(Dispatchers.IO) {
        if (proxyName.equals("DIRECT", ignoreCase = true)) return@withContext 0
        if (proxyName.equals("REJECT", ignoreCase = true)) return@withContext -2
        if (proxyName.equals("COMPATIBLE", ignoreCase = true)) return@withContext -2

        val allGroups = queryGroupNames()

        // Testing a group means health-checking the group itself.
        if (allGroups.contains(proxyName)) {
            val deferred = getOrStartGroupHealthCheck(proxyName)
            try {
                withTimeout(HEALTH_CHECK_TIMEOUT_MS.milliseconds) { deferred.await() }
            } catch (_: Throwable) {
            } finally {
                activeGroupChecks.remove(proxyName, deferred)
            }
            val subGroup = queryGroup(proxyName) ?: return@withContext -2
            val activeProxy = subGroup.proxies.find { it.name == subGroup.now }
            return@withContext activeProxy?.delay?.takeIf { it in 1..65534 }
                ?: subGroup.proxies.map { it.delay }.filter { it in 1..65534 }.minOrNull()
                ?: -2
        }

        val targetGroup = when {
            !groupName.isNullOrBlank() && allGroups.contains(groupName) -> groupName
            else -> allGroups.firstOrNull { g ->
                queryGroup(g)?.proxies?.any { it.name == proxyName } == true
            } ?: return@withContext -2
        }

        val initialDelay = queryGroup(targetGroup)?.proxies?.find { it.name == proxyName }?.delay ?: -1
        val deferred = getOrStartGroupHealthCheck(targetGroup)
        var result = -1
        try {
            // Poll for the target proxy's fresh delay so a single node reports before the
            // whole group finishes; the post-completion snapshot below covers the rest.
            withTimeout(HEALTH_CHECK_TIMEOUT_MS.milliseconds) {
                while (deferred.isActive) {
                    delay(150L.milliseconds)
                    val current = queryGroup(targetGroup)?.proxies?.find { it.name == proxyName }?.delay
                    if (current != null && current in 1..65534 && current != initialDelay) {
                        result = current
                        break
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            // Only remove completed checks: other waiters may still await a running one.
            if (deferred.isCompleted) {
                activeGroupChecks.remove(targetGroup, deferred)
            }
        }

        if (result !in 1..65534) {
            result = queryGroup(targetGroup)?.proxies?.find { it.name == proxyName }?.delay
                ?.takeIf { it in 1..65534 }
                ?: allGroups.firstNotNullOfOrNull { g ->
                    queryGroup(g)?.proxies?.find { it.name == proxyName }?.delay?.takeIf { it in 1..65534 }
                }
                ?: -2
        }
        result
    }

    @Suppress("unused")
    fun healthCheckAll() {
        runCatching { Bridge.nativeHealthCheckAll() }
    }

    fun reset() {
        isCoreLoaded = false
        loadedProfileId = null
        activeGroupChecks.clear()
        runCatching { Bridge.nativeReset() }
    }
}
