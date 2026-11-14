@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.core.bridge.LogcatInterface
import com.github.kr328.clash.core.bridge.TunInterface
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import com.github.mihomo.android.MihomoApplication
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.data.SettingsManager
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val CORE_TAG = "ClashCore"
internal const val DEFAULT_FALLBACK_DNS = "223.5.5.5,119.29.29.29"
internal const val HEALTH_CHECK_TIMEOUT_MS = 8_000L
internal const val HEALTH_CHECK_STALE_MS = 16_000L
private const val HEALTH_CHECK_POLL_INTERVAL_MS = 200L
private const val PROXY_DELAY_POLL_INTERVAL_MS = 150L
private const val LOAD_TIMEOUT_MS = 20_000L
private const val ASSETS_READY_TIMEOUT_MS = 120_000L
private const val MAX_VALID_DELAY_MS = 65534

private val TUN_IPV6_ADDRESS_BYTES = parseIpv6LiteralBytes(TunConfig.IPV6_ADDRESS)
private val TUN_IPV6_PORTAL_BYTES = parseIpv6LiteralBytes(TunConfig.IPV6_PORTAL)

private data class ActiveHealthCheck(
    val deferred: CompletableDeferred<Unit>,
    val startedAtMs: Long,
)

private data class InFlightLoad(
    val configDir: String,
    val deferred: CompletableDeferred<Unit>,
)

internal fun isTunnelAddress(address: String): Boolean {
    val normalized = address.trim()
    if (normalized.isEmpty()) return true
    if (normalized == TunConfig.IPV4_PORTAL || normalized == TunConfig.IPV4_ADDRESS) return true
    if (normalized.contains(':')) {
        val parsed = parseIpv6LiteralBytes(normalized)
        if (parsed != null &&
            (parsed.contentEquals(TUN_IPV6_PORTAL_BYTES) || parsed.contentEquals(TUN_IPV6_ADDRESS_BYTES))
        ) {
            return true
        }
        return normalized.equals(TunConfig.IPV6_PORTAL, ignoreCase = true) ||
            normalized.equals(TunConfig.IPV6_ADDRESS, ignoreCase = true)
    }
    return false
}

internal fun selectDnsServerString(
    rawAddresses: List<String>,
    previousDns: String?,
    fallbackDns: String = DEFAULT_FALLBACK_DNS,
): String {
    val sanitized = rawAddresses
        .map { it.trim() }
        .filter { !isTunnelAddress(it) }
        .distinct()
    return when {
        sanitized.isNotEmpty() -> sanitized.joinToString(",")
        !previousDns.isNullOrBlank() -> previousDns
        else -> fallbackDns
    }
}

internal fun isHealthCheckReusable(
    isCompleted: Boolean,
    startedAtMs: Long,
    nowMs: Long,
    staleThresholdMs: Long = HEALTH_CHECK_STALE_MS,
): Boolean = !isCompleted && (nowMs - startedAtMs) in 0 until staleThresholdMs

private fun createTunCallback(vpnService: VpnService, cm: ConnectivityManager?): TunInterface =
    object : TunInterface {
        override fun markSocket(fd: Int) {
            vpnService.protect(fd)
        }

        override fun querySocketUid(protocol: Int, source: String, target: String): Int =
            resolveConnectionOwnerUid(cm, protocol, source, target)
    }

