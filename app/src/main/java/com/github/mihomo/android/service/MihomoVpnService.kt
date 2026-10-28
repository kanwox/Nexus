package com.github.mihomo.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.github.kr328.clash.core.util.*
import com.github.mihomo.android.MihomoApplication
import com.github.mihomo.android.R
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.TunConfig
import com.github.mihomo.android.core.parseHostPort
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.mihomo.android.i18n.AppStrings
import kotlin.time.Duration.Companion.milliseconds

data class VpnState(
    val status: Status = Status.STOPPED,
    val upSpeed: String = "0 B/s",
    val downSpeed: String = "0 B/s",
    val totalUp: String = "0 B",
    val totalDown: String = "0 B",
    val coreVersion: String = "",
    val error: String? = null,
    /** Wall-clock time the tunnel reached RUNNING; 0 when it is not running. */
    val startedAtMs: Long = 0L
) {
    enum class Status {
        STOPPED, STARTING, RUNNING, STOPPING
    }
}

class MihomoVpnService : VpnService() {

    companion object {
        private const val TAG = "MihomoVpnService"
        const val ACTION_START = "com.github.mihomo.android.service.START"
        const val ACTION_STOP = "com.github.mihomo.android.service.STOP"
        const val ACTION_RESTART = "com.github.mihomo.android.service.RESTART"
        private const val NOTIFICATION_CHANNEL_ID = "mihomo_vpn_service"
        private const val NOTIFICATION_ID = 1001

        /** Upper bound for waiting on an in-flight native teardown. */
        private const val TEARDOWN_WAIT_MS = 15_000L

        private val _vpnState = MutableStateFlow(VpnState())
        val vpnState = _vpnState.asStateFlow()

        @Volatile
        var isUiInForeground: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, MihomoVpnService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                context.startForegroundService(intent)
            }.onFailure { Log.e(TAG, "Failed to start VPN service", it) }
        }

        fun restart(context: Context) {
            val status = _vpnState.value.status
            // A restart requested while STARTING must not be dropped: the tunnel is already
            // being built from stale settings, so queue it instead of ignoring the change.
            if (status != VpnState.Status.RUNNING && status != VpnState.Status.STARTING) return
            val intent = Intent(context, MihomoVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Failed to deliver restart command to VPN service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MihomoVpnService::class.java).apply {
                action = ACTION_STOP
            }
            // Background-start restrictions can reject this; the tunnel cleanup also runs from
            // onDestroy, so a rejected command is not fatal.
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Failed to deliver stop command to VPN service", it) }
        }

        /**
         * Restores the tunnel when the persisted intent says it should be running.
         *
         * The in-memory state is process-local: after a process kill the quick-settings
         * tile (and the service's own state) reset to STOPPED even though the user never
         * asked for the tunnel to stop. The persisted flag is the authoritative answer in
         * that case, so whoever notices the discrepancy asks the service to bring the
         * tunnel back up.
         */
        fun resumeIfIntentActive(context: Context) {
            val active = runCatching { SettingsManager(context).vpnIntentActive }.getOrDefault(false)
            if (!active) return
            Log.i(TAG, "Resuming the tunnel: the persisted intent says it should be running")
            start(context)
        }
    }

    // The scope performs blocking JNI calls (core init, config load, TUN teardown) and
    // binder calls (Builder.establish(), ParcelFileDescriptor.close()), so it must run on
    // the IO dispatcher: Dispatchers.Default has only a small CPU-bound pool and a
    // blocking binder round-trip there can starve it.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trafficMonitorJob: Job? = null

    // Written on the main thread and read from the service scope, so it must be volatile.
    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    /**
     * Set when a start/restart request arrives while a stop is still tearing the tunnel down;
     * closeTunnel picks it up and launches a fresh start once STOPPED is reached.
     */
    @Volatile
    private var pendingStartAfterStop = false

    /**
     * Guards the native teardown so it runs exactly once per tunnel.
     *
     * `closeTunnel()` and `onDestroy()` could both reach `ClashCore.stopTun()`, which is a
     * blocking JNI call into the Go core — two concurrent entries there are a data race
     * inside the core. The compare-and-set makes the second caller collapse into the first
     * one instead of starting a competing teardown.
     *
     * The guard is per tunnel: it is reset by [resetTeardownGuard] when a NEW tunnel is
     * started. Without that reset the second start of the VPN in the same process would
     * skip the teardown entirely and leak the TUN file descriptor.
     */
    @Volatile
    private var teardownStarted = false

    /** Serializes the compare-and-set on [teardownStarted]. */
    private val teardownLock = Any()

    /**
     * Completes when the (single) teardown routine has finished.
     *
     * Replaced for every new tunnel together with [teardownStarted], because a completed
     * deferred would make the next teardown wait for a signal that never comes again.
     */
    @Volatile
    private var teardownDone = CompletableDeferred<Unit>()

    /**
     * Re-arms the teardown guard for a new tunnel.
     *
     * Must be called before the tunnel is established again. The previous teardown has
     * finished by then (the start path only runs when the state is STOPPED, i.e. after
     * closeTunnel completed), so clearing the flag cannot race a running teardown.
     */
    private fun resetTeardownGuard() {
        synchronized(teardownLock) {
            teardownStarted = false
            teardownDone = CompletableDeferred()
        }
    }

    @Volatile
    private var isScreenInteractive: Boolean = true

    @Volatile
    private var lastNotificationContent: String? = null

    private var screenStateReceiverRegistered = false
    private var networkCallbackRegistered = false

    private val launchPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val stopPendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, MihomoVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    com.github.mihomo.android.data.LogRepository.isScreenOff = true
                    trafficMonitorJob?.cancel()
                    // nativeSuspend is a blocking JNI call and the receiver runs on the
                    // main thread, so it must not be executed inline.
                    serviceScope.launch { ClashCore.setSuspended(true) }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    com.github.mihomo.android.data.LogRepository.isScreenOff = false
                    serviceScope.launch { ClashCore.setSuspended(false) }
                    if (_vpnState.value.status == VpnState.Status.RUNNING) {
                        startTrafficMonitor()
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handlePhysicalNetworkChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handlePhysicalNetworkChanged(network)
        }

        /**
         * The physical network went away (e.g. Wi-Fi dropped while moving out of range).
         *
         * Without this the core never learned about the handover and, with the kill switch
         * enabled, the device stayed dark until the core's own timeout expired.
         */
        override fun onLost(network: Network) {
            Log.i(TAG, "Physical network lost: $network")
            handlePhysicalNetworkChanged(network)
        }

        /**
         * The network's capabilities changed — typically a Wi-Fi → cellular handover or a
         * validated → captive-portal transition. The DNS servers of the new transport have
         * to reach the core, otherwise it keeps resolving through the old network.
         */
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handlePhysicalNetworkChanged(network)
        }

        private fun handlePhysicalNetworkChanged(network: Network) {
            if (_vpnState.value.status != VpnState.Status.RUNNING) return
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            // The tunnel's own interface is not a physical network; telling the core about
            // it would report the tunnel's own DNS as the physical one.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            ClashCore.notifyNetworkChanged(this@MihomoVpnService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
        registerScreenStateReceiver()
        registerPhysicalNetworkCallback()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenStateReceiverRegistered = true
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        screenStateReceiverRegistered = false
    }

    private fun registerPhysicalNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterPhysicalNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RESTART -> restartVpn()
            // A null intent means the system restarted the service after the process was
            // killed. Rebuilding the tunnel unconditionally would resurrect a tunnel the
            // user had deliberately stopped, so only continue when the persisted intent
            // flag says the tunnel was supposed to be running.
            else -> {
                val settings = SettingsManager(this)
                if (!settings.vpnIntentActive) {
                    Log.i(TAG, "Restarted without an intent while the tunnel is not wanted; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundServiceNotification(getString(R.string.notification_restoring))
                startVpn()
            }
        }
        // START_NOT_STICKY: the tunnel must not be resurrected by the system after a process
        // kill unless the persisted intent flag asks for it (handled in the branch above).
        return START_NOT_STICKY
    }

    private fun restartVpn() {
        when (_vpnState.value.status) {
            VpnState.Status.STOPPED -> startVpn()
            // Tear down asynchronously (stopTun is a blocking JNI call) and start again from
            // closeTunnel once the old tunnel is fully gone.
            VpnState.Status.STARTING, VpnState.Status.RUNNING -> {
                pendingStartAfterStop = true
                stopVpn()
            }
            VpnState.Status.STOPPING -> pendingStartAfterStop = true
        }
    }

    private fun startVpn() {
        val status = _vpnState.value.status
        if (startJob?.isActive == true || status == VpnState.Status.STARTING || status == VpnState.Status.RUNNING) {
            // Even if already running or starting, satisfying a startForegroundService() contract
            // requires calling startForeground() promptly to avoid ForegroundServiceDidNotStartInTimeException.
            val currentContent = lastNotificationContent ?: getString(R.string.notification_restoring)
            startForegroundServiceNotification(currentContent)
            return
        }
        if (status == VpnState.Status.STOPPING) {
            // startForegroundService() attached a startForeground obligation to this call even
            // though the tunnel is not ready: satisfy it now and start once teardown finishes.
            startForegroundServiceNotification(getString(R.string.notification_restoring))
            pendingStartAfterStop = true
            return
        }

        val settings = SettingsManager(this)
        val appLanguage = settings.appLanguage
        _vpnState.value = _vpnState.value.copy(
            status = VpnState.Status.STARTING,
            error = null,
            startedAtMs = 0L
        )
        startForegroundServiceNotification(AppStrings.get("notification_starting_core", appLanguage))
        // A new tunnel is about to be established: re-arm the teardown guard, otherwise
        // the guard from the previous tunnel would make the next teardown a no-op and
        // leak the TUN file descriptor.
        resetTeardownGuard()

        startJob = serviceScope.launch {
            try {
                // 0. Refuse to start on a debuggable emulator.
                // The Go core cannot run under ARM translation (SIGILL inside libclash), and
                // MihomoApplication deliberately skips the native init in that case. The
                // service used to call ClashCore.init() unconditionally, which re-entered
                // the very load that crashes and took the process down. Fail with a clear
                // message instead so the UI can explain what happened.
                if (MihomoApplication.isDebuggable() && MihomoApplication.isEmulator()) {
                    error(
                        "The native core cannot run on a debuggable emulator " +
                            "(ARM translation is unsupported). Install a release build on a real device."
                    )
                }

                // 1. Initialize native core
                ClashCore.init(this@MihomoVpnService)
                val coreVer = ClashCore.getCoreVersion()

                // 2. Select and prepare configuration
                val profiles = ConfigManager.getProfiles(this@MihomoVpnService)
                val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
                if (activeProfile != null && activeProfile.id != settings.selectedProfileId) {
                    com.github.mihomo.android.data.LogRepository.addLog(
                        "warning",
                        AppStrings.get("profile_fallback_missing", appLanguage)
                            .replace("{name}", activeProfile.name)
                    )
                }

                // Per-app whitelist with nothing selected used to fall through to "route every
                // app", which is the opposite of the label. Fail fast with an explicit reason
                // instead of silently proxying apps the user meant to leave alone.
                if (settings.perAppProxyMode == PerAppProxyMode.WHITELIST &&
                    settings.selectedPackages.none { it != packageName }
                ) {
                    error(AppStrings.get("whitelist_empty", appLanguage))
                }

                val configFile = withContext(Dispatchers.IO) {
                    ConfigManager.prepareConfig(this@MihomoVpnService, activeProfile?.file)
                }

                // 3. Load config into core
                val loadRes = ClashCore.load(configFile, activeProfile?.id)
                if (loadRes.isFailure) {
                    error(
                        AppStrings.get("vpn_error_load_config", appLanguage)
                            .replace("{msg}", loadRes.exceptionOrNull()?.message ?: "unknown")
                    )
                }
                // From here on the coroutine never suspends, so a stop request must be able to
                // abort before we establish the TUN interface; otherwise it would tear down
                // first and this job would resurrect a ghost tunnel afterwards.
                ensureActive()

                // Restore user's saved proxy selections into core
                runCatching {
                    val allSaved = settings.getAllSelectedNodes()
                    allSaved.forEach { (gName, savedNode) ->
                        ClashCore.patchSelector(gName, savedNode)
                        Log.i(TAG, "Restored group selector: $gName -> $savedNode")
                    }
                }

                // Notify physical network DNS and Timezone to core
                ClashCore.notifyNetworkChanged(this@MihomoVpnService)

                // 4. Establish Android VpnService TUN
                // Some devices reject the IPv6 ULA assignment: when that happens we fall
                // back to an IPv4-only TUN instead of letting the core try to listen on an
                // address that was never assigned ("bind: cannot assign requested address").
                var ipv6TunActive = false
                val builder = Builder().apply {
                    setSession("Nexus")
                    setMtu(settings.tunMtu)
                    // The address plan lives in TunConfig: the same constants are handed to
                    // the native core (ClashCore.startTun) and build the plaintext-DNS
                    // reject rule, so they cannot drift apart.
                    addAddress(TunConfig.IPV4_ADDRESS, TunConfig.IPV4_PREFIX)
                    addRoute("0.0.0.0", 0)
                    addRoute("198.18.0.0", 16) // Explicit route for Fake-IP range!
                    addDnsServer(TunConfig.IPV4_PORTAL)

                    // Kill switch: while the service holds the interface, apps are blocked
                    // from network access whenever the tunnel is not fully connected, so a
                    // stalled reconnect cannot leak the real IP.
                    // setBlocking() only exists from API 29 — on API 26-28 the kill switch
                    // silently has no effect at all.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.killSwitchEnabled) {
                        runCatching { setBlocking(true) }
                            .onFailure { Log.w(TAG, "setBlocking (kill switch) failed", it) }
                    }
                    // allowBypass() lets an app bind its sockets to a NON-VPN network (so a
                    // LAN-only feature such as casting or a printer can still reach the
                    // local network). It is NOT a split-tunnel escape hatch for LAN
                    // destinations: the 0.0.0.0/0 route above still captures all RFC1918
                    // traffic, so LAN destinations keep going through the tunnel.
                    if (settings.allowBypassEnabled) {
                        runCatching { allowBypass() }
                            .onFailure { Log.w(TAG, "allowBypass failed", it) }
                    }

                    // Route IPv6 only when the user enabled it: the injected config sets the
                    // core's top-level `ipv6` from the same switch, so a disabled switch keeps
                    // IPv6 traffic on the physical network instead of failing inside the TUN.
                    if (settings.ipv6Enabled) {
                        runCatching {
                            addAddress(TunConfig.IPV6_ADDRESS, TunConfig.IPV6_PREFIX)
                            addRoute("::", 0)
                            addDnsServer(TunConfig.IPV6_PORTAL)
                            ipv6TunActive = true
                        }.onFailure {
                            Log.w(TAG, "IPv6 TUN setup failed; running the tunnel IPv4-only", it)
                        }
                    }

                    // Per-App routing
                    val packages = settings.selectedPackages
                    when (settings.perAppProxyMode) {
                        PerAppProxyMode.WHITELIST -> {
                            // startVpn() already rejects an empty whitelist, so there is no
                            // silent "no allowed apps -> route everything" fallback here.
                            // The app itself must be included in allowed applications so that
                            // the System and Mixed stacks (which bind a local TCP listener on the
                            // TUN interface) can exchange packets with the TUN interface.
                            (packages + packageName).forEach { pkg ->
                                runCatching { addAllowedApplication(pkg) }
                                    .onFailure { Log.w(TAG, "addAllowedApplication($pkg) failed", it) }
                            }
                        }
                        PerAppProxyMode.BLACKLIST -> {
                            // The app itself must never be disallowed: System and Mixed stacks
                            // require the host UID to receive and respond to TCP connections on TUN.
                            (packages - packageName).forEach { pkg ->
                                runCatching { addDisallowedApplication(pkg) }
                                    .onFailure { Log.w(TAG, "addDisallowedApplication($pkg) failed", it) }
                            }
                        }
                        PerAppProxyMode.DISABLED -> {
                            // Global routing mode: all apps route through the tunnel.
                            // Do not disallow the host app.
                        }
                    }
                }

                val pfd = builder.establish()
                    ?: error(AppStrings.get("vpn_error_establish_tun", appLanguage))
                // Assign the descriptor to the field BEFORE checking for cancellation.
                // The old order (check first, assign later) left a window in which a
                // cancellation could arrive between the two statements: the TUN fd was
                // then never handed to the core and never closed, leaking the interface.
                vpnInterface = pfd
                if (!isActive) {
                    // The cancellation arrived right after establish(): close the fd we
                    // just took ownership of and let the cancellation abort the start.
                    runCatching { pfd.close() }
                    vpnInterface = null
                    currentCoroutineContext().ensureActive()
                }

                // 5. Hand over fd to Mihomo core
                val fd = pfd.detachFd()
                // The IPv6 gateway/portal/DNS entries are only handed to the core when the
                // corresponding address was actually assigned to the TUN above.
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
                        vpnService = this@MihomoVpnService
                    )
                } catch (t: Throwable) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    throw t
                }

                // 6. Report running state
                _vpnState.value = _vpnState.value.copy(
                    status = VpnState.Status.RUNNING,
                    coreVersion = coreVer,
                    error = null,
                    startedAtMs = System.currentTimeMillis()
                )
                // The tunnel is live now: persist the intent so a process death (and the
                // quick-settings tile) still knows the user wants the tunnel running.
                settings.vpnIntentActive = true

                // Structured connection tracking via the external-controller API.
                com.github.mihomo.android.data.RequestRepository.startPolling(
                    scope = serviceScope,
                    context = this@MihomoVpnService,
                    resolver = { protocol, source, target ->
                        queryConnectionOwnerUid(protocol, source, target)
                    }
                )

                ClashCore.setSuspended(!isScreenInteractive)
                if (isScreenInteractive) {
                    startTrafficMonitor()
                } else {
                    updateNotification("\u2191 0 B/s   \u2193 0 B/s")
                }
                Log.i(TAG, "Nexus VPN successfully started! Core: $coreVer")
                com.github.mihomo.android.data.LogRepository.addLog(
                    "info",
                    AppStrings.get("log_vpn_started", appLanguage).replace("{version}", coreVer)
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start Nexus VPN", e)
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: AppStrings.get("vpn_error_unknown", appLanguage)
                com.github.mihomo.android.data.LogRepository.addLog(
                    "error",
                    AppStrings.get("log_vpn_start_failed", appLanguage).replace("{msg}", message)
                )
                _vpnState.value = _vpnState.value.copy(
                    status = VpnState.Status.STOPPED,
                    error = message
                )
                stopSelf()
            }
        }
    }

    private var lastNotificationUpdateTimeMs: Long = 0L

    /** Connection-owner lookup for RequestRepository (API 29+ only, returns 0 below). */
    private fun queryConnectionOwnerUid(protocol: Int, source: String, target: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
        return runCatching {
            // Shared parser: see parseHostPort in TunConfig.kt. It handles bracketed IPv6
            // literals and rejects endpoints without a numeric port.
            val srcAddr = parseHostPort(source)
            val dstAddr = parseHostPort(target)
            if (srcAddr != null && dstAddr != null) {
                cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
            } else 0
        }.getOrDefault(0)
    }

    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        if (!isScreenInteractive) return
        trafficMonitorJob = serviceScope.launch {
            while (isActive && isScreenInteractive && _vpnState.value.status == VpnState.Status.RUNNING) {
                val pollIntervalMs = if (isUiInForeground) 1000L else 3000L
                delay(pollIntervalMs)
                if (!isScreenInteractive || _vpnState.value.status != VpnState.Status.RUNNING) break
                val now = ClashCore.queryTrafficNow()

                val upStr = now.trafficSpeedUpload()
                val downStr = now.trafficSpeedDownload()
                val cur = _vpnState.value
                val speedChanged = cur.upSpeed != upStr || cur.downSpeed != downStr

                // Only query cumulative total traffic when UI is visible or when speed is non-zero
                if (isUiInForeground || now != 0L || speedChanged) {
                    val total = ClashCore.queryTrafficTotal()
                    val totalUpStr = total.trafficUpload()
                    val totalDownStr = total.trafficDownload()
                    val totalChanged = cur.totalUp != totalUpStr || cur.totalDown != totalDownStr
                    if (speedChanged || totalChanged) {
                        _vpnState.value = cur.copy(
                            upSpeed = upStr,
                            downSpeed = downStr,
                            totalUp = totalUpStr,
                            totalDown = totalDownStr
                        )
                    }
                }

                // Throttle SystemUI NotificationManager Binder IPC updates to at most once every 3s
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastNotificationUpdateTimeMs >= 3000L) {
                    lastNotificationUpdateTimeMs = nowMs
                    updateNotification("\u2191 $upStr   \u2193 $downStr")
                }
            }
        }
    }

    private fun stopVpn() {
        if (_vpnState.value.status == VpnState.Status.STOPPED && startJob == null) {
            stopSelf()
            return
        }
        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
        trafficMonitorJob?.cancel()

        val job = startJob
        startJob = null
        if (job != null && job.isActive) {
            // Wait for the in-flight start to finish before tearing down. Cancelling without
            // joining used to race: the start job had no suspension point after ClashCore.load
            // and could establish the TUN after closeTunnel had already reported STOPPED,
            // leaking the fd and leaving the state stuck on RUNNING.
            serviceScope.launch {
                job.cancelAndJoin()
                closeTunnel()
            }
        } else {
            serviceScope.launch { closeTunnel() }
        }
    }
    /**
     * The single native teardown routine.
     *
     * `ClashCore.stopTun()` is a blocking JNI call into the Go core; two concurrent
     * entries there are a data race inside the core. The compare-and-set on
     * [teardownStarted] makes every later caller collapse into the one that is already
     * running instead of starting a competing teardown, and [teardownDone] lets a caller
     * wait for the teardown that is actually in flight.
     *
     * @return true when this call performed the teardown, false when another call was
     *         already doing it.
     */
    private fun performTeardown(): Boolean {
        val owns = synchronized(teardownLock) {
            if (teardownStarted) {
                false
            } else {
                teardownStarted = true
                true
            }
        }
        if (!owns) {
            // Another caller is already tearing the tunnel down; that caller owns the
            // native call. The caller waits for teardownDone instead.
            return false
        }
        // Capture the deferred instance: resetTeardownGuard() replaces the field when a
        // new tunnel starts, and this teardown must complete the instance it belongs to.
        val done = teardownDone
        try {
            com.github.mihomo.android.data.RequestRepository.stopPolling()
            ClashCore.stopTun()
            val pfd = vpnInterface
            vpnInterface = null
            runCatching { pfd?.close() }
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing VPN", e)
        } finally {
            done.complete(Unit)
        }
        return true
    }

    /**
     * Waits for the (single) teardown to finish.
     *
     * When this call did not own the teardown, the native call is still running in the
     * other thread — the state must only be reported as STOPPED once it is really gone.
     */
    private suspend fun awaitTeardown() {
        // Capture the instance once: resetTeardownGuard() may replace the field while we
        // wait, and awaiting a fresh (never-completing) deferred would stall the teardown.
        val done = teardownDone
        if (done.isCompleted) return
        withTimeoutOrNull(TEARDOWN_WAIT_MS.milliseconds) { done.await() }
            ?: Log.w(TAG, "Timed out waiting for the VPN teardown to finish")
    }

    private suspend fun closeTunnel() {
        // The native teardown must not be interrupted half-way. Coroutine cancellation
        // only takes effect at a suspension point, but the state update below still has to
        // run; NonCancellable makes that guarantee explicit instead of accidental.
        withContext(NonCancellable) {
            performTeardown()
            awaitTeardown()
            val restartAfterStop = pendingStartAfterStop
            pendingStartAfterStop = false
            if (!restartAfterStop) {
                // The user asked for the tunnel to go away: clear the persisted intent so a
                // process restart (and the quick-settings tile) does not bring it back.
                // A restart keeps the flag — the tunnel is coming right back up.
                runCatching { SettingsManager(this@MihomoVpnService).vpnIntentActive = false }
            }
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
            if (restartAfterStop) {
                startVpn()
            } else {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
                Log.i(TAG, "Nexus VPN stopped.")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    @Suppress("SameParameterValue")
    private fun startForegroundServiceNotification(content: String) {
        lastNotificationContent = content
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(launchPendingIntent)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The service can be destroyed without ACTION_STOP (process death, low memory), so the
        // native tunnel and the shared state are cleaned up here as well.
        unregisterScreenStateReceiver()
        unregisterPhysicalNetworkCallback()
        // The screen-state receiver lives only as long as this service. Without this reset a
        // teardown while the screen is off left LogRepository (and the native core) gated off
        // forever. Refresh both from the authoritative PowerManager state instead of blindly
        // clearing them, so an actually-off screen keeps its battery-saving gate.
        val interactive = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        com.github.mihomo.android.data.LogRepository.isScreenOff = !interactive
        trafficMonitorJob?.cancel()

        // Cancel the start job so it cannot establish a TUN behind our back. It is JOINED
        // on the teardown thread below (not here): the main thread must not block on a
        // job that may be inside a blocking JNI call.
        val job = startJob
        startJob = null
        job?.cancel()

        // nativeStopTun is a blocking JNI call that can stall for a while with many live
        // connections, so it must not run on the main thread. It goes through the SAME
        // guarded routine as closeTunnel(): performTeardown() performs the teardown at
        // most once, so this can never race a concurrent closeTunnel() into a second
        // concurrent native call.
        val teardownThread = Thread {
            // Join the cancelled start job first: it assigns `vpnInterface` right after
            // establishing the TUN, so the teardown must not read that field before the
            // job is done (otherwise the interface would leak).
            if (job != null) {
                runCatching {
                    kotlinx.coroutines.runBlocking { job.join() }
                }.onFailure { Log.w(TAG, "Failed to join the start job during teardown", it) }
            }
            // setSuspended is a blocking JNI call as well, so it stays off the main thread.
            runCatching { ClashCore.setSuspended(!interactive) }
            performTeardown()
        }.apply {
            name = "mihomo-vpn-teardown"
            isDaemon = true
        }
        teardownThread.start()

        // The scope is cancelled only after the start job has been cancelled, so a
        // closeTunnel() coroutine that is already inside stopTun() cannot be cancelled
        // half-way through the native teardown (closeTunnel runs in NonCancellable).
        serviceScope.cancel()

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (_vpnState.value.status != VpnState.Status.STOPPED) {
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
        }
    }

    override fun onRevoke() {
        // The VPN permission was revoked: the user no longer wants the tunnel.
        runCatching { SettingsManager(this).vpnIntentActive = false }
        stopVpn()
        super.onRevoke()
    }
}


