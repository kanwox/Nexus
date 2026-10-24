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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        }
        return START_NOT_STICKY
    }

    private fun restartVpn() {
        startJob?.cancel()
        startJob = null
        trafficMonitorJob?.cancel()
        runCatching { ClashCore.stopTun() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPED, error = null)
        startVpn()
    }

    private fun startVpn() {
        val status = _vpnState.value.status
        if (startJob?.isActive == true || status != VpnState.Status.STOPPED) {
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
                val builder = Builder().apply {
                    setSession("Nexus")
                    setMtu(9000)
                    addAddress("172.19.0.1", 30)
                    addRoute("0.0.0.0", 0)
                    addRoute("198.18.0.0", 16) // Explicit route for Fake-IP range!
                    addDnsServer("172.19.0.2")

                    // Route IPv6 to prevent IPv6 traffic bypassing VPN on cellular / Wi-Fi
                    runCatching {
                        addAddress("fdfe:dcba:9876::1", 126)
                        addRoute("::", 0)
                        addDnsServer("fdfe:dcba:9876::2")
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
                vpnInterface = pfd

                // 5. Hand over fd to Mihomo core
                val fd = pfd.detachFd()
                try {
                    ClashCore.startTun(
                        fd = fd,
                        stack = settings.tunStack,
                        gateway = "172.19.0.1/30,fdfe:dcba:9876::1/126",
                        portal = "172.19.0.2,fdfe:dcba:9876::2",
                        dns = "172.19.0.2,fdfe:dcba:9876::2",
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
        if (_vpnState.value.status == VpnState.Status.STOPPED) {
            stopSelf()
            return
        }
        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING)
        // Cancel an in-flight start first: otherwise it can establish the TUN and flip the state
        // back to RUNNING after we have already torn down and reported STOPPED.
        startJob?.cancel()
        startJob = null
        trafficMonitorJob?.cancel()

        serviceScope.launch { closeTunnel() }
    }

    private fun closeTunnel() {
        try {
            ClashCore.stopTun()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing VPN", e)
        } finally {
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            Log.i(TAG, "Nexus VPN stopped.")
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
        runCatching { ClashCore.stopTun() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
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


