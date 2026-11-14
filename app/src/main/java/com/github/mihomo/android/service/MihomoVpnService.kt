@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficSpeedDownload
import com.github.kr328.clash.core.util.trafficSpeedUpload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.mihomo.android.MihomoApplication
import com.github.mihomo.android.R
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.TunConfig
import com.github.mihomo.android.core.resolveConnectionOwnerUid
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SERVICE_TAG = "MihomoVpnService"
private const val NOTIFICATION_CHANNEL_ID = "mihomo_vpn_service"
private const val NOTIFICATION_ID = 1001
private const val TEARDOWN_WAIT_MS = 15_000L
private const val NETWORK_NOTIFY_DEBOUNCE_MS = 150L
private const val NOTIFICATION_THROTTLE_MS = 3_000L

data class VpnState(
    val status: Status = Status.STOPPED,
    val upSpeed: String = "0 B/s",
    val downSpeed: String = "0 B/s",
    val totalUp: String = "0 B",
    val totalDown: String = "0 B",
    val coreVersion: String = "",
    val error: String? = null,
    /** Wall-clock time the tunnel reached RUNNING; 0 when it is not running. */
    val startedAtMs: Long = 0L,
) {
    enum class Status {
        STOPPED, STARTING, RUNNING, STOPPING
    }
}

private sealed interface ServiceCommand {
    data class Start(val startId: Int, val seq: Long) : ServiceCommand
    data class Restart(val startId: Int, val seq: Long) : ServiceCommand
    data class Stop(
        val startId: Int?,
        val seq: Long,
        val clearPersistedIntent: Boolean = true,
    ) : ServiceCommand
    data class UnwantedNullStart(val startId: Int, val seq: Long) : ServiceCommand
    data class StartSucceeded(
        val token: Long,
        val coreVersion: String,
        val appLanguage: String,
    ) : ServiceCommand
    data class StartFailed(val token: Long, val message: String) : ServiceCommand
}