private fun isNonVpnInternetNetwork(cm: ConnectivityManager, network: Network): Boolean {
    val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return false
    return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun resolveLinkPropertiesForNetwork(cm: ConnectivityManager, physical: Network?): LinkProperties? {
    if (physical != null) {
        return cm.getLinkProperties(physical)
    }
    val active = cm.activeNetwork ?: return null
    val activeCaps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return null
    return if (!activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        cm.getLinkProperties(active)
    } else {
        null
    }
}

private fun wrapClashFailure(message: String, error: Throwable): ClashException =
    error as? ClashException ?: ClashException(message, error)

private fun completeDeferredSafely(deferred: CompletableDeferred<Unit>, error: Throwable? = null) {
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

private suspend fun awaitAssetsExtractionReady() {
    val failure: ClashException? = try {
        val finished = withTimeoutOrNull(ASSETS_READY_TIMEOUT_MS.milliseconds) {
            Bridge.assetsReady()
            true
        }
        if (finished == null) {
            ClashException("Timed out waiting for the geo database assets to be extracted")
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        wrapClashFailure("Failed to prepare the geo database assets", e)
    }
    if (failure != null) throw failure
}

private suspend fun awaitPreviousLoadDeferred(deferred: CompletableDeferred<Unit>) {
    try {
        deferred.await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Previous native load failed; Go core is idle for the next load.
    }
}

private suspend fun awaitNativeLoadCompletion(configDir: String, deferred: CompletableDeferred<Unit>) {
    val failure: ClashException? = try {
        val finished = withTimeoutOrNull(LOAD_TIMEOUT_MS.milliseconds) {
            deferred.await()
            true
        }
        if (finished == null) {
            ClashException("Timed out loading the configuration from $configDir")
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        wrapClashFailure("Failed to load the configuration from $configDir", e)
    }
    if (failure != null) throw failure
}

private suspend fun pollGroupHealthProgress(
    groupName: String,
    deferred: CompletableDeferred<Unit>,
    initialDelays: Map<String, Int>,
    reported: MutableSet<String>,
    groupFetcher: (String) -> ProxyGroup?,
    onProgress: (String, Int) -> Unit,
) {
    while (currentCoroutineContext().isActive && !deferred.isCompleted) {
        delay(HEALTH_CHECK_POLL_INTERVAL_MS.milliseconds)
        val proxies = groupFetcher(groupName)?.proxies ?: emptyList()
        for (p in proxies) {
            val newDelay = p.delay
            if (newDelay in 1..MAX_VALID_DELAY_MS && initialDelays[p.name] != newDelay && reported.add(p.name)) {
                onProgress(p.name, newDelay)
            }
        }
        if (proxies.isNotEmpty() && reported.size >= proxies.size) {
            break
        }
    }
}

private fun buildGroupHealthResult(
    group: ProxyGroup?,
    reported: MutableSet<String>,
    onProgress: ((String, Int) -> Unit)?,
): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    group?.proxies?.forEach { p ->
        val d = when {
            p.delay in 1..MAX_VALID_DELAY_MS -> p.delay
            p.name.equals("DIRECT", ignoreCase = true) -> 0
            else -> -2
        }
        result[p.name] = d
        if (reported.add(p.name)) {
            onProgress?.invoke(p.name, d)
        }
    }
    return result
}

object ClashCore {
    private val loadLock = Mutex()
    private val configPipelineLock = Mutex()

    @Volatile
    private var inFlightLoad: InFlightLoad? = null

    private val activeGroupChecks = ConcurrentHashMap<String, ActiveHealthCheck>()

    @Volatile
    private var loadedProfileId: String? = null

    @Volatile
    private var trackedPhysicalNetwork: Network? = null

    @Volatile
    private var lastNotifiedDns: String? = null

    @Volatile
    private var lastNotifiedTzId: String? = null

    @Volatile
    private var lastNotifiedTzOffsetSec: Int? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var initialized = false

    @Volatile
    var isCoreLoaded = false
        private set

    fun updateTrackedPhysicalNetwork(network: Network?) {
        trackedPhysicalNetwork = network
    }

    private fun resolvePhysicalNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null && isNonVpnInternetNetwork(cm, active)) {
            trackedPhysicalNetwork = active
            return active
        }
        val tracked = trackedPhysicalNetwork
        if (tracked != null && isNonVpnInternetNetwork(cm, tracked)) {
            return tracked
        }
        return null
    }

    fun notifyNetworkChanged(context: Context, force: Boolean = false) {
        if (!ensureInitializedIfPossible(context)) return
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                Log.w(CORE_TAG, "No ConnectivityManager; keeping the previous DNS configuration")
                return@runCatching
            }
            val physical = resolvePhysicalNetwork(cm)
            val linkProps = resolveLinkPropertiesForNetwork(cm, physical)
            val rawDns = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            val dnsStr = selectDnsServerString(rawDns, lastNotifiedDns)
            if (force || dnsStr != lastNotifiedDns) {
                Bridge.nativeNotifyDnsChanged(dnsStr)
                lastNotifiedDns = dnsStr
                Log.i(CORE_TAG, "Notified native core DNS: $dnsStr")
            }
            val tz = TimeZone.getDefault()
            val tzId = tz.id
            val tzOffsetSec = tz.rawOffset / 1000
            if (force || tzId != lastNotifiedTzId || tzOffsetSec != lastNotifiedTzOffsetSec) {
                Bridge.nativeNotifyTimeZoneChanged(tzId, tzOffsetSec)
                lastNotifiedTzId = tzId
                lastNotifiedTzOffsetSec = tzOffsetSec
            }
        }.onFailure {
            Log.w(CORE_TAG, "Failed to notify DNS to native core", it)
        }
    }

    /** Idempotent; safe to call from any thread. Native loading and asset extraction are heavy. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        Bridge.init(context)
        runCatching {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    LogRepository.addRawLogcat(jsonPayload)
                }
            })
        }.onFailure {
            Log.w(CORE_TAG, "Failed to subscribe to the native logcat stream", it)
        }
        initialized = true
        notifyNetworkChanged(context, force = true)
        Log.i(CORE_TAG, "Mihomo Core initialized. Version: ${getCoreVersion()}")
    }

    private fun ensureInitializedIfPossible(context: Context? = null): Boolean {
        if (initialized) return true
        if (MihomoApplication.isDebuggable() && MihomoApplication.isEmulator()) {
            return false
        }
        val targetContext = context?.applicationContext
            ?: runCatching { Global.application }.getOrNull()
            ?: return false
        return runCatching {
            init(targetContext)
            initialized
        }.getOrDefault(false)
    }

    @Suppress("unused")
    suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
        init(context)
    }

    private fun restoreSavedSelectors(context: Context, profileId: String?) {
        runCatching {
            val settings = SettingsManager(context)
            val allSaved = settings.getSelectedNodesForProfile(profileId ?: "")
            allSaved.forEach { (gName, sNode) ->
                patchSelector(gName, sNode)
            }
        }.onFailure {
            Log.w(CORE_TAG, "Failed to restore saved selectors for profile $profileId", it)
        }
    }

    suspend fun prepareAndLoadProfile(
        context: Context,
        profileFile: File?,
        profileId: String?,
    ): Result<Unit> = configPipelineLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                init(context)
                val configFile = ConfigManager.prepareConfig(context, profileFile)
                val res = load(configFile, profileId)
                if (res.isSuccess) {
                    restoreSavedSelectors(context, profileId)
                    notifyNetworkChanged(context, force = true)
                }
                res
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }
    }

    suspend fun reloadActiveConfig(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            init(context)
            val settings = SettingsManager(context)
            val profiles = ConfigManager.getProfiles(context)
            val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
            prepareAndLoadProfile(context, activeProfile?.file, activeProfile?.id).isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    suspend fun ensureCoreLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = SettingsManager(context)
        val profiles = ConfigManager.getProfiles(context)
        val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
        if (isCoreLoaded && activeProfile?.id == loadedProfileId) {
            notifyNetworkChanged(context)
            return@withContext true
        }
        reloadActiveConfig(context)
    }

    fun getCoreVersion(): String {
        if (!ensureInitializedIfPossible()) return "Unknown (uninitialized)"
        return try {
            Bridge.nativeCoreVersion()
        } catch (e: Throwable) {
            Log.w(CORE_TAG, "Failed to query core version", e)
            "Unknown (${e.message})"
        }
    }

    private suspend fun awaitInFlightNativeLoad() {
        val previous = inFlightLoad ?: return
        if (previous.deferred.isCompleted) {
            inFlightLoad = null
            return
        }
        val finished = withTimeoutOrNull(LOAD_TIMEOUT_MS.milliseconds) {
            awaitPreviousLoadDeferred(previous.deferred)
            true
        }
        if (finished == null) {
            throw ClashException("Timed out waiting for the previous configuration load from ${previous.configDir}")
        }
        if (previous.deferred.isCompleted && inFlightLoad === previous) {
            inFlightLoad = null
        }
    }

    private fun startNativeLoad(configDir: String): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        val entry = InFlightLoad(configDir, deferred)
        inFlightLoad = entry
        deferred.invokeOnCompletion {
            if (inFlightLoad === entry) {
                inFlightLoad = null
            }
        }
        try {
            Bridge.nativeLoad(deferred, configDir)
        } catch (e: Throwable) {
            val failure = ClashException("Failed to load the configuration from $configDir", e)
            completeDeferredSafely(deferred, failure)
            throw failure
        }
        return deferred
    }

    suspend fun load(configFile: File, profileId: String? = null): Result<Unit> = loadLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureInitializedIfPossible()
                awaitAssetsExtractionReady()
                awaitInFlightNativeLoad()

                val configDir = if (configFile.isDirectory) {
                    configFile.absolutePath
                } else {
                    configFile.parentFile?.absolutePath ?: configFile.absolutePath
                }
                Log.i(CORE_TAG, "Loading core config from directory: $configDir (target file: ${configFile.name})")
                val deferred = startNativeLoad(configDir)
                awaitNativeLoadCompletion(configDir, deferred)
                activeGroupChecks.clear()
                isCoreLoaded = true
                if (profileId != null) {
                    loadedProfileId = profileId
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }
    }

    fun setSuspended(suspended: Boolean) {
        if (!initialized) return
        runCatching { Bridge.nativeSuspend(suspended) }
    }

    fun startTun(
        fd: Int,
        stack: String = "mips",
        gateway: String = TunConfig.GATEWAY,
        portal: String = TunConfig.PORTAL,
        dns: String = TunConfig.DNS,
        vpnService: VpnService,
    ) {
        ensureInitializedIfPossible(vpnService)
        val effectiveStack = if (stack.equals("gvisor", ignoreCase = true)) "gvisor" else "mips"
        Log.i(CORE_TAG, "Starting TUN with fd=$fd, stack=$effectiveStack (configured=$stack)")
        val cm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } else {
            null
        }
        Bridge.nativeStartTun(fd, effectiveStack, gateway, portal, dns, createTunCallback(vpnService, cm))
    }

    fun stopTun() {
        if (!initialized) return
        Log.i(CORE_TAG, "Stopping TUN")
        runCatching { Bridge.nativeStopTun() }
    }

    fun queryTrafficNow(): Traffic {
        if (!initialized) return 0L
        return runCatching { Bridge.nativeQueryTrafficNow() }.getOrDefault(0L)
    }

    fun queryTrafficTotal(): Traffic {
        if (!initialized) return 0L
        return runCatching { Bridge.nativeQueryTrafficTotal() }.getOrDefault(0L)
    }

    @Suppress("unused")
    fun queryTunnelState(): TunnelState {
        if (!ensureInitializedIfPossible()) return TunnelState("rule")
        return runCatching {
            val res = Bridge.nativeQueryTunnelState()
            json.decodeFromString<TunnelState>(res)
        }.getOrDefault(TunnelState("rule"))
    }

    fun queryGroupNames(excludeNotSelectable: Boolean = false): List<String> {
        if (!ensureInitializedIfPossible()) return emptyList()
        return runCatching {
            val raw = Bridge.nativeQueryGroupNames(excludeNotSelectable)
            val array = json.decodeFromString<JsonArray>(raw)
            array.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    fun queryGroup(name: String, sort: ProxySort = ProxySort.Default): ProxyGroup? {
        if (!ensureInitializedIfPossible()) return null
        return runCatching {
            val raw = Bridge.nativeQueryGroup(name, sort.name)
            if (!raw.isNullOrEmpty()) {
                json.decodeFromString<ProxyGroup>(raw)
            } else {
                null
            }
        }.getOrNull()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        if (!ensureInitializedIfPossible()) return false
        return runCatching { Bridge.nativePatchSelector(selector, name) }.getOrDefault(false)
    }

    private fun evictStaleHealthChecks(nowMs: Long) {
        activeGroupChecks.entries.removeIf { entry ->
            !isHealthCheckReusable(entry.value.deferred.isCompleted, entry.value.startedAtMs, nowMs)
        }
    }

    private fun removeCompletedGroupCheck(groupName: String, deferred: CompletableDeferred<Unit>) {
        if (deferred.isCompleted) {
            activeGroupChecks.entries.removeIf { entry ->
                entry.key == groupName && entry.value.deferred === deferred
            }
        }
    }

    private fun getOrStartGroupHealthCheck(groupName: String): CompletableDeferred<Unit> {
        ensureInitializedIfPossible()
        val nowMs = System.currentTimeMillis()
        evictStaleHealthChecks(nowMs)
        var resolvedEntry: ActiveHealthCheck? = null
        activeGroupChecks.compute(groupName) { _, existing ->
            val nextEntry = if (
                existing != null &&
                isHealthCheckReusable(existing.deferred.isCompleted, existing.startedAtMs, nowMs)
            ) {
                existing
            } else {
                createAndStartHealthCheck(groupName, nowMs)
            }
            resolvedEntry = nextEntry
            nextEntry
        }
        return checkNotNull(resolvedEntry).deferred
    }

    private fun createAndStartHealthCheck(groupName: String, startedAtMs: Long): ActiveHealthCheck {
        val deferred = CompletableDeferred<Unit>()
        val entry = ActiveHealthCheck(deferred, startedAtMs)
        deferred.invokeOnCompletion {
            activeGroupChecks.remove(groupName, entry)
        }
        runCatching {
            Bridge.nativeHealthCheck(deferred, groupName)
        }.onFailure { error ->
            completeDeferredSafely(
                deferred,
                ClashException("Failed to start the health check for group '$groupName'", error)
            )
        }
        return entry
    }

    suspend fun healthCheckGroupNative(
        name: String,
        onProgress: ((String, Int) -> Unit)? = null,
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val initialGroup = queryGroup(name)
        val initialDelays = initialGroup?.proxies?.associate { it.name to it.delay } ?: emptyMap()
        val deferred = getOrStartGroupHealthCheck(name)
        val reported = ConcurrentHashMap.newKeySet<String>()
        var pollJob: Job? = null
        try {
            if (onProgress != null) {
                pollJob = launch {
                    pollGroupHealthProgress(
                        groupName = name,
                        deferred = deferred,
                        initialDelays = initialDelays,
                        reported = reported,
                        groupFetcher = ::queryGroup,
                        onProgress = onProgress,
                    )
                }
            }
            val completedInTime = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS.milliseconds) {
                deferred.await()
                true
            }
            if (completedInTime == null) {
                Log.d(CORE_TAG, "Health check timed out for group '$name'")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(CORE_TAG, "Health check aborted for group '$name'", e)
        } finally {
            pollJob?.cancel()
            removeCompletedGroupCheck(name, deferred)
        }
        currentCoroutineContext().ensureActive()
        buildGroupHealthResult(queryGroup(name), reported, onProgress)
    }

    suspend fun healthCheck(name: String, onProgress: ((String, Int) -> Unit)? = null): Map<String, Int> =
        healthCheckGroupNative(name, onProgress)

    suspend fun testProxyDelayNative(proxyName: String, groupName: String? = null): Int = withContext(Dispatchers.IO) {
        if (proxyName.equals("DIRECT", ignoreCase = true)) return@withContext 0
        if (proxyName.equals("REJECT", ignoreCase = true) || proxyName.equals("COMPATIBLE", ignoreCase = true)) {
            return@withContext -2
        }

        val allGroups = queryGroupNames()
        if (allGroups.contains(proxyName)) {
            return@withContext testGroupDelayInternal(proxyName)
        }

        val groupCache = HashMap<String, ProxyGroup?>()
        fun cachedGroup(g: String): ProxyGroup? = groupCache.getOrPut(g) { queryGroup(g) }

        val targetGroup = when {
            !groupName.isNullOrBlank() && allGroups.contains(groupName) -> groupName
            else -> allGroups.firstOrNull { g ->
                cachedGroup(g)?.proxies?.any { it.name == proxyName } == true
            } ?: return@withContext -2
        }

        val initialDelay = cachedGroup(targetGroup)?.proxies?.find { it.name == proxyName }?.delay ?: -1
        val deferred = getOrStartGroupHealthCheck(targetGroup)
        val result = awaitSingleProxyDelay(targetGroup, proxyName, initialDelay, deferred)
        currentCoroutineContext().ensureActive()

        if (result in 1..MAX_VALID_DELAY_MS) {
            return@withContext result
        }
        val postCheckGroup = queryGroup(targetGroup)
        val proxyItem = postCheckGroup?.proxies?.find { it.name == proxyName }
        if (proxyItem != null) {
            return@withContext proxyItem.delay.takeIf { it in 1..MAX_VALID_DELAY_MS } ?: -2
        }
        allGroups.asSequence()
            .filter { it != targetGroup }
            .firstNotNullOfOrNull { g ->
                queryGroup(g)?.proxies?.find { it.name == proxyName }?.delay?.takeIf { it in 1..MAX_VALID_DELAY_MS }
            }
            ?: -2
    }

    private suspend fun testGroupDelayInternal(groupName: String): Int {
        val deferred = getOrStartGroupHealthCheck(groupName)
        try {
            withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS.milliseconds) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            removeCompletedGroupCheck(groupName, deferred)
        }
        val subGroup = queryGroup(groupName) ?: return -2
        val activeProxy = subGroup.proxies.find { it.name == subGroup.now }
        return activeProxy?.delay?.takeIf { it in 1..MAX_VALID_DELAY_MS }
            ?: subGroup.proxies.map { it.delay }.filter { it in 1..MAX_VALID_DELAY_MS }.minOrNull()
            ?: -2
    }

    private suspend fun awaitSingleProxyDelay(
        targetGroup: String,
        proxyName: String,
        initialDelay: Int,
        deferred: CompletableDeferred<Unit>,
    ): Int {
        var result = -1
        try {
            withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS.milliseconds) {
                while (!deferred.isCompleted) {
                    delay(PROXY_DELAY_POLL_INTERVAL_MS.milliseconds)
                    val validDelay = queryGroup(targetGroup)?.proxies
                        ?.find { it.name == proxyName }
                        ?.delay
                        ?.takeIf { it in 1..MAX_VALID_DELAY_MS }
                        ?: continue
                    if (validDelay != initialDelay || deferred.isCompleted) {
                        result = validDelay
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            removeCompletedGroupCheck(targetGroup, deferred)
        }
        return result
    }

    @Suppress("unused")
    fun healthCheckAll() {
        if (!ensureInitializedIfPossible()) return
        runCatching { Bridge.nativeHealthCheckAll() }
    }

    fun reset() {
        isCoreLoaded = false
        loadedProfileId = null
        lastNotifiedDns = null
        lastNotifiedTzId = null
        lastNotifiedTzOffsetSec = null
        activeGroupChecks.clear()
        if (!initialized) return
        runCatching { Bridge.nativeReset() }
    }
}
