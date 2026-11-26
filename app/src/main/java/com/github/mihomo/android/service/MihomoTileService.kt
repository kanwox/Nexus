package com.github.mihomo.android.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.github.mihomo.android.R
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
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
        when (MihomoVpnService.vpnState.value.status) {
            VpnState.Status.RUNNING, VpnState.Status.STARTING -> {
                // Require unlock for stopping too so the lock screen cannot bypass VPN protection.
                unlockAndRun {
                    val status = MihomoVpnService.vpnState.value.status
                    if (status == VpnState.Status.RUNNING || status == VpnState.Status.STARTING) {
                        MihomoVpnService.stop(this)
                    }
                }
            }
            VpnState.Status.STOPPED -> {
                // MihomoVpnService runs in the same process, so STOPPED is authoritative:
                // clear any stale persisted intent flag without issuing a background startService.
                if (persistedIntentActive()) {
                    MihomoVpnService.clearPersistedIntent(this)
                }
                unlockAndRun {
                    if (MihomoVpnService.vpnState.value.status == VpnState.Status.STOPPED) {
                        launchTileVpnActivity()
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

    private fun launchTileVpnActivity() {
        val intent = Intent(this, TileVpnLaunchActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_TILE_LAUNCH,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapseLegacy(intent)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to launch VPN trampoline activity from tile", error)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun updateTileState(status: VpnState.Status) {
        val tile = qsTile ?: return
        val lang = runCatching { SettingsManager(this).appLanguage }.getOrDefault("auto")
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        when (status) {
            VpnState.Status.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, AppStrings.get("tile_connected", lang))
            }
            VpnState.Status.STARTING -> {
                // The tunnel is on its way up: show it as active so the tile matches what a
                // tap would do (stop), and say so in the subtitle.
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, AppStrings.get("tile_connecting", lang))
            }
            VpnState.Status.STOPPING -> {
                // The tunnel is on its way down: a tap is ignored, but the subtitle must
                // say that instead of leaving the previous state on screen.
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, AppStrings.get("tile_disconnecting", lang))
            }
            VpnState.Status.STOPPED -> {
                // In-memory STOPPED is authoritative within this process; never display ACTIVE
                // or resurrect the foreground service from background tile callbacks.
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, AppStrings.get("tile_disconnected", lang))
            }
        }
        tile.updateTile()
    }

    /** True when the persisted intent says the tunnel was previously left active. */
    private fun persistedIntentActive(): Boolean =
        runCatching { SettingsManager(this).vpnIntentActive }.getOrDefault(false)

    private fun setSubtitle(tile: Tile, subtitle: String) {
        // Tile.setSubtitle only exists from API 29 while minSdk is 26; calling it on 8.x/9 throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
    }

    private companion object {
        private const val TAG = "MihomoTileService"
        private const val REQUEST_CODE_TILE_LAUNCH = 1002
    }
}
