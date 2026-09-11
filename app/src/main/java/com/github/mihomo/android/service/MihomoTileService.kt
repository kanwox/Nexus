package com.github.mihomo.android.service

import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class MihomoTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            MihomoVpnService.vpnState.collectLatest { state ->
                updateTileState(state.status)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
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
                // VPN permission not yet granted by user; need to open app
                vpnIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(vpnIntent)
            }
        }
    }

    private fun updateTileState(status: VpnState.Status) {
        val tile = qsTile ?: return
        when (status) {
            VpnState.Status.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "已连接"
            }
            VpnState.Status.STARTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "连接中…"
            }
            VpnState.Status.STOPPING -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "断开中…"
            }
            VpnState.Status.STOPPED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "已断开"
            }
        }
        tile.updateTile()
    }
}
