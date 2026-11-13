package com.github.mihomo.android.service

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent trampoline Activity launched from the Quick Settings tile.
 *
 * Starting a foreground service directly from [android.service.quicksettings.TileService] in the
 * background violates Android 12/14+ FGS restrictions. Launching this Activity via
 * `startActivityAndCollapse` enters a foreground state so [MihomoVpnService.start] (and any
 * required [VpnService.prepare] consent flow) executes legally.
 */
class TileVpnLaunchActivity : ComponentActivity() {

    private var waitingForPermission = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        waitingForPermission = false
        if (result.resultCode == RESULT_OK) {
            MihomoVpnService.start(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForPermission =
            savedInstanceState?.getBoolean(KEY_WAITING_FOR_PERMISSION, false) ?: false
        if (waitingForPermission) return

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            MihomoVpnService.start(this)
            finish()
            return
        }

        waitingForPermission = true
        runCatching {
            vpnPermissionLauncher.launch(prepareIntent)
        }.onFailure {
            waitingForPermission = false
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_WAITING_FOR_PERMISSION, waitingForPermission)
    }

    private companion object {
        private const val KEY_WAITING_FOR_PERMISSION = "waiting_for_permission"
    }
}
