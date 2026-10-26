package com.github.mihomo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
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
            CupertinoToast.show("需要授予 VPN 权限才能建立代理", isDestructive = true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // The toast host collects a MutableStateFlow, so show() only needs the main thread;
        // permission callbacks always land on it. runCatching guards the rare case where the
        // user answers before the first composition exists to collect the flow.
        runCatching {
            if (granted) {
                CupertinoToast.show(notificationToastGranted())
            } else {
                CupertinoToast.show(notificationToastDenied(), isDestructive = true)
            }
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
    }

    override fun onStop() {
        MihomoVpnService.isUiInForeground = false
        super.onStop()
    }

    private fun notificationToastGranted(): String =
        if (usesChineseUi()) "通知权限已开启" else "Notification permission granted"

    private fun notificationToastDenied(): String =
        if (usesChineseUi()) {
            "未授予通知权限，状态栏将不显示速率"
        } else {
            "Notification permission denied; the status bar will not show speeds"
        }

    private fun usesChineseUi(): Boolean {
        val language = if (::settingsManager.isInitialized) settingsManager.appLanguage else "SYSTEM"
        val effective = if (language == "SYSTEM") java.util.Locale.getDefault().language else language
        return effective.startsWith("zh", ignoreCase = true)
    }

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
