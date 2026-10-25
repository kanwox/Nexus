package com.github.mihomo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.theme.MihomoTheme
import java.util.Locale

/**
 * Activity shell: owns permission flows and theme wiring. All UI orchestration
 * (navigation state, tabs, sub-screens, speed-test logic) lives in [MainAppScreen].
 *
 * Extends [FragmentActivity] (itself a ComponentActivity, so Compose's setContent keeps
 * working) because androidx.biometric.BiometricPrompt only accepts a FragmentActivity host.
 */
class MainActivity : FragmentActivity() {

    private lateinit var settingsManager: SettingsManager

    // App lock: armed at process start and on every trip to the background.
    private var locked = true

    // Stops onResume from immediately re-prompting after a failed/cancelled attempt; the
    // next foreground session (onStart) offers the prompt again.
    private var promptShownThisResume = false

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

        // FLAG_SECURE only takes effect after relaunching the app, which is acceptable for
        // a privacy toggle; it hides this app from screenshots and the recents preview.
        if (settingsManager.preventScreenshots) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

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
        promptShownThisResume = false
        MihomoVpnService.isUiInForeground = true
    }

    override fun onResume() {
        super.onResume()
        if (settingsManager.appLockEnabled && locked && !promptShownThisResume) {
            promptShownThisResume = true
            showBiometricUnlock()
        }
    }

    override fun onStop() {
        MihomoVpnService.isUiInForeground = false
        if (settingsManager.appLockEnabled) {
            locked = true
        }
        super.onStop()
    }

    /**
     * Biometric / device-credential unlock. Failure, cancellation and non-confirmed results
     * all leave [locked] untouched: the default callback implementation is a no-op, so the
     * prompt is simply offered again on the next resume without any retry loop here.
     */
    private fun showBiometricUnlock() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appLockPromptTitle())
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        runCatching {
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        // androidx.biometric only invokes success for an authenticated result.
                        locked = false
                    }
                }
            ).authenticate(promptInfo)
        }
    }

    private fun usesChineseUi(): Boolean {
        val language = if (::settingsManager.isInitialized) settingsManager.appLanguage else "SYSTEM"
        val effective = if (language == "SYSTEM") Locale.getDefault().language else language
        return effective.startsWith("zh", ignoreCase = true)
    }

    private fun appLockPromptTitle(): String =
        if (usesChineseUi()) "解锁 Nexus" else "Unlock Nexus"

    private fun notificationToastGranted(): String =
        if (usesChineseUi()) "通知权限已开启" else "Notification permission granted"

    private fun notificationToastDenied(): String =
        if (usesChineseUi()) {
            "未授予通知权限，状态栏将不显示速率"
        } else {
            "Notification permission denied; the status bar will not show speeds"
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
