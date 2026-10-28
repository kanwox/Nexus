package com.github.mihomo.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.core.bridge.LogcatInterface
import com.github.kr328.clash.core.bridge.TunInterface
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ClashCore {
    private const val TAG = "ClashCore"
    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val HEALTH_CHECK_TIMEOUT_MS = 8_000L
    private const val ASSETS_READY_TIMEOUT_MS = 120_000L

    /**
     * Serializes config loads.
     *
     * `load()` is reachable from three independent sources (the UI's reload path, the
     * subscription worker and the VPN service's start path). Two overlapping
     * `Bridge.nativeLoad` calls rebuild the Go core's config state concurrently and the
     * second `CompletableDeferred` can be completed by the *first* load's native callback,
     * which leaves the core and the caller disagreeing about which config is live.
     */
    private val loadLock = Mutex()

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
            if (cm == null) {
                Log.w(TAG, "No ConnectivityManager; keeping the previous DNS configuration")
                return@runCatching
            }
            // While the tunnel is up, `activeNetwork` IS the VPN interface, so asking it for
            // the DNS servers would tell the core that "the physical DNS server" is the
            // tunnel's own portal address (172.19.0.2) — a self-reference that breaks
            // resolution. Enumerate the physical networks instead and pick the first one
            // that is not a VPN transport.
            @Suppress("DEPRECATION")
            val physical = cm.allNetworks.firstOrNull { net ->
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull()
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            // Fall back to the active network when no physical one could be identified
            // (e.g. a device that only exposes the VPN transport).
            val linkProps = cm.getLinkProperties(physical ?: cm.activeNetwork)
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
        // Bridge.init() loads the native libraries and runs nativeInit; either can throw
        // (e.g. libclash.so is missing on an unsupported ABI). The flag is only set once
        // every risky step succeeded, so a later call can retry instead of silently
        // operating against a core that never came up.
        Bridge.init(context)
        runCatching {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    com.github.mihomo.android.data.LogRepository.addRawLogcat(jsonPayload)
                }
            })
        }.onFailure {
            // Losing the logcat stream is not fatal — the core still works — but it must not
            // leave the core in a half-initialized state.
            Log.w(TAG, "Failed to subscribe to the native logcat stream", it)
        }
        initialized = true
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

    suspend fun load(configFile: File, profileId: String? = null): Result<Unit> = loadLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                // The geo databases are extracted off the main thread at init; the core reads them
                // while parsing the config, so wait for the extraction to finish first.
                // The timeout guards against the extraction thread never running (it would
                // otherwise hang this call forever, holding the load lock).
                try {
                    withTimeout(ASSETS_READY_TIMEOUT_MS.milliseconds) { Bridge.assetsReady() }
                } catch (e: TimeoutCancellationException) {
                    throw ClashException(
                        "Timed out waiting for the geo database assets to be extracted",
                        e
                    )
                } catch (e: ClashException) {
                    throw e
                } catch (e: Throwable) {
                    throw ClashException("Failed to prepare the geo database assets", e)
                }
                val deferred = CompletableDeferred<Unit>()
                val configDir = if (configFile.isDirectory) configFile.absolutePath else (configFile.parentFile?.absolutePath ?: configFile.absolutePath)
                Log.i(TAG, "Loading core config from directory: $configDir (target file: ${configFile.name})")
                try {
                    Bridge.nativeLoad(deferred, configDir)
                } catch (e: Throwable) {
                    throw ClashException("Failed to load the configuration from $configDir", e)
                }
                try {
                    withTimeout(LOAD_TIMEOUT_MS.milliseconds) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    throw ClashException("Timed out loading the configuration from $configDir", e)
                } catch (e: ClashException) {
                    throw e
                } catch (e: Throwable) {
                    throw ClashException("Failed to load the configuration from $configDir", e)
                }
                isCoreLoaded = true
                if (profileId != null) {
                    loadedProfileId = profileId
                }
                Result.success(Unit)
            } catch (e: Exception) {
                // A cancelled coroutine must NOT be reported as an ordinary failure: the
                // caller (e.g. a stop request during the start path) relies on the
                // cancellation propagating instead of being turned into a Result.
                // ensureActive() rethrows it when the job really was cancelled, and
                // returns normally for every other exception (a timeout, for example).
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
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
        stack: String = "mips",
        // Defaults come from TunConfig so the address plan handed to the core can never
        // drift away from the one the VPN service assigns to the interface.
        gateway: String = TunConfig.GATEWAY,
        portal: String = TunConfig.PORTAL,
        dns: String = TunConfig.DNS,
        vpnService: VpnService
    ) {
        val effectiveStack = if (stack.equals("gvisor", ignoreCase = true)) "gvisor" else "mips"
        Log.i(TAG, "Starting TUN with fd=$fd, stack=$effectiveStack (configured=$stack)")
        val cm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } else null

        Bridge.nativeStartTun(fd, effectiveStack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                vpnService.protect(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
                    try {
                        // Shared parser: see parseHostPort in TunConfig.kt.
                        val srcAddr = parseHostPort(source)
                        val dstAddr = parseHostPort(target)
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
                    }.onFailure { error ->
                        // The native call failed outright, so no callback will ever complete
                        // this deferred: release the waiters with a failure instead of
                        // leaving them blocked until their own timeout.
                        completeSafely(
                            deferred,
                            ClashException("Failed to start the health check for group '$groupName'", error)
                        )
                    }
                }
            }
        }!!
    }

    /**
     * Completes a deferred that native code also holds a reference to.
     *
     * The native side completes the deferred from a JNI callback thread. If the deferred
     * was already completed (or cancelled) in the meantime, the completion must not be
     * allowed to blow up on the *native* thread, which would take the whole process down.
     * Swallowing it here makes a late/duplicate completion a no-op, which is exactly the
     * semantics we need: the deferred must never be cancelled while native code still
     * holds it, only dropped from the map.
     *
     * @param error the failure to report, or null to complete normally.
     */
    private fun completeSafely(deferred: CompletableDeferred<Unit>, error: Throwable? = null) {
        try {
            if (error != null) {
                deferred.completeExceptionally(error)
            } else {
                deferred.complete(Unit)
            }
        } catch (_: IllegalStateException) {
            // Already completed or cancelled: nothing left to do.
        }
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
            // Only drop the map entry once the check has actually finished. Removing it
            // unconditionally meant that a waiter which timed out first dropped the entry
            // while the native check was still running, so the next caller started a
            // SECOND concurrent native health check on the same group.
            //
            // The deferred is deliberately NOT cancelled: native code still holds it and
            // will complete it later. Cancelling it would make that late completion throw
            // on the JNI thread (see completeSafely).
            if (deferred.isCompleted) {
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
