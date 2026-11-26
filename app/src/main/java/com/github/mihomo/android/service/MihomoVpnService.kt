@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
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

private fun requestTileRefreshSafely(context: Context) {
    runCatching {
        TileService.requestListeningState(
            context.applicationContext,
            ComponentName(context.applicationContext, MihomoTileService::class.java)
        )
    }
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
    if (MihomoApplication.shouldSkipNativeCore()) {
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
        val causeMsg = loadRes.exceptionOrNull()?.message ?: "unknown"
        error(
            AppStrings.get("vpn_error_load_config", appLanguage)
                .replace("{msg}", causeMsg)
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
        addDnsServer(TunConfig.IPV4_PORTAL)

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
            // Host app is intentionally excluded from the tunnel (README): only selected
            // external apps are routed through TUN so subscription/update recovery always works.
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
            runCatching { builder.addDisallowedApplication(hostPackageName) }
                .onFailure { Log.w(SERVICE_TAG, "addDisallowedApplication($hostPackageName) failed", it) }
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
        PerAppProxyMode.DISABLED -> {
            runCatching { builder.addDisallowedApplication(hostPackageName) }
                .onFailure { Log.w(SERVICE_TAG, "addDisallowedApplication($hostPackageName) failed", it) }
        }
    }
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
    isOwnerProvider: () -> Boolean,
    onPerformTeardown: () -> Unit,
    onFinallyComplete: () -> Unit,
) {
    Thread {
        try {
            runBlocking {
                withTimeoutOrNull(DESTROY_JOIN_TIMEOUT_MS.milliseconds) {
                    for (job in jobsToJoin) {
                        job?.join()
                    }
                } ?: Log.w(SERVICE_TAG, "Timed out joining VPN worker jobs during onDestroy")
            }
            if (isOwnerProvider()) {
                applyCoreSuspendedSafely(false)
            }
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

        private val startedUiCount = AtomicInteger(0)

        var isUiInForeground: Boolean
            get() = startedUiCount.get() > 0
            set(value) {
                if (value) {
                    onUiStarted()
                } else {
                    onUiStopped()
                }
            }

        fun onUiStarted() {
            startedUiCount.incrementAndGet()
        }

        fun onUiStopped() {
            startedUiCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }

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
    private val tunStateLock = Any()
    private val teardownLock = Any()

    private var actorJob: Job? = null
    private var suspendJob: Job? = null
    private var networkNotifyJob: Job? = null
    @Volatile private var screenOffSuspendJob: Job? = null

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
                scheduleScreenOffSuspend()
            },
            onScreenOn = {
                isScreenInteractive = true
                LogRepository.isScreenOff = false
                screenOffSuspendJob?.cancel()
                screenOffSuspendJob = null
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

    private fun scheduleScreenOffSuspend() {
        screenOffSuspendJob?.cancel()
        screenOffSuspendJob = serviceScope.launch {
            delay(SCREEN_OFF_SUSPEND_DEBOUNCE_MS.milliseconds)
            if (!isScreenInteractive && !isDestroying) {
                suspendChannel.trySend(true)
            }
        }
    }

    private fun isOwnedByThisInstance(): Boolean =
        synchronized(instanceLock) { activeInstanceId == instanceId }

    private fun updateStateAndNotifyTile(state: VpnState) {
        val statusChanged = _vpnState.value.status != state.status
        _vpnState.value = state
        if (statusChanged) {
            requestTileRefreshSafely(this)
        }
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
                try {
                    processCommand(cmd)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(SERVICE_TAG, "Unhandled error processing command $cmd", t)
                    recoverFromActorError(t)
                }
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

    private suspend fun recoverFromActorError(error: Throwable) {
        trafficMonitor.stop()
        withContext(NonCancellable) {
            runCatching { cancelAndJoinActiveStart() }
            runCatching { performTeardown() }
            runCatching { awaitTeardown() }
            if (isOwnedByThisInstance()) {
                updateStateAndNotifyTile(
                    VpnState(status = VpnState.Status.STOPPED, error = error.message)
                )
            }
            stopServiceIfIdle(commandSeq.get())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        return when (intent?.action) {
            ACTION_START, SERVICE_INTERFACE -> {
                val ok = enqueueStart(startId, notificationController.currentOrDefaultStartingText())
                if (ok) START_STICKY else START_NOT_STICKY
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
                synchronized(foregroundSyncLock) {
                    if (!notificationController.hasActiveForeground() &&
                        _vpnState.value.status != VpnState.Status.STOPPED
                    ) {
                        notificationController.startForegroundNotification(
                            notificationController.currentOrDefaultStartingText()
                        )
                    }
                }
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
        val foregroundOk = enqueueStart(startId, getString(R.string.notification_restoring))
        return if (foregroundOk) START_STICKY else START_NOT_STICKY
    }

    private fun enqueueStart(startId: Int, notificationText: String): Boolean {
        val seq = commandSeq.incrementAndGet()
        synchronized(foregroundSyncLock) {
            latestStartOrRestartSeq.set(seq)
            val started = notificationController.startForegroundNotification(notificationText)
            if (!started) {
                val stopSeq = commandSeq.incrementAndGet()
                commandChannel.trySend(ServiceCommand.UnwantedNullStart(startId, stopSeq))
                return false
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
            updateStateAndNotifyTile(_vpnState.value.copy(status = VpnState.Status.STOPPING, error = null))
            trafficMonitor.stop()
            withContext(NonCancellable) {
                cancelAndJoinActiveStart()
                performTeardown()
                awaitTeardown()
            }
            currentCoroutineContext().ensureActive()
            updateStateAndNotifyTile(VpnState(status = VpnState.Status.STOPPED))
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
        updateStateAndNotifyTile(_vpnState.value.copy(status = VpnState.Status.STOPPING, error = null))
        trafficMonitor.stop()
        withContext(NonCancellable) {
            cancelAndJoinActiveStart()
            performTeardown()
            awaitTeardown()
            if (cmd.clearPersistedIntent) clearPersistedIntent(this@MihomoVpnService)
            updateStateAndNotifyTile(VpnState(status = VpnState.Status.STOPPED))
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
        updateStateAndNotifyTile(
            _vpnState.value.copy(
                status = VpnState.Status.RUNNING,
                coreVersion = cmd.coreVersion,
                error = null,
                startedAtMs = System.currentTimeMillis()
            )
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
            notificationController.updateNotification("\u2191 0 KB/s   \u2193 0 KB/s", throttled = false)
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
            updateStateAndNotifyTile(VpnState(status = VpnState.Status.STOPPED, error = cmd.message))
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
        updateStateAndNotifyTile(
            _vpnState.value.copy(
                status = VpnState.Status.STARTING,
                error = null,
                startedAtMs = 0L
            )
        )
        val promoted = synchronized(foregroundSyncLock) {
            notificationController.resetState(clearContent = true)
            notificationController.startForegroundNotification(
                AppStrings.get("notification_starting_core", appLanguage)
            )
        }
        if (!promoted) {
            val msg = AppStrings.get("vpn_error_unknown", appLanguage)
            updateStateAndNotifyTile(VpnState(status = VpnState.Status.STOPPED, error = msg))
            stopServiceIfIdle(startSeq)
            return
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
        val active = currentCoroutineContext().isActive
        val fd = synchronized(tunStateLock) {
            if (!active || isDestroying) {
                closePfdSafely(pfd)
                null
            } else {
                vpnInterface = pfd
                val detached = pfd.detachFd()
                vpnInterface = null
                detached
            }
        }
        if (fd == null) {
            currentCoroutineContext().ensureActive()
            return
        }
        startNativeTunOrCloseFd(this@MihomoVpnService, fd, ipv6TunActive, settings)
        synchronized(tunStateLock) {
            tunNeedsStop = true
        }
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
            val isOwner = isOwnedByThisInstance()
            if (isOwner) {
                RequestRepository.stopPolling()
            }
            val shouldStopTun = synchronized(tunStateLock) {
                val active = tunNeedsStop
                tunNeedsStop = false
                active
            }
            if (shouldStopTun && isOwner) {
                ClashCore.stopTun()
            }
            val pfd = synchronized(tunStateLock) {
                val current = vpnInterface
                vpnInterface = null
                current
            }
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
        screenOffSuspendJob?.cancel()
        screenOffSuspendJob = null
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

        val ownedByThisInstance = isOwnedByThisInstance()
        if (ownedByThisInstance && _vpnState.value.status != VpnState.Status.STOPPED) {
            updateStateAndNotifyTile(_vpnState.value.copy(status = VpnState.Status.STOPPING, error = null))
        }

        val destroyDone = CompletableDeferred<Unit>()
        synchronized(destroyLock) { inFlightDestroyTeardown = destroyDone }

        spawnVpnDestroyTeardownThread(
            jobsToJoin = jobsToJoin,
            isOwnerProvider = ::isOwnedByThisInstance,
            onPerformTeardown = ::performTeardown,
            onFinallyComplete = {
                destroyDone.complete(Unit)
                synchronized(destroyLock) {
                    if (inFlightDestroyTeardown === destroyDone) inFlightDestroyTeardown = null
                }
                if (isOwnedByThisInstance() && _vpnState.value.status != VpnState.Status.STOPPED) {
                    updateStateAndNotifyTile(VpnState(status = VpnState.Status.STOPPED))
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
