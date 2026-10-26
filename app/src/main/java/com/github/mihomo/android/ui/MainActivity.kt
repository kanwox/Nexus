package com.github.mihomo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.theme.AppStrings
import com.github.mihomo.android.ui.theme.MihomoTheme

/**
 * Activity shell: owns permission flows and theme wiring. All UI orchestration
 * (navigation state, tabs, sub-screens, speed-test logic) lives in [MainAppScreen].
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            MihomoVpnService.start(this)
        } else {
            CupertinoToast.show(
                AppStrings.get("vpn_permission_required", uiLanguage()),
                isDestructive = true
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // The toast host collects a MutableStateFlow, so show() only needs the main thread;
        // permission callbacks always land on it. runCatching guards the rare case where the
        // user answers before the first composition exists to collect the flow.
        runCatching {
            CupertinoToast.show(
                AppStrings.get(
                    if (granted) "notification_permission_granted" else "notification_permission_denied",
                    uiLanguage()
                ),
                isDestructive = !granted
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            var themeMode by remember { mutableStateOf(settingsManager.themeMode) }
            var appFont by remember { mutableStateOf(settingsManager.appFont) }
            MihomoTheme(
                themeMode = themeMode,
                appFont = appFont
            ) {
                MainAppScreen(
                    settingsManager = settingsManager,
                    onToggleVpn = { handleToggleVpn() },
                    currentThemeMode = themeMode,
                    currentAppFont = appFont,
                    onThemeModeChanged = { newMode ->
                        settingsManager.themeMode = newMode
                        themeMode = newMode
                    },
                    onAppFontChanged = { newFont ->
                        settingsManager.appFont = newFont
                        appFont = newFont
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MihomoVpnService.isUiInForeground = true
        // The screen-state receiver lives in the VPN service; if the service was destroyed
        // while the screen was off, LogRepository (and the native core) could stay gated off.
        // Refresh both from the authoritative PowerManager state whenever the UI comes back.
        val interactive = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        LogRepository.isScreenOff = !interactive
        if (interactive) ClashCore.setSuspended(false)
    }

    override fun onStop() {
        MihomoVpnService.isUiInForeground = false
        super.onStop()
    }

    private fun uiLanguage(): String =
        if (::settingsManager.isInitialized) settingsManager.appLanguage else "SYSTEM"

    private fun handleToggleVpn() {
        val currentStatus = MihomoVpnService.vpnState.value.status
        if (currentStatus == VpnState.Status.RUNNING || currentStatus == VpnState.Status.STARTING) {
            MihomoVpnService.stop(this)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                MihomoVpnService.start(this)
            }
        }
    }
}
