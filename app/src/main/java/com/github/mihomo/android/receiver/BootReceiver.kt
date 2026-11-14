package com.github.mihomo.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.service.MihomoVpnService
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val BROADCAST_TIMEOUT_MS = 8_000L
    }

    @Suppress("InjectDispatcher")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.w(TAG, "Ignoring unsupported broadcast action: $action")
            return
        }
        Log.i(TAG, "Received broadcast action: $action")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(BROADCAST_TIMEOUT_MS.milliseconds) {
                    handleBroadcast(appContext, action)
                    true
                }
                if (completed == null) {
                    Log.w(TAG, "Timed out handling broadcast action: $action")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleBroadcast(context: Context, action: String) {
        val settingsManager = SettingsManager(context)

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // MY_PACKAGE_REPLACED fires on EVERY app update. Restarting the tunnel then
            // would resurrect a tunnel the user had deliberately stopped, so only continue
            // when the persisted intent says the tunnel was running before the update.
            if (!settingsManager.vpnIntentActive) {
                Log.i(TAG, "App updated while the tunnel was stopped; not starting it")
                return
            }
            Log.i(TAG, "App updated while the tunnel was running; restarting it")
            startVpnService(context)
            return
        }

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            if (settingsManager.bootOnStartup) {
                startVpnService(context)
            } else {
                MihomoVpnService.clearPersistedIntent(context)
                Log.i(TAG, "Boot on startup is disabled, ignoring")
            }
        }
    }

    private fun startVpnService(context: Context) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not prepared; clearing persisted intent and skipping boot start")
            MihomoVpnService.clearPersistedIntent(context)
            return
        }
        val profiles = ConfigManager.getProfiles(context)
        if (profiles.isEmpty()) {
            Log.w(TAG, "No profiles available; clearing persisted intent and skipping boot start")
            MihomoVpnService.clearPersistedIntent(context)
            return
        }
        Log.i(TAG, "Boot/update auto-start preconditions met, launching MihomoVpnService")
        MihomoVpnService.start(context)
    }
}
