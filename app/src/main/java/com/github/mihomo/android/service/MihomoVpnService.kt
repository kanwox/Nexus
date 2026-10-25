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
import com.github.mihomo.android.R
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.MainActivity
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

data class VpnState(
    val status: Status = Status.STOPPED,
    val upSpeed: String = "0 B/s",
    val downSpeed: String = "0 B/s",
    val totalUp: String = "0 B",
    val totalDown: String = "0 B",
    val coreVersion: String = "",
    val error: String? = null
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
            if (_vpnState.value.status != VpnState.Status.RUNNING) return
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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trafficMonitorJob: Job? = null
    private var startJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    // Set when a start/restart request arrives while a stop is still tearing the tunnel down;
    // closeTunnel picks it up and launches a fresh start once STOPPED is reached.
    @Volatile
    private var pendingStartAfterStop = false

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
                    ClashCore.setSuspended(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    com.github.mihomo.android.data.LogRepository.isScreenOff = false
                    ClashCore.setSuspended(false)
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

        private fun handlePhysicalNetworkChanged(network: Network) {
            if (_vpnState.value.status != VpnState.Status.RUNNING) return
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
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
            // START_STICKY restart after the process was killed: no intent is delivered. The
            // system still expects startForeground() promptly, then the tunnel is rebuilt.
            else -> {
                startForegroundServiceNotification(getString(R.string.notification_restoring))
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun restartVpn() {
        if (_vpnState.value.status == VpnState.Status.STOPPED) {
            startVpn()
            return
        }
        // Tear down asynchronously (stopTun is a blocking JNI call) and start again from
        // closeTunnel once the old tunnel is fully gone.
        pendingStartAfterStop = true
        stopVpn()
    }

    private fun startVpn() {
        val status = _vpnState.value.status
        if (startJob?.isActive == true || status == VpnState.Status.STARTING || status == VpnState.Status.RUNNING) {
            return
        }
        if (status == VpnState.Status.STOPPING) {
            // startForegroundService() attached a startForeground obligation to this call even
            // though the tunnel is not ready: satisfy it now and start once teardown finishes.
            startForegroundServiceNotification(getString(R.string.notification_restoring))
            pendingStartAfterStop = true
            return
        }

        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STARTING, error = null)
        startForegroundServiceNotification("启动 Nexus 核心")

        startJob = serviceScope.launch {
            try {
                // 1. Initialize native core
                ClashCore.init(this@MihomoVpnService)
                val coreVer = ClashCore.getCoreVersion()

                // 2. Select and prepare configuration
                val settings = SettingsManager(this@MihomoVpnService)
                val profiles = ConfigManager.getProfiles(this@MihomoVpnService)
                val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()

                val configFile = ConfigManager.prepareConfig(this@MihomoVpnService, activeProfile?.file)

                // 3. Load config into core
                val loadRes = ClashCore.load(configFile, activeProfile?.id)
                if (loadRes.isFailure) {
                    error("加载配置文件失败: ${loadRes.exceptionOrNull()?.message}")
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
                    setMtu(9000)
                    addAddress("172.19.0.1", 30)
                    addRoute("0.0.0.0", 0)
                    addRoute("198.18.0.0", 16) // Explicit route for Fake-IP range!
                    addDnsServer("172.19.0.2")

                    // Route IPv6 only when the user enabled it: the injected config sets the
                    // core's top-level `ipv6` from the same switch, so a disabled switch keeps
                    // IPv6 traffic on the physical network instead of failing inside the TUN.
                    if (settings.ipv6Enabled) {
                        runCatching {
                            addAddress("fdfe:dcba:9876::1", 126)
                            addRoute("::", 0)
                            addDnsServer("fdfe:dcba:9876::2")
                            ipv6TunActive = true
                        }.onFailure {
                            Log.w(TAG, "IPv6 TUN setup failed; running the tunnel IPv4-only", it)
                        }
                    }

                    // Per-App routing
                    val packages = settings.selectedPackages
                    when (settings.perAppProxyMode) {
                        PerAppProxyMode.WHITELIST -> {
                            val allowed = packages.filter { it != packageName }
                            if (allowed.isEmpty()) {
                                runCatching { addDisallowedApplication(packageName) }
                            } else {
                                allowed.forEach { pkg ->
                                    runCatching { addAllowedApplication(pkg) }
                                }
                            }
                        }
                        PerAppProxyMode.BLACKLIST -> {
                            packages.forEach { pkg ->
                                runCatching { addDisallowedApplication(pkg) }
                            }
                            runCatching { addDisallowedApplication(packageName) }
                        }
                        PerAppProxyMode.DISABLED -> {
                            runCatching { addDisallowedApplication(packageName) }
                        }
                    }
                }

                val pfd = builder.establish() ?: error("无法建立 VPN TUN 接口 (Builder.establish() 返回 null)")
                if (!isActive) {
                    // vpnInterface is not assigned yet, so this is the only place that can
                    // release the freshly established fd before the cancellation aborts us.
                    runCatching { pfd.close() }
                    currentCoroutineContext().ensureActive()
                }
                vpnInterface = pfd

                // 5. Hand over fd to Mihomo core
                val fd = pfd.detachFd()
                // The IPv6 gateway/portal/DNS entries are only handed to the core when the
                // corresponding address was actually assigned to the TUN above.
                val gatewayStr = if (ipv6TunActive) "172.19.0.1/30,fdfe:dcba:9876::1/126" else "172.19.0.1/30"
                val portalStr = if (ipv6TunActive) "172.19.0.2,fdfe:dcba:9876::2" else "172.19.0.2"
                val dnsStr = if (ipv6TunActive) "172.19.0.2,fdfe:dcba:9876::2" else "172.19.0.2"
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
                    error = null
                )

                ClashCore.setSuspended(!isScreenInteractive)
                if (isScreenInteractive) {
                    startTrafficMonitor()
                } else {
                    updateNotification("\u2191 0 B/s   \u2193 0 B/s")
                }
                Log.i(TAG, "Nexus VPN successfully started! Core: $coreVer")
                com.github.mihomo.android.data.LogRepository.addLog("info", "Nexus VPN 已成功建立！核心版本: $coreVer")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start Nexus VPN", e)
                com.github.mihomo.android.data.LogRepository.addLog("error", "启动失败: ${e.message}")
                _vpnState.value = _vpnState.value.copy(
                    status = VpnState.Status.STOPPED,
                    error = e.message ?: "未知启动错误"
                )
                stopSelf()
            }
        }
    }

    private var lastNotificationUpdateTimeMs: Long = 0L

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

    private fun closeTunnel() {
        try {
            ClashCore.stopTun()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing VPN", e)
        } finally {
            val restartAfterStop = pendingStartAfterStop
            pendingStartAfterStop = false
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
        trafficMonitorJob?.cancel()
        startJob?.cancel()
        serviceScope.cancel()
        val pfd = vpnInterface
        vpnInterface = null
        // nativeStopTun is a blocking JNI call that can stall for a while with many live
        // connections; keep it off the main thread. A daemon thread is enough: on process
        // death the kernel closes the fds anyway. An overlap with an in-flight closeTunnel
        // is tolerated — the normal stop path already runs stopTun twice (closeTunnel, then
        // onDestroy), and ParcelFileDescriptor.close is idempotent.
        Thread {
            runCatching { ClashCore.stopTun() }
            runCatching { pfd?.close() }
        }.apply {
            name = "mihomo-vpn-teardown"
            isDaemon = true
            start()
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (_vpnState.value.status != VpnState.Status.STOPPED) {
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}


