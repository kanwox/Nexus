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
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficSpeedDownload
import com.github.kr328.clash.core.util.trafficSpeedUpload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.mihomo.android.R
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val SERVICE_TAG = "MihomoVpnService"
internal const val NOTIFICATION_CHANNEL_ID = "mihomo_vpn_service"
internal const val NOTIFICATION_ID = 1001
internal const val TEARDOWN_WAIT_MS = 15_000L
internal const val DESTROY_JOIN_TIMEOUT_MS = 5_000L
internal const val NETWORK_NOTIFY_DEBOUNCE_MS = 150L
internal const val SCREEN_OFF_SUSPEND_DEBOUNCE_MS = 5_000L
internal const val NOTIFICATION_THROTTLE_MS = 3_000L

data class VpnState(
    val status: Status = Status.STOPPED,
    val upSpeed: String = "0 KB/s",
    val downSpeed: String = "0 KB/s",
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

/**
 * Tracks all active physical (non-VPN) networks so losing a secondary network
 * (e.g. cellular dropping while Wi-Fi remains active) falls back cleanly to a
 * remaining healthy physical network instead of clearing tracked DNS state.
 */
internal class PhysicalNetworkCallback(
    private val connectivityProvider: () -> ConnectivityManager?,
    private val onPhysicalNetworkEvent: () -> Unit,
) : ConnectivityManager.NetworkCallback() {
    private val lock = Any()
    private val activePhysicalNetworks = LinkedHashSet<Network>()
    private var lastDispatchedNetwork: Network? = null
    private var lastDispatchedDnsSignature: String? = null

    override fun onAvailable(network: Network) {
        if (!isPhysicalNetwork(network)) return
        val shouldNotify = synchronized(lock) {
            activePhysicalNetworks.add(network)
            ClashCore.updateTrackedPhysicalNetwork(network)
            if (lastDispatchedNetwork != network) {
                lastDispatchedNetwork = network
                lastDispatchedDnsSignature = null
                true
            } else {
                false
            }
        }
        if (shouldNotify) onPhysicalNetworkEvent()
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        if (!isPhysicalNetwork(network)) return
        val dnsSig = linkProperties.dnsServers.mapNotNull { it.hostAddress }.joinToString(",")
        val shouldNotify = synchronized(lock) {
            activePhysicalNetworks.add(network)
            ClashCore.updateTrackedPhysicalNetwork(network)
            if (lastDispatchedNetwork != network || lastDispatchedDnsSignature != dnsSig) {
                lastDispatchedNetwork = network
                lastDispatchedDnsSignature = dnsSig
                true
            } else {
                false
            }
        }
        if (shouldNotify) onPhysicalNetworkEvent()
    }

    override fun onLost(network: Network) {
        Log.i(SERVICE_TAG, "Physical network lost: $network")
        synchronized(lock) {
            activePhysicalNetworks.remove(network)
            if (lastDispatchedNetwork == network) {
                val fallback = activePhysicalNetworks.lastOrNull { isPhysicalNetwork(it) }
                lastDispatchedNetwork = fallback
                lastDispatchedDnsSignature = null
                ClashCore.updateTrackedPhysicalNetwork(fallback)
            }
        }
        onPhysicalNetworkEvent()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val shouldNotify = synchronized(lock) {
            activePhysicalNetworks.add(network)
            ClashCore.updateTrackedPhysicalNetwork(network)
            if (lastDispatchedNetwork != network) {
                lastDispatchedNetwork = network
                lastDispatchedDnsSignature = null
                true
            } else {
                false
            }
        }
        if (shouldNotify) onPhysicalNetworkEvent()
    }

    fun clear() {
        synchronized(lock) {
            activePhysicalNetworks.clear()
            lastDispatchedNetwork = null
            lastDispatchedDnsSignature = null
        }
    }

    private fun isPhysicalNetwork(network: Network): Boolean {
        val cm = connectivityProvider() ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        return caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

internal class VpnSystemMonitors(
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
            networkCallback.clear()
            networkCallbackRegistered = false
        }
    }
}

internal class VpnNotificationController(
    private val service: Service,
) {
    @Volatile
    private var lastContent: String? = null

    @Volatile
    private var lastUpdateTimeMs: Long = 0L

    @Volatile
    private var isInForeground: Boolean = false

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

    fun hasActiveForeground(): Boolean = isInForeground

    fun startForegroundNotification(content: String): Boolean {
        return runCatching {
            lastContent = content
            val notification = buildNotification(content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
            isInForeground = true
            true
        }.onFailure { error ->
            Log.w(SERVICE_TAG, "Failed to promote VPN service to foreground", error)
        }.getOrDefault(false)
    }

    fun updateNotification(content: String, throttled: Boolean) {
        if (content == lastContent) return
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val nowMs = System.currentTimeMillis()
        if (throttled && nowMs - lastUpdateTimeMs < NOTIFICATION_THROTTLE_MS) return
        if (throttled) {
            lastUpdateTimeMs = nowMs
        }
        lastContent = content
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(content)) }
    }

    fun stopForegroundAndReset() {
        runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        isInForeground = false
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

internal class VpnTrafficMonitor(
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

internal fun computeUpdatedTrafficSnapshot(
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
