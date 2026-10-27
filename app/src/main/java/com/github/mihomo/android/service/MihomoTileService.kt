package com.github.mihomo.android.service

import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.github.mihomo.android.data.SettingsManager
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
        // The service may have been killed while the tunnel was still running: the
        // persisted intent flag knows that, the in-memory state does not. Ask the service
        // to bring the tunnel back up so the tile does not show a lie.
        MihomoVpnService.resumeIfIntentActive(this)
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
        when (current) {
            VpnState.Status.RUNNING, VpnState.Status.STARTING -> {
                // RUNNING || STARTING means "stop", exactly like MainActivity.handleToggleVpn.
                // Treating STARTING as a no-op made the tile look broken: the user tapped and
                // nothing happened at all.
                MihomoVpnService.stop(this)
            }
            VpnState.Status.STOPPED -> {
                // The tile shows ACTIVE when the persisted intent says the tunnel should be
                // running (it may still be running after a process kill). A tap on an ACTIVE
                // tile must therefore stop the tunnel instead of starting a second one.
                if (persistedIntentActive()) {
                    MihomoVpnService.stop(this)
                    return
                }
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
                        @Suppress("DEPRECATED")
                        @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                        startActivityAndCollapse(vpnIntent)
                    }
                }
            }
            VpnState.Status.STOPPING -> {
                // The tunnel is already being torn down; a second stop request would only
                // race the teardown. The subtitle is updated to say so, so the tap is not
                // silently swallowed.
                updateTileState(VpnState.Status.STOPPING)
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
                // The tunnel is on its way up: show it as active so the tile matches what a
                // tap would do (stop), and say so in the subtitle.
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_connecting"))
            }
            VpnState.Status.STOPPING -> {
                // The tunnel is on its way down: a tap is ignored, but the subtitle must
                // say that instead of leaving the previous state on screen.
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_disconnecting"))
            }
            VpnState.Status.STOPPED -> {
                // The in-memory state is process-local: after a process kill it resets to
                // STOPPED even though the tunnel may still be running. The persisted
                // intent flag is the authoritative answer in that case.
                if (persistedIntentActive()) {
                    tile.state = Tile.STATE_ACTIVE
                    setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_connected"))
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    setSubtitle(tile, com.github.mihomo.android.i18n.AppStrings.get("tile_disconnected"))
                }
            }
        }
        tile.updateTile()
    }

    /** True when the persisted intent says the tunnel was supposed to be running. */
    private fun persistedIntentActive(): Boolean =
        runCatching { SettingsManager(this).vpnIntentActive }.getOrDefault(false)

    private fun setSubtitle(tile: Tile, subtitle: String) {
        // Tile.setSubtitle only exists from API 29 while minSdk is 26; calling it on 8.x/9 throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
    }
}
