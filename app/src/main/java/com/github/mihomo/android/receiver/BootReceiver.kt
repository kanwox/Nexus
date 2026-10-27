package com.github.mihomo.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.service.MihomoVpnService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received broadcast action: $action")
        val settingsManager = SettingsManager(context)

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

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

        if (isBoot) {
            if (settingsManager.bootOnStartup) {
                startVpnService(context)
            } else {
                Log.i(TAG, "Boot on startup is disabled, ignoring")
            }
        }
    }

    private fun startVpnService(context: Context) {
        if (android.net.VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not prepared; cannot start on boot")
            return
        }
        val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(context)
        if (profiles.isEmpty()) {
            Log.w(TAG, "No profiles available to start on boot")
            return
        }
        Log.i(TAG, "Boot on startup is enabled with valid profile, launching MihomoVpnService")
        try {
            MihomoVpnService.start(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start VPN on boot: ${e.message}", e)
        }
    }
}
