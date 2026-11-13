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
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficSpeedDownload
import com.github.kr328.clash.core.util.trafficSpeedUpload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.mihomo.android.MihomoApplication
import com.github.mihomo.android.R
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.core.TunConfig
import com.github.mihomo.android.core.parseHostPort
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.MainActivity
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

    private sealed interface ServiceCommand {
        data class Start(val startId: Int, val seq: Long) : ServiceCommand
        data class Restart(val startId: Int, val seq: Long) : ServiceCommand
        data class Stop(
            val startId: Int?,
            val seq: Long,
            val clearPersistedIntent: Boolean = true
        ) : ServiceCommand
        data class UnwantedNullStart(val startId: Int, val seq: Long) : ServiceCommand
        data class StartSucceeded(
            val token: Long,
            val coreVersion: String,
            val appLanguage: String
        ) : ServiceCommand
        data class StartFailed(val token: Long, val message: String) : ServiceCommand
    }

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

        private val nextInstanceId = AtomicLong(0L)
        private val instanceLock = Any()
        private var activeInstanceId: Long = 0L

        private val destroyLock = Any()

        @Volatile
        private var inFlightDestroyTeardown: CompletableDeferred<Unit>? = null

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
            if (_vpnState.value.status == VpnState.Status.STOPPED) {
                clearPersistedIntent(context)
                return
            }
            val intent = Intent(context, MihomoVpnService::class.java).apply {
                action = ACTION_STOP
            }
            // Background-start restrictions can reject this; the tunnel cleanup also runs from
            // onDestroy, so a rejected command is not fatal.
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.w(TAG, "Failed to deliver stop command to VPN service", it)
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

        /**
         * Kept for binary compatibility; callers should launch the service from foreground/user action.
         */
        @Suppress("unused")
        fun resumeIfIntentActive(context: Context) {
            val active = runCatching { SettingsManager(context).vpnIntentActive }.getOrDefault(false)
            if (!active) return
            Log.i(TAG, "Resuming the tunnel: the persisted intent says it should be running")
            start(context)
        }

        private suspend fun awaitInFlightDestroyTeardown() {
            val pending = synchronized(destroyLock) { inFlightDestroyTeardown } ?: return
            if (pending.isCompleted) return
            withTimeoutOrNull(TEARDOWN_WAIT_MS.milliseconds) { pending.await() }
                ?: Log.w(TAG, "Timed out waiting for prior service instance teardown")
        }
    }

    private val instanceId: Long = nextInstanceId.incrementAndGet()

    // The scope performs blocking JNI calls (core init, config load, TUN teardown) and
    // binder calls (Builder.establish(), ParcelFileDescriptor.close()), so it must run on
    // the IO dispatcher: Dispatchers.Default has only a small CPU-bound pool and a
    // blocking binder round-trip there can starve it.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandChannel = Channel<ServiceCommand>(Channel.UNLIMITED)
    private val suspendChannel = Channel<Boolean>(Channel.UNLIMITED)
    private val networkChangeChannel = Channel<Unit>(Channel.CONFLATED)

    private val commandSeq = AtomicLong(0L)
    private val latestStopSeq = AtomicLong(0L)
    private val latestStartOrRestartSeq = AtomicLong(0L)
    private val latestStartId = AtomicInteger(0)

    private val foregroundSyncLock = Any()
    private val trafficMonitorLock = Any()

    @Volatile
    private var trafficMonitorJob: Job? = null

    private var actorJob: Job? = null
    private var suspendJob: Job? = null
    private var networkNotifyJob: Job? = null

    @Volatile
    private var activeStartJob: Job? = null

    @Volatile
    private var activeStartToken: Long = 0L

    @Volatile
    private var activeStartSeq: Long = 0L

    private var nextStartToken: Long = 0L

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var tunNeedsStop: Boolean = false

    @Volatile
    private var isDestroying: Boolean = false

    /**
     * Guards the native teardown so it runs at most once per active tunnel.
     */
    @Volatile
    private var teardownStarted = false

    /** Serializes the compare-and-set on [teardownStarted]. */
    private val teardownLock = Any()

    /**
     * Completes when the teardown routine for the current tunnel finishes.
     */
    @Volatile
    private var teardownDone = CompletableDeferred(Unit)

    /**
     * Re-arms the teardown guard for a new tunnel.
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

    private var lastNotificationUpdateTimeMs: Long = 0L

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
                    LogRepository.isScreenOff = true
                    stopTrafficMonitor()
                    // nativeSuspend is a blocking JNI call and the receiver runs on the
                    // main thread; serialize through suspendChannel to preserve toggle order.
                    suspendChannel.trySend(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    LogRepository.isScreenOff = false
                    suspendChannel.trySend(false)
                    if (_vpnState.value.status == VpnState.Status.RUNNING) {
                        startTrafficMonitor()
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            dispatchPhysicalNetworkChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            dispatchPhysicalNetworkChanged(network)
        }

        /**
         * The physical network went away (e.g. Wi-Fi dropped while moving out of range).
         */
        override fun onLost(network: Network) {
            Log.i(TAG, "Physical network lost: $network")
            dispatchPhysicalNetworkChanged(network)
        }

        /**
         * The network's capabilities changed — typically a Wi-Fi -> cellular handover.
         */
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            dispatchPhysicalNetworkChanged(network)
        }

        private fun dispatchPhysicalNetworkChanged(network: Network) {
            if (_vpnState.value.status != VpnState.Status.RUNNING) return
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            // The tunnel's own interface is not a physical network; telling the core about
            // it would report the tunnel's own DNS as the physical one.
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            networkChangeChannel.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(instanceLock) {
            activeInstanceId = instanceId
        }
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        isScreenInteractive = pm?.isInteractive ?: true
        LogRepository.isScreenOff = !isScreenInteractive
        registerScreenStateReceiver()
        registerPhysicalNetworkCallback()
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
                runCatching { ClashCore.setSuspended(suspended) }
            }
        }
        networkNotifyJob = serviceScope.launch {
            for (ignored in networkChangeChannel) {
                if (_vpnState.value.status == VpnState.Status.RUNNING) {
                    runCatching { ClashCore.notifyNetworkChanged(this@MihomoVpnService) }
                }
            }
        }
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
        latestStartId.set(startId)
        return when (intent?.action) {
            ACTION_START, VpnService.SERVICE_INTERFACE -> {
                enqueueStart(startId, startingNotificationText())
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
            // System sticky restart (null intent) or unknown action: only restore if persisted intent is active.
            else -> handleNullOrUnknownStart(startId)
        }
    }

    private fun handleNullOrUnknownStart(startId: Int): Int {
        val shouldRestore = runCatching { SettingsManager(this).vpnIntentActive }.getOrDefault(false)
        if (!shouldRestore) {
            Log.i(TAG, "Restarted without an active intent while the tunnel is not wanted; stopping")
            val seq = commandSeq.incrementAndGet()
            commandChannel.trySend(ServiceCommand.UnwantedNullStart(startId, seq))
            return START_NOT_STICKY
        }
        val foregroundOk = enqueueStart(startId, getString(R.string.notification_restoring), safeStart = true)
        return if (foregroundOk) START_STICKY else START_NOT_STICKY
    }

    private fun startingNotificationText(): String {
        val current = lastNotificationContent
        if (current != null) return current
        val lang = runCatching { SettingsManager(this).appLanguage }.getOrDefault("auto")
        return AppStrings.get("notification_starting_core", lang)
    }

    private fun enqueueStart(startId: Int, notificationText: String, safeStart: Boolean = false): Boolean {
        val seq = commandSeq.incrementAndGet()
        synchronized(foregroundSyncLock) {
            latestStartOrRestartSeq.set(seq)
            if (safeStart) {
                val started = runCatching { startForegroundServiceNotification(notificationText) }
                    .onFailure { Log.w(TAG, "Foreground promotion rejected during sticky restore", it) }
                    .isSuccess
                if (!started) {
                    val stopSeq = commandSeq.incrementAndGet()
                    commandChannel.trySend(ServiceCommand.UnwantedNullStart(startId, stopSeq))
                    return false
                }
            } else {
                startForegroundServiceNotification(notificationText)
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
        if (latestStopSeq.get() > cmd.seq) return
        if (isStartingOrRunning()) return
        launchStartPipeline(nextStartToken + 1L, cmd.seq)
    }

    private suspend fun handleRestart(cmd: ServiceCommand.Restart) {
        if (latestStopSeq.get() > cmd.seq) return
        if (isStartingOrRunning()) {
            _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
            stopTrafficMonitor()
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
        if (_vpnState.value.status == VpnState.Status.STOPPED && activeStartJob == null) {
            if (cmd.clearPersistedIntent) {
                clearPersistedIntent(this)
            }
            stopServiceIfIdle(cmd.seq)
            return
        }
        _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
        stopTrafficMonitor()
        withContext(NonCancellable) {
            cancelAndJoinActiveStart()
            performTeardown()
            awaitTeardown()
            if (cmd.clearPersistedIntent) {
                clearPersistedIntent(this@MihomoVpnService)
            }
            _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
            stopServiceIfIdle(cmd.seq)
            Log.i(TAG, "Nexus VPN stopped.")
        }
    }

    private fun handleUnwantedNullStart(cmd: ServiceCommand.UnwantedNullStart) {
        if (_vpnState.value.status != VpnState.Status.STOPPED || activeStartJob != null) return
        synchronized(foregroundSyncLock) {
            if (latestStartOrRestartSeq.get() < cmd.seq) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                lastNotificationContent = null
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

        val settings = SettingsManager(this)
        _vpnState.value = _vpnState.value.copy(
            status = VpnState.Status.RUNNING,
            coreVersion = cmd.coreVersion,
            error = null,
            startedAtMs = System.currentTimeMillis()
        )
        // The tunnel is live now: persist the intent so a process restart knows it should run.
        settings.vpnIntentActive = true

        RequestRepository.startPolling(
            scope = serviceScope,
            context = this@MihomoVpnService,
            resolver = { protocol, source, target ->
                queryConnectionOwnerUid(protocol, source, target)
            }
        )

        val interactive = isScreenInteractive
        LogRepository.isScreenOff = !interactive
        suspendChannel.trySend(!interactive)
        if (interactive) {
            startTrafficMonitor()
        } else {
            updateNotification("\u2191 0 B/s   \u2193 0 B/s")
        }
        Log.i(TAG, "Nexus VPN successfully started! Core: ${cmd.coreVersion}")
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
        stopTrafficMonitor()
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
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                lastNotificationContent = null
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
        lastNotificationUpdateTimeMs = 0L
        synchronized(foregroundSyncLock) {
            lastNotificationContent = null
            startForegroundServiceNotification(AppStrings.get("notification_starting_core", appLanguage))
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
                Log.e(TAG, "Failed to start Nexus VPN", e)
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
        // Never overlap ClashCore init/load/startTun with a previous service instance's teardown.
        awaitInFlightDestroyTeardown()
        currentCoroutineContext().ensureActive()

        // 0. Refuse to start on a debuggable emulator.
        // The Go core cannot run under ARM translation (SIGILL inside libclash), and
        // MihomoApplication deliberately skips the native init in that case.
        if (MihomoApplication.isDebuggable() && MihomoApplication.isEmulator()) {
            error(
                "The native core cannot run on a debuggable emulator " +
                    "(ARM translation is unsupported). Install a release build on a real device."
            )
        }

        // 1. Initialize native core
        ClashCore.init(this@MihomoVpnService)
        val coreVer = ClashCore.getCoreVersion()

        // 2. Select profile & validate routing mode
        val activeProfile = selectActiveProfile(settings, appLanguage)
        // Per-app whitelist with nothing selected used to fall through to "route every app".
        if (settings.perAppProxyMode == PerAppProxyMode.WHITELIST &&
            settings.selectedPackages.none { it != packageName }
        ) {
            error(AppStrings.get("whitelist_empty", appLanguage))
        }

        // 3. Serialized prepareConfig -> load -> patchSelector -> notifyNetworkChanged
        val loadRes = ClashCore.prepareAndLoadProfile(
            context = this@MihomoVpnService,
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

        // 4. Establish Android VpnService TUN and hand fd to Mihomo core
        val (builder, ipv6TunActive) = buildVpnInterface(settings)
        establishAndStartTun(builder, ipv6TunActive, settings, appLanguage)
        return coreVer
    }

    private fun selectActiveProfile(
        settings: SettingsManager,
        appLanguage: String
    ): ProfileItem? {
        val profiles = ConfigManager.getProfiles(this@MihomoVpnService)
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

    private fun buildVpnInterface(settings: SettingsManager): Pair<Builder, Boolean> {
        // Some devices reject the IPv6 ULA assignment: when that happens we fall
        // back to an IPv4-only TUN instead of letting the core try to listen on an
        // address that was never assigned ("bind: cannot assign requested address").
        var ipv6TunActive = false
        val builder = Builder().apply {
            setSession("Nexus")
            setMtu(settings.tunMtu)
            // The address plan lives in TunConfig: the same constants are handed to
            // the native core (ClashCore.startTun) and build the plaintext-DNS reject rule.
            addAddress(TunConfig.IPV4_ADDRESS, TunConfig.IPV4_PREFIX)
            addRoute("0.0.0.0", 0)
            addRoute("198.18.0.0", 16) // Explicit route for Fake-IP range!
            addDnsServer(TunConfig.IPV4_PORTAL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.killSwitchEnabled) {
                runCatching { setBlocking(true) }
                    .onFailure { Log.w(TAG, "setBlocking (kill switch) failed", it) }
            } else if (settings.killSwitchEnabled) {
                LogRepository.addLog(
                    "warning",
                    "本系统版本 (API ${Build.VERSION.SDK_INT}) 不支持断网保护，该开关在本机不生效"
                )
            }
            // allowBypass() lets an app bind its sockets to a NON-VPN network (LAN features).
            if (settings.allowBypassEnabled) {
                runCatching { allowBypass() }
                    .onFailure { Log.w(TAG, "allowBypass failed", it) }
            }

            // Route IPv6 only when the user enabled it so disabled switch keeps IPv6 physical.
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

            configurePerAppRouting(this, settings)
        }
        return builder to ipv6TunActive
    }

    private fun configurePerAppRouting(builder: Builder, settings: SettingsManager) {
        val packages = settings.selectedPackages
        when (settings.perAppProxyMode) {
            PerAppProxyMode.WHITELIST -> {
                // Host app must be included so System and Mixed stacks can accept TUN TCP packets.
                (packages + packageName).forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "addAllowedApplication($pkg) failed", it) }
                }
            }
            PerAppProxyMode.BLACKLIST -> {
                // Host app must never be disallowed for System/Mixed stacks.
                (packages - packageName).forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "addDisallowedApplication($pkg) failed", it) }
                }
            }
            PerAppProxyMode.DISABLED -> Unit
        }
    }

    private suspend fun establishAndStartTun(
        builder: Builder,
        ipv6TunActive: Boolean,
        settings: SettingsManager,
        appLanguage: String
    ) {
        val pfd = builder.establish()
            ?: error(AppStrings.get("vpn_error_establish_tun", appLanguage))
        // Assign the descriptor before checking cancellation so teardown can always close it.
        vpnInterface = pfd
        if (!currentCoroutineContext().isActive) {
            runCatching { pfd.close() }
            vpnInterface = null
            currentCoroutineContext().ensureActive()
        }

        val fd = pfd.detachFd()
        vpnInterface = null
        // The IPv6 gateway/portal/DNS entries are only handed to the core when assigned above.
        val gatewayStr = if (ipv6TunActive) TunConfig.GATEWAY else TunConfig.GATEWAY_IPV4
        val portalStr = if (ipv6TunActive) TunConfig.PORTAL else TunConfig.PORTAL_IPV4
        val dnsStr = if (ipv6TunActive) TunConfig.DNS else TunConfig.DNS_IPV4
        tunNeedsStop = true
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
    }

    /** Connection-owner lookup for RequestRepository (API 29+ only, returns 0 below). */
    private fun queryConnectionOwnerUid(protocol: Int, source: String, target: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
        return runCatching {
            // Shared parser: see parseHostPort in TunConfig.kt.
            val srcAddr = parseHostPort(source)
            val dstAddr = parseHostPort(target)
            if (srcAddr != null && dstAddr != null) {
                cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
            } else {
                0
            }
        }.getOrDefault(0)
    }

    private fun startTrafficMonitor() {
        synchronized(trafficMonitorLock) {
            trafficMonitorJob?.cancel()
            if (!isScreenInteractive || _vpnState.value.status != VpnState.Status.RUNNING) {
                trafficMonitorJob = null
                return
            }
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
    }

    private fun stopTrafficMonitor() {
        synchronized(trafficMonitorLock) {
            trafficMonitorJob?.cancel()
            trafficMonitorJob = null
        }
    }

    /**
     * The single native teardown routine.
     *
     * @return true when this call performed the teardown, false when another call already ran it.
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
            return false
        }
        val done = teardownDone
        try {
            RequestRepository.stopPolling()
            if (tunNeedsStop) {
                tunNeedsStop = false
                ClashCore.stopTun()
            }
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

    private suspend fun awaitTeardown() {
        val done = teardownDone
        if (done.isCompleted) return
        withTimeoutOrNull(TEARDOWN_WAIT_MS.milliseconds) { done.await() }
            ?: Log.w(TAG, "Timed out waiting for the VPN teardown to finish")
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
        isDestroying = true
        unregisterScreenStateReceiver()
        unregisterPhysicalNetworkCallback()
        LogRepository.isScreenOff = false
        stopTrafficMonitor()

        val activeStart = activeStartJob
        activeStartJob = null
        activeStartToken = 0L
        activeStart?.cancel()

        val actor = actorJob
        val suspendConsumer = suspendJob
        val networkConsumer = networkNotifyJob
        actor?.cancel()
        suspendConsumer?.cancel()
        networkConsumer?.cancel()
        commandChannel.close()
        suspendChannel.close()
        networkChangeChannel.close()

        val ownedByThisInstance = synchronized(instanceLock) { activeInstanceId == instanceId }
        if (ownedByThisInstance && _vpnState.value.status != VpnState.Status.STOPPED) {
            _vpnState.value = _vpnState.value.copy(status = VpnState.Status.STOPPING, error = null)
        }

        val destroyDone = CompletableDeferred<Unit>()
        synchronized(destroyLock) {
            inFlightDestroyTeardown = destroyDone
        }

        val teardownThread = Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    runCatching { activeStart?.join() }
                    runCatching { actor?.join() }
                    runCatching { suspendConsumer?.join() }
                    runCatching { networkConsumer?.join() }
                }
                runCatching { ClashCore.setSuspended(false) }
                performTeardown()
            } finally {
                destroyDone.complete(Unit)
                synchronized(destroyLock) {
                    if (inFlightDestroyTeardown === destroyDone) {
                        inFlightDestroyTeardown = null
                    }
                }
                val isStillOwner = synchronized(instanceLock) { activeInstanceId == instanceId }
                if (isStillOwner && _vpnState.value.status != VpnState.Status.STOPPED) {
                    _vpnState.value = VpnState(status = VpnState.Status.STOPPED)
                }
            }
        }.apply {
            name = "mihomo-vpn-teardown"
            isDaemon = true
        }
        teardownThread.start()

        serviceScope.cancel()
        synchronized(foregroundSyncLock) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            lastNotificationContent = null
        }
    }

    override fun onRevoke() {
        // The VPN permission was revoked: the user no longer wants the tunnel.
        clearPersistedIntent(this)
        val seq = commandSeq.incrementAndGet()
        latestStopSeq.set(seq)
        commandChannel.trySend(ServiceCommand.Stop(startId = null, seq = seq, clearPersistedIntent = true))
        super.onRevoke()
    }
}
