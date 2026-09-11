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
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val settingsManager = SettingsManager(context)
            if (settingsManager.bootOnStartup) {
                Log.i(TAG, "Boot on startup is enabled, launching MihomoVpnService")
                try {
                    MihomoVpnService.start(context)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start VPN on boot: ${e.message}", e)
                }
            } else {
                Log.i(TAG, "Boot on startup is disabled, ignoring")
            }
        }
    }
}
