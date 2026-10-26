package com.github.mihomo.android.service

import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MihomoTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            MihomoVpnService.vpnState
                .map { it.status }
                .distinctUntilChanged()
                .collectLatest { status ->
                    updateTileState(status)
                }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val current = MihomoVpnService.vpnState.value.status
        if (current == VpnState.Status.RUNNING) {
            MihomoVpnService.stop(this)
        } else if (current == VpnState.Status.STOPPED) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent == null) {
                MihomoVpnService.start(this)
            } else {
                vpnIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        vpnIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(vpnIntent)
                }
            }
        }
    }

    private fun updateTileState(status: VpnState.Status) {
        val tile = qsTile ?: return
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.github.mihomo.android.R.drawable.ic_qs_tile)
        when (status) {
            VpnState.Status.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_connected"))
            }
            VpnState.Status.STARTING -> {
                // Keep the last settled state during transitions: flipping to ACTIVE before
                // the tunnel exists (and back to INACTIVE mid-stop) made the tile lie about
                // what a tap would do.
            }
            VpnState.Status.STOPPING -> {
            }
            VpnState.Status.STOPPED -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_disconnected"))
            }
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, subtitle: String) {
        // Tile.setSubtitle only exists from API 29 while minSdk is 26; calling it on 8.x/9 throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
    }
}