private class PhysicalNetworkCallback(
    private val connectivityProvider: () -> ConnectivityManager?,
    private val onPhysicalNetworkEvent: () -> Unit,
) : ConnectivityManager.NetworkCallback() {
    @Volatile
    private var lastDispatchedNetwork: Network? = null

    @Volatile
    private var lastDispatchedDnsSignature: String? = null

    override fun onAvailable(network: Network) {
        if (!isPhysicalNetwork(network)) return
        ClashCore.updateTrackedPhysicalNetwork(network)
        if (lastDispatchedNetwork != network) {
            lastDispatchedNetwork = network
            lastDispatchedDnsSignature = null
            onPhysicalNetworkEvent()
        }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (!isPhysicalNetwork(network)) return
        ClashCore.updateTrackedPhysicalNetwork(network)
        val dnsSig = linkProperties.dnsServers.mapNotNull { it.hostAddress }.joinToString(",")
        if (lastDispatchedNetwork != network || lastDispatchedDnsSignature != dnsSig) {
            lastDispatchedNetwork = network
            lastDispatchedDnsSignature = dnsSig
            onPhysicalNetworkEvent()
        }
    }

    override fun onLost(network: Network) {
        Log.i(SERVICE_TAG, "Physical network lost: $network")
        if (lastDispatchedNetwork == network) {
            lastDispatchedNetwork = null
            lastDispatchedDnsSignature = null
            ClashCore.updateTrackedPhysicalNetwork(null)
        }
        onPhysicalNetworkEvent()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        ClashCore.updateTrackedPhysicalNetwork(network)
        if (lastDispatchedNetwork != network) {
            lastDispatchedNetwork = network
            lastDispatchedDnsSignature = null
            onPhysicalNetworkEvent()
        }
    }

    private fun isPhysicalNetwork(network: Network): Boolean {
        val cm = connectivityProvider() ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        return caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

private class VpnSystemMonitors(
    private val hostContext: Context,
    private val connectivityProvider: () -> ConnectivityManager?,
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit,
    private val onPhysicalNetworkEvent: () -> Unit,
) {
    private var screenStateReceiverRegistered = false
    private var networkCallbackRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    private val networkCallback = PhysicalNetworkCallback(
        connectivityProvider = connectivityProvider,
        onPhysicalNetworkEvent = onPhysicalNetworkEvent
    )

    fun registerAll() {
        if (!screenStateReceiverRegistered) {
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hostContext.registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    hostContext.registerReceiver(screenStateReceiver, filter)
                }
                screenStateReceiverRegistered = true
            }
        }
        if (!networkCallbackRegistered) {
            runCatching {
                val cm = connectivityProvider() ?: return
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
                networkCallbackRegistered = true
            }
        }
    }

    fun unregisterAll() {
        if (screenStateReceiverRegistered) {
            runCatching { hostContext.unregisterReceiver(screenStateReceiver) }
            screenStateReceiverRegistered = false
        }
        if (networkCallbackRegistered) {
            runCatching { connectivityProvider()?.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
    }
}

private class VpnNotificationController(
    private val service: Service,
) {
    @Volatile
    private var lastContent: String? = null

    @Volatile
    private var lastUpdateTimeMs: Long = 0L

    private val launchPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val stopPendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            service,
            1,
            Intent(service, MihomoVpnService::class.java).apply { action = MihomoVpnService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun createChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            service.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }

    fun currentOrDefaultStartingText(): String {
        val current = lastContent
        if (current != null) return current
        val lang = runCatching { SettingsManager(service).appLanguage }.getOrDefault("auto")
        return AppStrings.get("notification_starting_core", lang)
    }

    fun resetState(clearContent: Boolean) {
        if (clearContent) {
            lastContent = null
        }
        lastUpdateTimeMs = 0L
    }

    fun startForegroundNotification(content: String) {
        lastContent = content
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(content: String, throttled: Boolean) {
        if (content == lastContent) return
        val nowMs = System.currentTimeMillis()
        if (throttled && nowMs - lastUpdateTimeMs < NOTIFICATION_THROTTLE_MS) return
        if (throttled) {
            lastUpdateTimeMs = nowMs
        }
        lastContent = content
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    fun stopForegroundAndReset() {
        runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        resetState(clearContent = true)
    }

    private fun buildNotification(content: String): Notification =
        NotificationCompat.Builder(service, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(launchPendingIntent)
            .addAction(0, service.getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

private class VpnTrafficMonitor(
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<VpnState>,
    private val isInteractiveProvider: () -> Boolean,
    private val isForegroundProvider: () -> Boolean,
    private val notificationController: VpnNotificationController,
) {
    private val lock = Any()

    @Volatile
    private var monitorJob: Job? = null

    fun start() {
        synchronized(lock) {
            monitorJob?.cancel()
            if (!isInteractiveProvider() || stateFlow.value.status != VpnState.Status.RUNNING) {
                monitorJob = null
                return
            }
            monitorJob = scope.launch { pollLoop() }
        }
    }

    fun stop() {
        synchronized(lock) {
            monitorJob?.cancel()
            monitorJob = null
            notificationController.resetState(clearContent = false)
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive &&
            isInteractiveProvider() &&
            stateFlow.value.status == VpnState.Status.RUNNING
        ) {
            val foreground = isForegroundProvider()
            delay(if (foreground) 1_000L else 3_000L)
            if (!isInteractiveProvider() || stateFlow.value.status != VpnState.Status.RUNNING) break
            val (nextState, notificationText) = computeUpdatedTrafficSnapshot(stateFlow.value, foreground)
            if (nextState != null) {
                stateFlow.value = nextState
            }
            notificationController.updateNotification(notificationText, throttled = true)
        }
    }
}

internal fun hasValidWhitelistTargets(
    selectedPackages: Set<String>,
    hostPackageName: String,
    appliedExternalCount: Int,
): Boolean {
    val externalRequested = selectedPackages.any { it != hostPackageName }
    return externalRequested && appliedExternalCount > 0
}

private fun closePfdSafely(pfd: ParcelFileDescriptor?) {
    runCatching { pfd?.close() }
}

private fun applyCoreSuspendedSafely(suspended: Boolean) {
    runCatching { ClashCore.setSuspended(suspended) }
}

private fun notifyNetworkChangedSafely(context: Context) {
    runCatching { ClashCore.notifyNetworkChanged(context) }
}

private fun selectActiveProfileForVpn(
    context: Context,
    settings: SettingsManager,
    appLanguage: String,
): ProfileItem? {
    val profiles = ConfigManager.getProfiles(context)
    val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
    if (activeProfile != null && activeProfile.id != settings.selectedProfileId) {
        LogRepository.addLog(
            "warning",
            AppStrings.get("profile_fallback_missing", appLanguage)
                .replace("{name}", activeProfile.name)
        )
    }
    return activeProfile
}

private suspend fun validateAndPrepareVpnStart(
    service: MihomoVpnService,
    settings: SettingsManager,
    appLanguage: String,
): Pair<String, Pair<VpnService.Builder, Boolean>> {
    if (MihomoApplication.isDebuggable() && MihomoApplication.isEmulator()) {
        error(
            "The native core cannot run on a debuggable emulator " +
                "(ARM translation is unsupported). Install a release build on a real device."
        )
    }

    ClashCore.init(service)
    val coreVer = ClashCore.getCoreVersion()
    val activeProfile = selectActiveProfileForVpn(service, settings, appLanguage)
    if (settings.perAppProxyMode == PerAppProxyMode.WHITELIST &&
        settings.selectedPackages.none { it != service.packageName }
    ) {
        error(AppStrings.get("whitelist_empty", appLanguage))
    }

    val loadRes = ClashCore.prepareAndLoadProfile(
        context = service,
        profileFile = activeProfile?.file,
        profileId = activeProfile?.id
    )
    if (loadRes.isFailure) {
        error(
            AppStrings.get("vpn_error_load_config", appLanguage)
                .replace("{msg}", loadRes.exceptionOrNull()?.message ?: "unknown")
        )
    }
    currentCoroutineContext().ensureActive()
    return coreVer to configureVpnBuilder(service, settings, appLanguage)
}

private fun configureVpnBuilder(
    service: MihomoVpnService,
    settings: SettingsManager,
    appLanguage: String,
): Pair<VpnService.Builder, Boolean> {
    var ipv6TunActive = false
    val builder = service.Builder().apply {
        setSession("Nexus")
        setMtu(settings.tunMtu)
        addAddress(TunConfig.IPV4_ADDRESS, TunConfig.IPV4_PREFIX)
        addRoute("0.0.0.0", 0)
        addRoute("198.18.0.0", 16)
        addDnsServer(TunConfig.IPV4_PORTAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.killSwitchEnabled) {
            runCatching { setBlocking(true) }
                .onFailure { Log.w(SERVICE_TAG, "setBlocking (kill switch) failed", it) }
        } else if (settings.killSwitchEnabled) {
            LogRepository.addLog(
                "warning",
                "本系统版本 (API ${Build.VERSION.SDK_INT}) 不支持断网保护，该开关在本机不生效"
            )
        }
        if (settings.allowBypassEnabled) {
            runCatching { allowBypass() }
                .onFailure { Log.w(SERVICE_TAG, "allowBypass failed", it) }
        }
        if (settings.ipv6Enabled) {
            runCatching {
                addAddress(TunConfig.IPV6_ADDRESS, TunConfig.IPV6_PREFIX)
                addRoute("::", 0)
                addDnsServer(TunConfig.IPV6_PORTAL)
                ipv6TunActive = true
            }.onFailure {
                Log.w(SERVICE_TAG, "IPv6 TUN setup failed; running the tunnel IPv4-only", it)
            }
        }
        configurePerAppRouting(this, service.packageName, settings, appLanguage)
    }
    return builder to ipv6TunActive
}

private fun configurePerAppRouting(
    builder: VpnService.Builder,
    hostPackageName: String,
    settings: SettingsManager,
    appLanguage: String,
) {
    val packages = settings.selectedPackages
    when (settings.perAppProxyMode) {
        PerAppProxyMode.WHITELIST -> {
            runCatching { builder.addAllowedApplication(hostPackageName) }
                .onFailure { Log.w(SERVICE_TAG, "addAllowedApplication($hostPackageName) failed", it) }
            var appliedExternalCount = 0
            val missing = mutableListOf<String>()
            for (pkg in packages) {
                if (pkg == hostPackageName) continue
                runCatching {
                    builder.addAllowedApplication(pkg)
                    appliedExternalCount++
                }.onFailure { e ->
                    missing.add(pkg)
                    Log.w(SERVICE_TAG, "addAllowedApplication($pkg) failed", e)
                }
            }
            if (missing.isNotEmpty()) {
                LogRepository.addLog(
                    "warning",
                    "Per-app whitelist skipped uninstalled packages: ${missing.joinToString(", ")}"
                )
            }
            if (!hasValidWhitelistTargets(packages, hostPackageName, appliedExternalCount)) {
                error(AppStrings.get("whitelist_empty", appLanguage))
            }
        }
        PerAppProxyMode.BLACKLIST -> {
            val missing = mutableListOf<String>()
            for (pkg in packages) {
                if (pkg == hostPackageName) continue
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { e ->
                        missing.add(pkg)
                        Log.w(SERVICE_TAG, "addDisallowedApplication($pkg) failed", e)
                    }
            }
            if (missing.isNotEmpty()) {
                LogRepository.addLog(
                    "warning",
                    "Per-app blacklist skipped uninstalled packages: ${missing.joinToString(", ")}"
                )
            }
        }
        PerAppProxyMode.DISABLED -> Unit
    }
}

private fun computeUpdatedTrafficSnapshot(
    cur: VpnState,
    isUiInForeground: Boolean,
): Pair<VpnState?, String> {
    val now = ClashCore.queryTrafficNow()
    val upStr = now.trafficSpeedUpload()
    val downStr = now.trafficSpeedDownload()
    val speedChanged = cur.upSpeed != upStr || cur.downSpeed != downStr
    var nextState: VpnState? = null
    if (isUiInForeground || now != 0L || speedChanged) {
        val total = ClashCore.queryTrafficTotal()
        val totalUpStr = total.trafficUpload()
        val totalDownStr = total.trafficDownload()
        val totalChanged = cur.totalUp != totalUpStr || cur.totalDown != totalDownStr
        if (speedChanged || totalChanged) {
            nextState = cur.copy(
                upSpeed = upStr,
                downSpeed = downStr,
                totalUp = totalUpStr,
                totalDown = totalDownStr
            )
        }
    }
    return nextState to "\u2191 $upStr   \u2193 $downStr"
}

private fun startNativeTunOrCloseFd(
    service: MihomoVpnService,
    fd: Int,
    ipv6TunActive: Boolean,
    settings: SettingsManager,
) {
    val gatewayStr = if (ipv6TunActive) TunConfig.GATEWAY else TunConfig.GATEWAY_IPV4
    val portalStr = if (ipv6TunActive) TunConfig.PORTAL else TunConfig.PORTAL_IPV4
    val dnsStr = if (ipv6TunActive) TunConfig.DNS else TunConfig.DNS_IPV4
    try {
        ClashCore.startTun(
            fd = fd,
            stack = settings.tunStack,
            gateway = gatewayStr,
            portal = portalStr,
            dns = dnsStr,
            vpnService = service
        )
    } catch (t: Throwable) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        throw t
    }
}

private fun spawnVpnDestroyTeardownThread(
    jobsToJoin: List<Job?>,
    onPerformTeardown: () -> Unit,
    onFinallyComplete: () -> Unit,
) {
    Thread {
        try {
            runBlocking {
                for (job in jobsToJoin) {
                    job?.join()
                }
            }
            applyCoreSuspendedSafely(false)
            onPerformTeardown()
        } finally {
            onFinallyComplete()
        }
    }.apply {
        name = "mihomo-vpn-teardown"
        isDaemon = true
    }.start()
}

class MihomoVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.github.mihomo.android.service.START"
        const val ACTION_STOP = "com.github.mihomo.android.service.STOP"
        const val ACTION_RESTART = "com.github.mihomo.android.service.RESTART"

        private val _vpnState = MutableStateFlow(VpnState())
        val vpnState = _vpnState.asStateFlow()

        @Volatile
        var isUiInForeground: Boolean = false

        private val nextInstanceId = AtomicLong(0L)
        private val instanceLock = Any()
        private var activeInstanceId: Long = 0L
        private val destroyLock = Any()

        @Volatile
        private var inFlightDestroyTeardown: CompletableDeferred<Unit>? = null

        fun start(context: Context) {
            val intent = Intent(context, MihomoVpnService::class.java).apply { action = ACTION_START }
            runCatching { context.startForegroundService(intent) }
                .onFailure {
                    Log.e(SERVICE_TAG, "Failed to start VPN service", it)
                    clearPersistedIntent(context)
                }
        }

        fun restart(context: Context) {
            val status = _vpnState.value.status
            if (status != VpnState.Status.RUNNING && status != VpnState.Status.STARTING) return
            val intent = Intent(context, MihomoVpnService::class.java).apply { action = ACTION_RESTART }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(SERVICE_TAG, "Failed to deliver restart command to VPN service", it) }
        }

        fun stop(context: Context) {
            if (_vpnState.value.status == VpnState.Status.STOPPED) {
                clearPersistedIntent(context)
                return
            }
            val intent = Intent(context, MihomoVpnService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.w(SERVICE_TAG, "Failed to deliver stop command to VPN service", it)
                    clearPersistedIntent(context)
                }
        }

        fun clearPersistedIntent(context: Context) {
            runCatching {
                val settings = SettingsManager(context)
                if (settings.vpnIntentActive) {
                    settings.vpnIntentActive = false
                }
            }
        }

        @Suppress("unused")
        fun resumeIfIntentActive(context: Context) {
            val active = runCatching { SettingsManager(context).vpnIntentActive }.getOrDefault(false)
            if (!active) return
            Log.i(SERVICE_TAG, "Resuming the tunnel: the persisted intent says it should be running")
            start(context)
        }

        private suspend fun awaitInFlightDestroyTeardown() {
            val pending = synchronized(destroyLock) { inFlightDestroyTeardown } ?: return
            if (pending.isCompleted) return
            withTimeoutOrNull(TEARDOWN_WAIT_MS.milliseconds) { pending.await() }
                ?: Log.w(SERVICE_TAG, "Timed out waiting for prior service instance teardown")
        }
    }

    private val instanceId: Long = nextInstanceId.incrementAndGet()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandChannel = Channel<ServiceCommand>(Channel.UNLIMITED)
    private val suspendChannel = Channel<Boolean>(Channel.UNLIMITED)
    private val networkChangeChannel = Channel<Unit>(Channel.CONFLATED)

    private val commandSeq = AtomicLong(0L)
    private val latestStopSeq = AtomicLong(0L)
    private val latestStartOrRestartSeq = AtomicLong(0L)
    private val latestStartId = AtomicInteger(0)

    private val foregroundSyncLock = Any()
    private val teardownLock = Any()

    private var actorJob: Job? = null
    private var suspendJob: Job? = null
    private var networkNotifyJob: Job? = null

    @Volatile private var activeStartJob: Job? = null
    @Volatile private var activeStartToken: Long = 0L
    @Volatile private var activeStartSeq: Long = 0L
    private var nextStartToken: Long = 0L

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var tunNeedsStop: Boolean = false
    @Volatile private var isDestroying: Boolean = false
    @Volatile private var teardownStarted = false
    @Volatile private var teardownDone = CompletableDeferred(Unit)
    @Volatile private var isScreenInteractive: Boolean = true

    private val connectivityManager: ConnectivityManager? by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private val notificationController by lazy { VpnNotificationController(this) }

    private val trafficMonitor by lazy {
        VpnTrafficMonitor(
            scope = serviceScope,
            stateFlow = _vpnState,
            isInteractiveProvider = { isScreenInteractive },
            isForegroundProvider = { isUiInForeground },
            notificationController = notificationController
        )
    }

    private val systemMonitors by lazy {
        VpnSystemMonitors(
            hostContext = this,
            connectivityProvider = { connectivityManager },
            onScreenOff = {
                isScreenInteractive = false
                LogRepository.isScreenOff = true
                trafficMonitor.stop()
                suspendChannel.trySend(true)
            },
            onScreenOn = {
                isScreenInteractive = true
                LogRepository.isScreenOff = false
                suspendChannel.trySend(false)
                if (_vpnState.value.status == VpnState.Status.RUNNING) {
                    trafficMonitor.start()
                }
            },
            onPhysicalNetworkEvent = {
                if (_vpnState.value.status == VpnState.Status.RUNNING) {
                    networkChangeChannel.trySend(Unit)
                }
            }
        )
    }

    private fun resetTeardownGuard() {
        synchronized(teardownLock) {
            teardownStarted = false
            teardownDone = CompletableDeferred()
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(instanceLock) { activeInstanceId = instanceId }
        notificationController.createChannel()
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
        LogRepository.isScreenOff = !isScreenInteractive
        systemMonitors.registerAll()
        startWorkers()
    }

    private fun startWorkers() {
        actorJob = serviceScope.launch {
            for (cmd in commandChannel) {
                if (isDestroying) break
                processCommand(cmd)
            }
        }
        suspendJob = serviceScope.launch {
            for (suspended in suspendChannel) {
                applyCoreSuspendedSafely(suspended)
            }
        }
        networkNotifyJob = serviceScope.launch {
            for (ignored in networkChangeChannel) {
                delay(NETWORK_NOTIFY_DEBOUNCE_MS.milliseconds)
                if (_vpnState.value.status == VpnState.Status.RUNNING) {
                    notifyNetworkChangedSafely(this@MihomoVpnService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        return when (intent?.action) {
            ACTION_START, SERVICE_INTERFACE -> {
                enqueueStart(startId, notificationController.currentOrDefaultStartingText())
                START_STICKY
            }
            ACTION_RESTART -> {
                val seq = commandSeq.incrementAndGet()
                latestStartOrRestartSeq.set(seq)
                commandChannel.trySend(ServiceCommand.Restart(startId, seq))
                START_STICKY
            }
            ACTION_STOP -> {
                val seq = commandSeq.incrementAndGet()
                latestStopSeq.set(seq)
                commandChannel.trySend(ServiceCommand.Stop(startId, seq, clearPersistedIntent = true))
                START_NOT_STICKY
            }
            else -> handleNullOrUnknownStart(startId)
        }
    }

    private fun handleNullOrUnknownStart(startId: Int): Int {
        val shouldRestore = runCatching { SettingsManager(this).vpnIntentActive }.getOrDefault(false)
        if (!shouldRestore) {
            Log.i(SERVICE_TAG, "Restarted without an active intent while the tunnel is not wanted; stopping")
            val seq = commandSeq.incrementAndGet()
            commandChannel.trySend(ServiceCommand.UnwantedNullStart(startId, seq))
            return START_NOT_STICKY
        }
        val foregroundOk = enqueueStart(startId, getString(R.string.notification_restoring), safeStart = true)
        return if (foregroundOk) START_STICKY else START_NOT_STICKY
    }

    private fun enqueueStart(startId: Int, notificationText: String, safeStart: Boolean = false): Boolean {
        val seq = commandSeq.incrementAndGet()
        synchronized(foregroundSyncLock) {
            latestStartOrRestartSeq.set(seq)
            if (safeStart) {
                val started = runCatching { notificationController.startForegroundNotification(notificationText) }
                    .onFailure { Log.w(SERVICE_TAG, "Foreground promotion rejected during sticky restore", it) }
                    .isSuccess
                if (!started) {
                    val stopSeq = commandSeq.incrementAndGet()
                    commandChannel.trySend(ServiceCommand.UnwantedNullStart(startId, stopSeq))
                    return false
                }
            } else {
                notificationController.startForegroundNotification(notificationText)
            }
        }
        commandChannel.trySend(ServiceCommand.Start(startId, seq))
        return true
    }

    private suspend fun processCommand(cmd: ServiceCommand) {
        when (cmd) {
            is ServiceCommand.Start -> handleStart(cmd)
            is ServiceCommand.Restart -> handleRestart(cmd)
            is ServiceCommand.Stop -> handleStop(cmd)
            is ServiceCommand.UnwantedNullStart -> handleUnwantedNullStart(cmd)
            is ServiceCommand.StartSucceeded -> handleStartSucceeded(cmd)
            is ServiceCommand.StartFailed -> handleStartFailed(cmd)
        }
    }

    private fun isStartingOrRunning(): Boolean {
        val status = _vpnState.value.status
        return activeStartJob?.isActive == true ||
            status == VpnState.Status.STARTING ||
            status == VpnState.Status.RUNNING
    }

    private fun handleStart(cmd: ServiceCommand.Start) {
        if (latestStopSeq.get() > cmd.seq || isStartingOrRunning()) return
        launchStartPipeline(nextStartToken + 1L, cmd.seq)
    }

    private suspend fun handleRestart(cmd: ServiceCommand.Restart) {
        if (latestStopSeq.get() > cmd.seq) return
        notificationController.resetState(clearContent = false)
        if (isStartingOrRunning()) {
            _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
            trafficMonitor.stop()
            withContext(NonCancellable) {
                cancelAndJoinActiveStart()
                performTeardown()
                awaitTeardown()
            }
            currentCoroutineContext().ensureActive()
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
        }
        if (isDestroying || latestStopSeq.get() > cmd.seq) return
        launchStartPipeline(nextStartToken + 1L, cmd.seq)
    }

    private suspend fun handleStop(cmd: ServiceCommand.Stop) {
        notificationController.resetState(clearContent = false)
        if (_vpnState.value.status == VpnState.Status.STOPPED && activeStartJob == null) {
            if (cmd.clearPersistedIntent) clearPersistedIntent(this)
            stopServiceIfIdle(cmd.seq)
            return
        }
        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
        trafficMonitor.stop()
        withContext(NonCancellable) {
            cancelAndJoinActiveStart()
            performTeardown()
            awaitTeardown()
            if (cmd.clearPersistedIntent) clearPersistedIntent(this@MihomoVpnService)
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
            stopServiceIfIdle(cmd.seq)
            Log.i(SERVICE_TAG, "Nexus VPN stopped.")
        }
    }

    private fun handleUnwantedNullStart(cmd: ServiceCommand.UnwantedNullStart) {
        if (_vpnState.value.status != VpnState.Status.STOPPED || activeStartJob != null) return
        synchronized(foregroundSyncLock) {
            if (latestStartOrRestartSeq.get() < cmd.seq) {
                notificationController.stopForegroundAndReset()
                stopSelfResult(cmd.startId)
            }
        }
    }

    private suspend fun handleStartSucceeded(cmd: ServiceCommand.StartSucceeded) {
        if (cmd.token != activeStartToken) return
        val supersededByStop = latestStopSeq.get() > activeStartSeq
        activeStartJob = null
        activeStartToken = 0L
        if (supersededByStop) {
            withContext(NonCancellable) {
                performTeardown()
                awaitTeardown()
            }
            return
        }

        SettingsManager(this).vpnIntentActive = true
        _vpnState.value = _vpnState.value.copy(
            status = VpnState.Status.RUNNING,
            coreVersion = cmd.coreVersion,
            error = null,
            startedAtMs = System.currentTimeMillis()
        )

        RequestRepository.startPolling(
            scope = serviceScope,
            context = this@MihomoVpnService,
            resolver = ::queryConnectionOwnerUid
        )

        val interactive = isScreenInteractive
        LogRepository.isScreenOff = !interactive
        suspendChannel.trySend(!interactive)
        if (interactive) {
            trafficMonitor.start()
        } else {
            notificationController.updateNotification("\u2191 0 B/s   \u2193 0 B/s", throttled = false)
        }
        Log.i(SERVICE_TAG, "Nexus VPN successfully started! Core: ${cmd.coreVersion}")
        LogRepository.addLog(
            "info",
            AppStrings.get("log_vpn_started", cmd.appLanguage).replace("{version}", cmd.coreVersion)
        )
    }

    private suspend fun handleStartFailed(cmd: ServiceCommand.StartFailed) {
        if (cmd.token != activeStartToken) return
        val failedSeq = activeStartSeq
        activeStartJob = null
        activeStartToken = 0L
        trafficMonitor.stop()
        withContext(NonCancellable) {
            performTeardown()
            awaitTeardown()
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED, error = cmd.message)
            stopServiceIfIdle(failedSeq)
        }
    }

    private suspend fun cancelAndJoinActiveStart() {
        val job = activeStartJob
        activeStartJob = null
        activeStartToken = 0L
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
    }

    private fun stopServiceIfIdle(handledUpToSeq: Long) {
        synchronized(foregroundSyncLock) {
            if (latestStartOrRestartSeq.get() <= handledUpToSeq) {
                notificationController.stopForegroundAndReset()
                stopSelfResult(latestStartId.get())
            }
        }
    }

    private fun launchStartPipeline(token: Long, startSeq: Long) {
        nextStartToken = token
        val settings = SettingsManager(this)
        val appLanguage = settings.appLanguage
        _vpnState.value = _vpnState.value.copy(
            status = VpnState.Status.STARTING,
            error = null,
            startedAtMs = 0L
        )
        synchronized(foregroundSyncLock) {
            notificationController.resetState(clearContent = true)
            notificationController.startForegroundNotification(
                AppStrings.get("notification_starting_core", appLanguage)
            )
        }
        resetTeardownGuard()

        activeStartToken = token
        activeStartSeq = startSeq
        activeStartJob = serviceScope.launch {
            try {
                val coreVer = runStartPipeline(settings, appLanguage)
                ensureActive()
                commandChannel.trySend(ServiceCommand.StartSucceeded(token, coreVer, appLanguage))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(SERVICE_TAG, "Failed to start Nexus VPN", e)
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: AppStrings.get("vpn_error_unknown", appLanguage)
                LogRepository.addLog(
                    "error",
                    AppStrings.get("log_vpn_start_failed", appLanguage).replace("{msg}", message)
                )
                commandChannel.trySend(ServiceCommand.StartFailed(token, message))
            }
        }
    }

    private suspend fun runStartPipeline(settings: SettingsManager, appLanguage: String): String {
        awaitInFlightDestroyTeardown()
        currentCoroutineContext().ensureActive()
        val (coreVer, builderPair) = validateAndPrepareVpnStart(this@MihomoVpnService, settings, appLanguage)
        establishAndStartTun(builderPair.first, builderPair.second, settings, appLanguage)
        return coreVer
    }

    private suspend fun establishAndStartTun(
        builder: Builder,
        ipv6TunActive: Boolean,
        settings: SettingsManager,
        appLanguage: String,
    ) {
        val pfd = builder.establish()
            ?: error(AppStrings.get("vpn_error_establish_tun", appLanguage))
        vpnInterface = pfd
        if (!currentCoroutineContext().isActive) {
            closePfdSafely(pfd)
            vpnInterface = null
            currentCoroutineContext().ensureActive()
        }

        val fd = pfd.detachFd()
        vpnInterface = null
        tunNeedsStop = true
        startNativeTunOrCloseFd(this@MihomoVpnService, fd, ipv6TunActive, settings)
    }

    private fun queryConnectionOwnerUid(protocol: Int, source: String, target: String): Int =
        resolveConnectionOwnerUid(connectivityManager, protocol, source, target)

    private fun performTeardown(): Boolean {
        val owns = synchronized(teardownLock) {
            if (teardownStarted) false else {
                teardownStarted = true
                true
            }
        }
        if (!owns) return false
        val done = teardownDone
        try {
            RequestRepository.stopPolling()
            if (tunNeedsStop) {
                tunNeedsStop = false
                ClashCore.stopTun()
            }
            val pfd = vpnInterface
            vpnInterface = null
            closePfdSafely(pfd)
        } catch (e: Throwable) {
            Log.w(SERVICE_TAG, "Error closing VPN", e)
        } finally {
            done.complete(Unit)
        }
        return true
    }

    private suspend fun awaitTeardown() {
        val done = teardownDone
        if (done.isCompleted) return
        withTimeoutOrNull(TEARDOWN_WAIT_MS.milliseconds) { done.await() }
            ?: Log.w(SERVICE_TAG, "Timed out waiting for the VPN teardown to finish")
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroying = true
        systemMonitors.unregisterAll()
        LogRepository.isScreenOff = false
        trafficMonitor.stop()

        val activeStart = activeStartJob
        activeStartJob = null
        activeStartToken = 0L
        activeStart?.cancel()

        val jobsToJoin = listOf(activeStart, actorJob, suspendJob, networkNotifyJob)
        actorJob?.cancel()
        suspendJob?.cancel()
        networkNotifyJob?.cancel()
        commandChannel.close()
        suspendChannel.close()
        networkChangeChannel.close()

        val ownedByThisInstance = synchronized(instanceLock) { activeInstanceId == instanceId }
        if (ownedByThisInstance && _vpnState.value.status != VpnState.Status.STOPPED) {
            _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
        }

        val destroyDone = CompletableDeferred<Unit>()
        synchronized(destroyLock) { inFlightDestroyTeardown = destroyDone }

        spawnVpnDestroyTeardownThread(
            jobsToJoin = jobsToJoin,
            onPerformTeardown = ::performTeardown,
            onFinallyComplete = {
                destroyDone.complete(Unit)
                synchronized(destroyLock) {
                    if (inFlightDestroyTeardown === destroyDone) inFlightDestroyTeardown = null
                }
                val isStillOwner = synchronized(instanceLock) { activeInstanceId == instanceId }
                if (isStillOwner && _vpnState.value.status != VpnState.Status.STOPPED) {
                    _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
                }
            }
        )

        serviceScope.cancel()
        synchronized(foregroundSyncLock) {
            notificationController.stopForegroundAndReset()
        }
    }

    override fun onRevoke() {
        clearPersistedIntent(this)
        val seq = commandSeq.incrementAndGet()
        latestStopSeq.set(seq)
        commandChannel.trySend(ServiceCommand.Stop(startId = null, seq = seq, clearPersistedIntent = true))
        super.onRevoke()
    }
}
